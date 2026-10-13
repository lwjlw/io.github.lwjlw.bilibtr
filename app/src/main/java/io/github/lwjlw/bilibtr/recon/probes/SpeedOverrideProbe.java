package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 倍速强制（**测试工具**，不是功能）。
 *
 * 为什么需要：卡顿很随机，靠"碰巧遇到卡"来验证加速基本不可行。
 * 但**把播放倍速调到 2~3 倍，等于把需要的码率翻 2~3 倍** ——
 * 于是可以人为造出"带宽不够"的场景，让加速效果立刻可量化。
 *
 * 用法：`btr.conf` 里写 `speed=2.0`（0 或省略 = 不启用，完全不挂钩）。
 * 实现：拦截 `IjkMediaPlayer.setSpeed(float)`，把入参换成配置值。
 */
public final class SpeedOverrideProbe {

    private SpeedOverrideProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        // 总是安装：悬浮球可以在运行时决定倍速（选"跟随"时就完全不干预）

        Class<?> ijk = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayer");
        int n = 0;
        for (Method m : Reflect.declaredNamed(ijk, "setSpeed")) {
            Hooks.install(x, m, "speed-override", chain -> {
                float fromApp = 1f;
                try {
                    Object a = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                    if (a instanceof Number) fromApp = ((Number) a).floatValue();
                } catch (Throwable ignored) {
                }
                // 先记下 B站 自己设的值 —— 悬浮球选"跟随"时要设回它
                io.github.lwjlw.bilibtr.proxy.PlaySpeed.noteAppSpeed(fromApp);
                float v = io.github.lwjlw.bilibtr.proxy.PlaySpeed.effective(fromApp);
                // ★ 诊断（2026-10-13）：把"谁在改倍速"记下来。
                //   ours=true → 我们自己主动设的（悬浮球 / applyNow 的调用）；
                //   ours=false → B站 自己的调用。它可能在我们设完之后又改回去，
                //   这正是"选了 4x 却跑不到"要排查的第一件事。同一值只记一次，避免刷屏。
                try {
                    boolean ours = io.github.lwjlw.bilibtr.proxy.PlaySpeed.isApplying();
                    if (Recon.first("SPEED:SET", (ours ? "ours" : "app") + ":" + fromApp)) {
                        Recon.note("SPEED:SET", (ours ? "我们" : "B站") + " 调 setSpeed(" + fromApp + ")"
                                + (Math.abs(v - fromApp) > 0.001f ? " → 实际下发 " + v + "x" : "（不改动）"));
                    }
                } catch (Throwable ignored) {
                }
                if (Math.abs(v - fromApp) > 0.001f) {
                    return chain.proceed(new Object[]{v});
                }
                return chain.proceed();
            });
            n++;
        }
        Recon.note("PROBE", "SpeedOverrideProbe: 已挂 " + n + " 个 setSpeed（倍速由悬浮球/配置决定）");
    }
}
