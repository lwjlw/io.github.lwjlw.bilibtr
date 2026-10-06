package com.lw5.bilibtr.proxy;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lw5.bilibtr.recon.Recon;

/**
 * **界面 ↔ 注入侧 的配置/测速通道（回环 TCP）。**
 *
 * ## 为什么不用 ContentProvider
 * Android 11+ 的**软件包可见性**会让 B站 进程看不到我们模块 App 的 provider：
 * ```
 * [SETTINGS] 读 provider 失败：Unknown authority com.lw5.bilibtr.settings
 * ```
 * 而让 B站 的清单里声明 `<queries>` 是做不到的（那是它的 APK）。
 *
 * **回环 TCP 没有任何这类限制**：任何进程都能连 `127.0.0.1`，
 * 不需要权限、不需要可见性 —— 代理本身也是靠这个跑起来的。
 *
 * ## 协议（故意不用 JSON，注入侧不引任何库）
 * | 方法 | 路径 | 说明 |
 * | --- | --- | --- |
 * | GET | `/ping` | 返回 `ok` |
 * | GET | `/config` | 返回当前生效设置，每行 `key=value` |
 * | POST | `/config` | 请求体每行 `key=value`，直接覆盖生效 |
 * | GET | `/stats` | 返回测速 JSON（给面板用） |
 *
 * 端口固定 **18889**（代理端口 +1 易冲突，干脆独立固定）。
 */
public final class ConfigServer {

    public static final int PORT = 18889;

