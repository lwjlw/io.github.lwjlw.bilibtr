package com.lw5.bilibtr.proxy;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lw5.bilibtr.recon.Recon;
import com.lw5.bilibtr.recon.SpeedSession;

/**
 * 把实时测速数据推给界面（测速面板）。
 *
 * 每 2 秒一次，**在后台线程**做（跨进程调用绝不能放在播放线程上）。
 * 只有在"确实有流量"时才推，避免界面长时间空转、耗电。
 *
 * 数据格式见 {@link #buildJson(long)}。
 */
public final class StatsPusher {

    private static final long INTERVAL_MS = 2000L;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private StatsPusher() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            long lastBytes = LocalProxy.totalBytes();
            long lastAt = System.currentTimeMillis();
            while (true) {
                try {
                    Thread.sleep(INTERVAL_MS);
                    long now = System.currentTimeMillis();
                    long bytes = LocalProxy.totalBytes();
                    long dt = Math.max(1, now - lastAt);
                    long bps = (bytes - lastBytes) * 1000L / dt;
                    lastBytes = bytes;
                    lastAt = now;
                    // ★ 闭环输入：把实际吞吐喂给并发控制器
                    ConnSpeed.observeAggregate(bps);
                    if (bytes > 0) {
                        String json = buildJson(bps);
                        ConfigServer.setStats(json);      // ← 界面走这条（TCP）
                        SettingsClient.putStats(json);    // ← 顺带写 provider，方便 adb 看
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable err) {
                    // 推送失败不能影响任何东西
                }
            }
        }, "btr-stats");
        t.setDaemon(true);
        t.start();
        Recon.note("PROBE", "StatsPusher 已启动（每 2 秒推一次测速数据给界面）");
    }

    /** 拼 JSON（手拼，注入侧不引 JSON 库）。 */
    private static String buildJson(long bps) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"throughputBps\":").append(bps);
        sb.append(",\"requests\":").append(LocalProxy.totalRequests());
        sb.append(",\"bytes\":").append(LocalProxy.totalBytes());
        sb.append(",\"concurrency\":").append(ConnSpeed.lastDesired());
        sb.append(",\"perConnBps\":").append((long) ConnSpeed.perConnBps());
        sb.append(",\"stallsPerMin\":").append(SpeedSession.lastStallsPerMin());
        sb.append(",\"ttfbP50Ms\":").append(SpeedSession.lastTtfbP50());
        String active = CdnRacer.activeHost();
        sb.append(",\"activeHost\":\"").append(esc(active)).append('"');
        sb.append(",\"hosts\":[");
        boolean first = true;
        for (CdnRacer.HostStat h : CdnRacer.hostStats()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"h\":\"").append(esc(h.host)).append("\",\"b\":").append(h.bytes)
                    .append(",\"n\":").append(h.reqs).append('}');
        }
        sb.append("],\"ema\":\"").append(esc(CdnRacer.emaSummary())).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static {
        // 让 Locale 被引用，避免某些 lint 报未使用（无实际作用）
        Locale.getDefault();
    }
}
