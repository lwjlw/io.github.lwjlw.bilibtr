package com.lw5.bilibtr.recon.probes;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import com.lw5.bilibtr.proxy.ProxyConfig;
import com.lw5.bilibtr.recon.Hooks;
import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.Reflect;

/**
 * **缓冲大小 / 缓冲时长**（用户点名的功能）。
 *
 * ## 为什么这个功能值得单独做
 * 参考实现作者的原话：**"线路抖动时，缓冲比并发更能救体感"** ——
 * 并发是把管道加粗，缓冲是把水缸做大。网络一抖，缸小的立刻见底 → 卡。
 *
 * ## 怎么生效
 * IJK 的缓冲由播放器选项控制（ffmpeg/ffplay 那一套）：
 * | 选项 | 含义 |
 * | --- | --- |
 * | `max-buffer-size` | 缓冲字节上限 → 对应界面的**缓冲大小** |
 * | `first/next/last-high-water-mark-ms` | 高/低水位（毫秒）→ 对应**缓冲时长** |
 * | `packet-buffering` / `infbuf` / `min-frames` | 相关开关 |
 *
 * 做法：**拦 `setOption`**，凡是命中上面这些名字就把值换成界面配置的。
 * 界面上填 0 = 不干预（完全保持 B站 默认）。
 *
 * ## 实测发现（很重要）
 * B站 **自己已经把 `max-buffer-size` 设成了 150MB**：
 * ```
 * [OPT:SEEN] B站 设置缓冲选项 max-buffer-size = 157286400
 * ```
 * 所以"缓冲大小"其实已经很富裕，**真正没设的是水位（缓冲时长）**。
 * 于是本探针的重点是：**搭 B站 那次 `setOption(max-buffer-size)` 的便车**，
 * 在同一台播放器实例上把 `first/next/last-high-water-mark-ms` 补上。
 */
public final class BufferTuneProbe {

    /** 命中这些关键字就认为与缓冲有关。 */
    private static final String[] KEYS = {
            "buffer", "water-mark", "infbuf", "min-frames", "packet-buffering"
    };

    /** 防止"我们自己在 hook 里再调 setOption"导致无限递归。 */
    private static final ThreadLocal<Boolean> OURS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BufferTuneProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> player = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayer");
        Class<?> itemClient = Reflect.load(cl, "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient");

