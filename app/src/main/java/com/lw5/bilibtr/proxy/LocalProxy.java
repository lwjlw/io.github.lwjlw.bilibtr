package com.lw5.bilibtr.proxy;

import android.os.SystemClock;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.SpeedSession;

/**
 * 阶段 A.5 用的**最小本地 HTTP 代理**（只验证可行性，不做并发/竞速）。
 *
 * 它只回答三个问题：
 *   1) 官方播放器接不接受 `http://127.0.0.1:<port>`？
 *   2) 它发的是不是标准 HTTP `Range`？
 *   3) 它跟不跟 `302`？
 *
 * 形态：
 *   播放器 ──▶ http://127.0.0.1:<port>/media?u=<base64 原始地址>&k=video|audio
 *                    └─ 转发到原始地址（原样带上 Range 与全部签名 query），把上游响应原样回传
 *
 * 明确不做的事：分块、并发、hedge、CDN 竞速、缓存、改字节。
 * 这些留到阶段 C，照抄 PiliPlus-BTR 的参数。
 *
 * 安全：只绑 127.0.0.1；只转发白名单内的视频字节地址；日志全部脱敏。
 */
public final class LocalProxy {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int BUF = 64 * 1024;
    /** `proxy-stats.txt` 超过这么大就裁剪（只留尾部若干行），避免无限增长。 */
    private static final long STATS_MAX_BYTES = 256 * 1024;
    /** 裁剪后保留的行数。 */
    private static final int STATS_KEEP_LINES = 400;

    private static final Object LOCK = new Object();
    private static ServerSocket server;
    private static volatile int port = -1;

    private static final AtomicLong REQ_COUNT = new AtomicLong();
    private static final AtomicLong BYTES_OUT = new AtomicLong();
    /** 上一次实际使用的并发数（仅用于日志：让"并发 X → Y"里的 X 有意义）。 */
    private static final java.util.concurrent.atomic.AtomicInteger LAST_CONC =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    private LocalProxy() {
    }

    /** 累计转发字节（测速面板用）。 */
    public static long totalBytes() {
        return BYTES_OUT.get();
    }

    /** 累计请求数（测速面板用）。 */
    public static long totalRequests() {
        return REQ_COUNT.get();
    }

    /** 懒启动：第一次需要改写 URL 时才绑定端口（那时 Application 已就绪，能读配置）。 */
    public static int ensureStarted() {
        if (port > 0) return port;
        synchronized (LOCK) {
            if (port > 0) return port;
            int want = ProxyConfig.preferredPort();
            ServerSocket ss = bind(want);
            if (ss == null) ss = bind(0);
            if (ss == null) {
                Recon.note("PROXY", "绑定失败，本次不改写 URL");
                return -1;
            }
            server = ss;
            port = ss.getLocalPort();
            Recon.note("PROXY", "listening 127.0.0.1:" + port + " mode=" + ProxyConfig.mode());
            Thread t = new Thread(LocalProxy::acceptLoop, "btr-proxy-accept");
            t.setDaemon(true);
            t.start();
            return port;
        }
    }

