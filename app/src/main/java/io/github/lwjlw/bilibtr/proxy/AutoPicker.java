package io.github.lwjlw.bilibtr.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lwjlw.bilibtr.recon.Recon;

/**
 * **播放时自动对"这个视频的候选 URL"测速，然后据此选节点**（不需要打开 btr 软件）。
 *
 * ## 为什么需要它
 * 在这之前，"测速选节点"这件事其实是断的：
 *   - 界面上点「对全部节点测速」（256KB/节点）的结果**只用于显示**，选路根本不看它；
 *   - 真正影响选路的只有"已经发生过的真实流量"（`CdnRacer.EMA_REAL`），
 *     以及一种 64KB 的冷启动探测（且只在锚点毫无实测时才用）。
 * 于是"哪个节点最快"这件事，只能在**跑起来之后**才知道 —— 而用户要的是：
 * 播之前/卡的时候，主动测一遍，选最快的那条。
 *
 * ## 时序（用户定的策略）
 * 1. **进新视频**：延后 {@value #FIRST_DELAY_MS} 毫秒再测 —— 起播那几秒带宽最紧张，
 *    实测"起播时探测会把起播从 284ms 拖到 1273ms"，必须避开；
 * 2. **卡顿了**：由 {@link StallWatcher} 触发**立刻复测**并重新选节点（受间隔/轮数限制）。
 *
 * ## 与"真实流量评分"的关系
 * 测速**不会**推翻"真实流量才是最终裁判"这条铁律：
 * 换节点时要过 {@link CdnRacer} 的 1.2× 门槛 + 15 秒驻留 + 每分钟 ≤4 次；
 * 之后播放过程中产生的真实流量仍然会继续修正评分（见 `CdnRacer.record`）。
 *
 * ## 开销（已经用户确认：任何网络都自动测速）
 * 一轮 ≈ 候选节点数 × 256 KB（十几个节点约 3 MB）。嫌费流量就在界面里关掉，
 * 或在 `btr.conf` 写 `autopick=false`。
 */
public final class AutoPicker {

    /** 进视频后延后这么久才开始测速（避开起播）。 */
    private static final long FIRST_DELAY_MS = 5_000L;
    /** 单个节点探多少字节。64KB 太短（排序会反），256KB 与界面测速同款。 */
    private static final int PROBE_BYTES = 256 * 1024;
    /**
     * 一次只跑几条探测。
     * ⚠️ 不能像界面测速那样"每节点一条线程"：那是在**正在播放**的同时抢带宽，
     * 会把视频自己拖卡 —— 正是我们要解决的问题。
     */
    private static final int WINDOW = 2;
    /** 一轮探测的总看门狗（探测本身是 3 秒连接 + 5 秒读、不重试）。 */
    private static final long ROUND_TIMEOUT_MS = 12_000L;
    /** 两次自动测速的最小间隔（卡顿可能连续来，别反复探测把自己拖垮）。 */
    private static final long MIN_ROUND_INTERVAL_MS = 30_000L;
    /** 同一条视频最多自动测几轮（进视频 1 轮 + 卡顿补救若干轮）。 */
    private static final int MAX_ROUNDS_PER_VIDEO = 4;

    // ---------------------------------------------------------------- 当前视频的上下文
    private static volatile String cid = "";
    private static volatile List<String> cands = Collections.emptyList();
    /** 探测必须带上播放器的那套请求头（UA/Cookie 等），否则 CDN 可能直接 4xx。 */
    private static volatile Map<String, String> headers = Collections.emptyMap();

    private static final AtomicInteger ROUNDS = new AtomicInteger();
    private static volatile long lastRoundAt = 0L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** 当前这条视频是否已经排过"进视频测速"。 */
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    private AutoPicker() {
    }