    /** 界面推过来的覆盖值（优先级最高，压过文件和 provider）。 */
    private static final Map<String, String> LIVE = new LinkedHashMap<>();
    private static volatile String statsJson = "";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private ConfigServer() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                Recon.note("PROBE", "ConfigServer 已启动：http://127.0.0.1:" + PORT + "（界面用它读写设置）");
                while (true) {
                    final Socket c = ss.accept();
                    Thread w = new Thread(() -> handle(c), "btr-cfg");
                    w.setDaemon(true);
                    w.start();
                }
            } catch (Throwable t2) {
                Recon.note("CFG:ERR", "ConfigServer 启动失败：" + t2);
            } finally {
                try {
                    if (ss != null) ss.close();
                } catch (Throwable ignored) {
                }
            }
        }, "btr-cfg-server");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 生效值

    /** 界面推过来的值（没有则返回 null）。 */
    public static String live(String key) {
        synchronized (LIVE) {
            return LIVE.get(key);
        }
    }

    public static boolean hasLive() {
        synchronized (LIVE) {
            return !LIVE.isEmpty();
        }
    }

    private static void applyLine(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) return;
        String k = line.substring(0, eq).trim().toLowerCase();
        String v = line.substring(eq + 1).trim();
        if (k.isEmpty()) return;
        synchronized (LIVE) {
            LIVE.put(k, v);
        }
        ProxyConfig.forceReload();
        Recon.note("CFG:SET", "界面更新设置 " + k + "=" + v);
        // ★ 关键：**写回 btr.conf**。
        //   B站 是分进程的（ConfigServer 可能起在 A 进程，播放却在 B 进程），
        //   只放内存的话别的进程读不到。写文件后所有进程都能读到，而且能持久化。
        writeConfFile();
    }

    /** 把界面推来的设置落盘到 B站 目录下的 btr.conf（所有进程都能读）。 */
    private static void writeConfFile() {
        try {
            java.io.File dir = com.lw5.bilibtr.recon.AppHolder.reconDir();
            if (dir == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("# BiliBTR —— 这个文件由界面自动生成（也可手工编辑）\n");
            synchronized (LIVE) {
                sb.append("enabled=").append(LIVE.getOrDefault("enabled", "true")).append('\n');
                sb.append("mode=").append(LIVE.getOrDefault("mode", "proxy")).append('\n');
                sb.append("concurrency=").append(LIVE.getOrDefault("concurrency", "8")).append('\n');
                sb.append("minsplitkb=").append(LIVE.getOrDefault("minsplitkb", "128")).append('\n');
                sb.append("port=").append(LIVE.getOrDefault("port", "18888")).append('\n');
                sb.append("bufsizekb=").append(LIVE.getOrDefault("bufsizekb", "0")).append('\n');
                sb.append("buftimems=").append(LIVE.getOrDefault("buftimems", "0")).append('\n');
                sb.append("qn=").append(LIVE.getOrDefault("qn", "0")).append('\n');
                sb.append("ball=").append(LIVE.getOrDefault("ball", "true")).append('\n');
                // 侦察探针开关：**不在 LIVE 里就沿用当前值**。
                // 不能写成 getOrDefault("recon","false")：那样手工设的 recon=1
                // 会被下一次界面推送悄悄抹掉（这个文件是整体覆写的）。
                sb.append("recon=").append(LIVE.getOrDefault("recon",
                        ProxyConfig.reconProbes() ? "true" : "false")).append('\n');
                String sp = LIVE.get("speed");
                if (sp != null) sb.append("speed=").append(sp).append('\n');
            }
            java.io.File f = new java.io.File(dir, "btr.conf");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable t) {
            Recon.note("CFG:ERR", "写 btr.conf 失败：" + t);
        }
    }

    public static void setStats(String json) {
        statsJson = json == null ? "" : json;
    }

    /** 从 `a=1&b=2` 里取一个参数。 */
    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return "";
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (name.equals(kv.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Throwable t) {
                    return kv.substring(eq + 1);
                }
            }
        }
        return "";
    }

    /**
     * 给界面用的"当前在放什么 + 全部候选测速结果"。
     * 手拼 JSON（注入侧不引库），字段：cid / url / activeHost / pinned / auto / stAt / hosts[]。
     */
    private static String infoJson() {
        StringBuilder sb = new StringBuilder(1024);
        String url = CdnRacer.lastUrl();
        sb.append('{');
        sb.append("\"cid\":\"").append(nz(CdnRacer.cidOf(url))).append('"');
        sb.append(",\"url\":\"").append(esc(stripQuery(url))).append('"');
        sb.append(",\"used\":\"").append(esc(stripQuery(CdnRacer.lastUsed()))).append('"');
        sb.append(",\"pinApplied\":").append(CdnRacer.pinApplied());
        sb.append(",\"activeHost\":\"").append(esc(CdnRacer.activeHost())).append('"');
        sb.append(",\"pinned\":\"").append(esc(CdnRacer.pinned())).append('"');
        sb.append(",\"auto\":").append(CdnRacer.auto());
        sb.append(",\"stAt\":").append(CdnRacer.speedTestAt());
        sb.append(",\"stDone\":").append(CdnRacer.speedTestDone());
        sb.append(",\"stTotal\":").append(CdnRacer.speedTestTotal());
        sb.append(",\"hosts\":[");
        boolean first = true;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String u : CdnRacer.candidates()) {
            String h = com.lw5.bilibtr.recon.Recon.hostOf(u);
            if (h == null) continue;
            if (!seen.add(h)) continue;          // 同一节点只列一次
            if (!first) sb.append(',');
            first = false;
            String st = CdnRacer.speedTestResult(h);
            sb.append("{\"h\":\"").append(esc(h)).append('"');
            sb.append(",\"st\":\"").append(st == null ? "" : st).append('"');
            sb.append(",\"active\":").append(h.equals(CdnRacer.activeHost()));
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 去掉签名 query：只留 `host/path`（签名不该外发，界面也不需要）。 */
    private static String stripQuery(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        return q > 0 ? u.substring(0, q) : u;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String configText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LIVE) {
            for (Map.Entry<String, String> e : LIVE.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTTP

    private static void handle(Socket c) {
        try {
            c.setSoTimeout(5000);
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String reqLine = r.readLine();
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            String path = rawPath;
            String query = "";
            int qm = rawPath.indexOf('?');
            if (qm >= 0) {
                path = rawPath.substring(0, qm);
                query = rawPath.substring(qm + 1);
            }

            int contentLength = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Throwable ignored) {
                    }
                }
            }

            String body = "";
            if ("POST".equalsIgnoreCase(method) && contentLength > 0) {
                char[] buf = new char[Math.min(contentLength, 8192)];
                int read = 0;
                while (read < buf.length) {
                    int n = r.read(buf, read, buf.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
                for (String l : body.split("\n")) {
                    if (!l.trim().isEmpty()) applyLine(l.trim());
                }
            }

            String resp;
            if (path.startsWith("/config")) {
                resp = configText();
            } else if (path.startsWith("/stats")) {
                resp = statsJson;
            } else if (path.startsWith("/info")) {
                resp = infoJson();
            } else if (path.startsWith("/speed")) {
                // 调试/联动用：/speed?v=3 强制 3x，v=0 表示跟随 B站
                float v = 0f;
                try {
                    v = Float.parseFloat(queryParam(query, "v"));
                } catch (Throwable ignored) {
                }
                PlaySpeed.setOverride(v);
                resp = "ok\n";
            } else if (path.startsWith("/speedtest")) {
                CdnRacer.speedTestAll();
                resp = "ok\n";
            } else if (path.startsWith("/pin")) {
                CdnRacer.pin(queryParam(query, "h"));
                resp = "ok\n";
            } else if (path.startsWith("/auto")) {
                CdnRacer.setAuto(!"0".equals(queryParam(query, "on")));
                resp = "ok\n";
            } else {
                resp = "ok\n";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
