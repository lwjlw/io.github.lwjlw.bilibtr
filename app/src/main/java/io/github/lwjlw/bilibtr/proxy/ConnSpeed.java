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

    /**
     * 单次最多爬升到几条。
     * 2026-10-13：8 → **16**。真正的上限仍由用户配置决定（`cap = min(MAX_RAMP, 用户值)`），
     * 这里放宽只是让"用户想开更多"时不被代码挡住。
     */
    private static final int MAX_RAMP = 16;
    private static final double EMA_OLD = 0.8;
    private static final double EMA_NEW = 0.2;
    /**
     * 小于这个长度的分片不参与"每连接速度"估计（太短，数字虚高）。
     * 2026-10-13：64KB → **256KB** —— 边缘缓存命中时 64KB 能测出 12~14MB/s 的假速度，
     * 冷启动除以它就会算出"1 条就够"。
     */
    private static final long MIN_SAMPLE_BYTES = 256 * 1024L;
    private static final double MIN_BPS = 32 * 1024.0;
    /** 单连接速度上限（估计用）。50MB/s → 12MB/s：手机链路上单连接不该有这么快。 */
    private static final double MAX_BPS = 12 * 1024 * 1024.0;
    /** 评估间隔。 */
    private static final long MIN_EVAL_INTERVAL_MS = 2000L;
    /** 减并发的最小间隔。 */
    private static final long MIN_DEC_INTERVAL_MS = 10_000L;
    /** 缓冲宽裕到什么程度才允许"减并发"（毫秒）。 */
    private static final long DEC_BUFFER_OK_MS = 15_000L;

    /** 判定饱和所需的最小新样本数（每个样本 = 一个 2 秒窗口）。 */
    private static final int SAT_MIN_SAMPLES = 2;
    /** 判定饱和前至少等这么久。 */
    private static final long SAT_MIN_ELAPSED_MS = 5_000L;
    /** 等这么久还拿不到样本就放弃判定（**不冻结**）。 */
    private static final long SAT_MAX_WAIT_MS = 20_000L;
    /** 冷静期分级（8s → 16s → 30s），有效果时清零。 */
    private static final long[] SAT_COOLDOWN_STEPS = {8_000L, 16_000L, 30_000L};

    /** 每条连接的实测吞吐（B/s）；0 = 没有数据。只用于冷启动初始值。 */
    private static volatile double perConnBps = 0;

    private static volatile int lastDesired = 0;
    private static volatile long lastEvalAt = 0L;
    /** 实际吞吐（B/s），由 StatsPusher 每 2 秒喂进来 —— **闭环的输入**。 */
    private static volatile double aggBps = 0;
    private static volatile double aggBeforeInc = -1;
    private static volatile long incAt = 0L;
    private static volatile long satUntil = 0L;
    /** 冷静期等级（0 = 没在冷静；有效果或换流时清零）。 */
    private static volatile int satEscalate = 0;
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
        onStreamIfChanged(targetBps);    // 换清晰度/换视频 → 清冷静期与 streak
        snapshotIfDue(targetBps);        // 诊断：每 30 秒留一行状态快照

        // 冷启动：先用"每连接实测"推一个起点，别从 1 条慢慢爬
        if (lastDesired <= 0) {
            int n = 2;
            if (perConnBps > 0) {
                n = (int) Math.ceil(targetBps / perConnBps);
            }
            // ★ 视频流**最少从 2 条起**：`perConnBps` 是"某一片有多快"的 EWMA，
            //   在边缘缓存命中时会虚高（实测出现过 12~14MB/s），除以它就会算出 1 条 →
            //   然后老老实实一条条爬，这正是"并发涨得太慢"的一个来源。
            if (n < 2) n = 2;
            lastDesired = Math.min(cap, n);
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
        //   ★ 2026-10-13 修复：**必须先拿到足够的新样本才允许判定饱和**。
        //     诊断实测：旧代码会在"期间新样本 0 个"时判定饱和（日志里 182KB/s → 182KB/s
        //     完全相等就是这个特征），然后冻结 30 秒 —— 那不是"加了没用"，是"那几秒播放器
        //     根本没拉数据"。样本不够就挂起这次比较，等下一轮再判；等太久（SAT_MAX_WAIT_MS）
        //     就放弃判定且**不冻结**。
        if (aggBeforeInc > 0) {
            long newSamples = AGG_SAMPLES.get() - samplesAtInc;
            long elapsed = now - incAt;
            if (newSamples >= SAT_MIN_SAMPLES && elapsed >= SAT_MIN_ELAPSED_MS) {
                if (agg < aggBeforeInc * 1.10) {
                    satEscalate = Math.min(satEscalate + 1, SAT_COOLDOWN_STEPS.length);
                    long cool = SAT_COOLDOWN_STEPS[satEscalate - 1];
                    satUntil = now + cool;
                    Recon.note("CONC:SAT", String.format(Locale.US,
                            "加到 %d 条后吞吐没涨（%s → %s，新样本 %d 个/%dms）"
                                    + "→ 判定链路已饱和，%d 秒内不再加（第 %d 级冷静期）"
                                    + "【窗口原始值 %s，每连接估算 %s，缓冲 %dms，卡顿 %d 次/分】",
                            lastDesired, kb(aggBeforeInc), kb(agg), newSamples, elapsed,
                            cool / 1000, satEscalate, kb(aggWindowBps), kb(perConnBps),
                            lastBufferMs(), lastStalls()));
                } else {
                    satEscalate = 0;      // 有效果 → 冷静期等级清零
                }
                aggBeforeInc = -1;
            } else if (elapsed > SAT_MAX_WAIT_MS) {
                Recon.note("CONC:SAT-SKIP", String.format(Locale.US,
                        "加上去之后只拿到 %d 个新样本（%dms 内）→ 样本不足，本次不做饱和判定、不冻结",
                        newSamples, elapsed));
                aggBeforeInc = -1;
            }
            // 否则：保持挂起，下一轮评估再看
        }

        if (agg < targetBps * 0.95) {
            lowStreak++;
        } else {
            lowStreak = 0;
        }

        // ② 加并发：**差得远就一次加多条**（原来无论多差都只加 1 条、还要连续两次不达标）
        boolean farBelow = agg < targetBps * 0.5;
        int step = farBelow ? 2 : 1;
        int needStreak = farBelow ? 1 : 2;
        if (lowStreak >= needStreak && lastDesired < cap && now > satUntil) {
            lowStreak = 0;
            aggBeforeInc = agg;
            incAt = now;
            samplesAtInc = AGG_SAMPLES.get();
            lastDesired = Math.min(cap, lastDesired + step);
            Recon.note("CONC:UP", String.format(Locale.US,
                    "吞吐 %s < 目标 %s（%.0f%%）→ 并发%s到 %d 条【每连接估算 %s，上限 %d，"
                            + "窗口原始值 %s，样本数 %d，冷静期剩余 %dms】",
                    kb(agg), kb(targetBps), 100.0 * agg / targetBps,
                    farBelow ? "一次加 2 →" : "加", lastDesired, kb(perConnBps), cap,
                    kb(aggWindowBps), AGG_SAMPLES.get(), satMsLeft()));
        } else if (agg > targetBps * 1.30 && lastDesired > 1
                && now - lastDecAt > MIN_DEC_INTERVAL_MS
                && bufferComfortable()) {
            // ③ 远高于目标就减 —— ★ 但**只在缓冲已经够宽裕时**才减。
            //    否则会出现"刚好跑快一点就减、减完又慢"的抖动（低码率视频尤其明显）。
            lastDesired--;
            lastDecAt = now;
            Recon.note("CONC:DOWN", String.format(Locale.US,
                    "吞吐 %s 远高于目标 %s 且缓冲宽裕（%dms）→ 并发减到 %d 条"
                            + "【窗口原始值 %s，卡顿 %d 次/分，样本数 %d】",
                    kb(agg), kb(targetBps), lastCachedMs(), lastDesired,
                    kb(aggWindowBps), lastStalls(), AGG_SAMPLES.get()));
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

    /** 播放器当前"往前缓存了多少毫秒"（-1 = 读不到）。 */
    private static long lastCachedMs() {
        try {
            return io.github.lwjlw.bilibtr.recon.SpeedSession.lastVideoCachedMs();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 缓冲是否宽裕到可以"减并发"。
     *
     * 为什么要这道闸：`agg > 目标×1.30` 只说明"这两秒拉得比需要的快"，
     * 完全可能是在补刚才欠下的债（缓冲见底）。此时减并发 = 火上浇油。
     * 读不到缓存时长时（-1）按"不能判断"处理 → 不允许减（宁可多开一条）。
     */
    private static boolean bufferComfortable() {
        long cached = lastCachedMs();
        return cached >= DEC_BUFFER_OK_MS;
    }

    /**
     * 目标码率发生明显变化（换清晰度/换视频）→ **清空冷静期与 streak**。
     *
     * 踩过：720P 阶段留下的 `lowStreak`/冷静期会被 4K 阶段继承，
     * 于是切成 4K 之后还要白等几十秒才开始加并发。
     */
    private static void onStreamIfChanged(double targetBps) {
        double prev = lastTargetBps;
        if (prev <= 0) return;
        double diff = Math.abs(targetBps - prev) / prev;
        if (diff <= 0.25) return;
        satUntil = 0L;
        satEscalate = 0;
        lowStreak = 0;
        aggBeforeInc = -1;
        Recon.note("CONC:RESET", String.format(Locale.US,
                "目标码率 %s → %s（变化 %.0f%%）→ 清空冷静期与 streak，允许重新爬升",
                kb(prev), kb(targetBps), diff * 100));
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
