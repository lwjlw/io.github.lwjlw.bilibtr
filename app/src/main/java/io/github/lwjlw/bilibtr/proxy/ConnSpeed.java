package io.github.lwjlw.bilibtr.proxy;

import java.util.Locale;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * **并发数控制**（该开几条连接）。
 *
 * ## 为什么不能只用 `目标 ÷ 每连接速度`（踩过的坑）
 * 那个公式有个**死循环**：一旦降到 1 条，就走透传路径、不再产生"每片多大"的测量，
 * **估算值被冻住**，于是永远停在 1 条（用户实测反馈："4K 还显示并发 1"）。
 * 而且它还有更根本的问题：链路被**共享限速**时，连接越多每连接越慢，
 * `每连接速度` 随并发数下降 → 公式要求更多连接 → **正反馈发散到上限**。
 *
 * ## 现在用闭环控制（盯着**实际吞吐**，而不是猜单连接能力）
 * ```text
 * 实际吞吐 < 目标 × 0.95        → 加一条（最多到上限）
 * 实际吞吐 > 目标 × 1.30 且 >1  → 减一条（省开销）
 * 加过一条但吞吐没涨（<10%）     → 判定链路已饱和，30 秒内不再加
 * ```
 * `每连接实测速度`只用来**给冷启动的初始值**。
 */
public final class ConnSpeed {

    /** 单次最多爬升到几条（参考实现同值）。 */
    private static final int MAX_RAMP = 8;
    private static final double EMA_OLD = 0.8;
    private static final double EMA_NEW = 0.2;
    /** 小于这个长度的分片不参与"每连接速度"估计（太短，数字虚高）。 */
    private static final long MIN_SAMPLE_BYTES = 64 * 1024L;
    private static final double MIN_BPS = 32 * 1024.0;
    private static final double MAX_BPS = 50 * 1024 * 1024.0;
    /** 评估间隔。 */
    private static final long MIN_EVAL_INTERVAL_MS = 2000L;
    /** 加完并发后等这么久再判断"到底有没有用"。 */
    private static final long INC_SETTLE_MS = 3000L;
    /** 判定饱和后的冷静期。 */
    private static final long SAT_COOLDOWN_MS = 30_000L;
    /** 减并发的最小间隔。 */
    private static final long MIN_DEC_INTERVAL_MS = 10_000L;

    /** 每条连接的实测吞吐（B/s）；0 = 没有数据。只用于冷启动初始值。 */
    private static volatile double perConnBps = 0;

    private static volatile int lastDesired = 0;
    private static volatile long lastEvalAt = 0L;
    /** 实际吞吐（B/s），由 StatsPusher 每 2 秒喂进来 —— **闭环的输入**。 */
    private static volatile double aggBps = 0;
    private static volatile double aggBeforeInc = -1;
    private static volatile long incAt = 0L;
    private static volatile long satUntil = 0L;
    private static volatile long lastDecAt = 0L;
    /** 连续几次不达标（要连续两次才加并发，避免被瞬时抖动带偏）。 */
    private static volatile int lowStreak = 0;

    // ================= 诊断用字段（2026-10-13 加）—— **不参与任何决策** =================
    // 起因：日志里出现 `加到 8 条后吞吐没涨（182KB/s → 182KB/s）` 这种"前后完全相等"的
    // 饱和判定。相等只可能来自"这段时间根本没有新的吞吐样本"（播放器在缓冲时 bps=0，
    // observeAggregate 直接 return），而不是"加了并发没用"。没有这两个计数器就无法在日志里
    // 区分这两件事 —— 所以先把它们记下来（**行为一行不改**）。
    /** observeAggregate 被调用的总次数（含 bps<=0 的空窗口）。 */
    private static final java.util.concurrent.atomic.AtomicLong AGG_TICKS =
            new java.util.concurrent.atomic.AtomicLong();
    /** 其中真正拿到有效吞吐（bps>0）的样本数。 */
    private static final java.util.concurrent.atomic.AtomicLong AGG_SAMPLES =
            new java.util.concurrent.atomic.AtomicLong();
    /** 最近一个 2 秒窗口的**原始**吞吐（未平滑）。 */
    private static volatile double aggWindowBps = 0;
    private static volatile long aggWindowAt = 0L;
    /** 上一次"加并发"那一刻的样本数快照 → 用来算"加完之后有没有新样本"。 */
    private static volatile long samplesAtInc = 0L;
    /** 最近一次评估用的目标码率（视频流），给面板/日志用。 */
    private static volatile double lastTargetBps = 0;
    private static volatile long lastSnapAt = 0L;