    private static ServerSocket bind(int p) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 64);
            return ss;
        } catch (Throwable t) {
            if (p != 0) Recon.note("PROXY", "端口 " + p + " 绑定失败：" + t);
            return null;
        }
    }

    public static int port() {
        return port;
    }

    private static void acceptLoop() {
        while (true) {
            try {
                Socket c = server.accept();
                Thread t = new Thread(() -> handle(c), "btr-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                Recon.note("PROXY", "accept 异常：" + t);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 单连接

    private static void handle(Socket client) {
        UpstreamPool.Conn upstream = null;
        long t0 = System.currentTimeMillis();
        try {
            client.setSoTimeout(READ_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 8192);
            BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream(), 8192);

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = readHeaders(in);

            String original = extractUpstream(target);
            String kind = queryParam(target, "k");
            if (original == null || !original.startsWith("http")) {
                writeSimple(out, 400, "Bad Request", "missing u=\n");
                return;
            }
            // 安全阀门：只转发白名单里的视频字节地址
            if (!Recon.isVideoByteUrl(original)) {
                Recon.note("PROXY", "拒绝非媒体地址：" + Recon.sanitize(original));
                writeSimple(out, 403, "Forbidden", "not a media url\n");
                return;
            }
            // 播放器会把一部分参数挂在代理 URL 后面（那些参数原本就属于媒体地址），
            // 必须并回上游 URL，否则 CDN 会因签名/防盗链校验失败回 403。
            original = mergeExtraParams(original, target);

            // ---------- 阶段 P0：CDN 候选竞速 ----------
            // 播放器给的是一条地址；若改写时登记了同一路流的其它**自带签名**的备用地址，
            // 就按竞速结果（后台跑、TTL 300s、迟滞 1.2×）改用最快的那条。
            // 没有结论时保持原地址，绝不阻塞请求。
            String upUrl = original;
            // region-agnostic：候选 = 上游给的地址 ∪ 上游的备用地址 ∪ 同家族换 host 的变体
            //（大陆 8 + 海外 4 全在表里）。谁实测快用谁，不做地理偏好。
            // 没有候选 id 时也照样能跑（靠换 host 变体）。
            String pick = CdnRacer.resolve(queryParam(target, "id"), original, headers);
            if (pick != null && !pick.equals(original)) {
                upUrl = mergeExtraParams(pick, target);
                if (!Recon.hostOf(original).equals(Recon.hostOf(upUrl))
                        && Recon.first("CDN:USE", Recon.hostOf(upUrl))) {
                    Recon.note("CDN:USE", "改用 " + Recon.hostOf(upUrl)
                            + "（原 " + Recon.hostOf(original) + "）"
                            + " 实测评分{" + CdnRacer.emaSummary() + "}");
                }
            }

            long n = REQ_COUNT.incrementAndGet();
            String reqKey = method + " k=" + kind + " range=" + headers.get("range")
                    + " host=" + Recon.hostOf(original) + " path=" + pathOf(original);
            if (Recon.first("PROXY:REQ", reqKey)) {
                Recon.note("PROXY:REQ#" + n, reqKey
                        + " ua=" + Recon.clip(headers.get("user-agent"), 40));
                // 调试：客户端原始 target（u= 的 base64 只留长度）+ 全部请求头
                Recon.note("PROXY:TARGET#" + n, scrubTarget(target));
                Recon.note("PROXY:HDRS#" + n, headersSummary(headers));
            }

            long tReq = SystemClock.elapsedRealtime();
            SpeedSession.proxyRequest(Recon.hostOf(upUrl), kind, headers.get("range"));

            // 302 模式：直接把原始地址甩回去，用来测播放器跟不跟重定向
            if (ProxyConfig.MODE_302.equals(ProxyConfig.mode())) {
                String head = "HTTP/1.1 302 Found\r\n"
                        + "Location: " + upUrl + "\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                Recon.note("PROXY:302#" + n, "-> " + Recon.sanitize(upUrl));
                return;
            }

            // ---------- 预读缓存命中：直接内存里给，0 延迟 ----------
            // 这是实测出来的关键：播放器是"要一块等一块"的节奏，
            // 只要每次请求都要等，总吞吐就被请求延迟拖死（直连 2.0MB/s vs 代理 0.57MB/s）。
            // 缓存键必须**稳定**：完整 URL 里带着签名和播放器追加的参数
            //（orderid 会 0,2 → 1,2，hdnts 时有时无），拿它当键永远命中不了。
            // 同一个清晰度就是一个文件，路径唯一且稳定。
            String cacheKey = pathOf(upUrl);
            long[] cr0 = "GET".equalsIgnoreCase(method)
                    ? RangeFetcher.parseClientRange(headers.get("range")) : null;
            if (cr0 != null && RangeCache.covers(cacheKey, cr0[0], cr0[1])) {
                byte[] hit = RangeCache.read(cacheKey, cr0[0], cr0[1]);
                if (hit != null) {
                    writeCached206(out, hit, cr0[0], cr0[1],
                            RangeCache.totalOf(cacheKey), RangeCache.contentTypeOf(cacheKey));
                    BYTES_OUT.addAndGet(hit.length);
                    SpeedSession.proxyResponse(206, hit.length, 0, 0);
                    if (Recon.first("PROXY:CACHE-HIT", cr0[0] + "-" + cr0[1])) {
                        Recon.note("PROXY:CACHE-HIT#" + n, "range=" + cr0[0] + "-" + cr0[1]
                                + " len=" + hit.length + "（0 延迟）");
                    }
                    Prefetcher.ensure(cacheKey, upUrl, headers, cr0[1] + 1);
                    return;
                }
            }

            // ---------- 多 Range 并发（边收边发） ----------
            // 参考实现 DESIGN §3.2/§3.3：**拿到第 1 片就发响应头 + 第 1 片，后续片按序跟上**，
            // 而不是"收齐才回"（那是起播慢 3 倍的根因）。
            if ("GET".equalsIgnoreCase(method)) {
                long[] cr = RangeFetcher.parseClientRange(headers.get("range"));
                if (cr != null) {
                    long len = cr[1] - cr[0] + 1;
                    // ★ 并发数自适应（参考实现 DESIGN §3.4）：
                    //   需要几条 = 码率÷8×1.2 ÷ 每条实测速度；上限取用户配置。
                    //   低码率（720P）会自动降到 1 条：省掉多余的握手与慢启动。
                    int concMax = ProxyConfig.concurrency();
                    int conc = ConnSpeed.desired(CdnRacer.targetBps(upUrl), concMax);
                    // 只把**视频流**的并发记到面板上：音频流目标只有 ~15KB/s，
                    // 它的"1 条"会把视频的真实并发覆盖掉（用户看到 4K 显示并发 1 就是这个原因）
                    double tgt = CdnRacer.targetBps(upUrl);
                    ConnSpeed.noteIfChanged(LAST_CONC.get(), conc, tgt);
                    if (tgt >= 128 * 1024) LAST_CONC.set(conc);
                    if (conc > 1 && len >= ProxyConfig.minSplit() && !RangeFetcher.degraded()) {
                        long t0s = SystemClock.elapsedRealtime();
                        int rc = RangeFetcher.streamRange(upUrl, headers, cr[0], cr[1], conc, 0L, out);
                        if (rc == RangeFetcher.STREAM_OK) {
                            long spent = SystemClock.elapsedRealtime() - tReq;
                            BYTES_OUT.addAndGet(len);
                            SpeedSession.proxyResponse(206, len, spent,
                                    Math.max(0, RangeFetcher.lastUpstreamTtfb()));
                            CdnRacer.record(Recon.hostOf(upUrl), len,
                                    Math.max(1, SystemClock.elapsedRealtime() - t0s));
                            String pkey = cr[0] + "-" + cr[1];
                            if (Recon.first("PROXY:STREAM", pkey)) {
                                long ms = Math.max(1, SystemClock.elapsedRealtime() - t0s);
                                Recon.note("PROXY:STREAM#" + n, "range=" + cr[0] + "-" + cr[1]
                                        + " len=" + len + " conc=" + conc
                                        + " ms=" + ms + " bps=" + (len * 1000L / ms)
                                        + " host=" + Recon.hostOf(upUrl));
                            }
                            Prefetcher.ensure(cacheKey, upUrl, headers, cr[1] + 1);
                            return;
                        }
                        if (rc == RangeFetcher.STREAM_ABORTED) {
                            // 已经写过字节了，不能再降级（否则客户端拿到半截数据）
                            Recon.note("PROXY:STREAM-ABORT#" + n, "range=" + cr[0] + "-" + cr[1]
                                    + " 回传中途失败 → 断开让播放器重来（绝不跳块）");
                            return;
                        }
                        Recon.note("PROXY:PAR-FALLBACK#" + n, "range=" + cr[0] + "-" + cr[1]
                                + " -> 单连接透传（首片就没拿到，一个字节未写）");
                    }
                }
            }

            URL u = new URL(upUrl);
            int upPort = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80);
            upstream = UpstreamPool.acquire(u.getHost(), upPort, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            UpstreamPool.noteReuse(upstream);

            // 原样转发请求（签名 query 必须一字不动），只换 Host、强制短连接
            String req = buildUpstreamRequest(method, upUrl, u, headers);
            if (Recon.first("PROXY:UPREQ", Recon.sanitize(upUrl) + method)) {
                Recon.note("PROXY:UPREQ", Recon.clip(Recon.scrub(req), 600));
            }
            OutputStream upOut = upstream.out;
            upOut.write(req.getBytes(StandardCharsets.ISO_8859_1));
            upOut.flush();

            // 回传上游响应（状态行 + 头 + 体）
            BufferedInputStream upIn = upstream.in;
            String status = readLine(upIn);
            if (status == null) {
                writeSimple(out, 502, "Bad Gateway", "upstream closed\n");
                return;
            }
            long ttfb = SystemClock.elapsedRealtime() - tReq;
            out.write((status + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            long contentLength = -1;
            String contentRange = null;
            String line;
            while ((line = readLine(upIn)) != null && !line.isEmpty()) {
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("connection:")) {
                    if (line.toLowerCase(Locale.US).contains("close")) upstream.reusable = false;
                    continue;
                }
                if (low.startsWith("transfer-encoding:")
                        && line.toLowerCase(Locale.US).contains("chunked")) {
                    upstream.reusable = false;
                }
                if (low.startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    } catch (Throwable ignored) {
                    }
                } else if (low.startsWith("content-range:")) {
                    contentRange = line.substring(line.indexOf(':') + 1).trim();
                }
                out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            out.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            long body = 0;
            int code = codeOf(status);
            ByteArrayOutputStream errBody = code >= 400 ? new ByteArrayOutputStream() : null;
            if (!"HEAD".equalsIgnoreCase(method)) {
                byte[] buf = new byte[BUF];
                int r;
                while (true) {
                    if (contentLength >= 0 && body >= contentLength) break;
                    int want = contentLength >= 0 ? (int) Math.min(buf.length, contentLength - body) : buf.length;
                    r = upIn.read(buf, 0, want);
                    if (r <= 0) break;
                    out.write(buf, 0, r);
                    if (errBody != null && errBody.size() < 512) {
                        errBody.write(buf, 0, Math.min(r, 512 - errBody.size()));
                    }
                    body += r;
                    if ((body & 0x3FFFF) < r) out.flush();
                }
                out.flush();
            }
            // 只有"按 Content-Length 一字不差读完"才允许复用；否则丢弃（防串响应）
            if (body > 0 && contentLength >= 0 && body == contentLength && upstream.reusable) {
                UpstreamPool.release(upstream);
                upstream = null;
            }
            BYTES_OUT.addAndGet(body);
            SpeedSession.proxyResponse(code, body, SystemClock.elapsedRealtime() - tReq, ttfb);
            if (cr0 != null && code == 206) {
                Prefetcher.ensure(cacheKey, upUrl, headers, cr0[1] + 1);
            }
            CdnRacer.record(Recon.hostOf(upUrl), body, SystemClock.elapsedRealtime() - tReq);
            if (errBody != null) {
                Recon.note("PROXY:ERRBODY",
                        Recon.clip(Recon.scrub(new String(errBody.toByteArray(), StandardCharsets.ISO_8859_1)), 400));
            }

            String rspKey = status.trim() + " len=" + contentLength + " cr=" + contentRange;
            if (Recon.first("PROXY:RSP", rspKey)) {
                Recon.note("PROXY:RSP#" + n, rspKey + " relayed=" + body
                        + "B ms=" + (System.currentTimeMillis() - t0));
                dumpStats();
            }
        } catch (Throwable t) {
            Recon.note("PROXY:ERR", String.valueOf(t));
        } finally {
            if (upstream != null) UpstreamPool.discard(upstream);
            closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------ HTTP 小工具

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) (c & 0xFF));
            if (sb.length() > 16384) return sb.toString();
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Map<String, String> readHeaders(InputStream in) throws Exception {
        Map<String, String> h = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            h.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        return h;
    }

    private static boolean isHopByHop(String k) {
        return "connection".equals(k) || "keep-alive".equals(k) || "proxy-connection".equals(k)
                || "te".equals(k) || "trailer".equals(k) || "upgrade".equals(k)
                || "proxy-authorization".equals(k);
    }

    private static int codeOf(String statusLine) {
        try {
            String[] p = statusLine.trim().split("\\s+");
            return Integer.parseInt(p[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 原样转发请求；签名 query 一字不动，只换 Host 并强制短连接。 */
    private static String buildUpstreamRequest(String method, String original, URL u,
                                               Map<String, String> headers) {
        StringBuilder req = new StringBuilder(512);
        req.append(method).append(' ').append(pathWithQuery(original)).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(u.getHost()).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            if (isHopByHop(k) || "host".equals(k)) continue;
            req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        req.append("Connection: keep-alive\r\n\r\n");
        return req.toString();
    }

    /**
     * 把播放器挂在代理 URL 后面的参数并回上游 URL。
     *
     * 为什么需要：B站 的媒体地址在播放器内部是「base 地址 + 一批参数」拼出来的。
     * 我们把 base 换成 127.0.0.1 之后，播放器依旧会把它那批参数（buvid / build / bw /
     * hdnts / orderid …）附在我们 URL 后面。若转发时只取 base64 里那份地址，
     * 这些参数就丢了 —— 实测后果是 CDN 直接 403。
     *
     * 去重规则：跳过 `u`（我们自己的载荷）与 `k`（我们的分类标记），
     * 其余参数若上游地址里没有同名键才追加，避免重复参数引发签名不一致。
     */
    private static String mergeExtraParams(String original, String target) {
        int tq = target.indexOf('?');
        if (tq < 0) return original;
        java.util.HashSet<String> have = new java.util.HashSet<>();
        int oq = original.indexOf('?');
        if (oq >= 0) {
            for (String kv : original.substring(oq + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) have.add(kv.substring(0, e));
            }
        }
        List<String> add = new java.util.ArrayList<>();
        for (String kv : target.substring(tq + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            String k = kv.substring(0, e);
            if ("u".equals(k) || "k".equals(k)) continue;
            if (have.contains(k)) continue;
            add.add(kv);
        }
        if (add.isEmpty()) return original;
        StringBuilder sb = new StringBuilder(original);
        sb.append(oq >= 0 ? '&' : '?');
        for (int i = 0; i < add.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(add.get(i));
        }
        return sb.toString();
    }

    /** 调试：客户端请求行脱敏（base64 只留长度）。 */
    private static String scrubTarget(String target) {
        int q = target.indexOf('?');
        if (q < 0) return Recon.clip(target, 200);
        String path = target.substring(0, q);
        StringBuilder sb = new StringBuilder(path).append('?');
        for (String kv : target.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) {
                sb.append(Recon.clip(kv, 24)).append('&');
                continue;
            }
            String k = kv.substring(0, e);
            String v = kv.substring(e + 1);
            if ("u".equals(k)) sb.append("u=<b64:").append(v.length()).append('>');
            else if (v.length() > 100) sb.append(k).append("=<").append(v.length()).append("ch>");
            else sb.append(k).append('=').append(v);
            sb.append('&');
        }
        return Recon.clip(sb.toString(), 500);
    }

    /** 调试：客户端发来的全部请求头。 */
    private static String headersSummary(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (sb.length() > 0) sb.append(" | ");
            if ("cookie".equals(k)) sb.append("cookie=<").append(v.length()).append("ch>");
            else sb.append(k).append('=').append(Recon.clip(v, 120));
        }
        return Recon.clip(sb.toString(), 700);
    }

    /** 阶段 C2：把并发拼好的整段数据作为一次标准 206 回给播放器。 */
    private static void writeParallel(OutputStream out, RangeFetcher.Result pr) throws Exception {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 206 Partial Content\r\n");
        if (pr.contentType != null) h.append("Content-Type: ").append(pr.contentType).append("\r\n");
        h.append("Content-Length: ").append(pr.body.length).append("\r\n");
        h.append("Content-Range: ").append(pr.contentRange).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(pr.body);
        out.flush();
    }

    /** 从预读缓存直接回 206（精确 Content-Range + Content-Length，DESIGN §3.3）。 */
    private static void writeCached206(OutputStream out, byte[] data, long start, long end,
                                       long total, String contentType) throws Exception {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 206 Partial Content\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(data.length).append("\r\n");
        h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/')
                .append(total >= 0 ? total : "*").append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static void writeSimple(OutputStream out, int code, String msg, String body) {
        try {
            String s = "HTTP/1.1 " + code + " " + msg + "\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n" + body;
            out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String extractUpstream(String target) {
        String b64 = queryParam(target, "u");
        if (b64 == null) return null;
        try {
            StringBuilder sb = new StringBuilder(b64);
            while (sb.length() % 4 != 0) sb.append('=');
            byte[] raw = Base64.decode(sb.toString(), Base64.URL_SAFE);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String queryParam(String target, String name) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        String query = target.substring(q + 1);
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            if (name.equals(kv.substring(0, e))) return kv.substring(e + 1);
        }
        return null;
    }

    private static String pathOf(String url) {
        try {
            URL u = new URL(url);
            String p = u.getPath();
            return p == null ? "" : p;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 上游请求行：origin-form（/path?query），query 一字不改。 */
    private static String pathWithQuery(String url) {
        try {
            URL u = new URL(url);
            String p = u.getPath();
            String q = u.getQuery();
            return (p == null || p.isEmpty() ? "/" : p) + (q == null ? "" : "?" + q);
        } catch (Throwable t) {
            return "/";
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }

    /** 统计快照，便于一次性打印"这场播放代理到底干了什么"。 */
    public static String stats() {
        return "requests=" + REQ_COUNT.get() + " relayed=" + BYTES_OUT.get() + "B"
                + " (" + (BYTES_OUT.get() / 1024 / 1024) + " MiB)";
    }

    /** 把统计写进 recon 目录，便于 adb pull 时一并取回。 */
    public static void dumpStats() {
        try {
            File dir = com.lw5.bilibtr.recon.AppHolder.reconDir();
            if (dir == null) return;
            File f = new File(dir, "proxy-stats.txt");
            FileOutputStream fo = new FileOutputStream(f, true);
            try {
                fo.write((new java.util.Date() + " " + stats() + "\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                fo.close();
            }
            // ⚠️ 这个文件原来**只追加、永不清理**（实测一天就长到 176 KB，长期会一直涨）。
            // 超过上限就把最老的行丢掉，只留尾部。
            if (f.length() > STATS_MAX_BYTES) trimStats(f);
        } catch (Throwable ignored) {
        }
    }

    /** 只保留最后 {@value #STATS_KEEP_LINES} 行。 */
    private static void trimStats(File f) {
        try {
            java.util.List<String> lines = new java.util.ArrayList<>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } finally {
                r.close();
            }
            if (lines.size() <= STATS_KEEP_LINES) return;
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                for (String line : lines.subList(lines.size() - STATS_KEEP_LINES, lines.size())) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
