package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.proxy.UrlRewriter;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 阶段 A.5 的真正注入点（第二版）。
 *
 * 第一版挂在 `java.net.URL` / `Uri.parse` 上：实测**播放时那两个点根本没被调用**
 * （之前看到的 URI-PARSE 是 BiliRoaming 制造的），native 拿到的还是原始地址 → 失败。
 *
 * 结构侦察（`DumpProbe`）之后定位到真实通道：
 *
 * ```text
 * IjkMediaPlayerItem.mediaAssetToJson() -> String[]      ← 媒体资产序列化（URL 就在里面）
 *        │  AIDL（IIjkMediaPlayerItem）
 *        ▼
 * IjkMediaPlayerItemClient.setDataSourceJson(String[], int, int)   ← 落到 native 侧的那一端
 *        │
 *        ├─ _setDataSource(String, String[], String[])
 *        └─ _setDataSourceJson(String[], int, int)
 * ```
 *
 * 实测运行时确实看到：
 *   `[ITEM:setDataSource]     IjkMediaPlayerItemClient.setDataSource(String) args="ijkdash"`
 *   `[ITEM:setDataSourceJson] IjkMediaPlayerItemClient.setDataSourceJson(String[],int,int)`
 *
 * 所以这里在**这两个点**上把 JSON 里的媒体 URL 换成 `127.0.0.1`：
 *   - `setDataSourceJson`：数组是 binder 刚反序列化出来的，改完再 proceed，native 拿到改过的；
 *   - `mediaAssetToJson`：返回值数组就地改，让上游拿到的就是本地地址（双保险）。
 *
 * 与第一版一样受 `btr.conf` 的 `enabled` 开关控制。
 */
public final class AssetRewriteProbe {

    private AssetRewriteProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> itemClient = Reflect.load(cl, "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient");
        Class<?> item = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayerItem");

        if (itemClient != null) {
            for (Method m : Reflect.declaredNamed(itemClient,
                    "setDataSourceJson", "setDataSource", "_setDataSource", "_setDataSourceJson",
                    "_setDashAudioMixDataSource")) {
                Hooks.install(x, m, "asset-client-" + m.getName(), hooker("ITEMCLIENT", m));
            }
        } else {
            Recon.note("PROBE", "AssetRewriteProbe: 没找到 IjkMediaPlayerItemClient");
        }