    private ConnSpeed() {
    }

    /** 每拉完一个分片就记一笔（只用于冷启动估计）。 */
    public static void record(long bytes, long ms) {
        if (bytes < MIN_SAMPLE_BYTES || ms <= 0) return;
        double bps = bytes * 1000.0 / ms;
        if (bps < MIN_BPS) bps = MIN_BPS;
        if (bps > MAX_BPS) bps = MAX_BPS;
        double old = perConnBps;
        perConnBps = old <= 0 ? bps : old * EMA_OLD + bps * EMA_NEW;
    }

    /** 实际吞吐反馈（StatsPusher 每 2 秒调一次）。 */
    public static void observeAggregate(double bps) {
        AGG_TICKS.incrementAndGet();
        if (bps <= 0) return;
        AGG_SAMPLES.incrementAndGet();
        aggWindowBps = bps;
        aggWindowAt = System.currentTimeMillis();
        double old = aggBps;
        aggBps = old <= 0 ? bps : old * 0.6 + bps * 0.4;
    }

    // ---- 诊断用 getter（面板/日志） ----
    /** 有效吞吐样本数。 */
    public static long aggSamples() {
        return AGG_SAMPLES.get();
    }

    /** 收到的窗口总数（含空窗口）。 */
    public static long aggTicks() {
        return AGG_TICKS.get();
    }

    /** 最近一个 2 秒窗口的原始吞吐。 */
    public static double aggWindowBps() {
        return aggWindowBps;
    }

    /** 最近一次评估用的目标码率。 */
    public static double targetBps() {
        return lastTargetBps;
    }

    /** 饱和冷静期还剩多少毫秒（0 = 没在冷静）。 */
    public static long satMsLeft() {
        return Math.max(0L, satUntil - System.currentTimeMillis());
    }

    public static double perConnBps() {
        return perConnBps;
    }

    public static int lastDesired() {
        return lastDesired;
    }

    public static double aggregateBps() {
        return aggBps;
    }

    /**
     * 算出这次请求该开几条。
     *
     * @param targetBps 目标速率（`bw÷8×1.2`）
     * @param maxConc   用户配置的上限
     */
    public static int desired(double targetBps, int maxConc) {
        int cap = Math.max(1, Math.min(MAX_RAMP, maxConc));
        long now = System.currentTimeMillis();

        // 音频流目标只有 ~15KB/s：**一条足够**，而且绝不参与闭环
        //（否则它会拿着自己的小目标去"减并发"，把视频流的判断带偏 —— 踩过）
        if (targetBps < 128 * 1024) return 1;

        lastTargetBps = targetBps;
        snapshotIfDue(targetBps);        // 诊断：每 30 秒留一行状态快照

        // 冷启动：先用"每连接实测"推一个起点，别从 1 条慢慢爬
        if (lastDesired <= 0) {
            if (perConnBps > 0) {
                int n = (int) Math.ceil(targetBps / perConnBps);
                if (n < 1) n = 1;
                lastDesired = Math.min(cap, n);
            } else {
                lastDesired = Math.min(cap, 2);
            }
            lastEvalAt = now;
            Recon.note("CONC:INIT", String.format(Locale.US,
                    "冷启动起点 %d 条（目标 %s，每连接实测 %s，上限 %d）",
                    lastDesired, kb(targetBps), kb(perConnBps), cap));
            return lastDesired;
        }

        if (now - lastEvalAt < MIN_EVAL_INTERVAL_MS) return Math.min(cap, lastDesired);
        lastEvalAt = now;

        double agg = aggBps;
        if (agg <= 0) return Math.min(cap, lastDesired);   // 还没有吞吐数据，不动

        // ① 上一次加并发到底有没有用？
        if (aggBeforeInc > 0 && now - incAt > INC_SETTLE_MS) {
            if (agg < aggBeforeInc * 1.10) {
                satUntil = now + SAT_COOLDOWN_MS;
                // ★ 诊断增强：把"这段时间到底有没有新样本"一起打出来。
                //   前后数值完全相等（182KB/s → 182KB/s）时，答案几乎总是 newSamples=0，
                //   也就是"播放器那几秒没拉数据"，而不是"链路饱和"。判据本身暂不改动。
                long newSamples = AGG_SAMPLES.get() - samplesAtInc;
                long elapsed = now - incAt;
                Recon.note("CONC:SAT", String.format(Locale.US,
                        "加到 %d 条后吞吐没涨（%s → %s）→ 判定链路已饱和，%d 秒内不再加"
                                + "【判据快照：期间新样本 %d 个/%dms，窗口原始值 %s，每连接估算 %s，"
                                + "缓冲 %dms，卡顿 %d 次/分】",
                        lastDesired, kb(aggBeforeInc), kb(agg), SAT_COOLDOWN_MS / 1000,
                        newSamples, elapsed, kb(aggWindowBps), kb(perConnBps),
                        lastBufferMs(), lastStalls()));
            }
            aggBeforeInc = -1;
        }

        if (agg < targetBps * 0.95) {
            lowStreak++;
        } else {
            lowStreak = 0;
        }

        if (lowStreak >= 2 && lastDesired < cap && now > satUntil) {
            // ② 连续两次不达标才加（除非刚判定饱和）
            lowStreak = 0;
            aggBeforeInc = agg;
            incAt = now;
            samplesAtInc = AGG_SAMPLES.get();
            lastDesired++;
            Recon.note("CONC:UP", String.format(Locale.US,
                    "吞吐 %s < 目标 %s → 并发加到 %d 条【每连接估算 %s，上限 %d，"
                            + "窗口原始值 %s，样本数 %d，冷静期剩余 %dms】",
                    kb(agg), kb(targetBps), lastDesired, kb(perConnBps), cap,
                    kb(aggWindowBps), AGG_SAMPLES.get(), satMsLeft()));
        } else if (agg > targetBps * 1.30 && lastDesired > 1
                && now - lastDecAt > MIN_DEC_INTERVAL_MS) {
            // ③ 远高于目标就减（低码率本该只需 1 条，省掉多余握手）
            lastDesired--;
            lastDecAt = now;
            Recon.note("CONC:DOWN", String.format(Locale.US,
                    "吞吐 %s 远高于目标 %s → 并发减到 %d 条【窗口原始值 %s，缓冲 %dms，"
                            + "卡顿 %d 次/分，样本数 %d】",
                    kb(agg), kb(targetBps), lastDesired, kb(aggWindowBps),
                    lastBufferMs(), lastStalls(), AGG_SAMPLES.get()));
        }
        return Math.min(cap, lastDesired);
    }

