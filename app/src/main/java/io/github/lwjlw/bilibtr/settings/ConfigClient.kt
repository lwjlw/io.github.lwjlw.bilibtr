package io.github.lwjlw.bilibtr.settings

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 界面 → 注入侧 的通道（**回环 TCP**，不是 ContentProvider）。
 *
 * ## 为什么不用 ContentProvider
 * Android 11+ 的**软件包可见性**会让 B站 进程报
 * `Unknown authority io.github.lwjlw.bilibtr.settings` ——
 * 除非 B站 自己的清单里声明 `<queries>`，而那是不可能的。
 *
 * 回环 TCP（`127.0.0.1`）没有任何这类限制，代理本身就是这么跑的。
 *
 * ⚠️ **所有方法都必须在 IO 线程调用**（主线程发网络请求会抛
 * `NetworkOnMainThreadException`，被 runCatching 吞掉后就表现为"永远未连接"）。
 */
object ConfigClient {

    /** 与注入侧的 [io.github.lwjlw.bilibtr.proxy.ConfigServer.PORT] 保持一致。 */
    private const val BASE = "http://127.0.0.1:18889"
    private const val TIMEOUT_MS = 1200

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "btr-cfg-client").apply { isDaemon = true }
    }

    private const val TAG = "BTR-UI"

    private fun get(path: String, timeout: Int = TIMEOUT_MS): String = try {
        val c = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        text
    } catch (t: Throwable) {
        // 不要静默吞掉：之前就是因为吞了异常，界面一直"未连接"却查不出原因
        Log.w(TAG, "GET $path 失败: $t")
        ""
    }

    private fun post(path: String, body: String): Boolean = try {
        val c = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "POST $path 失败: $t")
        false
    }

    /** 把设置推给注入侧（异步；B站 没在跑时连不上是正常的）。 */
    fun push(s: BtrSettings, onResult: ((Boolean) -> Unit)? = null) {
        pool.execute {
            val body = buildString {
                appendLine("enabled=${s.enabled}")
                appendLine("mode=${s.mode}")
                appendLine("concurrency=${s.concurrency}")
                appendLine("minsplitkb=${s.minSplitKb}")
                appendLine("port=${s.port}")
                appendLine("bufsizekb=${s.bufferSizeKb}")
                appendLine("buftimems=${s.bufferTimeMs}")
                appendLine("qn=${s.forceQuality}")
                appendLine("ball=${s.ball}")
            }
            val ok = post("/config", body)
            // 打日志：能直接看出"界面到底推没推、推了什么"
            Log.i(TAG, "push ok=$ok body=${body.replace("\n", " ")}")
            onResult?.invoke(ok)
        }
    }

    /** 测速数据（JSON）；IO 线程调用。 */
    fun fetchStats(): String = get("/stats")

    /** 当前播放的流 + 全部候选测速结果（JSON）；IO 线程调用。 */
    fun fetchInfo(): String = get("/info")

    /** 让注入侧对全部候选节点测速；IO 线程调用。 */
    fun speedTest(): Boolean = get("/speedtest", 3000).isNotEmpty()

    /** 手动锁定某个节点（host 传空 = 恢复自动）；IO 线程调用。 */
    fun pin(host: String): Boolean =
        get("/pin?h=" + java.net.URLEncoder.encode(host, "UTF-8")).isNotEmpty()

    /** 开关"自动切换最快节点"；IO 线程调用。 */
    fun setAuto(on: Boolean): Boolean = get("/auto?on=" + if (on) "1" else "0").isNotEmpty()

    /** 注入侧是否在线（B站 是否在跑）；IO 线程调用。 */
    fun ping(): Boolean = get("/ping", 800).contains("ok")
}
