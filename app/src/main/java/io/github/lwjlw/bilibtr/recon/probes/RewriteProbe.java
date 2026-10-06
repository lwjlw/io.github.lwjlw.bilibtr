package io.github.lwjlw.bilibtr.recon.probes;

import android.net.Uri;
import android.util.Base64;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import io.github.lwjlw.bilibtr.proxy.LocalProxy;
import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 阶段 A.5：把播放器的媒体 URL 改写到本地代理。
 *
 * 为什么挂在 URL/Uri 构造上而不是挂 `MediaResource` 本身：
 *   - 实测（见 `docs/BTR-阶段A-媒体请求画像.md`）证明 URL 是在
 *     `lib.media.resource.MediaResource.a:15`（主地址）与 `a:22`（backup）里
 *     用 `new java.net.URL(...)` 造出来的，`Uri.parse` 同理；
 *   - 只要在这两个"字符串变对象"的点上把字符串换掉，下游拿到的一定是本地地址，
 *     **完全不需要理解 `IjkMediaItem` 的内部结构**（那才是版本脆弱的地方）。
 *
 * 但**不能无条件替换**：`java.net.URL`/`Uri.parse` 是全局热路径，
 * 因此加了三道闸：
 *   1) `ProxyConfig.enabled()`（外部文件开关，可随时关掉）；
 *   2) `Recon.isVideoByteUrl(url)`（只碰白名单内的视频字节地址）；
 *   3) **调用栈里必须出现 `MediaResource` / `IjkMediaItemTransformer`**
 *      —— 只有播放器选流那条路才改，别的（开屏广告、上报、下载）一律不动。
 *
 * 顺带挂一个观测点：`IjkMediaPlayerTracker#updateUrl`。
 * native（ffmpeg）真正要发 HTTP 之前会回调到 Java，我们能借此确认
 * **改写后的地址确实到了 native**，以及它实际选了哪个节点。
 */
public final class RewriteProbe {

    /** 允许改写的调用方（栈里出现任意一个即可）。 */
    private static final String[] GATE_CLASSES = {
            "lib.media.resource.MediaResource",
            "tv.danmaku.videoplayer.coreV2.transformer.IjkMediaItemTransformer",
            "tv.danmaku.videoplayer.core.media.mediacenter.MediaCenter",
    };

    private RewriteProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> url = Reflect.load(cl, "java.net.URL");
        Class<?> uri = Reflect.load(cl, "android.net.Uri");

        Constructor<?> urlCtor = Reflect.ctor(url, String.class);
        Hooks.install(x, urlCtor, "rw-url-ctor", argZeroHooker("URL-CTOR"));

        Method parse = uri == null ? null : Reflect.exact(uri, "parse", String.class);
        Hooks.install(x, parse, "rw-uri-parse", argZeroHooker("URI-PARSE"));

        installTrackerWatch(x, cl);
    }

    /** 第 0 个参数是待处理的 URL 字符串；命中就换成代理地址。 */
    private static Hooker argZeroHooker(String tag) {
        return chain -> {
            Object a0 = chain.getArg(0);
            if (a0 instanceof String) {
                String rw = Rewriter.rewrite((String) a0, tag);
                if (rw != null) {
                    return chain.proceed(new Object[]{rw});
                }
            }
            return chain.proceed();
        };
    }

    /** 观测：native 即将请求的 URL（确认改写生效 + 看它选了哪个节点）。 */
    private static void installTrackerWatch(XposedInterface x, ClassLoader cl) {
        Class<?> tracker = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayerTracker");
        if (tracker == null) {
            Recon.note("PROBE", "RewriteProbe: 没找到 IjkMediaPlayerTracker（观测点跳过）");
            return;
        }
        List<Method> ms = Reflect.declaredNamed(tracker, "updateUrl");
        for (Method m : ms) {
            Hooks.install(x, m, "tracker-updateUrl", chain -> {
                try {
                    for (Object a : chain.getArgs()) {
                        if (!(a instanceof String)) continue;
                        String s = (String) a;
                        if (!s.startsWith("http")) continue;
                        String key = Recon.hostOf(s) + Recon.clip(pathOf(s), 80);
                        if (Recon.first("NATIVE:URL", key)) {
                            Recon.note("NATIVE:URL", "host=" + Recon.hostOf(s)
                                    + " local=" + s.startsWith("http://127.0.0.1")
                                    + " path=" + pathOf(s));
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        }
    }

    private static String pathOf(String url) {
        try {
            return Uri.parse(url).getPath();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ 改写逻辑

    static final class Rewriter {

        static String rewrite(String s, String tag) {
            try {
                if (s == null || s.length() < 16) return null;
                if (s.startsWith("http://127.0.0.1")) return null;
                if (!ProxyConfig.enabled()) return null;
                if (!Recon.isVideoByteUrl(s)) return null;
                if (!gatePassed()) {
                    // 诊断：媒体地址出现了，但调用方不在白名单里 —— 说明还有别的选流路径
                    if (Recon.first("GATE-MISS", Recon.sanitize(s))) {
                        Recon.record("GATE-MISS", "via=" + tag + " orig=" + Recon.sanitize(s), 12);
                    }
                    return null;
                }

                int port = LocalProxy.ensureStarted();
                if (port <= 0) return null;

                String b64 = Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String kind = kindOf(s);
                String out = "http://127.0.0.1:" + port + "/media?u=" + b64 + "&k=" + kind;

                if (Recon.first("REWRITE", Recon.sanitize(s))) {
                    Recon.record("REWRITE", "via=" + tag + " kind=" + kind
                            + " -> http://127.0.0.1:" + port
                            + " orig=" + Recon.sanitize(s), 10);
                }
                return out;
            } catch (Throwable t) {
                Recon.note("REWRITE:ERR", String.valueOf(t));
                return null;
            }
        }

        /** 只有播放器选流那条调用链才放行。 */
        private static boolean gatePassed() {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                for (String g : GATE_CLASSES) {
                    if (cn.startsWith(g)) return true;
                }
            }
            return false;
        }

        /** B站 音频流 id：30216(64k) / 30232(132k) / 30280(192k) / 30250(杜比)。 */
        private static String kindOf(String url) {
            int i = url.indexOf('/');
            String path = url;
            int slash = url.indexOf("/upgcxcode/");
            if (slash >= 0) path = url.substring(slash);
            if (path.contains("-30216.") || path.contains("-30232.")
                    || path.contains("-30280.") || path.contains("-30250.")
                    || path.contains("-30251.")) {
                return "audio";
            }
            if (i > 0 && url.contains("m4s")) return "video";
            return "other";
        }
    }
}
