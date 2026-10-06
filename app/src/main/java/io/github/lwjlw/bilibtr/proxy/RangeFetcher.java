package io.github.lwjlw.bilibtr.proxy;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 把一个客户端 Range 请求切成 N 段并发拉取。
 *
 * 两种用法：
 *   - {@link #fetch}  —— **全部收齐再返回**：只给"竞速探测"用（探测就是要静态量一下速度）；
 *   - {@link #streamRange} —— **边收边发**：正式播放路径用这个。
 *
 * ## 为什么正式路径必须是流式（参考实现 DESIGN §3.2 / §3.3）
 * 早先我用的是"收齐所有分块再一次性回给播放器"，后果实测：
 *   - 起播从 251~266ms 恶化到 852~901ms（慢 3 倍）；
 *   - TTFB 最坏 11 秒（某一段慢就把整单拖死）。
 * 正解是：**先拿到第 1 片，立刻发响应头 + 第 1 片，后续片按序跟上**。
 *
 * ## 硬约束
 *   - 每片必须 `206` 且 `Content-Range` 的起止与请求**严格一致**（零信任）；
 *   - 各片报出的文件总长必须一致，否则整单放弃（**绝不混拼**）；
 *   - 写入**绝不超过 `end`**（`endOffset` 硬上限，DESIGN §3.3）；
 *   - **分块失败绝不跳块**：先就地重试该片；仍失败则断开让播放器重来（宁可慢，不能坏帧）。
 */
public final class RangeFetcher {

    /** 小于这个长度不值得切（切了反而多花握手钱）。 */
    public static final long MIN_SPLIT = 64 * 1024L;
    /** 超过这个长度不做"先全部收完再回"，直接走透传（保护内存）。 */
    public static final long MAX_INMEM = 4 * 1024 * 1024L;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    /**
     * 轻量探测模式：**单次尝试 + 短超时**。
     *
     * 为什么需要：界面上点"全部节点测速"时，会遇到一堆死节点。
     * 正常抓取是"重试 3 次 ×（8 秒连接 + 20 秒读）"≈ 一个节点卡一分半，
     * 于是用户看到的进度**停在 2/13 不动**（实测反馈）。
     * 探测就该"探不到就算了"，别耗着。
     */
    private static final ThreadLocal<Boolean> LIGHT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final int LIGHT_CONNECT_MS = 3000;
    private static final int LIGHT_READ_MS = 5000;

    /** 在轻量模式下跑一次抓取（探测专用）。 */
    public static Result probe(String url, Map<String, String> headers, long bytes) {
        LIGHT.set(Boolean.TRUE);
        try {
            return fetch(url, "GET", headers, 0, Math.max(1, bytes) - 1, 1);
        } finally {
            LIGHT.set(Boolean.FALSE);
        }
    }
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int DEFAULT_RETRIES = 2;
    private static final int BUF = 64 * 1024;
    /** 一片超过这么久还没回来 → 认定是"慢块"，立刻补发一条（hedge）。 */
    private static final long HEDGE_AFTER_MS = 1500L;
    private static final java.util.concurrent.atomic.AtomicLong HEDGE_COUNT =
            new java.util.concurrent.atomic.AtomicLong();
    /** 连续失败计数 / 降级截止时刻（参考实现 DESIGN §3.11：降级 + 自动重接管）。 */
    /** 最近一次上游"首字节时间"（毫秒）：从发出请求到读到响应头。测速面板用。 */
    private static volatile long LAST_UPSTREAM_TTFB = -1;

    public static long lastUpstreamTtfb() {
        return LAST_UPSTREAM_TTFB;
    }

    private static final java.util.concurrent.atomic.AtomicInteger CONSEC_FAIL =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long DEGRADED_UNTIL = 0L;
    /** 连续失败几次就临时放弃流式。 */
    private static final int DEGRADE_AFTER_FAILS = 3;
    /** 降级持续多久（之后自动重接管，再试流式）。 */
    private static final long DEGRADE_FOR_MS = 120_000L;

    /** 当前是否处于"降级"状态（调用方据此跳过流式、直接透传）。 */
    public static boolean degraded() {
        return System.currentTimeMillis() < DEGRADED_UNTIL;
    }

    private static void noteFail() {
        int n = CONSEC_FAIL.incrementAndGet();
        if (n >= DEGRADE_AFTER_FAILS && !degraded()) {
            DEGRADED_UNTIL = System.currentTimeMillis() + DEGRADE_FOR_MS;
            Recon.note("STREAM:DEGRADE", "连续失败 " + n + " 次 → 暂停流式 "
                    + (DEGRADE_FOR_MS / 1000) + " 秒，期间走单连接透传");
        }
    }

    private static void noteOk() {
        if (CONSEC_FAIL.getAndSet(0) >= DEGRADE_AFTER_FAILS) {
            Recon.note("STREAM:RECOVER", "流式已恢复（自动重接管）");
        }
    }

    private RangeFetcher() {
    }

    // ================================================================== 流式（正式路径）

    /** 流式结果。 */
    public static final int STREAM_OK = 0;
    /** 一个字节都还没写给客户端，调用方可以安全降级为透传。 */
    public static final int STREAM_NOT_STARTED = 1;
    /** 已经写了一半才失败：只能断开连接让播放器重来（绝不跳块）。 */
    public static final int STREAM_ABORTED = 2;

    /**
     * 边收边发：把 `[start, end]` 分片并发拉取，**按序**写进客户端输出流。
     *
     * @param pieceBytes 单片大小；≤0 时按并发数自动算
     */
    public static int streamRange(String url, Map<String, String> headers,
                                  long start, long end, int concurrency, long pieceBytes,
                                  OutputStream clientOut) {
        long len = end - start + 1;
        if (len <= 0) return STREAM_NOT_STARTED;

        int conc = Math.max(1, concurrency);
        long piece = pieceBytes > 0 ? pieceBytes : Math.max(MIN_SPLIT, (len + conc - 1) / conc);
        int n = (int) ((len + piece - 1) / piece);
        List<long[]> ranges = new ArrayList<>(n);
        long s = start;
        while (s <= end) {
            long e = Math.min(end, s + piece - 1);
            ranges.add(new long[]{s, e});
            s = e + 1;
        }

        // ① 第 1 片：**只等响应头，不等整片**，拿到就立刻把 206 头顶给播放器。
        //    参考实现 DESIGN §3.2：播放器读响应头有超时（ffmpeg 4~5 秒），
        //    等整片收完才发头会直接超时重试；实测这一条让起播从 1.4s 恶化到 5.8s。
        Conn c0 = Conn.open(url, headers, ranges.get(0)[0], ranges.get(0)[1]);
        if (c0 == null) {
            noteFail();
            return STREAM_NOT_STARTED;
        }

        long written = 0;
        try {
            writeStreamHeaders(clientOut, start, end, c0.total, c0.contentType);
            byte[] buf = new byte[BUF];
            long remain = c0.contentLength;
            while (remain > 0) {
                int r = c0.pc.in.read(buf, 0, (int) Math.min(buf.length, remain));
                if (r <= 0) break;
                clientOut.write(buf, 0, r);
                remain -= r;
                written += r;
                if ((written & 0x3FFFF) < r) clientOut.flush();
            }
            clientOut.flush();
            if (remain != 0) {
                Recon.note("PROXY:STREAM-ABORT", "首片短读 剩 " + remain + "B");
                noteFail();
                return STREAM_ABORTED;
            }
            c0.consumed = true;    // 首片读完 → 连接可归还

            // ② 后续片：保持 conc 条在途，**按序**写
            if (n > 1) {
                Slot[] slots = new Slot[n];
                for (int i = 0; i < n; i++) slots[i] = new Slot(ranges.get(i)[0], ranges.get(i)[1]);
                int next = 1;
                int inFlight = 0;
                long pieceMsSum = 0L;
                int pieceDone = 0;
                for (int i = 1; i < n; i++) {
                    while (inFlight < conc && next < n) {
                        launch(slots[next], url, headers);
                        next++;
                        inFlight++;
                    }
                    Slot cur = slots[i];
                    // ⚠️ 判据必须**相对**：慢线路上每一片都可能 >1.5s，
                    //    用绝对阈值会变成"每片都补发"，流量翻倍、越补越慢。
                    //    所以门槛 = max(1.5s, 已完成分片平均耗时的 2 倍)。
                    long avgMs = pieceDone > 0 ? pieceMsSum / pieceDone : 0;
                    long thresh = Math.max(HEDGE_AFTER_MS, avgMs * 2);
                    if (!cur.await(thresh)) {
                        Recon.note("PROXY:HEDGE", "分片 " + cur.start + "-" + cur.end
                                + " 超过 " + thresh + "ms（均 " + avgMs + "ms）未回 → 补发第二条连接");
                        hedge(cur, url, headers);
                        if (!cur.await(READ_TIMEOUT_MS + 5000L)) {
                            Recon.note("PROXY:STREAM-ABORT", "分片 " + cur.start + "-" + cur.end + " 超时");
                            return STREAM_ABORTED;
                        }
                    }
                    if (!cur.ok) {
                        // 绝不跳块：就地重试这一片
                        Part again = fetchPartBytes(url, headers, cur.start, cur.end);
                        if (again == null) {
                            Recon.note("PROXY:STREAM-ABORT", "分片 " + cur.start + "-" + cur.end + " 重试仍失败");
                            noteFail();
                            return STREAM_ABORTED;
                        }
                        cur.data = again.data;
                        cur.ok = true;
                    }
                    long remainCap = end - (cur.start - 1);        // 硬上限：绝不多写
                    int w = (int) Math.min(cur.data.length, remainCap);
                    clientOut.write(cur.data, 0, w);
                    written += w;
                    clientOut.flush();
                    if (cur.launchedAt > 0) {
                        pieceMsSum += System.currentTimeMillis() - cur.launchedAt;
                        pieceDone++;
                    }
                    slots[i] = null;
                    inFlight--;
                }
            }
            noteOk();
            return STREAM_OK;
        } catch (Throwable t) {
            noteFail();
            Recon.note("PROXY:STREAM-ERR", String.valueOf(t) + " 已写 " + written + "B");
            return written == 0 ? STREAM_NOT_STARTED : STREAM_ABORTED;
        } finally {
            c0.finish();
        }
    }

    /** 立刻把精确 206 头顶给播放器（此时往往只读到了上游的响应头）。 */
    private static void writeStreamHeaders(OutputStream out, long start, long end,
                                           long total, String contentType) throws Exception {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 206 Partial Content\r\n");
        if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
        h.append("Content-Length: ").append(end - start + 1).append("\r\n");
        h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/')
                .append(total >= 0 ? total : "*").append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** 已建立的上游连接：响应头已解析、body 还没读完。 */
    private static final class Conn {
        UpstreamPool.Conn pc;
        long contentLength;
        long total = -1;
        String contentType;
        /** body 是否已完整读完（决定归还还是丢弃）。 */
        boolean consumed;

        static Conn open(String url, Map<String, String> headers, long start, long end) {
            Conn c = new Conn();
            long t0 = System.currentTimeMillis();
            try {
                URL u = new URL(url);
                int port = u.getPort() > 0 ? u.getPort()
                        : ("https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80);
                c.pc = UpstreamPool.acquire(u.getHost(), port,
                        LIGHT.get() ? LIGHT_CONNECT_MS : CONNECT_TIMEOUT_MS,
                        LIGHT.get() ? LIGHT_READ_MS : READ_TIMEOUT_MS);
                UpstreamPool.noteReuse(c.pc);
                c.pc.out.write(buildRequest("GET", u, headers, start, end)
                        .getBytes(StandardCharsets.ISO_8859_1));
                c.pc.out.flush();

                String statusLine = readLine(c.pc.in);
                if (statusLine == null) throw new IllegalStateException("no status");
                int status = codeOf(statusLine);
                String cr = null;
                long clen = -1;
                boolean connClose = false;
                boolean chunked = false;
                String line;
                while ((line = readLine(c.pc.in)) != null && !line.isEmpty()) {
                    String low = line.toLowerCase(Locale.US);
                    int ci = line.indexOf(':');
                    if (ci <= 0) continue;
                    String v = line.substring(ci + 1).trim();
                    if (low.startsWith("content-length:")) clen = parseLong(v, -1);
                    else if (low.startsWith("content-range:")) cr = v;
                    else if (low.startsWith("content-type:")) c.contentType = v;
                    else if (low.startsWith("connection:")) {
                        connClose = v.toLowerCase(Locale.US).contains("close");
                    } else if (low.startsWith("transfer-encoding:")) {
                        chunked = v.toLowerCase(Locale.US).contains("chunked");
                    }
                }
                if (status != 206) throw new IllegalStateException("status " + status);
                if (parseCrBound(cr, 0) != start || parseCrBound(cr, 1) != end) {
                    throw new IllegalStateException("content-range " + cr);
                }
                c.total = parseTotal(cr);
                long want = end - start + 1;
                if (clen >= 0 && clen != want) throw new IllegalStateException("len " + clen);
                c.contentLength = want;
                LAST_UPSTREAM_TTFB = System.currentTimeMillis() - t0;   // 真首字节时间
                // 只有"长度已知 + 非 chunked + 上游没说 close"才允许复用
                c.pc.reusable = !connClose && !chunked && clen >= 0;
                return c;
            } catch (Throwable t) {
                if (c.pc != null) UpstreamPool.discard(c.pc);
                return null;
            }
        }

        /** 正常收尾：读完就归还池子，没读完就丢弃。 */
        void finish() {
            if (pc == null) return;
            if (consumed) {
                UpstreamPool.release(pc);
            } else {
                UpstreamPool.discard(pc);
            }
        }
    }

    private static void launch(Slot slot, String url, Map<String, String> headers) {
        slot.launchedAt = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                Part p = fetchPartBytes(url, headers, slot.start, slot.end);
                if (p != null) {
                    slot.supply(p.data);
                } else {
                    slot.fail();
                }
            } catch (Throwable ignored) {
                slot.fail();
            }
        }, "btr-piece");
        t.setDaemon(true);
        t.start();
    }

    /**
     * **慢块补救（hedge）**：某一片迟迟不回来时，立刻**另开一条连接补发同一个区间**，
     * 谁先回来用谁（{@link Slot#supply} 保证只有一个算数）。
     *
     * 为什么必须做：一个请求切成 N 片后，**只要有一片卡住，整单就得等它**
     * —— 这正是"看着不卡但时不时顿一下"的来源。
     */
    private static void hedge(Slot slot, String url, Map<String, String> headers) {
        HEDGE_COUNT.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                Part p = fetchPartBytes(url, headers, slot.start, slot.end);
                if (p != null) {
                    slot.supply(p.data);
                }
                // 失败就让原来那条继续跑，不额外 fail（否则会误判成"没数据"）
            } catch (Throwable ignored) {
            }
        }, "btr-hedge");
        t.setDaemon(true);
        t.start();
    }

    private static final class Slot {
        final long start;
        final long end;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile byte[] data;
        volatile boolean ok;
        /** 本条出发的时刻（用于判断"这一片是不是明显比别的慢"）。 */
        volatile long launchedAt;
        /** 是否已经有人把数据填进来了（hedge 时用来"谁先回来用谁"）。 */
        private final java.util.concurrent.atomic.AtomicBoolean filled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Slot(long s, long e) {
            this.start = s;
            this.end = e;
        }

        /** 成功拿到数据：只有第一个到达的算数，后来的直接丢弃。 */
        boolean supply(byte[] d) {
            if (d == null) return false;
            if (!filled.compareAndSet(false, true)) return false;
            data = d;
            ok = true;
            latch.countDown();
            return true;
        }

        /** 拉取失败：唤醒等待者（ok 仍为 false），由等待者决定重试。 */
        void fail() {
            latch.countDown();
        }

        boolean await(long ms) throws InterruptedException {
            return latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    // ================================================================== 单片

    private static final class Part {
        byte[] data;
        String contentType;
        long total = -1;
    }

    private static Part fetchPartBytes(String url, Map<String, String> headers, long start, long end) {
        int retries = LIGHT.get() ? 0 : DEFAULT_RETRIES;   // 探测不重试
        for (int i = 0; i <= retries; i++) {
            Part p = fetchPartOnce(url, headers, start, end);
            if (p != null) return p;
        }
        return null;
    }

    private static Part fetchPartOnce(String url, Map<String, String> headers, long start, long end) {
        UpstreamPool.Conn c = null;
        long t0 = System.currentTimeMillis();
        try {
            URL u = new URL(url);
            int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getProtocol()) ? 443 : 80);
            c = UpstreamPool.acquire(u.getHost(), port,
                    LIGHT.get() ? LIGHT_CONNECT_MS : CONNECT_TIMEOUT_MS,
                    LIGHT.get() ? LIGHT_READ_MS : READ_TIMEOUT_MS);
            UpstreamPool.noteReuse(c);
            c.out.write(buildRequest("GET", u, headers, start, end)
                    .getBytes(StandardCharsets.ISO_8859_1));
            c.out.flush();

            String statusLine = readLine(c.in);
            if (statusLine == null) throw new IllegalStateException("no status");
            int status = codeOf(statusLine);
            long contentLength = -1;
            String cr = null;
            String ctype = null;
            boolean connClose = false;
            boolean chunked = false;
            String line;
            while ((line = readLine(c.in)) != null && !line.isEmpty()) {
                String low = line.toLowerCase(Locale.US);
                int ci = line.indexOf(':');
                if (ci <= 0) continue;
                String v = line.substring(ci + 1).trim();
                if (low.startsWith("content-length:")) contentLength = parseLong(v, -1);
                else if (low.startsWith("content-range:")) cr = v;
                else if (low.startsWith("content-type:")) ctype = v;
                else if (low.startsWith("connection:")) {
                    connClose = v.toLowerCase(Locale.US).contains("close");
                } else if (low.startsWith("transfer-encoding:")) {
                    chunked = v.toLowerCase(Locale.US).contains("chunked");
                }
            }
            if (status != 206) throw new IllegalStateException("status " + status);
            if (parseCrBound(cr, 0) != start || parseCrBound(cr, 1) != end) {
                throw new IllegalStateException("content-range " + cr);
            }
            long want = end - start + 1;
            if (contentLength >= 0 && contentLength != want) {
                throw new IllegalStateException("len " + contentLength);
            }
            c.reusable = !connClose && !chunked && contentLength >= 0;

            byte[] buf = new byte[(int) want];
            int off = 0;
            while (off < want) {
                int r = c.in.read(buf, off, (int) (want - off));
                if (r <= 0) break;
                off += r;
            }
            if (off != want) throw new IllegalStateException("short read " + off + "/" + want);

            Part p = new Part();
            p.data = buf;
            p.contentType = ctype;
            p.total = parseTotal(cr);
            UpstreamPool.release(c);        // 完整读完 → 归还池子
            // 记录"**单条连接**有多快"——自适应并发数的唯一依据
            ConnSpeed.record(off, System.currentTimeMillis() - t0);
            return p;
        } catch (Throwable t) {
            UpstreamPool.discard(c);        // 任何异常 → 丢弃，绝不归池
            return null;
        }
    }

    private static String buildRequest(String method, URL u, Map<String, String> headers,
                                       long start, long end) {
        String path = u.getPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = u.getQuery();
        StringBuilder sb = new StringBuilder(512);
        sb.append(method).append(' ').append(path)
                .append(query == null ? "" : "?" + query).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(u.getHost()).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            if (isHopByHop(k) || "host".equals(k) || "range".equals(k)) continue;
            if ("accept-encoding".equals(k)) continue;   // 字节拼接必须 identity
            sb.append(k).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Range: bytes=").append(start).append('-').append(end).append("\r\n");
        sb.append("Connection: keep-alive\r\n\r\n");
        return sb.toString();
    }

    private static boolean isHopByHop(String k) {
        return "connection".equals(k) || "keep-alive".equals(k) || "proxy-connection".equals(k)
                || "te".equals(k) || "trailer".equals(k) || "upgrade".equals(k)
                || "proxy-authorization".equals(k);
    }

    // ================================================================== 静态整段（探测用）

    public static final class Result {
        public boolean ok;
        public byte[] body;
        public long totalLength = -1;
        public String contentType;
        public String contentRange;
        public int parts;
        public int retries;
        public long ms;
        public String error = "";
    }

    /** 全部收齐再返回：只给竞速探测用。 */
    public static Result fetch(String url, String method, Map<String, String> headers,
                               long start, long end, int concurrency) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        try {
            long len = end - start + 1;
            if (len <= 0) {
                r.error = "empty range";
                return r;
            }
            int n = (int) Math.max(1, Math.min(concurrency, (len + MIN_SPLIT - 1) / MIN_SPLIT));
            long per = (len + n - 1) / n;
            List<Part> parts = new ArrayList<>();
            long s = start;
            while (s <= end) {
                long e = Math.min(end, s + per - 1);
                parts.add(fetchPartBytes(url, headers, s, e));
                s = e + 1;
            }
            r.parts = parts.size();
            int off = 0;
            byte[] all = new byte[(int) len];
            for (Part p : parts) {
                if (p == null) {
                    r.error = "part failed";
                    return r;
                }
                r.totalLength = p.total;
                r.contentType = p.contentType;
                System.arraycopy(p.data, 0, all, off, p.data.length);
                off += p.data.length;
            }
            r.ok = true;
            r.body = all;
            r.contentRange = "bytes " + start + "-" + end + "/" + (r.totalLength >= 0 ? r.totalLength : "*");
            r.ms = System.currentTimeMillis() - t0;
            return r;
        } catch (Throwable t) {
            r.error = String.valueOf(t);
            return r;
        }
    }

    // ================================================================== 小工具

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

    private static int codeOf(String statusLine) {
        try {
            return Integer.parseInt(statusLine.trim().split("\\s+")[1]);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static long parseTotal(String contentRange) {
        if (contentRange == null) return -1;
        int i = contentRange.indexOf('/');
        if (i < 0) return -1;
        String t = contentRange.substring(i + 1).trim();
        if ("*".equals(t)) return -1;
        return parseLong(t, -1);
    }

    public static long parseCrBound(String contentRange, int idx) {
        if (contentRange == null) return -1;
        int sp = contentRange.indexOf(' ');
        int slash = contentRange.indexOf('/');
        if (sp < 0 || slash < 0 || slash <= sp) return -1;
        String[] se = contentRange.substring(sp + 1, slash).split("-");
        if (se.length != 2) return -1;
        return parseLong(se[idx], -1);
    }

    /** 解析客户端的 `Range: bytes=start-end`；只接受闭区间。 */
    public static long[] parseClientRange(String range) {
        if (range == null) return null;
        String r = range.trim().toLowerCase(Locale.US);
        if (!r.startsWith("bytes=")) return null;
        r = r.substring(6).trim();
        if (r.contains(",")) return null;
        int dash = r.indexOf('-');
        if (dash <= 0) return null;
        String a = r.substring(0, dash).trim();
        String b = r.substring(dash + 1).trim();
        if (a.isEmpty() || b.isEmpty()) return null;
        try {
            long s = Long.parseLong(a);
            long e = Long.parseLong(b);
            if (s < 0 || e < s) return null;
            return new long[]{s, e};
        } catch (Throwable t) {
            return null;
        }
    }
}
