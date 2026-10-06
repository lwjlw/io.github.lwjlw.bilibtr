package com.lw5.bilibtr.proxy;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import com.lw5.bilibtr.recon.Recon;

/**
 * URL 改写器：把媒体直链换成 `http://127.0.0.1:<port>/media?u=<base64>&k=...`。
 *
 * 两种形态都要处理：
 *   - **纯 URL 字符串**（例如某个字段直接是地址）；
 *   - **JSON / 半结构化文本里嵌的 URL**（B站 的 `mediaAssetToJson()` 就是把整个
 *     媒体资产序列化成 String[]，里面每一路流的 baseUrl / backupUrl 都是完整签名地址）。
 *
 * 后者不能用 JSON 解析器（结构随版本变），用"扫 http 开头、按 JSON 字符串边界收尾"的方式最稳。
 *
 * 护栏：只改白名单内的视频字节地址；签名 query 原样进 base64，不外发。
 */
public final class UrlRewriter {

    private UrlRewriter() {
    }

    /** 单个纯 URL → 本地代理地址；不是媒体地址就返回 null。 */
    public static String proxyUrl(String original) {
        return proxyUrl(original, null);
    }

    /**
     * 单个纯 URL → 本地代理地址。
     *
     * @param poolId 候选池 id（同一条目里还有别的带签名地址时登记得到）；
     *               代理据此拿到整组候选去竞速。没有则传 null。
     */
    public static String proxyUrl(String original, String poolId) {
        if (original == null || original.length() < 16) return null;
        if (original.startsWith("http://127.0.0.1")) return null;
        if (!Recon.isVideoByteUrl(original)) return null;
        int port = LocalProxy.ensureStarted();
        if (port <= 0) return null;
        String b64 = Base64.encodeToString(original.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String url = "http://127.0.0.1:" + port + "/media?u=" + b64 + "&k=" + kindOf(original);
        if (poolId != null) url = url + "&id=" + poolId;
        return url;
    }

    /** 一组 JSON 字符串（或任意文本）里的媒体 URL 全部替换。 */
    public static String rewriteText(String s) {
        return rewriteText(s, null);
    }

    public static String rewriteText(String s, String poolId) {
        if (s == null || s.length() < 16) return s;
        int p0 = s.indexOf("http");
        if (p0 < 0) return s;

        StringBuilder out = new StringBuilder(s.length() + 128);
        int i = 0;
        int n = s.length();
        boolean changed = false;
        while (i < n) {
            int p = s.indexOf("http", i);
            if (p < 0) break;
            out.append(s, i, p);
            int j = scanEnd(s, p);
            String raw = s.substring(p, j);
            String plain = unescapeJson(raw);
            String rep = proxyUrl(plain, poolId);
            if (rep != null) {
                out.append(rep);
                changed = true;
                if (Recon.first("REWRITE", Recon.sanitize(plain))) {
                    Recon.record("REWRITE", "orig=" + Recon.sanitize(plain)
                            + " -> 127.0.0.1:" + LocalProxy.port() + " kind=" + kindOf(plain), 6);
                }
            } else {
                out.append(raw);
            }
            i = j;
        }
        out.append(s, i, n);
        return changed ? out.toString() : s;
    }

    /** 就地改写 String[]（返回是否改动过）。 */
    public static boolean rewriteArray(String[] arr) {
        if (arr == null) return false;
        boolean changed = false;
        for (int i = 0; i < arr.length; i++) {
            String v = arr[i];
            if (v == null) continue;
            // 每个元素 = 一路流（一个清晰度），里面可能同时有 url0 / backup_url0 等多个
            // **自带签名的地址** —— 它们正是 CDN 候选池，登记后交给代理竞速。
            String nv = rewriteElement(v);
            if (nv != v) {
                arr[i] = nv;
                changed = true;
            }
        }
        return changed;
    }

    /** 改写单个 JSON 条目：先登记候选池，再把里面所有媒体 URL 指向本地代理。 */
    public static String rewriteElement(String element) {
        if (element == null || element.indexOf("http") < 0) return element;
        java.util.List<String> cands = collectMediaUrls(element);
        String poolId = AltRegistry.register(cands);
        return rewriteText(element, poolId);
    }

    /** 按出现顺序收集文本里的媒体地址（已去重）。 */
    public static java.util.List<String> collectMediaUrls(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) return out;
        int i = 0;
        int n = s.length();
        while (i < n) {
            int p = s.indexOf("http", i);
            if (p < 0) break;
            int j = scanEnd(s, p);
            String plain = unescapeJson(s.substring(p, j));
            if (Recon.isVideoByteUrl(plain) && !out.contains(plain)) out.add(plain);
            i = j;
        }
        return out;
    }

    /**
     * 从 `http` 起点扫到一个 URL 的结尾。
     *
     * ⚠️ 这里有两个**踩过的坑**：
     *   1. **逗号不是终止符**：B站 媒体 URL 自带逗号（`k=audio,mid,...`、`orderid=0,2`），
     *      早先把 `,` 当终止符 → 只替换了 URL 前半段 → 上游签名校验失败 → 403；
     *   2. **要吃掉 JSON 转义**：`\/`、`\u0026` 都算 URL 的一部分，否则同样会截断。
     */
    private static int scanEnd(String s, int p) {
        int j = p;
        int n = s.length();
        while (j < n) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < n) {
                char nx = s.charAt(j + 1);
                if (nx == 'u' && j + 5 < n) {
                    j += 6;
                    continue;
                }
                j += 2;
                continue;
            }
            if (c == '"' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break;
            j++;
        }
        return j;
    }

    /** B站 音频流 id：30216(64k) / 30232(132k) / 30280(192k) / 30250(杜比)。 */
    public static String kindOf(String url) {
        int slash = url.indexOf("/upgcxcode/");
        String path = slash >= 0 ? url.substring(slash) : url;
        if (path.contains("-30216.") || path.contains("-30232.") || path.contains("-30280.")
                || path.contains("-30250.") || path.contains("-30251.")) {
            return "audio";
        }
        return url.contains(".m4s") ? "video" : "other";
    }

    /** 把 JSON 字符串里的转义还原（只处理 URL 里可能出现的那些）。 */
    public static String unescapeJson(String raw) {
        if (raw.indexOf('\\') < 0) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                sb.append(c);
                continue;
            }
            char n = raw.charAt(i + 1);
            if (n == '/') {
                sb.append('/');
                i++;
            } else if (n == 'u' && i + 5 < raw.length()) {
                try {
                    sb.append((char) Integer.parseInt(raw.substring(i + 2, i + 6), 16));
                    i += 5;
                } catch (Throwable t) {
                    sb.append(c);
                }
            } else if (n == '\\' || n == '"' || n == '\'') {
                sb.append(n);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
