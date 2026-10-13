package io.github.lwjlw.bilibtr.proxy;

import java.lang.reflect.Method;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * **播放倍速覆盖**（悬浮球用）。
 *
 * ## 语义（用户明确要求）
 * - 悬浮球选 3x / 4x → **强制覆盖** B站 的设置；
 * - 悬浮球选"跟随" → `override = 0`，**回到 B站 自己的倍速**。
 *
 * ## 两个必须做对的地方（都踩过）
 * **① 光 hook `setSpeed` 不够**：B站 只在需要时调它（进视频调一次，之后你不动它就不再调），
 * 所以用户点 3x 时必须**主动**对当前播放器实例调一次 `setSpeed`。
 *
 * **② 选"跟随"时必须把 B站 原来的倍速设回去**（踩过）：
 * 一开始 `override = 0` 就直接 `return` 什么都不做，结果播放器就**永远停在 3x**，
 * 点"跟"也回不到 1x。所以这里要记住 **B站 自己设过的那个值**（`appSpeed`），
 * 跟随的时候把它设回去。
 */
public final class PlaySpeed {

    /** 0 = 跟随 B站；>0 = 强制覆盖。 */
    private static volatile float override = 0f;
    /** B站 自己设过的倍速（默认 1.0）；"跟随"时要设回它。 */
    private static volatile float appSpeed = 1f;
    /** 当前播放器实例（弱引用，避免把 Activity 拖住）。 */
    private static volatile java.lang.ref.WeakReference<Object> player = null;

    /**
     * 重入保护（**踩过**）。
     *
     * 我们主动调 `setSpeed(4)` 时，**同样会经过我们自己的 hook**，
     * 于是 `noteAppSpeed(4)` 把"我们自己设的值"记成了"B站 的值" ——
     * 结果点"跟随"恢复出来的是 4x 而不是 1x（实测日志：`倍速跟随 B站（恢复 4.0x）`）。
     */
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PlaySpeed() {
    }

