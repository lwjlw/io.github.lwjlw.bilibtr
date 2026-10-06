package com.lw5.bilibtr.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.lw5.bilibtr.recon.Recon;

/**
 * 阶段 P0：**CDN 候选竞速 + 优选（含"锚点优先"）**。
 *
 * ## 一、候选与评分的两条铁律
 * - **候选地区无关**（{@link CdnCandidates}）：上游地址 ∪ 备用地址 ∪ 同家族换 host（大陆 8 + 海外 4），
 *   谁实测快用谁，不做地理偏好。签名不匹配的候选会在实测里被淘汰，所以多放候选是安全的。
 * - **评分必须来自真实流量**：64KB 探测只测到握手+慢启动，排序会反
 *   （实测：探测 cosov 288 > akamai 210，持续传输却是 akamai 快 11 倍）。
 *   所以真实传输反哺 {@code EMA_REAL}，探测只作**冷启动**依据。
 *
 * ## 二、锚点优先（参考实现 DESIGN §3.9 原话）
 * > 锚点优先级：**视频自身给的 host > 竞速最优 > 其它候选**（竞速只补位纠偏）
 *
 * 规则（见 {@link #resolve}）：
 * 1. **锚点够用就不动**：视频自己给的节点，其实测吞吐 ≥ `bw÷8×1.2` → 保持锚点；
 * 2. **锚点有实测但不够用**：只有当**另一节点也有真实实测**且 ≥1.5× 时才换；
 *    —— **绝不凭 64KB 探测就换**（踩过：冷门 4K 上被探测带去更慢的 akamai，卡顿反而变多）；
 * 3. **锚点毫无实测数据**（刚起播）→ 才用探测结果做冷启动。
 *
 * 防抖三件套（参考实现 DESIGN §3.6）：**迟滞 1.2× + 最小驻留 20s + 每分钟最多切 2 次**；
 * 另有**竞速全局节流 120s**（反复探测自身就占带宽）。
 */
public final class CdnRacer {

    private static final long TTL_MS = 300_000L;
    private static final double HYSTERESIS = 1.2;
    private static final int PROBE_BYTES = 64 * 1024;
    private static final long PROBE_TIMEOUT_MS = 800L;
    /** 并行窗口：一次只跑 2 条探测，避免自己把带宽占满（参考实现同款）。 */
    private static final int WINDOW = 2;
    private static final double EMA_OLD = 0.65;
    private static final double EMA_NEW = 0.35;
    /** 目标余量：`目标 = 码率 × 1.2`（参考实现）。 */
    private static final double TARGET_MARGIN = 1.2;
    /** 无码率信息时的兜底目标（偏保守，避免在慢线路上反复横跳）。 */
    private static final double FALLBACK_TARGET_BPS = 1.5 * 1024 * 1024;
    /** 换节点门槛：另一节点必须**真实实测**快这么多倍。 */
    private static final double SWITCH_MARGIN = 1.5;
    /**
     * 最小驻留：切换后至少这么久不再评估切换。
     * 参考实现的血泪结论（DESIGN §3.6）：判据互斥 + 迟滞 + 驻留，
     * 否则会 1~2 秒互切一次，**每次切换都掐断流**。
     */
    private static final long MIN_DWELL_MS = 20_000L;
    /** 每分钟最多切几次（参考实现同为 2 次）。 */
    private static final int MAX_SWITCH_PER_MIN = 2;
    /**
     * 竞速全局节流：节点快慢是"网络→节点"的属性，不同清晰度共享同一批节点，
     * 没必要每路都重探（实测：反复探测自身就占带宽、把播放拖慢）。
     */
    private static final long MIN_RACE_INTERVAL_MS = 120_000L;

