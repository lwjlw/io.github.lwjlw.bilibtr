package io.github.lwjlw.bilibtr.proxy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 后台预读：客户端要了 `[s,e]` 之后，顺手把后面 {@link #WINDOW_BYTES} 也拉进
 * {@link RangeCache}，让播放器的下一个请求**直接命中内存（0 延迟）**。
 *
 * 为什么必须这么做（实测，详见阶段 C 文档 §1.9）：
 * 播放器是"要一块 → 等 → 拿到 → 再要下一块"的节奏，直连时每次往返 ~30ms，
 * 走代理 ~100ms（本地回环 + 分片扇出 + 每次新建连接），总吞吐因此被拖慢 3.5 倍。
 * **代理把播放器自己的预读掐断了** —— 这个类就是把预读补回去。
 *
 * 规矩：
 *   - 每个 key **最多一个预读线程**（不抢带宽、不重复拉）；
 *   - 只顺着播放方向向前拉，拉满窗口就停（下一个请求会把窗口往前推）；
 *   - 拉到的分块按 512 KiB 对齐存进 {@link RangeCache}；
 *   - 任何失败都只是**停止预读**，绝不影响正常请求路径。
 */
public final class Prefetcher {

    /** 预读窗口：比播放位置提前这么多字节。 */
    private static final long WINDOW_BYTES = 8L * 1024 * 1024L;

    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
    /** key → [nextOffset, demandEnd]。 */
    private static final Map<String, long[]> STATE = new ConcurrentHashMap<>();

    private Prefetcher() {
    }

    /**
     * 保证 `[from, demand+WINDOW]` 会被预读。
     *
     * @param from 从这个位置开始预读（一般是"刚服务完的区间的下一个字节"）
     */
    public static void ensure(String key, String url, Map<String, String> headers, long from) {
        if (key == null || url == null || from < 0) return;
        try {
            long[] st = STATE.compute(key, (k, v) -> {
                if (v == null) return new long[]{from, from};
                synchronized (v) {
                    if (from > v[0]) v[0] = from;
                    if (from > v[1]) v[1] = from;
                }
                return v;
            });
            if (st == null) return;
            if (!RUNNING.add(key)) return;
            Thread t = new Thread(() -> loop(key, url, headers), "btr-prefetch");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    private static void loop(String key, String url, Map<String, String> headers) {
        long fetched = 0;
        try {
            while (true) {
                long[] st = STATE.get(key);
                if (st == null) break;
                long next, demand;
                synchronized (st) {
                    next = st[0];
                    demand = st[1];
                }
                long total = RangeCache.totalOf(key);
                if (total > 0 && next >= total) break;              // 到文件尾了
                if (next >= demand + WINDOW_BYTES) break;           // 窗口已满足，收工

                long cs = next - (next % RangeCache.CHUNK);
                long ce = cs + RangeCache.CHUNK - 1;
                if (total > 0 && ce >= total) ce = total - 1;
                if (ce < cs) break;

                RangeFetcher.Result r = RangeFetcher.fetch(url, "GET", headers, cs, ce, 1);
                if (!r.ok) {
                    Recon.note("PREFETCH:FAIL", "key=" + Recon.clip(key, 40) + " @" + cs
                            + " -> " + Recon.clip(r.error, 60));
                    break;
                }
                RangeCache.putChunk(key, cs, r.body, r.totalLength, r.contentType);
                fetched += r.body.length;
                synchronized (st) {
                    st[0] = ce + 1;
                }
            }
        } catch (Throwable t) {
            Recon.note("PREFETCH:ERR", String.valueOf(t));
        } finally {
            RUNNING.remove(key);
            if (fetched > 0) {
                Recon.note("PREFETCH:DONE", "key=" + Recon.clip(key, 40)
                        + " 预读 " + (fetched / 1024) + "KB，连续末尾=" + RangeCache.cachedEnd(key));
            }
        }
    }

    /** 清空全部预读状态（界面切节点时调用，保证立刻生效）。 */
    public static void clearAll() {
        STATE.clear();
        RangeCache.clear();
    }

    public static void forget(String key) {
        STATE.remove(key);
    }
}
