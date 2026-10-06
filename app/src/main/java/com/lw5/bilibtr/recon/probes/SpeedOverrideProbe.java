package com.lw5.bilibtr.recon.probes;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import com.lw5.bilibtr.proxy.ProxyConfig;
import com.lw5.bilibtr.recon.Hooks;
import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.Reflect;

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
                com.lw5.bilibtr.proxy.PlaySpeed.noteAppSpeed(fromApp);
                float v = com.lw5.bilibtr.proxy.PlaySpeed.effective(fromApp);
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
