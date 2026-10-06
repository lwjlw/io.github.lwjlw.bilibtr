package com.lw5.bilibtr.recon.probes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import com.lw5.bilibtr.recon.Hooks;
import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.Reflect;

/**
 * 结构侦察探针（阶段 A.5 第二步用）。
 *
 * 背景：第一版改写挂在 `java.net.URL(String)` / `Uri.parse(String)` 上，
 * 实测发现**播放时根本没有 URL 在这两个点被构造**（之前看到的那些是 BiliRoaming 制造的），
 * 而 native 拿到的仍是原始地址 —— 说明选流那条路用的是别的类型/别的时机。
 *
 * 所以先把候选类的**方法表与字段表**打出来，再决定精确的注入点。
 * 只读反射，不 hook 行为，零风险。
 */
public final class DumpProbe {

    /** 候选类：从 playurl 结果到 native 取 URL 的整条链。 */
    private static final String[] TARGETS = {
            "lib.media.resource.MediaResource",
            "tv.danmaku.ijk.media.player.IjkMediaPlayerItem",
            "tv.danmaku.ijk.media.player.IjkMediaPlayerTracker",
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient",
            "tv.danmaku.videoplayer.coreV2.transformer.IjkMediaItemTransformer",
            "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaItem",
            "tv.danmaku.videoplayer.coreV2.MediaItem",
            "tv.danmaku.videoplayer.coreV2.IMediaPlayParams",
            "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams",
    };

    private static final int MAX_MEMBERS = 90;

    private DumpProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        for (String name : TARGETS) {
            dump(cl, name);
        }
        hookItemDataSources(x, cl);
    }

    private static void dump(ClassLoader cl, String name) {
        Class<?> c = Reflect.load(cl, name);
        if (c == null) {
            Recon.note("DUMP", name + " : NOT FOUND");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("  supers=").append(c.getSuperclass() == null ? "-" : c.getSuperclass().getName());
        Class<?>[] itfs = c.getInterfaces();
        if (itfs.length > 0) {
            sb.append("  impls=");
            for (int i = 0; i < itfs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(itfs[i].getSimpleName());
            }
        }
        Recon.note("DUMP", sb.toString());

        // 字段：URL/字符串字段最可能就是要改的地方
        try {
            Field[] fs = c.getDeclaredFields();
            int n = 0;
            for (Field f : fs) {
                if (n++ >= MAX_MEMBERS) break;
                String t = f.getType().getSimpleName();
                if (!isInterestingField(t, f.getName())) continue;
                Recon.note("DUMP:F", name + "  " + Modifier.toString(f.getModifiers() & 0x0F)
                        + " " + t + " " + f.getName());
            }
        } catch (Throwable ignored) {
        }

        // 方法：挑和 url / datasource / media 相关的
        try {
            Method[] ms = c.getDeclaredMethods();
            int n = 0;
            for (Method m : ms) {
                if (n >= MAX_MEMBERS) break;
                String mn = m.getName().toLowerCase();
                if (!(mn.contains("url") || mn.contains("datasource") || mn.contains("data_source")
                        || mn.contains("media") || mn.contains("stream") || mn.contains("video")
                        || mn.contains("audio") || mn.contains("json") || mn.contains("save")
                        || mn.contains("get") || mn.contains("set"))) {
                    continue;
                }
                n++;
                Recon.note("DUMP:M", name + "  " + Reflect.sig(m) + " -> " + m.getReturnType().getSimpleName());
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isInterestingField(String type, String name) {
        String t = type.toLowerCase();
        String n = name.toLowerCase();
        if (t.contains("string") || t.contains("uri") || t.contains("url")) return true;
        return n.contains("url") || n.contains("stream") || n.contains("media") || n.contains("asset");
    }

    /**
     * AIDL 那套 `setDataSourceJson / Key / Id` 大概率就是 URL 进入 native 的通道
     * （dex 里有这些事务名）。这里把它们全挂上，看看到底传了什么。
     */
    private static void hookItemDataSources(XposedInterface x, ClassLoader cl) {
        List<String> classes = new ArrayList<>();
        classes.add("tv.danmaku.ijk.media.player.IjkMediaPlayerItem");
        classes.add("tv.danmaku.ijk.media.player.IjkMediaPlayerItem$IjkMediaPlayerItemBinder");
        classes.add("tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient");
        for (String cn : classes) {
            Class<?> c = Reflect.load(cl, cn);
            if (c == null) continue;
            List<Method> ms = Reflect.declaredNamed(c,
                    "setDataSourceJson", "setDataSourceKey", "setDataSourceId", "setDataSourceBase64",
                    "setDataSource", "getUrl", "getVideoUrl", "getAudioUrl", "saveUrl", "restoreUrl");
            for (Method m : ms) {
                Hooks.install(x, m, "item-" + c.getSimpleName() + "-" + m.getName(), chain -> {
                    Object r = chain.proceed();
                    try {
                        String args = renderArgs(chain.getArgs());
                        if (args != null || r != null) {
                            String key = cn + "#" + m.getName();
                            if (Recon.first("ITEM:" + m.getName(), key + args)) {
                                Recon.note("ITEM:" + m.getName(),
                                        cn + "." + Reflect.sig(m) + " args=" + args
                                                + " ret=" + renderOne(r));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return r;
                });
            }
        }
    }

    private static String renderArgs(List<Object> args) {
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            String s = renderOne(a);
            if (s == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 只对字符串/URL 类做摘要，避免刷屏；带签名的一律脱敏。 */
    private static String renderOne(Object a) {
        if (a == null) return null;
        if (a instanceof String) {
            String s = (String) a;
            if (s.startsWith("http")) return Recon.sanitize(s);
            if (s.length() > 400) return Recon.clip(s, 400);
            return "\"" + Recon.clip(s, 200) + "\"";
        }
        if (a instanceof Number || a instanceof Boolean) return String.valueOf(a);
        String cn = a.getClass().getName();
        if (cn.startsWith("android.net.Uri")) return Recon.sanitize(a.toString());
        if (cn.startsWith("java.net.URL")) return Recon.sanitize(a.toString());
        return null;
    }
}
