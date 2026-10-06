package io.github.lwjlw.bilibtr.proxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import io.github.lwjlw.bilibtr.recon.AppHolder;
import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * 代理开关配置：读宿主外部私有目录下的 `btr.conf`，改文件即可切换行为，不用重装模块。
 *
 *   enabled=true          # 总开关；缺省 false（安全默认）
 *   mode=proxy            # proxy=正常透传 | redirect302=一律回 302 到原始地址（用来测播放器跟不跟重定向）
 *   port=18888            # 固定端口；被占用时自动退回随机端口并记录
 *
 * 带 2 秒缓存：既能 adb 改完立刻生效，又不会每个请求都读盘。
 */
public final class ProxyConfig {

    public static final String FILE_NAME = "btr.conf";
    public static final int DEFAULT_PORT = 18888;
    public static final String MODE_PROXY = "proxy";
    public static final String MODE_302 = "redirect302";

    /** 默认并发：参考 BTR 官方建议 8~32，先保守取 4，实测后再调。 */
    public static final int DEFAULT_CONCURRENCY = 4;

    /**
     * 默认切分阈值：小于这个长度的 Range 不切。
     * 实测教训：阈值调到 64KB 时，起播从 328ms 恶化到 1376ms
     *（小段被切 → 多几次握手 → 而我们是"收齐才回"，首字节被推迟）。
     */
    public static final int DEFAULT_MIN_SPLIT_KB = 128;

    private static volatile boolean enabled = false;
    private static volatile String mode = MODE_PROXY;
    private static volatile int port = DEFAULT_PORT;
    private static volatile int concurrency = DEFAULT_CONCURRENCY;
    private static volatile int minSplitKb = DEFAULT_MIN_SPLIT_KB;
    /** 测试用：强制播放倍速（0 = 不启用）。 */
    private static volatile float playbackSpeed = 0f;
    /** 测试用：强制清晰度 qn（0 = 不启用；120=4K）。 */
    private static volatile int qualityOverride = 0;
    /** 缓冲大小（KB）；0 = 不干预播放器默认。 */
    private static volatile int bufferSizeKb = 0;
    /** 缓冲时长（ms）；0 = 不干预播放器默认。 */
    private static volatile int bufferTimeMs = 0;
    /** 播放页悬浮球（3x/4x 倍速）总开关。 */
    private static volatile boolean ballEnabled = true;
    /**
     * 阶段 A 侦察探针开关（`OkHttpProbe`/`BodyProbe`/`JavaUrlProbe`/`PlayerProbe`）。
     *
     * **默认 false**：它们挂在 OkHttp / URL / 播放器的高频路径上
     * （`BodyProbe` 还要在响应体上扫描），日常使用纯属白耗 CPU。
     * 只有重新做侦察、需要采集画像时才在 `btr.conf` 里写 `recon=1`。
     */
    private static volatile boolean reconProbes = false;
    private static volatile long lastLoad = 0L;

    private ProxyConfig() {
    }

    public static boolean enabled() {
        reloadIfStale();
        return enabled;
    }

    public static String mode() {
        reloadIfStale();
        return mode;
    }

    public static int preferredPort() {
        reloadIfStale();
        return port;
    }

    /** 单个客户端 Range 请求内部切几路并发（1 = 关闭并发，退回纯透传）。 */
    public static int concurrency() {
        reloadIfStale();
        int c = concurrency;
        if (c < 1) c = 1;
        if (c > 16) c = 16;
        return c;
    }

    /** 测试用强制清晰度 qn（120=4K）；<=0 表示不启用。 */
    public static int qualityOverride() {
        reloadIfStale();
        return qualityOverride;
    }

    /** 测试用强制倍速；<=0 表示不启用。 */
    public static float playbackSpeed() {
        reloadIfStale();
        return playbackSpeed;
    }

    /** 缓冲大小（字节）；0 = 不干预。 */
    public static long bufferSizeBytes() {
        reloadIfStale();
        int kb = bufferSizeKb;
        if (kb <= 0) return 0L;
        if (kb > 512 * 1024) kb = 512 * 1024;
        return kb * 1024L;
    }

    /** 缓冲时长（毫秒）；0 = 不干预。 */
    public static long bufferTimeMs() {
        reloadIfStale();
        int ms = bufferTimeMs;
        if (ms <= 0) return 0L;
        if (ms > 300_000) ms = 300_000;
        return ms;
    }

    /** 阶段 A 侦察探针是否启用（默认否）。 */
    public static boolean reconProbes() {
        reloadIfStale();
        return reconProbes;
    }

    /** 播放页悬浮球开关。 */
    public static boolean ballEnabled() {
        reloadIfStale();
        return ballEnabled;
    }

    /** 小于这个长度的 Range 不切分（字节）。 */
    public static long minSplit() {
        reloadIfStale();
        int kb = minSplitKb;
        if (kb < 16) kb = 16;
        if (kb > 4096) kb = 4096;
        return kb * 1024L;
    }

