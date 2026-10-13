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
     * **起播门槛**（`first-high-water-mark-ms`）默认值。
     *
     * 参考 ffplay 的默认值是 208ms —— 太激进（弱网会一直起播失败）；
     * 3 秒是"起播快"和"别一进去就卡"之间的折中。
     * ⚠️ 这里**绝不能**再用用户的"缓冲时长"（曾经是 60 秒）：
     * 那意味着"必须先攒够 60 秒才开始播"，正是用户报的"跑前面缓冲 60s"。
     */
    public static final int DEFAULT_FIRST_WATER_MS = 3_000;
    /**
     * **卡顿后恢复门槛**（`next-high-water-mark-ms`）默认值。
     *
     * ffplay 默认 10000ms。这个值 = "卡了一次要重新攒多久才继续播"，
     * 设成 60 秒的后果就是"卡一下罚站一分钟"。
     */
    public static final int DEFAULT_NEXT_WATER_MS = 15_000;
    /**
     * **读取恢复的低水位**（`last-high-water-mark-ms`）默认值。
     *
     * ffplay 默认 5000ms。**必须小**：它决定"缓冲掉到多少才继续读"，
     * 设成 30 秒就等于"播掉 30 秒之前完全不下载" —— 用户看到的
     * "一点点播完也不继续缓存，到 60s 后又卡"就是这么来的。
     */
    public static final int DEFAULT_LAST_WATER_MS = 3_000;

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
    /** 缓冲大小（KB）；0 = 不干预播放器默认。 */
    private static volatile int bufferSizeKb = 0;
    /** 缓冲时长（ms）；0 = 不干预播放器默认。 */
    private static volatile int bufferTimeMs = 0;
    /**
     * 起播门槛（`first-high-water-mark-ms`）：攒够这么多才**开始播**。
     * 小 = 起播快。默认 {@value #DEFAULT_FIRST_WATER_MS}ms。
     */
    private static volatile int firstWaterMs = DEFAULT_FIRST_WATER_MS;
    /**
     * 卡顿后恢复门槛（`next-high-water-mark-ms`）：缓冲见底后重新攒够这么多才**继续播**。
     * 这是"卡一次要等多久"的那个数。默认 {@value #DEFAULT_NEXT_WATER_MS}ms。
     */
    private static volatile int nextWaterMs = DEFAULT_NEXT_WATER_MS;
    /**
     * 读取恢复的低水位（`last-high-water-mark-ms`）：缓冲掉到这么低就**继续读**。
     * 小 = 一消耗就补，不会"播着播着不缓存了"。默认 {@value #DEFAULT_LAST_WATER_MS}ms。
     */
    private static volatile int lastWaterMs = DEFAULT_LAST_WATER_MS;
    /** 播放页悬浮球（3x/4x 倍速）总开关。 */
    private static volatile boolean ballEnabled = true;
    /**
     * **进视频/卡顿时自动对候选节点测速，并据此选最快节点**（{@link AutoPicker}）。
     *
     * 默认 **true**（用户点名要的能力）。做成开关是为了 A/B 对照：
     * 想验证"自动选节点到底有没有用"，把它关掉即可，不必整个关掉加速。
     */
    private static volatile boolean autoPick = true;
    /**
     * 因卡顿自动换节点时，是否弹 Toast 告知用户（{@link ToastNotifier}）。
     *
     * 默认 **false**（用户明确要求默认关）：提示只在"真的卡了并且真的换了节点"时出现，
     * 用户想看到它时在界面里打开。
     */
    private static volatile boolean toast = false;
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

    /** 测试用强制倍速；<=0 表示不启用。 */
    public static float playbackSpeed() {
        reloadIfStale();
        return playbackSpeed;
    }

    /**
     * **缓冲上限**（`max-buffer-size`，字节）；0 = 不干预。
     *
     * 两个来源（2026-10-13 改）：
     * 1. 用户在界面上直接设了「缓冲大小」(MB) → 用它（≥4MB 才认，见下面的防线）；
     * 2. 只设了「缓冲时长」(秒) → **用当前流的码率换算**：
     *    `字节 = 秒 × 目标吞吐`（目标吞吐 = `bw÷8×1.2`，即"播放需要多快"）。
     *    这样用户的"我要保持 60 秒缓冲"才有真实含义 —— 以前它被直接塞进了
     *    `first/next-high-water-mark-ms`，而那三个是**水位/门槛**，不是容量。
     */
    public static long bufferSizeBytes() {
        reloadIfStale();
        int kb = bufferSizeKb;
        if (kb >= 4096) {
            if (kb > 512 * 1024) kb = 512 * 1024;
            return kb * 1024L;
        }
        // ⚠️ **防线**：小于 4 MB 的"缓冲上限"对视频播放器毫无意义，
        // 只会把 B站 默认的 150 MB 压垮。
        // 起因：界面上单位写的是 MB、底层却是 KB，用户拉满到"200 MB"，
        // 实际写下去是 200 KB → 播放一直卡，且缓冲水位永远撑不满
        // （表现为"缓冲时长没生效"）。单位已在界面侧修正，这里再兜一层。
        if (kb > 0 && Recon.first("BUF:IGNORE", "v")) {
            Recon.note("BUF:IGNORE", "缓冲大小配置为 " + kb + "KB，过小，按「不干预」处理");
        }
        int ms = bufferTimeMs;
        if (ms > 0) {
            double bps = CdnRacer.targetBps(CdnRacer.lastUrl());   // 当前流的目标吞吐；拿不到就是兜底 1.5MB/s
            long bytes = (long) (bps * (ms / 1000.0));
            if (bytes < 32L * 1024 * 1024) bytes = 32L * 1024 * 1024;      // 下限 32MB
            if (bytes > 512L * 1024 * 1024) bytes = 512L * 1024 * 1024;    // 上限 512MB
            return bytes;
        }
        return 0L;
    }

    /** 缓冲时长（毫秒）；0 = 不干预。现在只用来换算"缓冲上限"。 */
    public static long bufferTimeMs() {
        reloadIfStale();
        int ms = bufferTimeMs;
        if (ms <= 0) return 0L;
        if (ms > 300_000) ms = 300_000;
        return ms;
    }

    /** 缓冲上限的**来源**（只用于日志/诊断，免得日志把"谁定的"说错）。 */
    public static String bufferSizeSource() {
        reloadIfStale();
        if (bufferSizeKb >= 4096) return "界面「缓冲大小」=" + bufferSizeKb + "KB";
        if (bufferTimeMs > 0) return "「缓冲时长」" + bufferTimeMs + "ms × 当前码率";
        return "未设置";
    }

    /** 起播门槛（毫秒）。 */
    public static long firstWaterMs() {
        reloadIfStale();
        int v = firstWaterMs;
        if (v < 500) v = 500;
        if (v > 60_000) v = 60_000;
        return v;
    }

    /** 卡顿后恢复门槛（毫秒）。 */
    public static long nextWaterMs() {
        reloadIfStale();
        int v = nextWaterMs;
        if (v < 1_000) v = 1_000;
        if (v > 120_000) v = 120_000;
        return v;
    }

    /** 读取恢复的低水位（毫秒）。 */
    public static long lastWaterMs() {
        reloadIfStale();
        int v = lastWaterMs;
        if (v < 200) v = 200;
        if (v > 60_000) v = 60_000;
        return v;
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

    /** 是否启用"自动测速选节点"。 */
    public static boolean autoPick() {
        reloadIfStale();
        return autoPick;
    }

    /** 是否允许弹 Toast 提示（换节点时）。 */
    public static boolean toastEnabled() {
        reloadIfStale();
        return toast;
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
            int bsk = 0;
            int btm = 0;
            boolean ball = true;
            boolean recon = false;
            boolean autoPickV = true;
            boolean toastV = false;
            int fwm = DEFAULT_FIRST_WATER_MS;
            int nwm = DEFAULT_NEXT_WATER_MS;
            int lwm = DEFAULT_LAST_WATER_MS;
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
                    } else if ("autopick".equals(k)) {
                        autoPickV = "true".equalsIgnoreCase(v) || "1".equals(v);
                    } else if ("toast".equals(k)) {
                        toastV = "true".equalsIgnoreCase(v) || "1".equals(v);
                    } else if ("firstms".equals(k)) {
                        try {
                            fwm = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("nextms".equals(k)) {
                        try {
                            nwm = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                    } else if ("lastms".equals(k)) {
                        try {
                            lwm = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
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
            bufferSizeKb = bsk;
            bufferTimeMs = btm;
            ballEnabled = ball;
            reconProbes = recon;
            autoPick = autoPickV;
            toast = toastV;
            firstWaterMs = fwm;
            nextWaterMs = nwm;
            lastWaterMs = lwm;

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
        if ((v = ConfigServer.live("bufsizekb")) != null) bufferSizeKb = parseInt(v, bufferSizeKb);
        if ((v = ConfigServer.live("buftimems")) != null) bufferTimeMs = parseInt(v, bufferTimeMs);
        if ((v = ConfigServer.live("firstms")) != null) firstWaterMs = parseInt(v, firstWaterMs);
        if ((v = ConfigServer.live("nextms")) != null) nextWaterMs = parseInt(v, nextWaterMs);
        if ((v = ConfigServer.live("lastms")) != null) lastWaterMs = parseInt(v, lastWaterMs);
        if ((v = ConfigServer.live("ball")) != null) ballEnabled = "true".equalsIgnoreCase(v) || "1".equals(v);
        if ((v = ConfigServer.live("autopick")) != null) autoPick = "true".equalsIgnoreCase(v) || "1".equals(v);
        if ((v = ConfigServer.live("toast")) != null) toast = "true".equalsIgnoreCase(v) || "1".equals(v);
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
