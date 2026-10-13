package io.github.lwjlw.bilibtr.proxy;

import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.SpeedSession;

/**
 * **卡顿哨兵**：盯着播放器自己的 QoE 指标，卡了就触发一次"重新测速 + 换节点"。
 *
 * ## 判据用的是官方口径
 * `IjkMediaPlayerTracker.getMinuteBufferCount()` —— 也就是本项目唯一的验收标准
 * （★卡顿次数/分钟，见 `SpeedSession.sampleLoop`）。**不是** tcpSpeed：
 * tcpSpeed 只代表"播放器拉了多少"，直连 2 MB/s 有时只是在囤货。
 *
 * ## 什么时候动作
 * - 只在卡顿计数**又涨了一次**时动作（不是"卡着就一直动作"）；
 * - 必须有真实流量（说明确实在放，而不是暂停/别的进程）；
 * - 必须真的挂着播放器实例；
 * - 30 秒内只触发一次（连续卡顿会让计数连续涨，不能每次都去探测 —— 探测本身抢带宽）。
 *
 * 触发之后由 {@link AutoPicker} 去测速、由 {@link CdnRacer} 去决定换不换，
 * 换成功且原因是卡顿时才 {@link ToastNotifier} 提示用户。
 */
public final class StallWatcher {

    /** 两次触发的最小间隔。 */
    private static final long MIN_GAP_MS = 30_000L;
    /** 上一次看到的卡顿计数。 */
    private static volatile long lastStalls = -1L;
    private static volatile long lastActionAt = 0L;

    private StallWatcher() {
    }

    /** 由 {@link StatsPusher} 每 2 秒喂一次（bps = 当前实际吞吐）。 */
    public static void tick(long bps) {
        try {
            long stalls = SpeedSession.lastStallsPerMin();
            if (stalls < 0) return;                 // 播放器侧还没采到数据
            long prev = lastStalls;
            lastStalls = stalls;
            if (stalls <= 0) return;                // 没卡
            if (prev < 0 || stalls <= prev) return; // 只在"又卡了一次"时动作
            if (bps <= 0) return;                   // 没有流量 → 不是在放
            if (!PlaySpeed.hasPlayer()) return;     // 没有播放器实例
            if (CdnRacer.lastUrl() == null) return; // 还没见过媒体流

            long now = System.currentTimeMillis();
            if (now - lastActionAt < MIN_GAP_MS) {
                Recon.note("STALL:WATCH", "卡顿 " + prev + " → " + stalls
                        + "，但距上次动作仅 " + ((now - lastActionAt) / 1000) + "s，暂不处理");
                return;
            }
            lastActionAt = now;
            Recon.note("STALL:WATCH", "★卡顿计数 " + prev + " → " + stalls
                    + " 次/分 → 触发自动测速并重新选节点");
            AutoPicker.requestRound("stall");
        } catch (Throwable t) {
            Recon.note("STALL:ERR", String.valueOf(t));
        }
    }
}
