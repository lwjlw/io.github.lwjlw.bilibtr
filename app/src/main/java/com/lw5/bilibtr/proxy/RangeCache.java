package com.lw5.bilibtr.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.lw5.bilibtr.recon.Recon;

/**
 * 预读缓存：把"播放器马上会要的字节"提前屯在内存里，让它下次请求**不用等**。
 *
 * ## 为什么需要（实测结论，见阶段 C 文档 §1.9）
 * 分片本身很快（中位 79ms、4.7 MB/s），但播放器是"**要一块 → 等 → 拿到 → 再要下一块**"。
 * 直连时每次往返约 30ms，走代理约 100ms（本地回环 + 分片扇出 + 每次新建连接），
 * 于是 **35 秒总吞吐被拖慢 3.5 倍**（2.0 MB/s → 0.57 MB/s）。
 *
 * 换句话说：**代理把播放器自己的预读掐断了**。
 * 解法不是"开更多连接"，而是**替它预读**：请求 [s,e] 之后顺手把 [e+1, e+8MB] 也拉回来，
 * 下次请求直接命中内存（0 延迟）。
 *
 * 存储：按 512 KiB 对齐的分块（与参考实现的分块粒度一致），
 * 整个 key 的缓存量超过 {@link #MAX_BYTES} 时按 LRU 淘汰。
 */
public final class RangeCache {

    /** 分块粒度。 */
    public static final int CHUNK = 512 * 1024;
    /** 单个 key 最多缓存多少字节。 */
    private static final long MAX_BYTES = 32L * 1024 * 1024L;
    /**
     * **所有 key 加起来**的内存上限。
     *
     * ⚠️ 原来只有"每 key 32 MB × 最多 8 key"的约束 → **理论最坏 256 MB**，
     * 对手机太重了。实测正常播放只有 1~2 个 key，但也可能因为切清晰度、
     * 音视频分开而同时存在多个。这里再加一道**全局闸门**。
     */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024L;
    /** 最多同时缓存几个 key。 */
    private static final int MAX_KEYS = 8;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Bucket> MAP =
            new LinkedHashMap<String, Bucket>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_KEYS;
                }
            };

    private RangeCache() {
    }

    private static final class Bucket {
        final TreeMap<Long, byte[]> chunks = new TreeMap<>();
        long total = -1;
        String contentType;

        long bytes() {
            long n = 0;
            for (byte[] b : chunks.values()) n += b.length;
            return n;
        }
    }

    private static Bucket entry(String key) {
        Bucket e = MAP.get(key);
        if (e == null) {
            e = new Bucket();
            MAP.put(key, e);
        }
        return e;
    }

    public static long totalOf(String key) {
        synchronized (LOCK) {
            Bucket e = MAP.get(key);
            return e == null ? -1 : e.total;
        }
    }

    public static String contentTypeOf(String key) {
        synchronized (LOCK) {
            Bucket e = MAP.get(key);
            return e == null ? null : e.contentType;
        }
    }

    /** 所有 key 当前占用的总字节数。 */
    private static long totalBytes() {
        long n = 0;
        for (Bucket b : MAP.values()) n += b.bytes();
        return n;
    }

    /** 该 key 目前连续缓存的末尾（用于日志/判断）。 */
    public static long cachedEnd(String key) {
        synchronized (LOCK) {
            Bucket e = MAP.get(key);
            if (e == null || e.chunks.isEmpty()) return -1;
            long end = -1;
            for (Map.Entry<Long, byte[]> c : e.chunks.entrySet()) {
                if (end < 0 || c.getKey() <= end + 1) end = c.getKey() + c.getValue().length - 1;
                else break;
            }
            return end;
        }
    }

    /** 是否完整覆盖 [start,end]。 */
    public static boolean covers(String key, long start, long end) {
        synchronized (LOCK) {
            Bucket e = MAP.get(key);
            if (e == null) return false;
            long need = start - (start % CHUNK);
            while (need <= end) {
                if (!e.chunks.containsKey(need)) return false;
                need += CHUNK;
            }
            return true;
        }
    }

    /** 读 [start,end]；未完整覆盖返回 null。 */
    public static byte[] read(String key, long start, long end) {
        synchronized (LOCK) {
            if (!covers(key, start, end)) return null;
            Bucket e = MAP.get(key);
            int len = (int) (end - start + 1);
            byte[] out = new byte[len];
            long pos = start;
            int off = 0;
            while (pos <= end) {
                long cs = pos - (pos % CHUNK);
                byte[] c = e.chunks.get(cs);
                int from = (int) (pos - cs);
                int n = Math.min(c.length - from, (int) (end - pos + 1));
                System.arraycopy(c, from, out, off, n);
                off += n;
                pos += n;
            }
            return out;
        }
    }

    /**
     * 写入一个**对齐好的**分块（调用方保证 offset 是 CHUNK 对齐、数据长度 ≤ CHUNK）。
     */
    public static void putChunk(String key, long offset, byte[] data, long total, String contentType) {
        if (data == null || data.length == 0) return;
        synchronized (LOCK) {
            Bucket e = entry(key);
            if (total > 0) e.total = total;
            if (contentType != null) e.contentType = contentType;
            e.chunks.put(offset, data);
            // 单 key 超限：从最前面丢（播放是顺序的，前面的基本不会再要）
            while (e.bytes() > MAX_BYTES && e.chunks.size() > 1) {
                Long first = e.chunks.firstKey();
                e.chunks.remove(first);
            }
            // 全局超限：从**最久没用过的 key** 开始整桶丢
            while (totalBytes() > MAX_TOTAL_BYTES && MAP.size() > 1) {
                String eldest = MAP.keySet().iterator().next();   // LinkedHashMap 访问序 → 最旧
                if (eldest.equals(key)) break;                     // 别把正在写的自己丢了
                MAP.remove(eldest);
            }
        }
        if (Recon.first("CACHE:PUT", key + "@" + offset)) {
            Recon.note("CACHE:PUT", "key=" + Recon.clip(key, 40) + " offset=" + offset
                    + " len=" + data.length + " 连续末尾=" + cachedEnd(key));
        }
    }

    /** 全部清空（切节点时用）。 */
    public static void clear() {
        synchronized (LOCK) {
            MAP.clear();
        }
    }

    public static void drop(String key) {
        synchronized (LOCK) {
            MAP.remove(key);
        }
    }

    public static List<String> keys() {
        synchronized (LOCK) {
            return new ArrayList<>(MAP.keySet());
        }
    }
}