    /** 真实流量实测吞吐（B/s）—— **唯一可信的评分来源**。 */
    private static final Map<String, Double> EMA_REAL = new ConcurrentHashMap<>();
    /** 冷启动探测吞吐（B/s）—— 只在"锚点毫无真实数据"时用。 */
    private static final Map<String, Double> PROBE_BPS = new ConcurrentHashMap<>();
    private static final Map<String, Pick> PROBE = new ConcurrentHashMap<>();
    private static final Set<String> RACING = ConcurrentHashMap.newKeySet();

    private static volatile String activeHost;

    // ---------------------------------------------------------------- 界面交互状态
    /** 界面手动锁定的 host（null = 自动）。 */
    private static volatile String pinHost = null;
    /** 是否允许自动换节点（界面上的"自动切换最快"开关）。 */
    private static volatile boolean autoMode = true;
    /** 最近一次请求用到的**原始地址**与候选列表（给界面显示/测速用）。 */
    private static volatile String lastUrl = null;
    private static volatile java.util.List<String> lastCands = java.util.Collections.emptyList();
    /** 候选列表按 cid 归并：**同一个视频只增不减**，否则界面上会一会儿 2 个一会儿 13 个。 */
    private static volatile String lastCid = "";
    /**
     * **实际真正使用的那条上游地址**。
     *
     * 注意与 {@link #lastUrl} 的区别：`lastUrl` 是**播放器请求的地址**（也就是 B站 给的），
     * 而播放器会在它自己的两个备用地址之间来回换 —— 界面上如果只显示 `lastUrl`，
     * 就会出现"锁定了 hz，地址却还在 sz/hz 跳"的错觉（用户实测反馈）。
     */
    private static volatile String lastUsed = null;

    /**
     * 记录"实际走了哪条"。
     *
     * ⚠️ **只记视频流**：`lastUsed` 是全局单值，音频流的请求也会走 resolve()，
     * 而音频那条流的候选里往往没有视频锁定的节点（只能用它自己的），
     * 于是界面上的"实际走"会被音频覆盖、看起来像在乱跳（用户实测反馈：
     * "锁定了 hz，地址却 sz、hz 跳来跳去"）。
     */
    private static void noteUsed(String url, String current) {
        if (url != null && isVideoStream(current)) lastUsed = url;
    }

    public static String lastUsed() {
        return lastUsed;
    }

    /** 本次是否成功用上了手动锁定的节点（没锁则为 true）。 */
    private static volatile boolean pinApplied = true;

    public static boolean pinApplied() {
        return pinApplied;
    }
    private static volatile java.util.Map<String, String> lastHeaders = java.util.Collections.emptyMap();
    /** 全部候选的测速结果：host -> "ok|fail:bps"。 */
    private static final Map<String, String> SPEEDTEST = new ConcurrentHashMap<>();
    private static volatile long speedTestAt = 0L;

    public static void pin(String host) {
        pinHost = (host == null || host.isEmpty()) ? null : host;
        Recon.note("CDN:PIN", pinHost == null ? "恢复自动选择节点" : "手动锁定节点 " + host);
        // 立刻生效：清掉预读缓存 + 丢弃旧节点的空闲连接，
        // 这样后续请求马上走新节点，不需要重新进视频。
        Prefetcher.clearAll();
        UpstreamPool.clearAll();
        if (pinHost == null) {
            activeHost = null;   // 让自动逻辑重新评估
        } else {
            activeHost = pinHost;
        }
    }

    public static String pinned() {
        return pinHost;
    }

    public static void setAuto(boolean auto) {
        autoMode = auto;
        Recon.note("CDN:AUTO", auto ? "已开启自动切换最快节点" : "已关闭自动切换（用手动选择）");
        if (auto) {
            pinHost = null;
            activeHost = null;
        }
    }

    public static boolean auto() {
        return autoMode;
    }

    /** 是不是视频流（用 URL 里的 `bw` 判断：视频远大于音频）。 */
    static boolean isVideoStream(String url) {
        return targetBps(url) >= 128 * 1024;   // 目标 ≥128KB/s（≈1Mbps）才算视频；音频只有 ~15KB/s
    }

