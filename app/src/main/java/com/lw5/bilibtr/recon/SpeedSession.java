package com.lw5.bilibtr.recon;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次播放的测速会话（阶段 C1 的"尺子"）。
 *
 * 为什么要两条口径：
 *   - **播放器侧**（B站 自己的 IJK 埋点）：直连和走代理**都能量**，
 *     是唯一能做 A/B 对照的共同基准；
 *   - **代理侧**（我们自己的 LocalProxy）：只在走代理时有，用来看并发调度内部行为。
 *
 * 实测确认的指标来源（**别记错类**）：
 *   - `IjkMediaPlayerItem.getTcpSpeed() -> long`        ← 速度在这！**不在 Tracker 上**
 *   - `IjkMediaPlayerItem.getPlayPosition() -> long`
 *   - `IjkMediaPlayerTracker.getBitrate(boolean) -> int`
 *   - `IjkMediaPlayerTracker.getNetError() -> long`
 *   - `IjkMediaPlayerTracker.getBufferTime(long) -> float`
 *
 * 产物：宿主外部目录下 `playback-<时间戳>.json`，字段名固定便于脚本对比；
 * 会话进行中**每 5 个采样重写一次同一个文件**，所以就算进程被强杀也不会丢数据。
 *
 * 全静态、单例、线程安全；任何异常都吞掉——测速代码绝不能影响播放。
 */
public final class SpeedSession {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
    private static final SimpleDateFormat LOG_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final long SAMPLE_MS = 1000L;
    /** 超过这么久没有任何活动，判定播放结束并落盘。 */
    private static final long IDLE_END_MS = 30000L;
    private static final int MAX_SAMPLES = 3600;
    /** `playback-*.json` 只保留最近这么多个（超出删最旧）。 */
    private static final int KEEP_JSON = 20;
    /** 每多少个采样做一次"中途落盘"。 */
    private static final int PERSIST_EVERY = 5;

    private static volatile Session current;
    private static volatile boolean samplerStarted = false;