        int n = 0;
        for (Method m : Reflect.declaredNamed(player, "setOption")) {
            // 这一类才是真播放器（IjkMediaPlayer）→ 抓实例给悬浮球用
            Hooks.install(x, m, "buf-opt-player-" + m.getParameterCount(), hook(m, true));
            n++;
        }
        for (Method m : Reflect.declaredNamed(itemClient, "setOptionLong", "setOptionString", "_setOption")) {
            Hooks.install(x, m, "buf-opt-item-" + m.getName(), hook(m, false));   // 代理端，不抓
            n++;
        }
        Recon.note("PROBE", "BufferTuneProbe: 挂了 " + n + " 个 setOption（缓冲大小="
                + ProxyConfig.bufferSizeBytes() + "B, 缓冲时长=" + ProxyConfig.bufferTimeMs() + "ms）");
    }

    private static io.github.libxposed.api.XposedInterface.Hooker hook(Method m) {
        return hook(m, false);
    }

    /**
     * @param capturePlayer 是否把 `this` 当成播放器实例交给 {@link com.lw5.bilibtr.proxy.PlaySpeed}。
     *        ⚠️ **只有 `IjkMediaPlayer` 才是真播放器**；`IjkMediaPlayerItemClient` 是代理端，
     *        它上面**没有** `setSpeed(float)` —— 之前不分青红皂白都抓，结果抓到的是代理端，
     *        点 3x 时报"这个播放器上没有找到 setSpeed(float)"（实测踩到）。
     */
    private static io.github.libxposed.api.XposedInterface.Hooker hook(
            Method m, final boolean capturePlayer) {
        return chain -> {
            if (capturePlayer) {
                try {
                    com.lw5.bilibtr.proxy.PlaySpeed.capture(chain.getThisObject());
                } catch (Throwable ignored) {
                }
            }
            Object[] args = null;
            try {
                List<Object> a = chain.getArgs();
                if (a.size() >= 2 && a.get(1) instanceof String) {
                    String name = (String) a.get(1);
                    if (isBufferKey(name)) {
                        String newVal = override(name, a.size() >= 3 ? a.get(2) : null);
                        // 记录 B站 实际用的选项（每个名字只记一次）
                        if (Recon.first("OPT:SEEN", name)) {
                            Recon.note("OPT:SEEN", "B站 设置缓冲选项 " + name + " = "
                                    + (a.size() >= 3 ? String.valueOf(a.get(2)) : "?")
                                    + (newVal != null ? "  → 覆盖为 " + newVal : "（未覆盖）"));
                        }
                        if (newVal != null) {
                            args = a.toArray();
                            if (a.get(2) instanceof Long || a.get(2) instanceof Integer) {
                                args[2] = Long.parseLong(newVal);
                            } else {
                                args[2] = newVal;   // String 重载
                            }
                        }
                    } else if (Recon.first("OPT:OTHER", name)) {
                        Recon.note("OPT:OTHER", "其他播放器选项 " + name + " = "
                                + (a.size() >= 3 ? String.valueOf(a.get(2)) : "?"));
                    }
                }
            } catch (Throwable t) {
                Recon.note("OPT:ERR", String.valueOf(t));
            }
            Object ret = args != null ? chain.proceed(args) : chain.proceed();

            // ★ 搭便车：B站 设置 max-buffer-size 时，顺手把"水位"补到同一台播放器上
            try {
                if (!OURS.get() && chain.getThisObject() != null
                        && "max-buffer-size".equals(String.valueOf(
                                chain.getArgs().size() >= 2 ? chain.getArg(1) : ""))) {
                    applyWaterMarks(chain.getThisObject(), chain.getArgs());
                }
            } catch (Throwable ignored) {
            }
            return ret;
        };
    }

    /**
     * 把水位（缓冲时长）设到播放器上。
     *
     * 为什么用"搭便车"而不是主动找播放器实例：
     * `IjkMediaPlayerItem` 只暴露 `getIjkMediaPlayerTracker()`，没有取播放器的方法；
     * 而 B站 **一定会**在初始化时调 `setOption(max-buffer-size)`，
     * 那一刻的 `this` 就是我们要的播放器。
     */
    private static void applyWaterMarks(Object target, List<Object> args) {
        // ⚠️ 这一瞬间播放器刚初始化，配置可能还是 2 秒缓存里的旧值 → 强制重读
        ProxyConfig.forceReload();
        long ms = ProxyConfig.bufferTimeMs();
        if (Recon.first("OPT:CHK", "v")) {
            Recon.note("OPT:CHK", "读取缓冲配置：时长=" + ms + "ms");
        }
        if (ms <= 0) return;
        int category = args.size() >= 1 && args.get(0) instanceof Number
                ? ((Number) args.get(0)).intValue() : 4;   // 4 = IJK_OPT_CATEGORY_PLAYER
        OURS.set(Boolean.TRUE);
        try {
            // 实测：`getThisObject()` 有时是 IjkMediaPlayer（方法叫 setOption），
            // 有时是 IjkMediaPlayerItemClient（方法叫 setOptionLong / setOptionString）
            // → **按名字逐个试**，别假设。
            callSetOption(target, category, "first-high-water-mark-ms", ms);
            callSetOption(target, category, "next-high-water-mark-ms", ms);
            callSetOption(target, category, "last-high-water-mark-ms", ms / 2);
            Recon.note("OPT:SET", "已设置缓冲水位 first/next=" + ms + "ms last=" + (ms / 2) + "ms"
                    + "（对象 " + target.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            Recon.note("OPT:ERR", "设置水位失败：" + t);
        } finally {
            OURS.set(Boolean.FALSE);
        }
    }

    /** 在目标对象上调用"设置选项"——同时兼容 long 与 String 两种重载、以及不同的方法名。 */
    private static void callSetOption(Object target, int category, String name, long value)
            throws Exception {
        Class<?> c = target.getClass();
        for (String n : new String[]{"setOption", "setOptionLong", "_setOption"}) {
            try {
                Method m = c.getMethod(n, int.class, String.class, long.class);
                m.invoke(target, category, name, value);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (String n : new String[]{"setOption", "setOptionString"}) {
            try {
                Method m = c.getMethod(n, int.class, String.class, String.class);
                m.invoke(target, category, name, String.valueOf(value));
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        // 最后兜底：扫一遍 declared（可能是 private / native）
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().toLowerCase().contains("setoption")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 3 || ps[0] != int.class || ps[1] != String.class) continue;
            try {
                m.setAccessible(true);
                if (ps[2] == long.class) {
                    m.invoke(target, category, name, value);
                    return;
                }
                if (ps[2] == String.class) {
                    m.invoke(target, category, name, String.valueOf(value));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchMethodException(c.getName());
    }

    private static boolean isBufferKey(String name) {
        String low = name.toLowerCase();
        for (String k : KEYS) {
            if (low.contains(k)) return true;
        }
        return false;
    }

    /** 返回要覆盖成的值（字符串形式）；null = 不覆盖。 */
    private static String override(String name, Object cur) {
        String low = name.toLowerCase();
        long bufSize = ProxyConfig.bufferSizeBytes();
        long bufTime = ProxyConfig.bufferTimeMs();
        if (low.contains("max-buffer-size") && bufSize > 0) return String.valueOf(bufSize);
        if (low.contains("high-water-mark") && bufTime > 0) return String.valueOf(bufTime);
        if (low.contains("min-frames") && bufTime > 0) {
            // min-frames 是帧数不是毫秒：按 30fps 折算，给个保守值
            return String.valueOf(Math.max(1, bufTime / 33));
        }
        return null;
    }
}
