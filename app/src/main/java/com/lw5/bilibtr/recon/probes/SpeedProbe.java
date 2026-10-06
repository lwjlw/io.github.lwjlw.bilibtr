package com.lw5.bilibtr.recon.probes;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import com.lw5.bilibtr.recon.Hooks;
import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.Reflect;
import com.lw5.bilibtr.recon.SpeedSession;

/**
 * 阶段 C1：测速埋点（播放器侧）。
 *
 * 实测确认的指标归属（**别记错类，这里踩过一次**）：
 *   - `IjkMediaPlayerItem.getTcpSpeed()` / `getPlayPosition()`   ← 速度/进度在 **Item** 上
 *   - `IjkMediaPlayerTracker.getBitrate(boolean)` / `getNetError()` / `getBufferTime(long)`
 *
 * 所以会话要同时抓两个对象：
 *   - `IjkMediaPlayerItem.setDataSourceToNative(boolean)` → 拿到 **Item**（并反射取它的 tracker）
 *   - `IjkMediaPlayerTracker.updateUrl(String,int)`       → 拿到 **Tracker**、以及本次播放的地址
 *   - `didFirstVideoRendered/didFirstAudioRendered`       → **首帧时刻**（起播耗时）
 *   - `getNativeDataItemStop` / `IjkMediaPlayerItem.reset` → 播放结束，落盘
 *
 * 真正的采样在 {@link SpeedSession}（1 秒一次，反射读取），并且会每 5 个采样
 * 中途覆盖落盘一次，所以进程被强杀也不会丢数据。
 */
public final class SpeedProbe {

    private SpeedProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        int n = 0;
        Class<?> item = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayerItem");
        Class<?> tracker = Reflect.load(cl, "tv.danmaku.ijk.media.player.IjkMediaPlayerTracker");

        // ① Item：会话的锚点
        if (item != null) {
            for (Method m : Reflect.declaredNamed(item, "setDataSourceToNative")) {
                Hooks.install(x, m, "speed-item-setds", chain -> {
            try {
                Object it = chain.getThisObject();
                if (it != null) {
                    java.lang.reflect.Field f = it.getClass().getDeclaredField("mBuvid");
                    f.setAccessible(true);
                    Object v = f.get(it);
                    if (v instanceof String) {
                        com.lw5.bilibtr.proxy.StreamMeta.captureBuvid((String) v);
                    }
                }
            } catch (Throwable ignored) {
            }
                    try {
                        Object self = chain.getThisObject();
                        SpeedSession.begin("(unknown)", self, trackerOf(self));
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                n++;
            }
            for (Method m : Reflect.declaredNamed(item, "reset")) {
                Hooks.install(x, m, "speed-item-reset", chain -> {
                    Object r = chain.proceed();
                    SpeedSession.end("item-reset");
                    return r;
                });
                n++;
            }
        } else {
            Recon.note("PROBE", "SpeedProbe: 没找到 IjkMediaPlayerItem");
        }

        // ② Tracker：地址 + 码率/错误
        if (tracker != null) {
            for (Method m : Reflect.declaredNamed(tracker, "updateUrl")) {
                Hooks.install(x, m, "speed-updateUrl", chain -> {
                    try {
                        Object self = chain.getThisObject();
                        String url = null;
                        for (Object a : chain.getArgs()) {
                            if (a instanceof String && ((String) a).startsWith("http")) {
                                url = (String) a;
                                break;
                            }
                        }
                        SpeedSession.begin(url != null ? url : "(unknown)", null, self);
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                n++;
            }
            for (Method m : Reflect.declaredNamed(tracker, "didFirstVideoRendered")) {
                Hooks.install(x, m, "speed-firstVideo", chain -> {
                    SpeedSession.firstFrame(true, argItem(chain.getArgs()));
                    return chain.proceed();
                });
                n++;
            }
            for (Method m : Reflect.declaredNamed(tracker, "didFirstAudioRendered")) {
                Hooks.install(x, m, "speed-firstAudio", chain -> {
                    SpeedSession.firstFrame(false, argItem(chain.getArgs()));
                    return chain.proceed();
                });
                n++;
            }
            for (Method m : Reflect.declaredNamed(tracker, "getNativeDataItemStop")) {
                Hooks.install(x, m, "speed-stop", chain -> {
                    Object r = chain.proceed();
                    SpeedSession.end("tracker-stop");
                    return r;
                });
                n++;
            }
        } else {
            Recon.note("PROBE", "SpeedProbe: 没找到 IjkMediaPlayerTracker");
        }

        Recon.note("PROBE", "SpeedProbe: 装上 " + n + " 个埋点（item=" + (item != null)
                + " tracker=" + (tracker != null) + "）");
    }

    /** 首帧回调的第 2 个参数就是 Item。 */
    private static Object argItem(List<Object> args) {
        for (Object a : args) {
            if (a != null && a.getClass().getName().endsWith("IjkMediaPlayerItem")) return a;
        }
        return null;
    }

    /** 从 Item 反射取它自己的 Tracker（`getIjkMediaPlayerTracker()`）。 */
    private static Object trackerOf(Object item) {
        try {
            Method m = item.getClass().getMethod("getIjkMediaPlayerTracker");
            return m.invoke(item);
        } catch (Throwable t) {
            return null;
        }
    }
}