    /**
     * 换视频时调用（由 {@link CdnRacer#resolve} 在 cid 变化时触发）。
     *
     * @param newCid        新的 cid（可能为空字符串）
     * @param candidates    该视频的候选地址（同一清晰度的一整组）
     * @param hdrs          播放器那次请求的头（探测时原样复用）
     */
    public static void onNewVideo(String newCid, List<String> candidates, Map<String, String> hdrs) {
        try {
            if (!ProxyConfig.autoPick() || !CdnRacer.auto()) return;
            if (candidates == null || candidates.size() < 2) return;
            String c = newCid == null ? "" : newCid;
            // ⚠️ **cid 为空就别测**：这说明这条流的地址里没有 `/upgcxcode/x/y/<cid>/`，
            //    多半是进播放页之前的预热/广告流 —— 实测这种情况会白扫十几个节点（约 3MB），
            //    等真正的视频流出现时结果还会因为"cid 变了"而作废。
            if (c.isEmpty()) {
                // 去重：进播放页前这类流会出现十几次，别刷屏
                if (Recon.first("AUTO:NOCID", "v")) {
                    Recon.note("AUTO:SKIP", "这条流没有 cid（可能是预热/广告流），不自动测速");
                }
                return;
            }
            if (c.equals(cid) && SCHEDULED.get()) return;      // 同一个视频不重复排
            boolean changedVideo = !c.equals(cid);
            cid = c;
            cands = new ArrayList<>(candidates);
            headers = hdrs == null ? Collections.emptyMap() : new LinkedHashMap<>(hdrs);
            // ★ 节流/轮数必须**按视频重置**：否则上一条视频（尤其是预热流那一轮）刚跑完，
            //   新视频的"进视频测速"会被 30 秒全局间隔直接挡掉 —— 实测就是这么漏掉的。
            ROUNDS.set(0);
            lastRoundAt = 0L;
            SCHEDULED.set(true);
            if (changedVideo) {
                Recon.note("AUTO:PICK", "新视频 cid=" + c + "，候选 " + cands.size()
                        + " 个 → " + (FIRST_DELAY_MS / 1000) + " 秒后自动测速（避开起播）");
            }
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(FIRST_DELAY_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!c.equals(cid)) return;                    // 已经换视频了，这轮作废
                runRound("进视频");
            }, "btr-autopick-delay");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Recon.note("AUTO:ERR", "onNewVideo " + t);
        }
    }

    /**
     * 卡顿补救：立刻排一轮测速（由 {@link StallWatcher} 调用）。
     * 受"最小间隔 / 每视频轮数 / 同时只跑一轮"三重限制，重复调用是安全的。
     */
    public static void requestRound(String reason) {
        try {
            if (!ProxyConfig.autoPick() || !CdnRacer.auto()) return;
            if (cands.size() < 2) return;                      // 还没见过播放
            if (CdnRacer.pinned() != null) return;              // 用户手动锁了节点，别自作主张
            runRound(reason);
        } catch (Throwable t) {
            Recon.note("AUTO:ERR", "requestRound " + t);
        }
    }

    private static void runRound(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastRoundAt < MIN_ROUND_INTERVAL_MS) {
            Recon.note("AUTO:SKIP", "距上次自动测速 " + ((now - lastRoundAt) / 1000)
                    + "s < " + (MIN_ROUND_INTERVAL_MS / 1000) + "s，跳过（" + reason + "）");
            return;
        }
        if (ROUNDS.get() >= MAX_ROUNDS_PER_VIDEO) {
            Recon.note("AUTO:SKIP", "本条视频已自动测速 " + ROUNDS.get() + " 轮，跳过（" + reason + "）");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Recon.note("AUTO:SKIP", "上一轮自动测速还在跑，跳过（" + reason + "）");
            return;
        }
        lastRoundAt = now;
        ROUNDS.incrementAndGet();
        final List<String> list = cands;
        final Map<String, String> hdrs = headers;
        Thread t = new Thread(() -> {
            try {
                probeAll(list, hdrs, reason);
            } catch (Throwable e) {
                Recon.note("AUTO:ERR", "probeAll " + e);
            } finally {
                RUNNING.set(false);
            }
        }, "btr-autopick");
        t.setDaemon(true);
        t.start();
    }

    /** 对本轮候选逐个探测（限窗 {@value #WINDOW} 条），然后把结果交给选路。 */
    private static void probeAll(List<String> list, Map<String, String> hdrs, String reason) {
        final String roundCid = cid;      // 探测期间用户可能已经换视频了，收尾时要核对
        // 按 host 去重：保留第一次出现的那条 URL —— 它带的是**本视频的合法签名**
        LinkedHashMap<String, String> byHost = new LinkedHashMap<>();
        for (String u : list) {
            String h = Recon.hostOf(u);
            if (h != null && !byHost.containsKey(h)) byHost.put(h, u);
        }
        if (byHost.isEmpty()) return;

        final Map<String, Double> scores = new ConcurrentHashMap<>();
        final Map<String, String> errors = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(WINDOW, byHost.size()), r -> {
            Thread th = new Thread(r, "btr-autopick-probe");
            th.setDaemon(true);
            return th;
        });
        try {
            for (Map.Entry<String, String> e : byHost.entrySet()) {
                pool.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    double bps = 0;
                    try {
                        RangeFetcher.Result r = RangeFetcher.probe(e.getValue(), hdrs, PROBE_BYTES);
                        if (r.ok) {
                            long ms = Math.max(1, System.currentTimeMillis() - t0);
                            bps = r.body.length * 1000.0 / ms;
                        } else {
                            // ★ 失败原因一定要记：不然"全都失败"根本没法查
                            errors.put(e.getKey(), r.error == null || r.error.isEmpty() ? "fail" : r.error);
                        }
                    } catch (Throwable t) {
                        errors.put(e.getKey(), String.valueOf(t));
                    }
                    scores.put(e.getKey(), bps);
                });
            }
            pool.shutdown();
            pool.awaitTermination(ROUND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            pool.shutdownNow();     // 没跑完的（超时节点）不再等它们的分数
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(shortHost(e.getKey())).append('=');
            if (e.getValue() > 0) {
                sb.append(kb(e.getValue()));
            } else {
                String err = errors.get(e.getKey());
                sb.append("FAIL(").append(clip(err == null ? "?" : err, 40)).append(')');
            }
        }
        Recon.note("AUTO:PROBE", "自动测速（" + reason + "）" + scores.size() + "/" + byHost.size()
                + " 个有结果：{" + sb + "}");

        if (!roundCid.equals(cid)) {
            Recon.note("AUTO:SKIP", "探测期间换视频了（" + roundCid + " → " + cid + "），本轮结果作废");
            return;
        }
        CdnRacer.applyProbeScores(scores);
        String chosen = CdnRacer.selectByProbe(reason);
        if (chosen != null) {
            // ⚠️ Toast **不在这里发**：切换也可能发生在 resolve() 那条路径上（按探测分换），
            //    只有 CdnRacer 内部知道每一次真正的切换 → 提示统一由 CdnRacer 负责，
            //    否则就会出现"换了节点却没有任何提示"（用户实测踩到）。
            Recon.note("AUTO:SWITCH", "已切到 " + shortHost(Recon.hostOf(chosen))
                    + "（原因：" + reason + "）");
        }
    }

    private static String shortHost(String h) {
        if (h == null) return "-";
        int dot = h.indexOf('.');
        return dot > 0 ? h.substring(0, dot) : h;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String kb(double bps) {
        if (bps >= 1048576) return String.format(java.util.Locale.US, "%.2fMB/s", bps / 1048576.0);
        return String.format(java.util.Locale.US, "%.0fKB/s", bps / 1024.0);
    }
}
