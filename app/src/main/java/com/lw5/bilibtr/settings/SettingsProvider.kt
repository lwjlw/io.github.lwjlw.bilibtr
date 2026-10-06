package com.lw5.bilibtr.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * **模块 App 与 B站 进程之间的唯一通道。**
 *
 * ## 为什么必须是 ContentProvider
 * 界面（模块 App 的 uid）**读不到也写不了** B站 的数据目录
 * （Android 分区存储：`/sdcard/Android/data/<别人包名>/` 不可见）。
 * 而注入代码跑在 **B站 的进程/uid** 里，同样读不到模块 App 的私有目录。
 * 于是只剩两条路：共享存储（要危险权限）或 **ContentProvider** —— 选后者。
 *
 * 权威名 [AUTHORITY] 在 `AndroidManifest.xml` 里声明为 `exported="true"`
 * （不加权限：数据只是几个开关，且不加权限才能用 `adb shell content call` 调试）。
 *
 * ## 方法（都用 `ContentResolver.call`）
 * | method | 参数 | 返回 |
 * | --- | --- | --- |
 * | `getSettings` | — | 全部设置（Bundle，键见 [BtrSettings]） |
 * | `setSetting` | `key` / `value` 两个 String extra | `ok`(Boolean) |
 * | `putStats` | `json` String extra | `ok`(Boolean) |
 * | `getStats` | — | `json` |
 *
 * ## 调试用命令（adb shell）
 * ```text
 * adb shell content call --uri content://com.lw5.bilibtr.settings --method getSettings
 * adb shell content call --uri content://com.lw5.bilibtr.settings --method setSetting \
 *     --extra key s enabled --extra value s false
 * ```
 */
class SettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.lw5.bilibtr.settings"
        val URI: Uri = Uri.parse("content://$AUTHORITY")

        const val M_GET_SETTINGS = "getSettings"
        const val M_SET_SETTING = "setSetting"
        const val M_PUT_STATS = "putStats"
        const val M_GET_STATS = "getStats"
        const val M_PING = "ping"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle.EMPTY
        return try {
            when (method) {
                M_PING -> Bundle().apply { putBoolean("ok", true) }

                M_GET_SETTINGS -> SettingsStore.load(ctx).toBundle()

                M_SET_SETTING -> {
                    val key = extras?.getString("key").orEmpty()
                    val value = extras?.getString("value").orEmpty()
                    Bundle().apply { putBoolean("ok", SettingsStore.setField(ctx, key, value)) }
                }

                M_PUT_STATS -> {
                    SettingsStore.putStats(ctx, extras?.getString("json").orEmpty())
                    Bundle().apply { putBoolean("ok", true) }
                }

                M_GET_STATS -> Bundle().apply { putString("json", SettingsStore.getStats(ctx)) }

                else -> Bundle.EMPTY
            }
        } catch (t: Throwable) {
            Bundle().apply {
                putBoolean("ok", false)
                putString("error", t.toString())
            }
        }
    }

    // 不用数据库，这几个方法留空实现即可（ContentProvider 是抽象类）
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0
}
