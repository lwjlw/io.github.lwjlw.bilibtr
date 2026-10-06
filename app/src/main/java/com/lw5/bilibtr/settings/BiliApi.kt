package com.lw5.bilibtr.settings

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查视频标题（给测速面板显示"正在播放什么"）。
 *
 * 注入侧只拿得到 `cid`（从媒体地址 `/upgcxcode/xx/yy/<cid>/...` 里抠出来），
 * 标题由**界面这边**去 B站 公开接口换 —— 这样注入侧不用拦 API 响应，简单可靠。
 *
 * 需要一个普通权限 `INTERNET`（见 AndroidManifest）。
 */
object BiliApi {

    private val cache = HashMap<String, String>()

    /** 按 cid 查标题；查不到返回空串。**必须在 IO 线程调用。** */
    fun titleOf(cid: String): String {
        if (cid.isEmpty()) return ""
        synchronized(cache) {
            cache[cid]?.let { return it }
        }
        val t = runCatching {
            val c = (URL("https://api.bilibili.com/x/web-interface/view?cid=$cid")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BiliBTR")
            }
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val o = JSONObject(text)
            if (o.optInt("code", -1) != 0) "" else o.optJSONObject("data")?.optString("title", "") ?: ""
        }.getOrDefault("")
        if (t.isNotEmpty()) {
            synchronized(cache) { cache[cid] = t }
        }
        return t
    }
}