        if (item != null) {
            for (Method m : Reflect.declaredNamed(item, "mediaAssetToJson", "mediaAssetToUrl")) {
                Hooks.install(x, m, "asset-item-" + m.getName(), hooker("ITEM", m));
            }
        } else {
            Recon.note("PROBE", "AssetRewriteProbe: 没找到 IjkMediaPlayerItem");
        }
    }

    private static io.github.libxposed.api.XposedInterface.Hooker hooker(String where, Method m) {
        final String tag = where + "." + Reflect.sig(m);
        return chain -> {
            boolean changed = false;
            Object[] newArgs = null;

            if (ProxyConfig.enabled()) {
                try {
                    for (int i = 0; i < chain.getArgs().size(); i++) {
                        Object a = chain.getArg(i);
                        if (a instanceof String[]) {
                            if (UrlRewriter.rewriteArray((String[]) a)) changed = true;
                        } else if (a instanceof String) {
                            String s = (String) a;
                            String nv = UrlRewriter.rewriteText(s);
                            if (nv != s) {
                                if (newArgs == null) newArgs = chain.getArgs().toArray();
                                newArgs[i] = nv;
                                changed = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Recon.note("REWRITE:ERR", tag + " " + t);
                }
            }

            Object r = (changed && newArgs != null) ? chain.proceed(newArgs) : chain.proceed();

            try {
                if (r instanceof String[]) {
                    if (ProxyConfig.enabled() && UrlRewriter.rewriteArray((String[]) r)) changed = true;
                } else if (r instanceof String) {
                    String s = (String) r;
                    if (ProxyConfig.enabled()) {
                        String nv = UrlRewriter.rewriteText(s);
                        if (nv != s) {
                            r = nv;
                            changed = true;
                        }
                    }
                }
            } catch (Throwable t) {
                Recon.note("REWRITE:ERR", tag + " ret " + t);
            }

            logQualities(tag, chain.getArgs(), r);

            // 只记第一次 + 内容有变化的那次，避免刷屏；内容一律过 scrub（URL 脱敏）
            String dump = Recon.clip(Recon.scrub(dumpArgs(chain.getArgs())), 320);
            if (Recon.first("ASSET:" + (changed ? "HIT" : "SEEN"), tag + dump)) {
                Recon.note("ASSET:" + (changed ? "HIT" : "SEEN"),
                        tag + " args=" + dump + " ret=" + Recon.clip(Recon.scrub(dumpOne(r)), 200));
            }
            return r;
        };
    }

    /**
     * 记录"这次交给播放器的资产里有哪些清晰度档 + 播放器传了哪两个整数"。
     *
     * 目的：搞清楚**怎么强制 4K** —— B站 有个毛病：刚进视频默认低分辨率，
     * 过一会才切 4K，甚至不全屏就不加载 4K。
     * 我们需要知道：资产里本来就带 4K 地址（那就能强制选），还是压根没请求 4K（那要另想办法）。
     *
     * B站 清晰度档位（URL 里的 `-1-<id>.m4s`）：
     * 30064=720P、30080=1080P、30116=1080P60、**30120=4K**、30125=HDR、30127=8K
     */
    private static void logQualities(String tag, java.util.List<Object> args, Object ret) {
        try {
            java.util.TreeSet<String> ids = new java.util.TreeSet<>();
            StringBuilder ints = new StringBuilder();
            StringBuilder per = new StringBuilder();
            for (Object a : args) {
                if (a instanceof Number) {
                    if (ints.length() > 0) ints.append(',');
                    ints.append(a);
                }
                if (a instanceof String[]) {
                    for (String e : (String[]) a) appendEntry(per, e);
                }
                collectIds(a, ids);
            }
            if (ret instanceof String[]) {
                for (String e : (String[]) ret) appendEntry(per, e);
            }
            collectIds(ret, ids);
            if (ids.isEmpty() && per.length() == 0) return;
            if (per.length() > 0) {
                String key = per.toString();
                if (Recon.first("ASSET:QLIST", key)) {
                    Recon.note("ASSET:QLIST", tag + " 各档位: " + key + " intArgs=[" + ints + "]");
                }
            }
            if (ids.isEmpty()) return;
            String line = "ids=" + String.join(",", ids) + " n=" + ids.size()
                    + " intArgs=[" + ints + "]";
            if (Recon.first("ASSET:QIDS", line)) {
                Recon.note("ASSET:QIDS", tag + " " + line);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 一个资产元素 → "档位:大小"（档位取自解码后的原始 URL）。 */
    private static void appendEntry(StringBuilder per, String elem) {
        if (elem == null || elem.length() < 16) return;
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        scanIds(elem, ids);
        String size = "?";
        java.util.regex.Matcher sz = java.util.regex.Pattern
                .compile("\"stream_size\"\\s*:\\s*\"?(\\d+)").matcher(elem);
        if (sz.find()) size = sz.group(1);
        per.append(per.length() > 0 ? " | " : "").append(String.join("/", ids)).append(':').append(size);
    }

    private static void collectIds(Object o, java.util.Set<String> out) {
        if (o instanceof String) {
            scanIds((String) o, out);
        } else if (o instanceof String[]) {
            for (String s : (String[]) o) scanIds(s, out);
        }
    }

    private static void scanIds(String s, java.util.Set<String> out) {
        if (s == null || s.length() < 16) return;
        // ① 直接扫（改写前的上游地址）
        java.util.regex.Matcher m = QID.matcher(s);
        while (m.find()) out.add(m.group(2));
        // ② 我们的代理地址里原始 URL 被 base64url 塞进 u= ，解码后再扫
        java.util.regex.Matcher u = UB64.matcher(s);
        while (u.find()) {
            try {
                byte[] raw = android.util.Base64.decode(u.group(1),
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING
                                | android.util.Base64.NO_WRAP);
                java.util.regex.Matcher m2 = QID.matcher(new String(raw, "UTF-8"));
                while (m2.find()) out.add(m2.group(2));
            } catch (Throwable ignored) {
            }
        }
    }

    private static final java.util.regex.Pattern UB64 =
            java.util.regex.Pattern.compile("[?&]u=([A-Za-z0-9_\\-]{24,})");

    private static final java.util.regex.Pattern QID =
            java.util.regex.Pattern.compile("-(\\d)-(\\d{5})\\.m4s");

    private static String dumpArgs(java.util.List<Object> args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(dumpOne(a));
        }
        return sb.toString();
    }

    private static String dumpOne(Object a) {
        if (a == null) return "null";
        if (a instanceof String) return (String) a;
        if (a instanceof String[]) {
            String[] arr = (String[]) a;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length && i < 4; i++) {
                if (i > 0) sb.append(" ; ");
                sb.append(arr[i]);
            }
            if (arr.length > 4) sb.append(" ; ...(").append(arr.length).append(")");
            return sb.append(']').toString();
        }
        if (a instanceof Number || a instanceof Boolean) return String.valueOf(a);
        return a.getClass().getSimpleName();
    }
}
