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
        Recon.note("SPEED:BALL", "已抓到播放器实例 " + p.getClass().getSimpleName());
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
                Recon.note("SPEED:BALL", "已对 " + p.getClass().getSimpleName() + " 设置 " + v + "x");
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