    public static void capture(Object p) {
        if (p == null) return;
        java.lang.ref.WeakReference<Object> old = player;
        if (old != null && old.get() == p) return;
        player = new java.lang.ref.WeakReference<>(p);
        // 带上实例身份（诊断用，2026-10-13）：B站 会创建很多播放器实例（疑似预加载池），
        // 只有把 hashCode 打出来，才能判断"我们设倍速的那个"是不是"正在播的那个"。
        Recon.note("SPEED:BALL", "已抓到播放器实例 " + p.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(p)));
    }

    /** 当前是否处于"我们自己正在设置倍速"的重入中（诊断/日志用）。 */
    public static boolean isApplying() {
        return APPLYING.get();
    }

    // ============ 诊断用读数（2026-10-13 加，只读，不改变任何播放行为） ============
    // 为什么不用 SpeedSession 里已有的那两个：实测本机上
    //   `IjkMediaPlayerItem.getPlayPosition()` 恒为 -1/0、
    //   `IjkMediaPlayerTracker.getBufferTime(0)` 恒为 0 —— 都测不出东西。
    // 于是改成问**真播放器自己**要（我们本来就抓着它的实例）。

    private static Object playerRef() {
        java.lang.ref.WeakReference<Object> r = player;
        return r == null ? null : r.get();
    }

    /** 当前抓到的真播放器实例（可能为 null）。给缓冲重算等诊断/调参用。 */
    public static Object player() {
        return playerRef();
    }

    /** 真播放器的当前播放位置（毫秒）；读不到返回 -1。 */
    public static long currentPositionMs() {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : new String[]{"getCurrentPosition", "_getCurrentPosition"}) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 真播放器的缓冲百分比（0~100）；读不到返回 -1。 */
    public static long bufferPercent() {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : new String[]{"getBufferPercentage", "_getBufferPercentage"}) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * **真播放器当前缓存了多少毫秒的视频**（`getVideoCachedDuration()`）；读不到返回 -1。
     *
     * 这是"往前缓冲了多少秒"唯一可信的读数（2026-10-13 加）：
     * `IjkMediaPlayerTracker.getBufferTime()` / `getBufferPercentage()` 在本机恒为 0/-1，
     * 而这个是 IJK 暴露"已缓存时长"的标准入口 —— 用户问的"缓冲到几秒开始补"
     * 只能靠它观测。
     */
    public static long videoCachedMs() {
        return callLongByName(new String[]{"getVideoCachedDuration", "_getVideoCachedDuration"});
    }

    /** 缓存了多少毫秒的音频；读不到返回 -1。 */
    public static long audioCachedMs() {
        return callLongByName(new String[]{"getAudioCachedDuration", "_getAudioCachedDuration"});
    }

    /** 已缓存的视频字节数；读不到返回 -1。 */
    public static long videoCachedBytes() {
        return callLongByName(new String[]{"getVideoCachedBytes", "_getVideoCachedBytes"});
    }

    private static long callLongByName(String[] names) {
        Object p = playerRef();
        if (p == null) return -1;
        for (String n : names) {
            try {
                Method m = p.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(p);
                if (v instanceof Number) return ((Number) v).longValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * 记录 **B站 自己** 传进来的倍速（在我们覆盖之前）。
     * 这就是"跟随"时要恢复的值。
     */
    public static void noteAppSpeed(float v) {
        if (APPLYING.get()) return;          // 我们自己设的不算
        if (v > 0f && v < 20f) appSpeed = v;
    }

    public static float appSpeed() {
        return appSpeed;
    }

    public static boolean hasPlayer() {
        java.lang.ref.WeakReference<Object> r = player;
        return r != null && r.get() != null;
    }

    public static float override() {
        return override;
    }

    /**
     * **当前实际生效的倍速**（≥1）：悬浮球覆盖值 > 配置里的测试值 > 1。
     *
     * 用途（2026-10-13 加）：倍速 3x 时播放器**消耗**的码率也是 3 倍，
     * 所以"够不够快"的目标（并发控制器的 target）必须跟着乘上去 ——
     * 实测漏了这一步：3x 下实际只跑到 1.97x，因为控制器还按 1x 的目标判断"够了"。
     */
    public static float activeSpeed() {
        float v = override;
        if (v <= 0f) v = ProxyConfig.playbackSpeed();
        return v > 1f ? v : 1f;
    }

    /** 悬浮球点了 3x/4x/跟随 时调用。 */
    public static void setOverride(float v) {
        override = Math.max(0f, v);
        Recon.note("SPEED:BALL", override <= 0f
                ? ("倍速跟随 B站（恢复 " + appSpeed + "x）")
                : ("强制倍速 " + override + "x"));
        applyNow();   // ★ 两种情况都要主动设一次（跟随 = 设回 B站 的值）
    }

    /**
     * 计算结果：悬浮球选了就用悬浮球的，否则用配置里的（`btr.conf` 的 `speed=`），
     * 都为 0 就返回原值（不干预）。
     */
    public static float effective(float fromApp) {
        noteAppSpeed(fromApp);
        float v = override;
        if (v <= 0f) v = ProxyConfig.playbackSpeed();
        return v > 0f ? v : fromApp;
    }

    /** 立刻对当前播放器生效（含"跟随"时恢复 B站 的值）。 */
    public static void applyNow() {
        java.lang.ref.WeakReference<Object> r = player;
        Object p = r == null ? null : r.get();
        if (p == null) {
            Recon.note("SPEED:BALL", "还没有播放器实例，等它下次初始化时生效");
            return;
        }
        float v = override;
        if (v <= 0f) v = ProxyConfig.playbackSpeed();
        if (v <= 0f) v = appSpeed;          // ★ 跟随：设回 B站 自己的倍速
        if (v <= 0f) v = 1f;
        APPLYING.set(Boolean.TRUE);
        try {
            if (invokeSetSpeed(p, v)) {
                Recon.note("SPEED:BALL", "已对 " + p.getClass().getSimpleName()
                        + "@" + Integer.toHexString(System.identityHashCode(p)) + " 设置 " + v + "x"
                        + "（实测速率见 playback-*.json 的 playRateActual）");
            } else {
                Recon.note("SPEED:BALL-ERR", "这个播放器上没有找到 setSpeed(float)");
            }
        } finally {
            APPLYING.set(Boolean.FALSE);
        }
    }

    private static boolean invokeSetSpeed(Object p, float v) {
        for (String name : new String[]{"setSpeed", "_setSpeed", "setPlaybackSpeed"}) {
            try {
                Method m = p.getClass().getMethod(name, float.class);
                m.invoke(p, v);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Recon.note("SPEED:BALL-ERR", String.valueOf(t));
                return false;
            }
        }
        for (Method m : p.getClass().getDeclaredMethods()) {
            if (!m.getName().toLowerCase().contains("setspeed")) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != float.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(p, v);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
