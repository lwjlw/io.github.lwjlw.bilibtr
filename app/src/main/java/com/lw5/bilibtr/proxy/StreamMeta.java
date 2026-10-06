package com.lw5.bilibtr.proxy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lw5.bilibtr.recon.Recon;

/**
 * 当前在播视频的元信息（标题等），给界面显示用。
 *
 * ## 标题为什么从这里来，而不是界面自己去请求
 * 界面拿到的只有 `cid`。让界面去调 B站 公开接口有两个风险：
 * ① 需要额外权限与网络（能通但不稳）；② 接口可能要风控参数。
 *
 * 而 **B站 自己在加载视频时必然请求过视频信息接口**（`x/web-interface/view` 等），
 * 那条响应体里就有 `"title"`。
 * {@link com.lw5.bilibtr.recon.probes.BodyProbe} 本来就在扫所有响应体，
 * 顺手把标题抠出来即可 —— **零额外请求**。
 */
public final class StreamMeta {

    /** `"title":"..."`，带最基本的转义处理。 */
    private static final Pattern TITLE = Pattern.compile("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static volatile String title = "";

    private StreamMeta() {
    }

    public static String title() {
        return title;
    }

    /**
     * B站 自己的 Cookie / UA（从它的 OkHttp 请求里抄来的）。
     *
     * 为什么需要：裸调 `x/web-interface/view` 会被**反爬拦掉**
     * （实测 `FileNotFoundException`，即 4xx，多为 412），因为它要 buvid 之类的 cookie。
     * 而 B站 自己的请求本来就带着合法 cookie —— 直接复用即可。
     */
    private static volatile String cookie = "";
    private static volatile String userAgent = "";

    /**
     * B站 的风控凭据 `buvid`（从播放器对象的 `mBuvid` 字段拿）。
     * 裸调接口返回的 **412** 就是缺它。
     */
    private static volatile String buvid = "";

    public static void captureBuvid(String b) {
        if (b != null && b.length() > 8 && !b.equals(buvid)) {
            buvid = b;
            Recon.note("META:BUV", "已取得 buvid（长度 " + b.length() + "）");
        }
    }

    public static void captureAuth(String ck, String ua) {
        if (ck != null && ck.length() > 8 && !ck.equals(cookie)) cookie = ck;
        if (ua != null && ua.length() > 8 && !ua.equals(userAgent)) userAgent = ua;
    }

    /** 已经查过的 cid / 正在查的 cid，避免重复请求。 */
    private static volatile String askedCid = "";

    /**
     * **按 cid 去查标题**（注入侧自己发请求）。
     *
     * ## 为什么由注入侧来查
     * 前面两条路都走不通（实测）：
     * ① 扫 API 响应体：`x/web-interface/view` 的 body **不走** `string()/bytes()`，扫不到；
     * ② 界面按 cid 查：要额外权限 + 明文/网络配置，还得跨进程回传。
     *
     * 而注入代码就跑在 **B站 的进程**里 —— 网络权限、UA、风控环境都是现成的。
     * 于是：注入侧拿到 cid 后**自己查一次**，结果直接进 `/info`。
     * 后台线程 + 只查一次 + 失败静默，绝不影响播放。
     */
    /**
     * **从系统"最近任务"里读标题。**
     *
     * 为什么这条最靠谱：B站 打开视频页时，最近任务卡片上显示的就是**视频名**
     * （用户切后台时能看到）。而注入代码跑在 **B站 的进程**里，
     * `ActivityManager.getAppTasks()` 返回的正是它自己的任务，直接读 label 即可。
     *
     * **不需要网络、不需要 cookie、不会被反爬拦。**
     */
    private static void tryTaskTitle() {
        try {
            android.content.Context ctx = com.lw5.bilibtr.recon.AppHolder.get();
            if (ctx == null) return;
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return;
            java.util.List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null) return;
            for (android.app.ActivityManager.AppTask task : tasks) {
                android.app.ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null || info.taskDescription == null) continue;
                CharSequence label = info.taskDescription.getLabel();
                if (label == null) continue;
                String t = label.toString().trim();
                if (t.length() < 4 || t.length() > 120) continue;
                if (t.toLowerCase().contains("bilibili")) continue;
                setTitle(t);
                return;
            }
        } catch (Throwable t) {
            Recon.note("META:TASK-ERR", String.valueOf(t));
        }
    }

    public static void requestTitle(String cid) {
        if (cid == null || cid.isEmpty()) return;
        if (cid.equals(askedCid)) return;
        askedCid = cid;
        Thread t = new Thread(() -> {
            // ① 先试"最近任务标签"（不需要网络）
            for (int i = 0; i < 6; i++) {
                tryTaskTitle();
                if (!title.isEmpty()) return;
                try {
                    Thread.sleep(2000);   // 进视频页后 label 可能要一两秒才更新
                } catch (InterruptedException ie) {
                    return;
                }
            }
            // ② 再试公开接口（需要 cookie，多半会被 412 拦）
            fetch(cid);
        }, "btr-title");
        t.setDaemon(true);
        t.start();
    }

    private static void fetch(String cid) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(
                    "https://api.bilibili.com/x/web-interface/view?cid=" + cid);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            String ua = userAgent;
            c.setRequestProperty("User-Agent", ua.isEmpty()
                    ? "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 BiliBTR" : ua);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            String ck = cookie;
            String bv = buvid;
            if (ck.isEmpty() && !bv.isEmpty()) {
                ck = "buvid3=" + bv + "; buvid4=" + bv;
            }
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 256 * 1024) {
                bos.write(buf, 0, n);
            }
            in.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            java.util.regex.Matcher m = TITLE.matcher(text);
            if (text.contains("\"bvid\"") && m.find()) {
                setTitle(unescape(m.group(1)));
            } else {
                Recon.note("META:FAIL", "按 cid 查标题没成功：" + Recon.clip(text, 160));
            }
        } catch (Throwable t) {
            String extra = "";
            try {
                if (c != null) extra = " HTTP=" + c.getResponseCode();
            } catch (Throwable ignored) {
            }
            Recon.note("META:FAIL", "按 cid 查标题异常：" + t + extra
                    + "（有 cookie=" + !cookie.isEmpty() + "）");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static void setTitle(String t) {
        if (t == null || t.isEmpty()) return;
        if (t.equals(title)) return;
        title = t;
        Recon.note("META:TITLE", "识别到视频标题：" + t);
    }

    /**
     * 从一段响应体里尝试抠标题。
     *
     * 只认"看起来像视频信息"的响应（必须同时出现 `cid`/`bvid`/`aid` 与 `title`），
     * 否则会误抓到 banner、广告位之类的 title。
     */
    public static void scan(String text) {
        if (text == null || text.length() < 32) return;
        // ⚠️ 判据必须**足够具体**。踩过两次：
        //   ① 只要求 cid/title → 抓到别的接口的 UI 文案（"配置互动组件"）；
        //   ② 只要求 bvid/title → 仍被抓到（有别的接口也带 bvid）。
        //   `x/web-interface/view` 的特征是 **owner + bvid + pic + title** 同时出现，用这个组合。
        if (!text.contains("\"bvid\"")) return;
        if (!text.contains("\"owner\"")) return;
        if (!text.contains("\"pic\"")) return;
        if (text.indexOf("\"title\"") < 0) return;
        Matcher m = TITLE.matcher(text);
        if (m.find()) {
            setTitle(unescape(m.group(1)));
        } else if (Recon.first("META:NO-TITLE", Recon.clip(text, 80))) {
            Recon.note("META:NO-TITLE", "像视频信息但没抠到 title：" + Recon.clip(text, 200));
        }
    }

    /**
     * 按**字节**扫（UTF-8 优先）。
     *
     * 为什么需要单独这个入口：`BodyProbe` 里为了扫 URL，把字节按 ISO-8859-1 映射
     * （对 ASCII 的 URL 最安全，也不会因非法 UTF-8 丢字符）。
     * 但**中文标题**用那个映射就成了乱码（实测："配置互动组件" 变成 "éç½®..."），
     * 所以标题这条路必须自己按 UTF-8 解。
     */
    public static void scanBytes(byte[] b, int len) {
        if (b == null || len < 32) return;
        try {
            scan(new String(b, 0, len, "UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    /** 处理 JSON 里常见的反斜杠转义：引号、反斜杠、斜杠，以及 u 开头的四位十六进制。 */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (Throwable ignored) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                    break;
                default: sb.append(n);
            }
        }
        return sb.toString();
    }
}
