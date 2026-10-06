package io.github.lwjlw.bilibtr.proxy;

import java.util.ArrayList;
import java.util.List;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 候选节点的**生成规则**（地区无关）。
 *
 * 用户明确要求：**不能把候选池特化成"香港/海外节点"**，否则换个地区就没效果了。
 * 所以这里同时纳入三类，**谁实测快就用谁**，不做任何地理偏好：
 *
 *   1. 上游 playurl 自己给的地址（每个地区本来就是最合适的，且自带合法签名）；
 *   2. 上游给的**备用地址**（`backup_url0` 等，也自带合法签名）；
 *   3. **同家族换 host** 得到的变体（搬 path+query、只换主机名、清空端口）——
 *      用的是 BTR 实测整理的节点表，**大陆 8 个 + 海外 4 个都在里面**。
 *
 * 第 3 类里签名不匹配的会**在实测中被淘汰**（返回 403 → 校验失败 → 不采用），
 * 所以"多放候选"是安全的：不会因为猜错节点而把播放搞坏。
 */
public final class CdnCandidates {

    /** BTR 实测整理的大陆节点（`src/cdn-resolver.js`，硬编码冻结数组）。 */
    private static final String[] MAINLAND = {
            "upos-sz-mirrorali.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
            "upos-sz-mirrorbos.bilivideo.com",
            "upos-sz-mirror08c.bilivideo.com",
            "upos-sz-mirrorbd.bilivideo.com",
            "upos-sz-mirror14b.bilivideo.com",
            "upos-sz-estgoss.bilivideo.com",
            "upos-sz-mirrorcos.bilivideo.com",
    };

    /** BTR 实测整理的海外节点。 */
    private static final String[] OVERSEAS = {
            "upos-sz-mirrorcosov.bilivideo.com",
            "upos-sz-mirroraliov.bilivideo.com",
            "cn-hk-eq-01-01.bilivideo.com",
            "cn-hk-eq-01-03.bilivideo.com",
    };

    /**
     * 候选上限。
     *
     * ⚠️ 曾经是 8，结果**界面上只看得到 7~8 个节点**，用户反馈
     * "不应该同时显示十二个节点挨个测吗"。现在放宽到全表（8 大陆 + 4 海外 = 12）。
     * 竞速是**分窗**跑的（一次 2 条），候选变多只是多几轮，不会一次性打满带宽。
     */
    private static final int MAX_CANDIDATES = 16;

    private CdnCandidates() {
    }

    public static String[] table() {
        String[] all = new String[MAINLAND.length + OVERSEAS.length];
        System.arraycopy(MAINLAND, 0, all, 0, MAINLAND.length);
        System.arraycopy(OVERSEAS, 0, all, MAINLAND.length, OVERSEAS.length);
        return all;
    }

    /**
     * 展开候选列表（去重、保序、限量）。
     *
     * @param primary    本次请求要用的上游地址（playurl 给的）
     * @param alternates 同一路流的其它自带签名地址（可以是 null）
     */
    public static List<String> expand(String primary, List<String> alternates) {
        List<String> out = new ArrayList<>();
        if (primary != null) out.add(primary);
        if (alternates != null) {
            for (String a : alternates) {
                if (a != null && !out.contains(a)) out.add(a);
            }
        }
        // 换 host 用一个"底座地址"：优先 bilivideo 家族的那条
        //（同签名体系，换出来的地址才可能有效；akamai 的 hdnts 是另签的）。
        //
        // ⚠️ 之前**只有** bilivideo 家族才做扩展，于是当主地址是 akamai 时
        //    候选就只剩 2 条，界面上节点列表**一会儿 2 个一会儿 13 个**（用户实测反馈）。
        //    现在无论主地址是什么家族，都把**全表节点**列出来：
        //    签名不匹配的会在测速里显示"不可用"，这本身就是有用的信息。
        String base = null;
        String host = Recon.hostOf(primary);
        if (host != null && host.endsWith(".bilivideo.com")) {
            base = primary;
        } else if (alternates != null) {
            for (String a : alternates) {
                String ah = Recon.hostOf(a);
                if (ah != null && ah.endsWith(".bilivideo.com")) {
                    base = a;
                    break;
                }
            }
        }
        if (base == null) base = primary;   // 兜底：用主地址当底座
        for (String h : table()) {
            String v = swapHost(base, h);
            if (v != null && !containsHost(out, h)) out.add(v);
            if (out.size() >= MAX_CANDIDATES) break;
        }
        if (out.size() > MAX_CANDIDATES) {
            out = new ArrayList<>(out.subList(0, MAX_CANDIDATES));
        }
        return out;
    }

    private static boolean containsHost(List<String> urls, String host) {
        for (String u : urls) {
            if (host.equalsIgnoreCase(Recon.hostOf(u))) return true;
        }
        return false;
    }

    /**
     * 换 host：**原样搬 path + query，只改主机名，并显式清空端口**。
     *
     * 「清空端口」是 BTR 的实测结论：有些 PCDN 地址带 `:4483`，
     * 不清端口换出来的地址全都连不上。
     */
    public static String swapHost(String url, String newHost) {
        try {
            int sp = url.indexOf("://");
            if (sp < 0) return null;
            int hostStart = sp + 3;
            int pathStart = url.indexOf('/', hostStart);
            if (pathStart < 0) pathStart = url.length();
            String pathAndQuery = url.substring(pathStart);
            String scheme = url.substring(0, sp + 3);
            return scheme + newHost + pathAndQuery;
        } catch (Throwable t) {
            return null;
        }
    }
}
