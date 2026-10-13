package io.github.lwjlw.bilibtr.proxy;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import io.github.lwjlw.bilibtr.recon.AppHolder;
import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 给用户看的**一次性提示**（Toast）。
 *
 * 为什么在注入侧弹：用户看视频时**不会打开 btr 软件**，
 * 而注入代码就跑在 B站 进程里、B站 又在前台 —— 直接用它自己的 Context 弹就行，
 * 不需要任何权限、也不会跑到别的应用上去。
 *
 * 规矩：
 *   - **默认关闭**（`btr.conf` 的 `toast=`，界面里也有开关），关着的时候一条都不弹；
 *   - 必须**在主线程**弹（Toast 要求有 Looper，主线程最稳）；
 *   - 5 秒内只弹一条，避免网络抖动时刷屏；
 *   - 任何异常都吞掉 —— 提示失败绝不能影响播放。
 */
public final class ToastNotifier {

    private static final long MIN_GAP_MS = 5_000L;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile long lastAt = 0L;

    private ToastNotifier() {
    }

    /** 弹一条提示；开关关闭 / 太频繁 / 没有宿主 Context 时静默返回。 */
    public static void show(String message) {
        if (message == null || message.isEmpty()) return;
        try {
            if (!ProxyConfig.toastEnabled()) return;
            long now = System.currentTimeMillis();
            if (now - lastAt < MIN_GAP_MS) return;
            lastAt = now;
            Recon.note("TOAST:SHOW", message);
            UI.post(() -> {
                try {
                    android.content.Context ctx = AppHolder.get();
                    if (ctx == null) return;
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
