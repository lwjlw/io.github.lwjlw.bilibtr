package com.lw5.bilibtr.recon.probes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import com.lw5.bilibtr.recon.Hooks;
import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.Reflect;

/**
 * 探针 4：播放器入口 —— 阶段 A 的**主target**。
 *
 * 静态情报（对 base.apk 31 个 dex 的实测结果）：
 *   - B站 9.8.0 的播放器是 **IJK**（`libijk.so` / `libijkffmpeg.so` / `libijksdl.so` 全套在包里）；
 *   - Java 侧存在 `tv.danmaku.ijk.media.player.IjkMediaPlayer`，并且是一套
 *     `IjkMediaPlayerService` + AIDL（`IIjkMediaPlayerService`）的架构 —— 播放器可能在**独立进程**；
 *   - 播放器框架是 `tv.danmaku.videoplayer.coreV2.*`，其中
 *     `adapter/ijk/IjkMediaPlayAdapter`、`transformer/IjkMediaItemTransformer` 最像
 *     "把 playurl 结果变成播放器 URL"的那一层。
 *
 * 但我们**不硬编码**这些混淆/易变的东西：这里只挂第三方（IJK/Android）的公开 API ——
 * `setDataSource*`。谁调用它，栈会自己招供。
 *
 * 同时挂 `IjkMediaPlayer` 的构造函数和 http/protocol 相关的 setOption，
 * 用来判断**媒体字节到底是 Java 拉的还是 native 拉的**（这直接决定阶段 C 的接入方式）。
 */
public final class PlayerProbe {

    /** 这些名字开头的都挂上（覆盖 String / Context+Uri / FileDescriptor / IMediaDataSource 等重载）。 */
    private static final String[] DS_PREFIXES = {"setDataSource"};

    /** 需要额外观察 setOption 的选项关键字。 */
    private static final String[] OPTION_KEYS = {"http", "protocol", "dns", "socket", "cache", "ijkio"};

    private PlayerProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> ijk = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayer");
        if (ijk != null) {
            Recon.note("PROBE", "PlayerProbe: IjkMediaPlayer 命中 " + ijk.getName());
            hookDataSourceMethods(x, ijk, "IJK");
            hookConstructors(x, ijk, "IJK");
            hookOptions(x, ijk);
        } else {
            Recon.note("PROBE", "PlayerProbe: 没找到 tv.danmaku.ijk.media.player.IjkMediaPlayer");
        }

        // 兜底：系统播放器 / 解封装器也会拿到媒体 URL（下载、缓存、缩略图路径）
        for (String cn : new String[]{
                "android.media.MediaPlayer",
                "android.media.MediaExtractor",
                "android.media.MediaMetadataRetriever"}) {
            Class<?> c = Reflect.load(cl, cn);
            if (c != null) hookDataSourceMethods(x, c, "SYS");
        }
    }

    // ------------------------------------------------------------ setDataSource

    private static void hookDataSourceMethods(XposedInterface x, Class<?> c, String tag) {
        for (Method m : declaredLike(c, DS_PREFIXES)) {
            Hooks.install(x, m, tag.toLowerCase() + "-ds-" + m.getName(), chain -> {
                try {
                    logArgs(tag + ":setDataSource", m.getName(), chain.getThisObject(), chain.getArgs());
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        }
    }

    private static void hookConstructors(XposedInterface x, Class<?> c, String tag) {
        Constructor<?>[] ctors;
        try {
            ctors = c.getDeclaredConstructors();
        } catch (Throwable t) {
            return;
        }
        int i = 0;
        for (Constructor<?> ctor : ctors) {
            Hooks.install(x, ctor, tag.toLowerCase() + "-ctor-" + (i++), chain -> {
                try {
                    String detail = "args=" + render(chain.getArgs());
                    if (Recon.first("IJK:CTOR", detail)) {
                        Recon.record("IJK:CTOR", detail, 10);
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        }
    }

    /**
     * setOption 是 IJK 配置 native 行为的通道。我们只关心 http/dns/protocol 这类，
     * 因为"媒体请求走了 native ffmpeg 的 http"与"走了 ijkio/okhttp"是两种完全不同的世界。
     */
    private static void hookOptions(XposedInterface x, Class<?> c) {
        for (Method m : declaredLike(c, new String[]{"setOption", "native_setOption"})) {
            Hooks.install(x, m, "ijk-opt-" + m.getName(), chain -> {
                try {
                    for (Object a : chain.getArgs()) {
                        if (!(a instanceof String)) continue;
                        String s = (String) a;
                        String low = s.toLowerCase();
                        for (String k : OPTION_KEYS) {
                            if (low.contains(k)) {
                                if (Recon.first("IJK:OPTION", s)) {
                                    Recon.note("IJK:OPTION", "name=" + Recon.clip(s, 80)
                                            + " args=" + render(chain.getArgs()));
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        }
    }

    // ------------------------------------------------------------ 记录

    private static void logArgs(String kind, String method, Object self, List<Object> args) {
        String url = null;
        for (Object a : args) {
            String s = asUrlString(a);
            if (s != null) {
                url = s;
                break;
            }
        }
        String detail = method + " args=" + render(args);
        if (url != null) {
            detail = method + " url=" + Recon.sanitize(url) + " (media=" + Recon.isVideoByteUrl(url) + ")";
        }
        String key = method + "|" + (url != null ? Recon.sanitize(url) : render(args));
        if (!Recon.first(kind, key)) return;
        Recon.record(kind, "proc=" + com.lw5.bilibtr.recon.AppHolder.processName() + " " + detail, 16);
    }

    /** 参数里可能藏着 URL 的形态：String / android.net.Uri。 */
    private static String asUrlString(Object a) {
        if (a == null) return null;
        if (a instanceof String) {
            String s = (String) a;
            return s.startsWith("http") ? s : null;
        }
        String cn = a.getClass().getName();
        if ("android.net.Uri".equals(cn) || cn.startsWith("android.net.Uri$")) {
            String s = a.toString();
            return s.startsWith("http") ? s : null;
        }
        return null;
    }

    static String render(List<Object> args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            Object a = args.get(i);
            if (a == null) {
                sb.append("null");
            } else if (a instanceof String) {
                String s = (String) a;
                sb.append(s.startsWith("http") ? Recon.sanitize(s) : Recon.clip(s, 60));
            } else if (a instanceof Number || a instanceof Boolean) {
                sb.append(a);
            } else {
                String tn = a.getClass().getName();
                if (tn.startsWith("android.net.Uri")) {
                    sb.append(Recon.sanitize(a.toString()));
                } else {
                    sb.append(tn).append('@').append(Integer.toHexString(System.identityHashCode(a)));
                }
            }
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------ 找方法

    /** 按名字前缀找声明方法（含父类），覆盖所有重载。 */
    private static List<Method> declaredLike(Class<?> c, String[] prefixes) {
        List<Method> out = new ArrayList<>();
        String[] names = new String[]{"setDataSource", "_setDataSource", "setOption", "native_setOption"};
        List<Method> candidates = Reflect.declaredNamed(c, names);
        for (Method m : candidates) {
            for (String p : prefixes) {
                if (m.getName().startsWith(p)) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }
}
