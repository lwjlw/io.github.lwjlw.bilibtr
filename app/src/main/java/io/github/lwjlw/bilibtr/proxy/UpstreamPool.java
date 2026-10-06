package io.github.lwjlw.bilibtr.proxy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 到 CDN 的**连接池（keep-alive 复用）**。
 *
 * ## 为什么需要（实测定位）
 * 之前每个客户端请求都新建一条到 CDN 的连接，于是：
 * - **每请求首字节 TTFB 中位 340ms** ≈ 一次 TCP 握手 + 一次请求往返；
 * - 每请求总耗时中位 **761ms**；起播因此比直连慢 ~900ms。
 * 而播放器**直连时是复用一条连接的**（只付一次握手）。这个类就是把复利补回来。
 *
 * ## 复用的硬前提（错一条就会串数据/串响应）
 * 1. **必须按 `Content-Length` 精确读完 body 才能归还** —— 不能读到 EOF（会阻塞），
 *    也不能少读（残余字节会被当成下一个响应的开头）；
 * 2. 上游回 `Connection: close`、或用 `Transfer-Encoding: chunked`、
 *    或没有 `Content-Length` → **一律不可复用**，直接关掉；
 * 3. 空闲超过 {@link #IDLE_TIMEOUT_MS} 的连接丢掉（服务端可能已单方面关闭）；
 * 4. 复用时若写/读失败，调用方要能**重试一次新连接**（上层已有重试）。
 */
public final class UpstreamPool {

    private static final int MAX_IDLE_PER_HOST = 4;
    /** 空闲上限。**必须短于服务端的 keep-alive 超时**（否则攥着一条已被对端关掉的连接）。 */
    private static final long IDLE_TIMEOUT_MS = 8_000L;

    /** 一条到上游的 HTTP/1.1 连接（含已包装的流）。 */
    public static final class Conn {
        public Socket sock;
        public BufferedInputStream in;
        public OutputStream out;
        public String host;
        public int port;
        /** 本响应结束后是否允许复用（由响应头决定）。 */
        public boolean reusable;
        /** 是否是从池里拿到的（用于日志统计）。 */
        public boolean reused;
        long idleSince;

        public void close() {
            try {
                if (sock != null) sock.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Map<String, Deque<Conn>> IDLE = new ConcurrentHashMap<>();

    private UpstreamPool() {
    }

    private static String key(String host, int port) {
        return host + ":" + port;
    }

    /** 拿一条连接：优先复用，没有就新建。 */
    public static Conn acquire(String host, int port, int connectTimeoutMs, int soTimeoutMs)
            throws IOException {
        Deque<Conn> dq = IDLE.get(key(host, port));
        if (dq != null) {
            for (Conn c = dq.pollFirst(); c != null; c = dq.pollFirst()) {
                if (System.currentTimeMillis() - c.idleSince > IDLE_TIMEOUT_MS
                        || c.sock.isClosed() || !c.sock.isConnected()) {
                    c.close();
                    continue;
                }
                if (!isAlive(c)) {
                    c.close();
                    continue;
                }
                try {
                    c.sock.setSoTimeout(soTimeoutMs);
                } catch (Throwable ignored) {
                }
                c.reused = true;
                c.reusable = true;
                return c;
            }
        }
        Conn c = new Conn();
        c.host = host;
        c.port = port;
        c.sock = new Socket();
        c.sock.setTcpNoDelay(true);
        c.sock.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        c.sock.setSoTimeout(soTimeoutMs);
        c.in = new BufferedInputStream(c.sock.getInputStream(), 16384);
        c.out = c.sock.getOutputStream();
        c.reused = false;
        return c;
    }

    /**
     * **复用前探活**：用 1 毫秒超时试着读一个字节 ——
     * - 读到 -1：对端已关 → 这条不能用；
     * - 读到数据：连接里有上一个响应残留 → 不干净，不能用；
     * - 读超时：**没有任何数据可读 = 连接还活着** → 可以复用。
     *
     * 不做这一步就会踩坑：池子里的连接被服务端悄悄关掉，
     * 复用后表现为"首片短读 / 收到半截响应"（实测踩到过）。
     */
    private static boolean isAlive(Conn c) {
        int so;
        try {
            so = c.sock.getSoTimeout();
        } catch (Throwable t) {
            return false;
        }
        try {
            c.sock.setSoTimeout(1);
            try {
                int b = c.in.read();
                return false;               // 有数据残留或已关闭（-1）都不复用
            } catch (java.net.SocketTimeoutException te) {
                return true;                // 没数据可读 = 健康
            }
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                c.sock.setSoTimeout(so);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 正常读完 body 后归还。返回值表示是否真的进了池子。 */
    public static boolean release(Conn c) {
        if (c == null) return false;
        if (c.sock == null || c.sock.isClosed() || !c.reusable) {
            c.close();
            return false;
        }
        c.idleSince = System.currentTimeMillis();
        Deque<Conn> dq = IDLE.computeIfAbsent(key(c.host, c.port), k -> new ArrayDeque<>());
        synchronized (dq) {
            if (dq.size() >= MAX_IDLE_PER_HOST) {
                c.close();
                return false;
            }
            dq.addFirst(c);
        }
        return true;
    }

    /** 出任何问题（半读、异常、不可复用）都走这里：直接丢弃，绝不归池。 */
    public static void discard(Conn c) {
        if (c == null) return;
        c.close();
    }

    /** 丢弃所有空闲连接（界面切节点时调用：保证不再复用旧节点的连接）。 */
    public static void clearAll() {
        for (java.util.Deque<Conn> dq : IDLE.values()) {
            synchronized (dq) {
                Conn c;
                while ((c = dq.pollFirst()) != null) c.close();
            }
        }
        IDLE.clear();
    }

    /** 日志用。 */
    public static String stats() {
        int n = 0;
        for (Deque<Conn> dq : IDLE.values()) {
            synchronized (dq) {
                n += dq.size();
            }
        }
        return "idle=" + n + " hosts=" + IDLE.size();
    }

    public static void noteReuse(Conn c) {
        if (c != null && c.reused && Recon.first("POOL:REUSE", c.host)) {
            Recon.note("POOL:REUSE", "复用连接 " + c.host + "（" + stats() + "）");
        }
    }
}
