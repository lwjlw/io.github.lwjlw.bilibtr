package io.github.lwjlw.bilibtr.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置的持久化（模块 App 自己的 SharedPreferences）。
 *
 * 为什么不让界面直接写 B站 的 `btr.conf`：**Android 分区存储不允许**
 * （`/sdcard/Android/data/<别的包名>/` 对其它 App 不可见）。
 * 所以界面写自己的存储，注入侧通过 [SettingsProvider] 来读。
 */
object SettingsStore {

    private const val PREF = "btr_settings"
    private const val KEY_STATS = "stats_json"

    /** 默认值集中在这里，改了要同步 `proxy/ProxyConfig.java` 的兜底值。 */
    val DEFAULT = BtrSettings()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): BtrSettings {
        val p = prefs(ctx)
        return BtrSettings(
            enabled = p.getBoolean(BtrSettings.KEY_ENABLED, DEFAULT.enabled),
            mode = p.getString(BtrSettings.KEY_MODE, DEFAULT.mode) ?: DEFAULT.mode,
            concurrency = p.getInt(BtrSettings.KEY_CONCURRENCY, DEFAULT.concurrency),
            minSplitKb = p.getInt(BtrSettings.KEY_MIN_SPLIT_KB, DEFAULT.minSplitKb),
            port = p.getInt(BtrSettings.KEY_PORT, DEFAULT.port),
            bufferSizeKb = p.getInt(BtrSettings.KEY_BUFFER_SIZE_KB, DEFAULT.bufferSizeKb),
            bufferTimeMs = p.getInt(BtrSettings.KEY_BUFFER_TIME_MS, DEFAULT.bufferTimeMs),
            forceQuality = p.getInt(BtrSettings.KEY_FORCE_QUALITY, DEFAULT.forceQuality),
            ball = p.getBoolean(BtrSettings.KEY_BALL, DEFAULT.ball),
        )
    }

    fun save(ctx: Context, s: BtrSettings) {
        prefs(ctx).edit()
            .putBoolean(BtrSettings.KEY_ENABLED, s.enabled)
            .putString(BtrSettings.KEY_MODE, s.mode)
            .putInt(BtrSettings.KEY_CONCURRENCY, s.concurrency)
            .putInt(BtrSettings.KEY_MIN_SPLIT_KB, s.minSplitKb)
            .putInt(BtrSettings.KEY_PORT, s.port)
            .putInt(BtrSettings.KEY_BUFFER_SIZE_KB, s.bufferSizeKb)
            .putInt(BtrSettings.KEY_BUFFER_TIME_MS, s.bufferTimeMs)
            .putInt(BtrSettings.KEY_FORCE_QUALITY, s.forceQuality)
            .putBoolean(BtrSettings.KEY_BALL, s.ball)
            .apply()
    }

    /** 单个字段写入（方便 `adb shell content call` 调试）。 */
    fun setField(ctx: Context, key: String, value: String): Boolean {
        val cur = load(ctx)
        val next = when (key) {
            BtrSettings.KEY_ENABLED -> cur.copy(enabled = value.toBooleanStrictOrNull() ?: return false)
            BtrSettings.KEY_MODE -> cur.copy(mode = value)
            BtrSettings.KEY_CONCURRENCY -> cur.copy(concurrency = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_MIN_SPLIT_KB -> cur.copy(minSplitKb = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_PORT -> cur.copy(port = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_BUFFER_SIZE_KB -> cur.copy(bufferSizeKb = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_BUFFER_TIME_MS -> cur.copy(bufferTimeMs = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_FORCE_QUALITY -> cur.copy(forceQuality = value.toIntOrNull() ?: return false)
            BtrSettings.KEY_BALL -> cur.copy(ball = value.toBooleanStrictOrNull() ?: return false)
            else -> return false
        }
        save(ctx, next)
        return true
    }

    // ------------------------------------------------------------------ 测速数据

    /**
     * 注入侧（B站 进程）推上来的实时测速数据（JSON）。
     * 用字符串存是为了**注入侧不用依赖任何 JSON 库**，它自己拼。
     */
    fun putStats(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_STATS, json).apply()
    }

    fun getStats(ctx: Context): String = prefs(ctx).getString(KEY_STATS, "") ?: ""
}
