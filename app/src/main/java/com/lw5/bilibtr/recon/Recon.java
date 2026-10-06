package com.lw5.bilibtr.recon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 侦察公共设施：URL 脱敏、媒体域名判定、去重、栈提取、统一落点。
 *
 * 阶段 A 的唯一目的：搞清楚 B站 9.8.0 里「谁决定了播放器最终请求哪个媒体 URL」。
 * 所以所有输出都服务于回答三件事：
 *   1) 媒体 URL 在 Java 层出现过吗？出现在哪一帧（栈）？
 *   2) 谁把它交给了播放器（IJK）？
 *   3) playurl 返回的 DASH 里有哪些 host / 哪些参数？
 *
 * 安全约束（硬性）：任何 URL 落日志前必须过 {@link #sanitize(String)}，
 * 签名参数一律抹成 `key=*`。绝不把带签名的完整地址写进 logcat 或文件。
 */
public final class Recon {

    public static final String TAG = "BTR-Recon";
    public static final String VERSION = "recon-0.2.0";

    /** 目标宿主包名。 */
    public static final String HOST_PKG = "tv.danmaku.bili";

    /** 媒体域名白名单（取自 BTR range-core.js，只多不少）。 */
    private static final Pattern MEDIA_HOST = Pattern.compile(
            "(?:^|\\.)(?:bilivideo\\.(?:com|cn|net)|akamaized\\.net|szbdyd\\.com|hdslb\\.com"
                    + "|xycdn\\.com|mountaintoys\\.cn|nexusedgeio\\.com|ahdohpiechei\\.com)$",
            Pattern.CASE_INSENSITIVE);

    /** 媒体后缀（B站 DASH 分片是 .m4s）。 */
    private static final Pattern MEDIA_EXT = Pattern.compile(
            "\\.(?:m4s|mp4|flv|m4a|mpd|ts)(?:$|[?#])", Pattern.CASE_INSENSITIVE);

    /** 从任意字节/文本里捞 URL。 */
    public static final Pattern URL_RE = Pattern.compile("https?://[!-~]{6,}");

    /** 这些 query 参数是内容标识而非签名，保留原值有助于定位视频；其余一律抹掉。 */
    private static final Set<String> SAFE_QUERY = new HashSet<>(Arrays.asList(
            "bw", "qn", "fnval", "fnver", "fourk", "otype", "platform", "mobi_app", "build",
            "cid", "aid", "avid", "bvid", "ep_id", "season_id", "type", "record", "module"));

    /** 去重表：key -> 命中次数。防同一事件刷爆日志。 */
    private static final ConcurrentHashMap<String, int[]> SEEN = new ConcurrentHashMap<>();
    private static final int SEEN_MAX = 40000;

    private Recon() {
    }

    // ---------------------------------------------------------------- 判定

    public static String hostOf(String url) {
        if (url == null) return null;
        int i = url.indexOf("://");
        if (i < 0) return null;
        int s = i + 3;
        int e = s;
        while (e < url.length()) {
            char c = url.charAt(e);
            if (c == '/' || c == '?' || c == '#') break;
            e++;
        }
        String hp = url.substring(s, e);
        int c = hp.lastIndexOf(':');
        if (c > 0) hp = hp.substring(0, c);
        int at = hp.lastIndexOf('@');
        if (at >= 0) hp = hp.substring(at + 1);
        return hp.toLowerCase();
    }

    /** 是不是媒体字节地址（白名单域名 / 媒体后缀 / upgcxcode 路径）。 */
    public static boolean isMediaUrl(String url) {
        if (url == null || url.length() < 8) return false;
        String host = hostOf(url);
        if (host != null && MEDIA_HOST.matcher(host).find()) return true;
        if (MEDIA_EXT.matcher(url).find()) return true;
        return url.contains("/upgcxcode/");
    }

    /**
     * 是不是**视频/音频字节**地址 —— 比 isMediaUrl 更严：
     * 排除掉 hdslb.com 上的图片/弹幕这类静态资源（否则日志会被图像请求刷爆）。
     */
    public static boolean isVideoByteUrl(String url) {
        if (url == null || url.length() < 12) return false;
        String host = hostOf(url);
        if (host != null) {
            if (host.endsWith(".bilivideo.com") || host.endsWith(".bilivideo.cn")
                    || host.endsWith(".bilivideo.net") || "bilivideo.com".equals(host)) return true;
            if (host.endsWith("akamaized.net") || host.endsWith("szbdyd.com")
                    || host.endsWith("xycdn.com") || host.endsWith("mountaintoys.cn")
                    || host.endsWith("nexusedgeio.com") || host.endsWith("ahdohpiechei.com")) return true;
        }
        if (MEDIA_EXT.matcher(url).find()) return true;
        return url.contains("/upgcxcode/");
    }

    /** 只保留视频字节地址并脱敏（OK 请求与响应体扫描共用）。 */
    public static List<String> videoUrlsIn(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String u : urlsIn(text, max * 4)) {
            if (isVideoByteUrl(u)) {
                out.add(sanitize(u));
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** 去重后的 host 列表（阶段 B 的节点表素材）。 */
    public static String distinctHosts(List<String> urls) {
        TreeSet<String> hosts = new TreeSet<>();
        for (String u : urls) {
            String h = hostOf(u);
            if (h != null) hosts.add(h);
        }
        return join(hosts, ",");
    }

    /** 是不是 B站 接口地址（用于观察 playurl 这类 API 调用）。 */
    public static boolean isBiliApi(String url) {
        String host = hostOf(url);
        if (host == null) return false;
        return host.endsWith("bilibili.com") || host.endsWith("biliapi.net")
                || host.endsWith("biliapi.com") || host.endsWith("bilibili.co");
    }

    public static boolean looksLikePlayUrl(String urlOrBody) {
        if (urlOrBody == null) return false;
        String s = urlOrBody.toLowerCase();
        return s.contains("playurl") || s.contains("playview") || s.contains("play_url");
    }

    // ---------------------------------------------------------------- 脱敏

    /**
     * 只保留 `scheme://host[:port]/path` + query 的 **键名**。
     * 少数内容标识参数（bw/cid/bvid...）保原值，其余值全部替换为 `*`。
     */
    public static String sanitize(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        if (q < 0) return clip(base, 300);
        String query = url.substring(q + 1);
        int h = query.indexOf('#');
        if (h >= 0) query = query.substring(0, h);
        TreeSet<String> keys = new TreeSet<>();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e);
            String v = e < 0 ? "" : kv.substring(e + 1);
            if (k.isEmpty()) continue;
            k = clip(k, 32);
            if (SAFE_QUERY.contains(k)) keys.add(k + "=" + clip(v, 24));
            else keys.add(k + "=*");
        }
        return clip(base, 220) + "?" + join(keys, "&");
    }

    public static String clip(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    /**
     * 把文本里出现的所有 URL 就地换成脱敏形式。
     * 用于"要打印一整段 JSON/数组"的场景——否则签名地址会随原文落盘。
     */
    public static String scrub(String s) {
        if (s == null) return "null";
        if (s.indexOf("http") < 0) return s;
        Matcher m = URL_RE.matcher(s);
        StringBuffer sb = new StringBuffer(s.length() + 64);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(sanitize(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String join(Iterable<String> it, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : it) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 去重

    /** 首次见到返回 true；之后返回 false（调用方可选择记数）。 */
    public static boolean first(String kind, String key) {
        String k = kind + "\u0000" + key;
        int[] cur = SEEN.get(k);
        if (cur == null) {
            if (SEEN.size() > SEEN_MAX) SEEN.clear();
            int[] fresh = new int[1];
            int[] prev = SEEN.putIfAbsent(k, fresh);
            if (prev == null) return true;
            cur = prev;
        }
        synchronized (cur) {
            cur[0]++;
        }
        return false;
    }

    public static int hits(String kind, String key) {
        int[] cur = SEEN.get(kind + "\u0000" + key);
        if (cur == null) return 0;
        synchronized (cur) {
            return cur[0];
        }
    }

    // ---------------------------------------------------------------- 栈

    /**
     * 取当前调用栈，**丢掉我们自己的帧和网络库/反射样板帧**。
     * 剩下的第一帧就是"谁在干这件事"——这是阶段 A 最关键的产出。
     */
    public static String stack(int maxFrames) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("com.lw5.bilibtr")) continue;
            if (cn.startsWith("io.github.libxposed")) continue;
            if (cn.startsWith("java.lang.Thread")) continue;
            if (cn.startsWith("okhttp3.") || cn.startsWith("okio.")) continue;
            if (cn.startsWith("com.android.okhttp")) continue;
            if (cn.startsWith("java.lang.reflect.")) continue;
            if (cn.startsWith("android.os.Handler") || cn.startsWith("android.os.Looper")) continue;
            if (cn.startsWith("com.android.internal.os")) continue;
            if (cn.startsWith("android.app.Instrumentation")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(simple(cn)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber());
            if (++n >= maxFrames) break;
        }
        return sb.length() == 0 ? "(no-frame)" : sb.toString();
    }

    /** com.a.b.C -> a.b.C，省点日志宽度。 */
    private static String simple(String cn) {
        if (cn.startsWith("com.") || cn.startsWith("android.") || cn.startsWith("androidx.")) {
            int i = cn.indexOf('.', cn.indexOf('.') + 1);
            if (i > 0 && i + 1 < cn.length()) return cn.substring(i + 1);
        }
        return cn;
    }

    // ---------------------------------------------------------------- 输出

    /** 一条侦察记录：logcat + 落盘。detail 里不得出现带签名的完整 URL。 */
    public static void record(String kind, String detail) {
        record(kind, detail, 10);
    }

    public static void record(String kind, String detail, int frames) {
        String line = "[" + kind + "] " + detail + " | stack=" + stack(frames);
        Sink.write(line);
    }

    /** 不带栈的记录（用于高频、低信息量的事件）。 */
    public static void note(String kind, String detail) {
        Sink.write("[" + kind + "] " + detail);
    }

    // ---------------------------------------------------------------- URL 提取

    /** 从一个文本块里捞出所有 URL（用于 dump 响应体里的 DASH 地址）。 */
    public static List<String> urlsIn(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = URL_RE.matcher(text);
        while (m.find() && out.size() < max) {
            String u = m.group();
            // 去掉 JSON/HTML 尾随的引号逗号
            int cut = u.length();
            while (cut > 0) {
                char c = u.charAt(cut - 1);
                if (c == '"' || c == '\'' || c == ',' || c == '}' || c == ']' || c == '\\'
                        || c == '<' || c == ';' || c == ')' || c == ' ') cut--;
                else break;
            }
            out.add(u.substring(0, cut));
        }
        return out;
    }

    /** 只保留媒体 URL 并脱敏，用于响应体扫描结果。 */
    public static List<String> mediaUrlsIn(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String u : urlsIn(text, max * 4)) {
            if (isMediaUrl(u)) {
                out.add(sanitize(u));
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    public static String join(List<String> list, String sep) {
        return join((Iterable<String>) list, sep);
    }
}
