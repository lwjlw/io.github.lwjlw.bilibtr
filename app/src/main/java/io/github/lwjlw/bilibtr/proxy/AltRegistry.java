package io.github.lwjlw.bilibtr.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 候选地址登记处（阶段 C 的 P0：CDN 节点优选）。
 *
 * 背景（实测推出来的关键事实）：
 *   B站 DASH 的**每个清晰度就是一个完整 `.m4s` 文件**，播放器是在**同一个文件上做 Range 读取**
 *   （`mediaAssetToJson` 里的 `stream_size` 就是文件总长）。
 *   所以同一个清晰度的签名 URL **整场播放不变** ——
 *   候选池只要竞速一次，就能全程复用，不需要每段重算。
 *
 * 于是改写 URL 时顺手把同一个 JSON 条目里的**其它带签名的备用地址**（`backup_url0` 等）
 * 登记进来，并给播放器一个短 id：`/media?u=<主地址>&id=<n>`。
 * 代理拿到 id 就能看到整个候选池，从而：
 *   - 竞速后改用最快的那个地址（**它自带合法签名，不需要猜 host**）；
 *   - 全部失败时退回原地址。
 *
 * 这样避开了"换 host 可能破坏签名"的坑（akamai 的 `hdnts` 是按 CDN 各签的）。
 */
public final class AltRegistry {

    /** 最多记多少组（每组对应一个清晰度/一路流）。 */
    private static final int MAX_ENTRIES = 128;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, List<String>> MAP =
            new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };
    private static long seq = 0;

    private AltRegistry() {
    }

    /** 登记一组候选，返回短 id；候选少于 2 个则不登记（返回 null）。 */
    public static String register(List<String> urls) {
        if (urls == null || urls.size() < 2) return null;
        List<String> uniq = new ArrayList<>();
        for (String u : urls) {
            if (u != null && !uniq.contains(u)) uniq.add(u);
        }
        if (uniq.size() < 2) return null;
        synchronized (LOCK) {
            String id = Long.toString(++seq, 36);
            MAP.put(id, uniq);
            Recon.note("CDN:POOL", "id=" + id + " 候选 " + uniq.size() + " 个: " + hostsOf(uniq));
            return id;
        }
    }

    public static List<String> get(String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            List<String> v = MAP.get(id);
            return v == null ? null : new ArrayList<>(v);
        }
    }

    public static String hostsOf(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String u : urls) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Recon.hostOf(u));
        }
        return sb.toString();
    }
}