    private SpeedSession() {
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 开一次播放会话。
     *
     * @param item    `IjkMediaPlayerItem`，速度/进度从这里取（可为 null）
     * @param tracker `IjkMediaPlayerTracker`，码率/错误从这里取（可为 null）
     */
    public static void begin(String url, Object item, Object tracker) {
        try {
            String san = Recon.sanitize(url);
            synchronized (LOCK) {
                Session s = current;
                if (s != null && !s.closed) {
                    // updateUrl / setDataSourceToNative 在一次播放里会调很多次
                    //（每路流、每次换节点）——必须合并，不能重开会话，
                    // 否则日志会被切成一堆毫秒级的碎片。
                    boolean samePlayback = true;
                    if (item != null && s.item != null && s.item != item) samePlayback = false;
                    if (tracker != null && s.tracker != null && s.tracker != tracker) samePlayback = false;
                    if (samePlayback) {
                        if (item != null) s.item = item;
                        if (tracker != null) s.tracker = tracker;
                        if (url != null && !"(unknown)".equals(url)) s.url = san;
                        s.lastActivity = SystemClock.elapsedRealtime();
                        return;
                    }
                    flush(s, "superseded");
                }
                current = new Session(san, item, tracker);
                ensureSampler();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 播放器侧：首帧渲染。 */
    public static void firstFrame(boolean video, Object item) {
        try {
            Session s = current;
            if (s == null || s.closed) return;
            long now = SystemClock.elapsedRealtime();
            synchronized (s) {
                if (item != null) s.item = item;
                if (video) {
                    if (s.videoFirstFrameMs < 0) s.videoFirstFrameMs = now - s.t0;
                } else if (s.audioFirstFrameMs < 0) {
                    s.audioFirstFrameMs = now - s.t0;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 播放停止/重置 → 落盘。 */
    public static void end(String reason) {
        try {
            synchronized (LOCK) {
                Session s = current;
                if (s == null || s.closed) return;
                flush(s, reason);
                current = null;
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 代理侧事件

    public static void proxyRequest(String host, String kind, String range) {
        try {
            Session s = current;
            if (s == null || s.closed) {
                synchronized (LOCK) {
                    if (current == null || current.closed) {
                        current = new Session("(proxy-only)", null, null);
                        ensureSampler();
                    }
                    s = current;
                }
            }
            synchronized (s) {
                s.proxyRequests++;
                s.lastActivity = SystemClock.elapsedRealtime();
                if (host != null) {
                    Integer c = s.proxyHosts.get(host);
                    s.proxyHosts.put(host, c == null ? 1 : c + 1);
                }
                if (range != null) s.lastRange = range;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void proxyResponse(int code, long bytes, long totalMs, long ttfbMs) {
        try {
            if (ttfbMs > 0) LAST_TTFB = ttfbMs;   // 给测速面板用
            Session s = current;
            if (s == null || s.closed) return;
            synchronized (s) {
                s.proxyResponses++;
                s.proxyBytes += bytes;
                if (code >= 200 && code < 300) s.proxyOk++;
                else s.proxyBad++;
                s.proxyTtfb.add(ttfbMs);
                s.proxyTotal.add(totalMs);
                if (ttfbMs > s.ttfbMaxMs) s.ttfbMaxMs = ttfbMs;
                s.lastActivity = SystemClock.elapsedRealtime();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 采样

    private static void ensureSampler() {
        if (samplerStarted) return;
        samplerStarted = true;
        Thread t = new Thread(SpeedSession::sampleLoop, "btr-speed-sampler");
        t.setDaemon(true);
        t.start();
    }

    private static void sampleLoop() {
        while (true) {
            try {
                Thread.sleep(SAMPLE_MS);
                Session s = current;
                if (s == null || s.closed) continue;
                long now = SystemClock.elapsedRealtime();
                Sample smp = new Sample();
                smp.t = now - s.t0;

                Object item = s.item;
                Object tracker = s.tracker;
                if (item != null) {
                    smp.tcpSpeed = callLong(item, "getTcpSpeed");
                    smp.playPosition = callLong(item, "getPlayPosition");
                }
                if (tracker != null) {
                    smp.bitrate = callLong(tracker, "getBitrate", true);
                    smp.netError = callLong(tracker, "getNetError");
                    smp.bufferTime = callLong(tracker, "getBufferTime", 0L);
                    // 卡顿口径：每分钟缓冲次数 + 掉帧率
                    smp.bufferPerMin = callLong(tracker, "getMinuteBufferCount");
                    LAST_STALLS = smp.bufferPerMin;
                }
                if (item != null) {
                    smp.dropRate = callLong(item, "getDropFrameRate");
                }

                int count;
                long last;
                synchronized (s) {
                    if (s.samples.size() < MAX_SAMPLES) s.samples.add(smp);
                    count = s.samples.size();
                    s.lastActivity = now;
                    last = s.lastActivity;
                }
                if (count % PERSIST_EVERY == 0) persist(s);

                if (last > 0 && SystemClock.elapsedRealtime() - last > IDLE_END_MS) {
                    end("idle");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static long callLong(Object target, String method, Object... args) {
        try {
            java.lang.reflect.Method m = find(target.getClass(), method, args.length);
            if (m == null) {
                if (Recon.first("SPEED:NOMETHOD", method)) {
                    Recon.note("SPEED:NOMETHOD", method + " 在 " + target.getClass().getSimpleName()
                            + " 上不存在（参数个数 " + args.length + "）");
                }
                return -1;
            }
            m.setAccessible(true);
            Object r = m.invoke(target, args);
            if (r instanceof Number) return ((Number) r).longValue();
            return -1;
        } catch (Throwable t) {
            if (Recon.first("SPEED:CALLFAIL", method)) {
                Recon.note("SPEED:CALLFAIL", method + " -> " + t);
            }
            return -1;
        }
    }

    private static java.lang.reflect.Method find(Class<?> c, String name, int argc) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == argc) return m;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 落盘

    /** 会话进行中的中途落盘（同一文件名，覆盖写）。 */
    private static void persist(Session s) {
        write(s, "in-progress", false);
    }

    private static void flush(Session s, String reason) {
        write(s, reason, true);
    }

    private static void write(Session s, String reason, boolean close) {
        try {
            if (close) s.closed = true;
            // 空会话（没采到样、没走代理、也没有首帧）不落盘
            if (s.samples.isEmpty() && s.proxyRequests == 0
                    && s.videoFirstFrameMs < 0 && s.audioFirstFrameMs < 0) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            JSONObject o = buildJson(s, reason, now);
            File dir = AppHolder.reconDir();
            if (dir != null) {
                File f = new File(dir, s.fileName);
                FileOutputStream fo = new FileOutputStream(f);
                try {
                    fo.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
                } finally {
                    fo.close();
                }
                pruneOld(dir);
            }
            if (close) {
                Recon.note("SPEED", "reason=" + reason
                        + " dur=" + (now - s.t0) + "ms"
                        + " firstFrame(video)=" + s.videoFirstFrameMs + "ms"
                        + " tcpAvg=" + fmtBps(avgSpeeds(s)) + " tcpMax=" + fmtBps(maxSpeed(s))
                        + " 卡顿=" + maxBufferPerMin(s) + "/min"
                        + " samples=" + s.samples.size()
                        + " | proxy req=" + s.proxyRequests + " ok=" + s.proxyOk + " bad=" + s.proxyBad
                        + " bytes=" + s.proxyBytes
                        + " ttfbP50=" + p50(s.proxyTtfb) + "ms"
                        + " avg=" + fmtBps(s.proxyBytes * 1000L / Math.max(1, now - s.t0))
                        + " hosts=" + s.proxyHosts.size());
            }
        } catch (Throwable t) {
            Recon.note("SPEED:ERR", String.valueOf(t));
        }
    }

    /**
     * `playback-*.json` **只保留最近 {@value #KEEP_JSON} 个**。
     *
     * 为什么要清：文件名带时间戳（`playback-20261005-231500.json`），
     * 每次播放都会新建一个（最多 3600 个采样点），**原来只增不删** ——
     * 长期使用会在设备里攒到几十上百 MB。
     */
    private static void pruneOld(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null) return;
            java.util.List<File> mine = new java.util.ArrayList<>();
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("playback-") && n.endsWith(".json")) mine.add(f);
            }
            if (mine.size() <= KEEP_JSON) return;
            // 按修改时间从新到旧，保留前 KEEP_JSON 个
            mine.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = KEEP_JSON; i < mine.size(); i++) mine.get(i).delete();
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject buildJson(Session s, String reason, long now) throws Exception {
        JSONObject o = new JSONObject();
        o.put("reason", reason);
        o.put("startedAt", s.wallStart);
        o.put("savedAt", LOG_FMT.format(new Date()));
        o.put("durationMs", now - s.t0);
        o.put("proc", AppHolder.processName());
        o.put("hostApp", AppHolder.hostVersion());
        o.put("url", s.url);
        o.put("proxyEnabled", s.proxyRequests > 0);
        o.put("videoFirstFrameMs", s.videoFirstFrameMs);
        o.put("audioFirstFrameMs", s.audioFirstFrameMs);

        JSONObject player = new JSONObject();
        player.put("samples", s.samples.size());
        player.put("tcpSpeedAvg", avgSpeeds(s));
        player.put("tcpSpeedMax", maxSpeed(s));
        player.put("bitrateLast", s.samples.isEmpty() ? -1 : s.samples.get(s.samples.size() - 1).bitrate);
        player.put("netErrorMax", maxNetError(s));
        player.put("bufferTimeLast", s.samples.isEmpty() ? -1 : s.samples.get(s.samples.size() - 1).bufferTime);
        player.put("bufferPerMinLast", s.samples.isEmpty() ? -1 : s.samples.get(s.samples.size() - 1).bufferPerMin);
        player.put("bufferPerMinMax", maxBufferPerMin(s));
        player.put("dropRateMax", maxDropRate(s));
        o.put("player", player);

        JSONArray arr = new JSONArray();
        for (Sample smp : s.samples) {
            JSONObject j = new JSONObject();
            j.put("t", smp.t);
            j.put("tcpSpeed", smp.tcpSpeed);
            j.put("bitrate", smp.bitrate);
            j.put("bufferTime", smp.bufferTime);
            j.put("netError", smp.netError);
            j.put("position", smp.playPosition);
            j.put("bufferPerMin", smp.bufferPerMin);
            j.put("dropRate", smp.dropRate);
            arr.put(j);
        }
        o.put("curve", arr);

        JSONObject proxy = new JSONObject();
        proxy.put("requests", s.proxyRequests);
        proxy.put("responses", s.proxyResponses);
        proxy.put("ok", s.proxyOk);
        proxy.put("bad", s.proxyBad);
        proxy.put("bytes", s.proxyBytes);
        proxy.put("ttfbP50Ms", p50(s.proxyTtfb));
        proxy.put("ttfbMaxMs", s.ttfbMaxMs);
        proxy.put("totalMsP50", p50(s.proxyTotal));
        long dur = Math.max(1, now - s.t0);
        proxy.put("avgBps", s.proxyBytes * 1000L / dur);
        proxy.put("avgMbps", s.proxyBytes * 8.0 * 1000.0 / dur / 1_000_000.0);
        JSONObject hosts = new JSONObject();
        for (Map.Entry<String, Integer> e : s.proxyHosts.entrySet()) {
            hosts.put(e.getKey(), e.getValue());
        }
        proxy.put("hosts", hosts);
        o.put("proxy", proxy);
        return o;
    }

    private static String fmtBps(long bps) {
        if (bps <= 0) return "-";
        if (bps > 1024 * 1024) return String.format(Locale.US, "%.2fMB/s", bps / 1048576.0);
        return String.format(Locale.US, "%dKB/s", bps / 1024);
    }

    private static long avgSpeeds(Session s) {
        long sum = 0;
        int n = 0;
        for (Sample x : s.samples) {
            if (x.tcpSpeed > 0) {
                sum += x.tcpSpeed;
                n++;
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    private static long maxSpeed(Session s) {
        long m = -1;
        for (Sample x : s.samples) m = Math.max(m, x.tcpSpeed);
        return m;
    }

    private static long maxBufferPerMin(Session s) {
        long m = -1;
        for (Sample x : s.samples) m = Math.max(m, x.bufferPerMin);
        return m;
    }

    private static long maxDropRate(Session s) {
        long m = -1;
        for (Sample x : s.samples) m = Math.max(m, x.dropRate);
        return m;
    }

    private static long maxNetError(Session s) {
        long m = 0;
        for (Sample x : s.samples) m = Math.max(m, x.netError);
        return m;
    }

    private static long p50(List<Long> v) {
        if (v.isEmpty()) return -1;
        List<Long> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(c.size() / 2);
    }

    // ------------------------------------------------------------------ 数据结构

    /** 最近一次采样到的"每分钟卡顿次数"（测速面板用）。 */
    private static volatile long LAST_STALLS = -1;
    /** 最近一次代理请求的首字节时间中位数（测速面板用）。 */
    private static volatile long LAST_TTFB = -1;

    public static long lastStallsPerMin() {
        return LAST_STALLS;
    }

    public static long lastTtfbP50() {
        return LAST_TTFB;
    }

    private static final class Sample {
        long t;
        /** 每分钟缓冲（卡顿）次数 —— 这是"卡不卡"的官方口径。 */
        long bufferPerMin = -1;
        /** 掉帧率。 */
        long dropRate = -1;
        long tcpSpeed = -1;
        long bitrate = -1;
        long bufferTime = -1;
        long netError;
        long playPosition = -1;
    }

    private static final class Session {
        final long t0 = SystemClock.elapsedRealtime();
        final String wallStart = LOG_FMT.format(new Date());
        final String fileName = "playback-" + FILE_FMT.format(new Date()) + ".json";
        volatile String url;
        volatile Object item;
        volatile Object tracker;
        volatile boolean closed = false;
        long videoFirstFrameMs = -1;
        long audioFirstFrameMs = -1;
        long lastActivity = SystemClock.elapsedRealtime();

        final List<Sample> samples = new ArrayList<>();
        int proxyRequests;
        int proxyResponses;
        int proxyOk;
        int proxyBad;
        long proxyBytes;
        long ttfbMaxMs;
        String lastRange;
        final List<Long> proxyTtfb = new ArrayList<>();
        final List<Long> proxyTotal = new ArrayList<>();
        final TreeMap<String, Integer> proxyHosts = new TreeMap<>();

        Session(String url, Object item, Object tracker) {
            this.url = url;
            this.item = item;
            this.tracker = tracker;
        }
    }
}