    /** 强制立即重读（播放器初始化的关键时刻用，别被 2 秒缓存挡住）。 */
    public static void forceReload() {
        lastLoad = 0L;
        reloadIfStale();
    }

    private static void reloadIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastLoad < 2000L) return;
        lastLoad = now;
        try {
            File dir = AppHolder.reconDir();
            if (dir == null) return;
            File f = new File(dir, FILE_NAME);
            boolean en = false;
            String md = MODE_PROXY;
            int pt = DEFAULT_PORT;
            int cc = DEFAULT_CONCURRENCY;
            int ms = DEFAULT_MIN_SPLIT_KB;
            float sp = 0f;
            int qn = 0;
            int bsk = 0;
            int btm = 0;
            boolean ball = true;
            boolean recon = false;
            BufferedReader r = f.isFile()
                    ? new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))
                    : null;
            try {
                if (r == null) {
                    // 没有配置文件：全部走默认，下面再由界面设置覆盖
                } else {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim().toLowerCase();
                    String v = line.substring(eq + 1).trim();
                    if ("enabled".equals(k)) en = "true".equalsIgnoreCase(v) || "1".equals(v);
                    else if ("mode".equals(k)) md = v.toLowerCase();
                    else if ("concurrency".equals(k)) {
                        try {
                            cc = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("qn".equals(k)) {
                        try {
                            qn = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("speed".equals(k)) {
                        try {
                            sp = Float.parseFloat(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("minsplitkb".equals(k)) {
                        try {
                            ms = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("port".equals(k)) {
                        try {
                            pt = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("bufsizekb".equals(k)) {
                        try {
                            bsk = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("recon".equals(k)) {
                        recon = "true".equalsIgnoreCase(v) || "1".equals(v);
                    } else if ("ball".equals(k)) {
                        ball = "true".equalsIgnoreCase(v) || "1".equals(v);
                    } else if ("buftimems".equals(k)) {
                        try {
                            btm = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                }
            } finally {
                if (r != null) r.close();
            }
            enabled = en;
            mode = md;
            port = pt;
            concurrency = cc;
            minSplitKb = ms;
            playbackSpeed = sp;
            qualityOverride = qn;
            bufferSizeKb = bsk;
            bufferTimeMs = btm;
            ballEnabled = ball;
            reconProbes = recon;

            // ② **界面设置优先**：用户改界面比改文件更常用，所以 provider 覆盖文件值。
            //    拿不到 provider（模块 App 没装/没启动）就保持文件值，绝不因此坏掉播放。
            //    用 adb 调试界面设置：
            //      content call --uri content://io.github.lwjlw.bilibtr.settings --method setSetting \
            //          --extra key s enabled --extra value s false
            android.os.Bundle b = SettingsClient.get();
            if (b != null) {
                enabled = SettingsClient.getBool(b, "enabled", enabled);
                mode = SettingsClient.getString(b, "mode", mode);
                port = SettingsClient.getInt(b, "port", port);
                concurrency = SettingsClient.getInt(b, "concurrency", concurrency);
                minSplitKb = SettingsClient.getInt(b, "minsplitkb", minSplitKb);
                qualityOverride = SettingsClient.getInt(b, "qn", qualityOverride);
                bufferSizeKb = SettingsClient.getInt(b, "bufsizekb", bufferSizeKb);
                bufferTimeMs = SettingsClient.getInt(b, "buftimems", bufferTimeMs);
            }
            // ③ **界面实时推送的值优先级最高**（回环 TCP 通道，见 ConfigServer）。
            //    provider 被 Android 软件包可见性挡住用不了，所以走 TCP。
            applyLive();
        } catch (Throwable t) {
            Recon.note("PROXY", "读 btr.conf 失败：" + t);
        }
    }

    /** 把界面推过来的值覆盖到内存（优先级：界面 > provider > 文件 > 默认）。 */
    private static void applyLive() {
        String v;
        if ((v = ConfigServer.live("enabled")) != null) enabled = "true".equalsIgnoreCase(v) || "1".equals(v);
        if ((v = ConfigServer.live("mode")) != null) mode = v;
        if ((v = ConfigServer.live("concurrency")) != null) concurrency = parseInt(v, concurrency);
        if ((v = ConfigServer.live("minsplitkb")) != null) minSplitKb = parseInt(v, minSplitKb);
        if ((v = ConfigServer.live("port")) != null) port = parseInt(v, port);
        if ((v = ConfigServer.live("qn")) != null) qualityOverride = parseInt(v, qualityOverride);
        if ((v = ConfigServer.live("bufsizekb")) != null) bufferSizeKb = parseInt(v, bufferSizeKb);
        if ((v = ConfigServer.live("buftimems")) != null) bufferTimeMs = parseInt(v, bufferTimeMs);
        if ((v = ConfigServer.live("ball")) != null) ballEnabled = "true".equalsIgnoreCase(v) || "1".equals(v);
        if ((v = ConfigServer.live("recon")) != null) reconProbes = "true".equalsIgnoreCase(v) || "1".equals(v);
        if ((v = ConfigServer.live("speed")) != null) {
            try {
                playbackSpeed = Float.parseFloat(v);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