    public static String cidOf(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/upgcxcode/\\d+/\\d+/(\\d+)/").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 测速进度：已完成 / 总数（界面显示"测速中 3/12"）。 */
    private static final java.util.concurrent.atomic.AtomicInteger ST_DONE =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ST_TOTAL =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int speedTestDone() {
        return ST_DONE.get();
    }

    public static int speedTestTotal() {
        return ST_TOTAL.get();
    }

    /**
     * 界面点"开始测速"时调用：对**全部候选节点**探一遍。
     *
     * 之前的实现有两个问题（用户实测反馈："点测速只显示测了一两个，其余未测速"）：
     * ① 候选只生成了 8 个，**根本就没列全**（已放宽到全表）；
     * ② 十几个探测线程**一次性全放出去**，互相抢带宽，谁也测不准，慢的半天没结果。
     *
     * 现在：按 host 去重 → **固定 4 个一批**并发 → 每完成一个就更新进度。
     */
    public static void speedTestAll() {
        java.util.List<String> base = new java.util.ArrayList<>(lastCands);
        java.util.Map<String, String> hdrs = lastHeaders;
        if (base.isEmpty()) {
            // 本进程没见过播放（B站 分进程）→ 用别的进程落盘的候选
            base = readCandsFile();
            hdrs = java.util.Collections.emptyMap();
            if (!base.isEmpty()) {
                Recon.note("CDN:TEST", "本进程无候选，改用落盘的 " + base.size() + " 个节点");
            }
        }
        final java.util.List<String> cands = base;
        final java.util.Map<String, String> headers = hdrs;
        if (cands.isEmpty()) {
            Recon.note("CDN:TEST", "没有候选节点可测（先播放一段视频）");
            return;
        }

        // 按 host 去重（保留第一次出现的那条 URL —— 它带的是本视频的合法签名）
        final java.util.LinkedHashMap<String, String> byHost = new java.util.LinkedHashMap<>();
        for (String u : cands) {
            String h = Recon.hostOf(u);
            if (h != null && !byHost.containsKey(h)) byHost.put(h, u);
        }
        if (byHost.isEmpty()) return;

        SPEEDTEST.clear();
        ST_TOTAL.set(byHost.size());
        ST_DONE.set(0);
        speedTestAt = System.currentTimeMillis();
        Recon.note("CDN:TEST", "开始对 " + byHost.size() + " 个节点测速（4 个一批）");

        // 直接用**无限线程池**：每个节点一条线程，谁也堵不住谁。
        //（之前用固定 4 线程：只要有一条卡住，排在后面的节点永远轮不到 → 进度停在 3/13）
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newCachedThreadPool();
        for (final java.util.Map.Entry<String, String> e : byHost.entrySet()) {
            pool.submit(() -> {
                String host = e.getKey();
                String url = e.getValue();
                long t0 = System.currentTimeMillis();
                Recon.note("CDN:TEST-START", "测 " + host);
                try {
                    // 探 256KB：64KB 太短，测出来的是握手/慢启动，排序会反
                    RangeFetcher.Result r = RangeFetcher.probe(url, headers, 256 * 1024);
                    long ms = Math.max(1, System.currentTimeMillis() - t0);
                    SPEEDTEST.put(host, r.ok ? ("1:" + (r.body.length * 1000L / ms)) : "0:0");
                    Recon.note("CDN:TEST-DONE", host + " " + (r.ok ? (r.body.length / 1024 + "KB/" + ms + "ms")
                            : "失败") + " 耗时 " + ms + "ms");
                } catch (Throwable t) {
                    SPEEDTEST.put(host, "0:0");
                    Recon.note("CDN:TEST-DONE", host + " 异常 " + t
                            + " 耗时 " + (System.currentTimeMillis() - t0) + "ms");
                } finally {
                    ST_DONE.incrementAndGet();
                }
            });
        }
        pool.shutdown();

        // ★ 看门狗：20 秒还没测完的，一律标记"超时"并收尾 —— 绝不让界面永远卡在 x/13
        final java.util.Map<String, String> target = byHost;
        Thread wd = new Thread(() -> {
            try {
                Thread.sleep(20_000L);
                int stuck = 0;
                for (String h : target.keySet()) {
                    if (!SPEEDTEST.containsKey(h)) {
                        SPEEDTEST.put(h, "0:0");
                        stuck++;
                        ST_DONE.incrementAndGet();
                    }
                }
                if (stuck > 0) {
                    Recon.note("CDN:TEST", "看门狗：有 " + stuck + " 个节点 20 秒没返回，已标记超时");
                }
            } catch (InterruptedException ignored) {
            }
        }, "btr-test-wd");
        wd.setDaemon(true);
        wd.start();
    }

    private static final String CANDS_FILE = "btr-cands.txt";

    /** 把候选列表写到 B站 目录（**跨进程共享**：ConfigServer 未必和播放在同一进程）。 */
    private static void writeCandsFile(java.util.List<String> cands) {
        try {
            java.io.File dir = com.lw5.bilibtr.recon.AppHolder.reconDir();
            if (dir == null) return;
            StringBuilder sb = new StringBuilder();
            for (String u : cands) sb.append(u).append('\n');
            java.io.File f = new java.io.File(dir, CANDS_FILE);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 从文件读候选（本进程没有时用）。 */
    private static java.util.List<String> readCandsFile() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.io.File dir = com.lw5.bilibtr.recon.AppHolder.reconDir();
            if (dir == null) return out;
            java.io.File f = new java.io.File(dir, CANDS_FILE);
            if (!f.isFile()) return out;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) out.add(line);
                }
            } finally {
                r.close();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static long speedTestAt() {
        return speedTestAt;
    }

    public static String speedTestResult(String host) {
        return SPEEDTEST.get(host);
    }

    public static java.util.List<String> candidates() {
        return lastCands;
    }

    public static String lastUrl() {
        return lastUrl;
    }
    private static volatile long lastSwitchAt = 0L;
    private static volatile long lastRaceAt = 0L;
    private static final java.util.ArrayDeque<Long> SWITCH_TIMES = new java.util.ArrayDeque<>();

    private CdnRacer() {
    }

    private static final class Pick {
        final String host;
        final long at = System.currentTimeMillis();

        Pick(String host) {
            this.host = host;
        }

        boolean expired() {
            return System.currentTimeMillis() - at > TTL_MS;
        }
    }

    // ------------------------------------------------------------------ 决策

    /**
     * 解析本次请求该用哪条上游地址。
     *
     * @return 选中的**完整地址**（签名来自候选本身，不会拼错）；没结论时返回 `current`
     */
    public static String resolve(String id, String current, Map<String, String> headers) {
        try {
            List<String> cands = CdnCandidates.expand(current, AltRegistry.get(id));
            // ⚠️ 只记**视频流**：音频流（bw≈100kbps）只有 2 个候选，
            //    会把界面上的节点列表冲成 2 行、切来切去（实测现象）。
            if (isVideoStream(current)) {
                lastUrl = current;
                lastHeaders = headers;
                String cid = cidOf(current);
                if (cid == null) cid = "";
                if (!cid.equals(lastCid) || lastCands.isEmpty()) {
                    // 换了视频：重新开始累积
                    lastCid = cid;
                    lastCands = new java.util.ArrayList<>(cands);
                } else {
                    // 同一个视频：**并集**（不同清晰度/不同来源给的候选不一样）
                    java.util.List<String> merged = new java.util.ArrayList<>(lastCands);
                    for (String u : cands) {
                        boolean dup = false;
                        for (String v : merged) {
                            if (v.equals(u)) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) merged.add(u);
                    }
                    lastCands = merged;
                }
                writeCandsFile(lastCands);
            }
            if (cands.size() < 2) return current;
            String key = keyOf(cands);
            String anchor = Recon.hostOf(current);
            if (anchor == null) return current;

            // ⓪ **界面手动锁定**优先级最高
            String pin = pinHost;
            if (pin != null) {
                String u = urlWithHost(cands, pin);
                if (u != null) {
                    noteUsed(u, current);
                    pinApplied = true;
                    return u;
                }
                // 锁定的节点**不在这条流的候选里**（B站 没给、也换不出来，比如签名体系不同）
                pinApplied = false;
                if (Recon.first("CDN:PIN-MISS", pin)) {
                    Recon.note("CDN:PIN-MISS", "锁定的 " + pin
                            + " 不在这条流的候选里 → 本次只能用它自己的地址");
                }
            } else {
                pinApplied = true;
            }
            // 关了自动切换又没锁定 → 就用视频自己给的地址
            if (!autoMode) {
                noteUsed(current, current);
                return current;
            }

            double target = targetBps(current);
            Double anchorEma = EMA_REAL.get(anchor);

            // ① 锚点够用 → 不动（锚点优先）
            if (anchorEma != null && anchorEma >= target) {
                activeHost = anchor;
                noteUsed(current, current);
                if (Recon.first("CDN:KEEP", anchor)) {
                    Recon.note("CDN:KEEP", "锚点 " + shortHost(anchor) + " 实测 " + kb(anchorEma)
                            + " ≥ 目标 " + kb(target) + " → 保持不动（锚点优先）");
                }
                return current;
            }

            // ② 锚点有实测但不够用 → 只在"另有真实实测明显更快"时换
            if (anchorEma != null) {
                HostBps best = bestRealExcept(cands, anchor);
                if (best != null && best.bps >= anchorEma * SWITCH_MARGIN) {
                    if (switchAllowed()) {
                        markSwitch();
                        activeHost = best.host;
                        noteUsed(urlWithHost(cands, best.host), current);
                        Recon.note("CDN:SWITCH", "锚点 " + shortHost(anchor) + "=" + kb(anchorEma)
                                + " 不够（目标 " + kb(target) + "）→ 换 " + shortHost(best.host)
                                + "=" + kb(best.bps) + "（真实实测 ≥" + SWITCH_MARGIN + "×）");
                        return urlWithHost(cands, best.host);
                    }
                    if (Recon.first("CDN:HOLD", best.host)) {
                        Recon.note("CDN:HOLD", "本可换到 " + shortHost(best.host)
                                + "，但受驻留/频率限制，暂缓");
                    }
                    return current;
                }
                if (Recon.first("CDN:KEEP-UNDER", anchor)) {
                    Recon.note("CDN:KEEP", "锚点 " + shortHost(anchor) + "=" + kb(anchorEma)
                            + " 低于目标 " + kb(target) + "，但**没有可信的更优节点** → 不猜、保持");
                }
                return current;
            }

            // ③ 锚点毫无实测数据 → 冷启动：才用探测结果
            Pick p = PROBE.get(key);
            if (p != null && !p.expired()) {
                String u = urlWithHost(cands, p.host);
                if (u != null) {
                    if (!p.host.equals(anchor) && Recon.first("CDN:COLD", p.host)) {
                        Recon.note("CDN:COLD", "起播冷启动：探测优选 " + shortHost(p.host)
                                + "（锚点 " + shortHost(anchor) + " 尚无实测）");
                    }
                    activeHost = p.host;
                    noteUsed(u, current);
                    return u;
                }
            }
            startRace(key, cands, headers);
            noteUsed(current, current);
            return current;
        } catch (Throwable t) {
            return current;
        }
    }

    /** 每节点累计字节/请求数（测速面板用）。 */
    private static final Map<String, long[]> HOST_BYTES = new ConcurrentHashMap<>();

    /** 给测速面板用的一行。 */
    public static final class HostStat {
        public String host;
        public long bytes;
        public long reqs;
    }

    /** 按传输量从大到小返回各节点统计。 */
    public static java.util.List<HostStat> hostStats() {
        java.util.List<HostStat> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : HOST_BYTES.entrySet()) {
            HostStat h = new HostStat();
            h.host = e.getKey();
            h.bytes = e.getValue()[0];
            h.reqs = e.getValue()[1];
            out.add(h);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.bytes, a.bytes));
        return out;
    }

    /** 真实流量反哺：每完成一次上游传输就记一笔。**评分只认这个。** */
    public static void record(String host, long bytes, long ms) {
        if (host == null || bytes <= 0 || ms <= 0) return;
        try {
            double bps = bytes * 1000.0 / ms;
            EMA_REAL.merge(host, bps, (old, nw) -> old * EMA_OLD + nw * EMA_NEW);
            long[] acc = HOST_BYTES.computeIfAbsent(host, k -> new long[2]);
            synchronized (acc) {
                acc[0] += bytes;
                acc[1]++;
            }
            if (activeHost == null) activeHost = host;
        } catch (Throwable ignored) {
        }
    }

    public static String activeHost() {
        return activeHost;
    }

    /** 日志用：真实实测评分摘要。 */
    public static String emaSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : EMA_REAL.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(shortHost(e.getKey())).append('=').append(kb(e.getValue()));
        }
        return sb.length() == 0 ? "(无真实数据)" : sb.toString();
    }

    // ------------------------------------------------------------------ 目标码率

    /** 目标速率 = URL 里的 `bw`(bit/s) ÷ 8 × 1.2；没有就用兜底值。 */
    static double targetBps(String url) {
        try {
            long bw = queryLong(url, "bw");
            if (bw > 0) return bw / 8.0 * TARGET_MARGIN;
        } catch (Throwable ignored) {
        }
        return FALLBACK_TARGET_BPS;
    }

    private static long queryLong(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return -1;
        for (String kv : url.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            if (name.equals(kv.substring(0, e))) {
                try {
                    return Long.parseLong(kv.substring(e + 1).trim());
                } catch (Throwable t) {
                    return -1;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 选路辅助

    private static final class HostBps {
        String host;
        double bps;
    }

    /** 候选里"除 anchor 外卖真实实测最好的"。**只认真实流量，不认探测。** */
    private static HostBps bestRealExcept(List<String> cands, String anchor) {
        HostBps best = null;
        for (String u : cands) {
            String h = Recon.hostOf(u);
            if (h == null || h.equals(anchor)) continue;
            Double v = EMA_REAL.get(h);
            if (v == null) continue;
            if (best == null || v > best.bps) {
                best = new HostBps();
                best.host = h;
                best.bps = v;
            }
        }
        return best;
    }

    private static void startRace(String key, List<String> cands, Map<String, String> headers) {
        if (System.currentTimeMillis() - lastRaceAt <= MIN_RACE_INTERVAL_MS) return;   // 全局节流
        if (!RACING.add(key)) return;
        final List<String> list = new ArrayList<>(cands);
        final String anchor = Recon.hostOf(cands.get(0));
        Thread t = new Thread(() -> {
            try {
                // ⚠️ 起播那一刻带宽最紧张，探测自己会抢带宽、把起播拖慢（实测：起播 284ms → 1273ms）。
                // 所以**延后 3 秒**再做；而且如果那时锚点已经有真实实测数据，就干脆不探了。
                Thread.sleep(3000L);
                if (anchor != null && EMA_REAL.get(anchor) != null) {
                    Recon.note("CDN:RACE-SKIP", "锚点 " + shortHost(anchor)
                            + " 已有实测数据，无需探测（省带宽）");
                    return;
                }
                lastRaceAt = System.currentTimeMillis();
                probeRace(key, list, headers);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                RACING.remove(key);
            }
        }, "btr-cdn-racer");
        t.setDaemon(true);
        t.start();
    }

    private static boolean switchAllowed() {
        long now = System.currentTimeMillis();
        if (now - lastSwitchAt < MIN_DWELL_MS) return false;
        synchronized (SWITCH_TIMES) {
            while (!SWITCH_TIMES.isEmpty() && now - SWITCH_TIMES.peekFirst() > 60_000L) {
                SWITCH_TIMES.pollFirst();
            }
            return SWITCH_TIMES.size() < MAX_SWITCH_PER_MIN;
        }
    }

    private static void markSwitch() {
        long now = System.currentTimeMillis();
        lastSwitchAt = now;
        synchronized (SWITCH_TIMES) {
            SWITCH_TIMES.addLast(now);
        }
    }

    // ------------------------------------------------------------------ 冷启动探测

    private static void probeRace(String key, List<String> cands, Map<String, String> headers) {
        List<Probe> probes = new ArrayList<>();
        for (int i = 0; i < cands.size(); i += WINDOW) {
            List<Thread> batch = new ArrayList<>();
            List<Probe> out = Collections.synchronizedList(new ArrayList<>());
            for (int k = i; k < Math.min(i + WINDOW, cands.size()); k++) {
                final String url = cands.get(k);
                Thread t = new Thread(() -> {
                    long s = System.currentTimeMillis();
                    RangeFetcher.Result r = RangeFetcher.fetch(url, "GET", headers, 0, PROBE_BYTES - 1, 1);
                    long ms = Math.max(1, System.currentTimeMillis() - s);
                    Probe pr = new Probe();
                    pr.host = Recon.hostOf(url);
                    pr.ok = r.ok;
                    pr.bps = r.ok ? (r.body.length * 1000L / ms) : 0;
                    if (pr.ok) PROBE_BPS.put(pr.host, (double) pr.bps);
                    out.add(pr);
                }, "btr-race-probe");
                t.setDaemon(true);
                batch.add(t);
                t.start();
            }
            for (Thread t : batch) {
                try {
                    t.join(PROBE_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            probes.addAll(out);
        }

        Probe best = null;
        int okCount = 0;
        StringBuilder sb = new StringBuilder();
        for (Probe p : probes) {
            if (p.ok) okCount++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(shortHost(p.host)).append('=').append(p.ok ? kb(p.bps) : "FAIL");
            if (p.ok && (best == null || p.bps > best.bps)) best = p;
        }
        if (best == null) {
            Recon.note("CDN:RACE", "候选{" + sb + "} 全部失败 → 保持原地址");
            return;
        }
        Pick old = PROBE.get(key);
        String chosen = best.host;
        if (old != null && !old.expired() && !old.host.equals(best.host)) {
            chosen = old.host;   // 探测噪声大，没明显优势就不换
        }
        PROBE.put(key, new Pick(chosen));
        Recon.note("CDN:RACE", "候选 " + probes.size() + " 个（可用 " + okCount + "）：{" + sb
                + "} → 冷启动优选 " + shortHost(chosen) + "（仅当锚点无实测时才用）");
    }

    private static final class Probe {
        String host;
        boolean ok;
        long bps;
    }

    // ------------------------------------------------------------------ 小工具

    private static String keyOf(List<String> urls) {
        List<String> hosts = new ArrayList<>();
        for (String u : urls) {
            String h = Recon.hostOf(u);
            if (h != null && !hosts.contains(h)) hosts.add(h);
        }
        Collections.sort(hosts);
        return String.join("|", hosts).toLowerCase(Locale.US);
    }

    private static String urlWithHost(List<String> cands, String host) {
        if (host == null) return null;
        for (String u : cands) {
            if (host.equals(Recon.hostOf(u))) return u;
        }
        return null;
    }

    private static String shortHost(String h) {
        if (h == null) return "-";
        int dot = h.indexOf('.');
        return dot > 0 ? h.substring(0, dot) : h;
    }

    private static String kb(double bps) {
        if (bps >= 1048576) return String.format(Locale.US, "%.2fMB/s", bps / 1048576.0);
        return String.format(Locale.US, "%.0fKB/s", bps / 1024.0);
    }
}
