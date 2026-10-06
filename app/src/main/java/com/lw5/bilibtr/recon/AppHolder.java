package com.lw5.bilibtr.recon;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Environment;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 宿主上下文与版本信息。
 *
 * 我们在宿主进程里跑，拿到的 Context 就是 tv.danmaku.bili 的，
 * 所以 getExternalFilesDir() 落在
 *   /sdcard/Android/data/tv.danmaku.bili/files/btr-recon/
 * —— 免 root、可直接 adb pull。
 *
 * 取 Application 有两条路：
 *   1) hook android.app.Instrumentation#callApplicationOnCreate（主路径，host 版本无关）
 *   2) android.app.ActivityThread#currentApplication 反射兜底
 */
public final class AppHolder {

    private static volatile Application app;
    private static volatile String hostVersion;

    private AppHolder() {
    }

    public static void set(Application a) {
        if (a != null) app = a;
    }

    public static Application get() {
        Application a = app;
        if (a != null) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object o = m.invoke(null);
            if (o instanceof Application) {
                app = (Application) o;
                return app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String processName() {
        try {
            Application a = get();
            if (a != null) return a.getProcessName();
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 宿主版本：用来确认我们确实在 9.8.0 上做的侦察。 */
    public static String hostVersion() {
        String cached = hostVersion;
        if (cached != null) return cached;
        try {
            Context c = get();
            if (c == null) return "?";
            PackageInfo pi = c.getPackageManager().getPackageInfo(Recon.HOST_PKG, 0);
            cached = pi.versionName + "(" + pi.getLongVersionCode() + ")";
            hostVersion = cached;
            return cached;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 可写的落盘目录；失败返回 null（此时只留 logcat）。 */
    public static File reconDir() {
        try {
            Application a = get();
            File d = a != null ? a.getExternalFilesDir("btr-recon") : null;
            if (d == null) {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null) {
                    d = new File(ext, "Android/data/" + Recon.HOST_PKG + "/files/btr-recon");
                }
            }
            if (d == null) return null;
            if (!d.isDirectory() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }
}
