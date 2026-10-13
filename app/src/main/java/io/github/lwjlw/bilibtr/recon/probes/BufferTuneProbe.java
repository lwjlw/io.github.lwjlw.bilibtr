package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

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

    /**
     * **B站 自己**设过的缓冲选项原值（诊断用，2026-10-13 加）。
     *
     * 为什么要单独记：`Recon.first("OPT:SEEN", ...)` 只记第一次，而第一次常常是我们
     * `applyWaterMarks` 写进去的 —— 于是日志里那几行"B站 设置 first/next=60000"
     * 其实是**我们自己**的值。分开记之后才能回答"B站 到底设没设水位"。
     */
    private static final java.util.Map<String, String> APP_RAW =
            new java.util.concurrent.ConcurrentHashMap<>();

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
     * @param capturePlayer 是否把 `this` 当成播放器实例交给 {@link io.github.lwjlw.bilibtr.proxy.PlaySpeed}。
     *        ⚠️ **只有 `IjkMediaPlayer` 才是真播放器**；`IjkMediaPlayerItemClient` 是代理端，
     *        它上面**没有** `setSpeed(float)` —— 之前不分青红皂白都抓，结果抓到的是代理端，
     *        点 3x 时报"这个播放器上没有找到 setSpeed(float)"（实测踩到）。
     */
    private static io.github.libxposed.api.XposedInterface.Hooker hook(
            Method m, final boolean capturePlayer) {
        return chain -> {
            if (capturePlayer) {
                try {
                    io.github.lwjlw.bilibtr.proxy.PlaySpeed.capture(chain.getThisObject());
                } catch (Throwable ignored) {
                }
            }
            Object[] args = null;
            try {
                List<Object> a = chain.getArgs();
                if (a.size() >= 2 && a.get(1) instanceof String) {
                    String name = (String) a.get(1);
                    if (isBufferKey(name)) {
                        // ★ 诊断修正（2026-10-13）：必须区分"这条是 B站 自己调的"还是"我们自己调的"。
                        //   之前用 Recon.first("OPT:SEEN", name) 只记第一次，而第一次往往就是
                        //   applyWaterMarks 自己写进去的那次 → 日志里显示的"B站 设置的值"其实是
                        //   **我们的值**，会把人骗到（曾据此误判"B站 也在设水位"）。
                        boolean ours = OURS.get();
                        String raw = a.size() >= 3 ? String.valueOf(a.get(2)) : "?";
                        String newVal = override(name, a.size() >= 3 ? a.get(2) : null);
                        if (ours) {
                            if (Recon.first("OPT:OURS", name)) {
                                Recon.note("OPT:OURS", "我们自己设置缓冲选项 " + name + " = " + raw);
                            }
                        } else {
                            APP_RAW.put(name, raw);       // 记下 B站 的原始值，便于汇总比对
                            if (Recon.first("OPT:SEEN", name)) {
                                Recon.note("OPT:SEEN", "B站 自己设置缓冲选项 " + name + " = " + raw
                                        + (newVal != null ? "  → 我们覆盖为 " + newVal : "（我们不覆盖）"));
                            }
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
        long first = ProxyConfig.firstWaterMs();
        long next = ProxyConfig.nextWaterMs();
        long last = ProxyConfig.lastWaterMs();
        long bufBytes = ProxyConfig.bufferSizeBytes();
        if (Recon.first("OPT:CHK", "v")) {
            // 诊断汇总（2026-10-13）：一次把"我们要设什么 / B站 自己设过什么"打全。
            // 上限的**来源**必须说清楚（曾经这里把"界面缓冲大小"说成"时长×码率"，会误导排查）。
            Recon.note("OPT:CHK", "缓冲配置：起播门槛=" + first + "ms、卡顿恢复=" + next
                    + "ms、读取低水位=" + last + "ms、上限=" + bufBytes + "B"
                    + "（来源：" + ProxyConfig.bufferSizeSource() + "）"
                    + "；B站 自己设过的缓冲选项=" + appRawSummary());
        }
        int category = args.size() >= 1 && args.get(0) instanceof Number
                ? ((Number) args.get(0)).intValue() : 4;   // 4 = IJK_OPT_CATEGORY_PLAYER
        OURS.set(Boolean.TRUE);
        try {
            // 实测：`getThisObject()` 有时是 IjkMediaPlayer（方法叫 setOption），
            // 有时是 IjkMediaPlayerItemClient（方法叫 setOptionLong / setOptionString）
            // → **按名字逐个试**，别假设。
            callSetOption(target, category, "first-high-water-mark-ms", first);
            callSetOption(target, category, "next-high-water-mark-ms", next);
            callSetOption(target, category, "last-high-water-mark-ms", last);
            Recon.note("OPT:SET", "我们设置缓冲：起播门槛=" + first + "ms 卡顿恢复=" + next
                    + "ms 读取低水位=" + last + "ms（对象 " + target.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(target)) + "）"
                    + "；B站 原始 max-buffer-size=" + APP_RAW.getOrDefault("max-buffer-size", "未见"));
        } catch (Throwable t) {
            Recon.note("OPT:ERR", "设置水位失败：" + t);
        } finally {
            OURS.set(Boolean.FALSE);
        }
    }

    /** B站 自己设过的缓冲选项汇总（没设过的就不出现）。 */
    private static String appRawSummary() {
        if (APP_RAW.isEmpty()) return "（无 —— 水位/缓冲上限都是默认值）";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : APP_RAW.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
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

    /**
     * 返回要覆盖成的值（字符串形式）；null = 不覆盖。
     *
     * ## 三个水位是三种**不同**的东西（2026-10-13 修正，之前混成一个数）
     * | 选项 | 语义 | 我们的值 |
     * | --- | --- | --- |
     * | `first-high-water-mark-ms` | 攒够多少才**开始播** | 3s（起播快） |
     * | `next-high-water-mark-ms` | 卡顿后攒够多少才**继续播** | 15s（一次卡顿别罚一分钟） |
     * | `last-high-water-mark-ms` | 缓冲掉到多少就**继续读** | 3s（一消耗就补） |
     * | `max-buffer-size` | **能囤多少**（容量） | 用户设的 MB，或"秒数 × 码率" |
     *
     * ⚠️ 以前把用户的"缓冲时长"（比如 60 秒）同时塞给 `first` 和 `next`：
     * 结果是"必须先攒 60 秒才开播""卡一次要再等 60 秒"，
     * 还会和"读线程的暂停条件"互相等成死锁（实测出现过连续 35 秒一个字节都不下载）。
     */
    private static String override(String name, Object cur) {
        String low = name.toLowerCase();
        long bufSize = ProxyConfig.bufferSizeBytes();
        if (low.contains("max-buffer-size")) {
            return bufSize > 0 ? String.valueOf(bufSize) : null;
        }
        if (low.contains("first-high-water-mark")) return String.valueOf(ProxyConfig.firstWaterMs());
        if (low.contains("next-high-water-mark")) return String.valueOf(ProxyConfig.nextWaterMs());
        if (low.contains("last-high-water-mark")) return String.valueOf(ProxyConfig.lastWaterMs());
        // ⚠️ `min-frames` **不再覆盖**：它是"最少攒多少帧"，按 60 秒折算成 1818 帧时
        //    等于又一个"卡住不播"的门槛。B站 自己没设过这个选项（实测），
        //    我们也就别去动它。
        return null;
    }
}
