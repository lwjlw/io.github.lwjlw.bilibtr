package com.lw5.bilibtr.settings

import android.os.Bundle

/**
 * BiliBTR 的全部可调参数。
 *
 * ⚠️ 改动这里必须**同步改两处**：
 * - 注入侧（B站 进程内）的 `proxy/ProxyConfig.java` 读取逻辑；
 * - provider 的字段名（本文件的 [toBundle] / [fromBundle]）。
 */
data class BtrSettings(
    /** 总开关。false = B站 完全按原样跑（我们的代码不介入字节）。 */
    val enabled: Boolean = true,
    /** `proxy`（正常） / `redirect302`（一律回 302，用于排查）。 */
    val mode: String = "proxy",
    /** 并发上限：单个 Range 最多切几路。实际路数由码率自适应决定，这里只是上限。 */
    val concurrency: Int = 8,
    /** 小于这个长度的 Range 不切分（KB）。 */
    val minSplitKb: Int = 128,
    /** 本地代理端口。 */
    val port: Int = 18888,
    /**
     * **缓冲大小（KB）**，0 = 不干预。
     * 对应播放器的 `max-buffer-size`。参考实现作者原话：
     * "线路抖动时**缓冲比并发更能救体感**"。
     */
    val bufferSizeKb: Int = 0,
    /**
     * **缓冲时长（ms）**，0 = 不干预。
     * 对应播放器的高/低水位（`first/next/last-high-water-mark-ms`）。
     */
    val bufferTimeMs: Int = 0,
    /** 强制清晰度 qn（0 = 不干预；120 = 4K）。 */
    val forceQuality: Int = 0,
    /**
     * **播放页悬浮球**（3x / 4x 倍速）总开关。
     * B站 原生没有 3x/4x，悬浮球补上；选"跟随"时完全不干预。
     */
    val ball: Boolean = true,
) {
    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_MODE = "mode"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_MIN_SPLIT_KB = "minsplitkb"
        const val KEY_PORT = "port"
        const val KEY_BUFFER_SIZE_KB = "bufsizekb"
        const val KEY_BUFFER_TIME_MS = "buftimems"
        const val KEY_FORCE_QUALITY = "qn"
        const val KEY_BALL = "ball"
    }
}

fun BtrSettings.toBundle(): Bundle = Bundle().apply {
    putBoolean(BtrSettings.KEY_ENABLED, enabled)
    putString(BtrSettings.KEY_MODE, mode)
    putInt(BtrSettings.KEY_CONCURRENCY, concurrency)
    putInt(BtrSettings.KEY_MIN_SPLIT_KB, minSplitKb)
    putInt(BtrSettings.KEY_PORT, port)
    putInt(BtrSettings.KEY_BUFFER_SIZE_KB, bufferSizeKb)
    putInt(BtrSettings.KEY_BUFFER_TIME_MS, bufferTimeMs)
    putInt(BtrSettings.KEY_FORCE_QUALITY, forceQuality)
    putBoolean(BtrSettings.KEY_BALL, ball)
}

fun bundleToSettings(b: Bundle, fallback: BtrSettings): BtrSettings = BtrSettings(
    enabled = b.getBoolean(BtrSettings.KEY_ENABLED, fallback.enabled),
    mode = b.getString(BtrSettings.KEY_MODE) ?: fallback.mode,
    concurrency = b.getInt(BtrSettings.KEY_CONCURRENCY, fallback.concurrency),
    minSplitKb = b.getInt(BtrSettings.KEY_MIN_SPLIT_KB, fallback.minSplitKb),
    port = b.getInt(BtrSettings.KEY_PORT, fallback.port),
    bufferSizeKb = b.getInt(BtrSettings.KEY_BUFFER_SIZE_KB, fallback.bufferSizeKb),
    bufferTimeMs = b.getInt(BtrSettings.KEY_BUFFER_TIME_MS, fallback.bufferTimeMs),
    forceQuality = b.getInt(BtrSettings.KEY_FORCE_QUALITY, fallback.forceQuality),
    ball = b.getBoolean(BtrSettings.KEY_BALL, fallback.ball),
)
