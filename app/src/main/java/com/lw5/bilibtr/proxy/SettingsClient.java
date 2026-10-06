package com.lw5.bilibtr.proxy;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.lw5.bilibtr.recon.AppHolder;
import com.lw5.bilibtr.recon.Recon;

/**
 * **注入侧**（跑在 B站 进程里）读取界面设置的通道，以及把测速数据推回去的出口。
 *
 * 为什么走 ContentProvider：界面（模块 App 的 uid）和 B站 进程 uid 不同，
 * 谁也不许碰对方的私有目录（Android 分区存储），
 * 所以用 `com.lw5.bilibtr.settings` 这个 provider 做唯一通道。
 *
 * - 读设置：{@link #get()}，2 秒缓存（改完最多 2 秒生效，又不会每请求都跨进程）；
 * - 推测速：{@link #putStats(String)}，由 {@link StatsPusher} 每 2 秒调一次。
 *
 * **拿不到 provider 时全部返回 null**，调用方一律回退到老的 `btr.conf`，
 * 所以模块 App 没装 / 没启动也不会把播放搞坏。
 */
public final class SettingsClient {

    private static final String AUTHORITY = "com.lw5.bilibtr.settings";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final long TTL_MS = 2000L;

    private static volatile Bundle cached;
    private static volatile long cachedAt = 0L;
    private static volatile boolean everOk = false;
    private static volatile boolean failLogged = false;

    private SettingsClient() {
    }

    /** 取设置；拿不到返回 null（可能返回上一次的缓存）。 */
    public static Bundle get() {
        long now = System.currentTimeMillis();
        Bundle c = cached;
        if (c != null && now - cachedAt < TTL_MS) return c;
        cachedAt = now;
        try {
            Context ctx = AppHolder.get();
            if (ctx == null) return cached;
            Bundle r = ctx.getContentResolver().call(URI, "getSettings", null, null);
            if (r != null) {
                if (!everOk) {
                    everOk = true;
                    Recon.note("SETTINGS", "已连上模块界面（provider 可用）");
                }
                cached = r;
                return r;
            }
        } catch (Throwable t) {
            if (!failLogged) {
                failLogged = true;
                Recon.note("SETTINGS", "读 provider 失败 → 回退 btr.conf：" + t);
            }
        }
        return cached;
    }

    /** provider 是否曾经连上过（用于日志判断）。 */
    public static boolean available() {
        return everOk;
    }

    public static boolean getBool(Bundle b, String key, boolean def) {
        if (b == null) return def;
        try {
            return b.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static int getInt(Bundle b, String key, int def) {
        if (b == null) return def;
        try {
            return b.getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static String getString(Bundle b, String key, String def) {
        if (b == null) return def;
        try {
            String s = b.getString(key);
            return s == null ? def : s;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 把测速数据（JSON 字符串）推给界面。失败静默。 */
    public static void putStats(String json) {
        if (json == null) return;
        try {
            Context ctx = AppHolder.get();
            if (ctx == null) return;
            Bundle b = new Bundle();
            b.putString("json", json);
            ctx.getContentResolver().call(URI, "putStats", null, b);
        } catch (Throwable ignored) {
        }
    }
}