    public static String describe(double targetBps, int maxConc) {
        return String.format(Locale.US, "并发 %d（目标 %s，实测吞吐 %s，每连接 %s，上限 %d）",
                lastDesired, kb(targetBps), kb(aggBps), kb(perConnBps), maxConc);
    }

    /**
     * 诊断：每 30 秒留一行状态快照（**只写日志，不改任何判据**）。
     *
     * 为什么需要：`CONC:UP/SAT/DOWN` 只在决策发生时才有日志，
     * 但"为什么这段时间没做任何决策"（一直在冷静期？样本一直是 0？目标到底是多少？）
     * 从零星的行里看不出来。有了快照就能把整条时间线接起来。
     */
    private static void snapshotIfDue(double targetBps) {
        long now = System.currentTimeMillis();
        if (now - lastSnapAt < 30_000L) return;
        lastSnapAt = now;
        Recon.note("CONC:SNAP", String.format(Locale.US,
                "状态：并发 %d 条 / 目标 %s / 平滑吞吐 %s / 窗口原始值 %s（%dms 前）/ "
                        + "每连接 %s / 有效样本 %d（共 %d 个窗口）/ 冷静期剩余 %dms / "
                        + "缓冲 %dms / 卡顿 %d 次/分 / lowStreak=%d",
                lastDesired, kb(targetBps), kb(aggBps), kb(aggWindowBps),
                aggWindowAt <= 0 ? -1 : (now - aggWindowAt),
                kb(perConnBps), AGG_SAMPLES.get(), AGG_TICKS.get(), satMsLeft(),
                lastBufferMs(), lastStalls(), lowStreak));
    }

    /** 播放器当前缓冲（诊断用；-1 = 还没采到）。 */
    private static long lastBufferMs() {
        try {
            return io.github.lwjlw.bilibtr.recon.SpeedSession.lastBufferMs();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 播放器口径的卡顿次数/分（诊断用；-1 = 还没采到）。 */
    private static long lastStalls() {
        try {
            return io.github.lwjlw.bilibtr.recon.SpeedSession.lastStallsPerMin();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String kb(double bps) {
        if (bps <= 0) return "-";
        return bps >= 1048576
                ? String.format(Locale.US, "%.2fMB/s", bps / 1048576.0)
                : String.format(Locale.US, "%.0fKB/s", bps / 1024.0);
    }
}
