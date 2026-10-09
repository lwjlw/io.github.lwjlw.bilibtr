package io.github.lwjlw.bilibtr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lwjlw.bilibtr.settings.BtrSettings
import io.github.lwjlw.bilibtr.settings.ConfigClient
import io.github.lwjlw.bilibtr.settings.SettingsStore
import io.github.lwjlw.bilibtr.ui.theme.BiliBTRTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BiliBTRTheme {
                SettingsScreen(this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: ComponentActivity) {
    var s by remember { mutableStateOf(SettingsStore.load(activity)) }
    var statsJson by remember { mutableStateOf("") }
    var infoJson by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }

    fun update(next: BtrSettings) {
        s = next
        SettingsStore.save(activity, next)
        ConfigClient.push(next)
    }

    // 打开界面先把设置推一次，保证两边一致
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ConfigClient.push(s) }
    }

    // 每秒刷新：测速数据 + 当前流信息。⚠️ 网络调用必须在 IO 线程
    var tickCount = 0
    LaunchedEffect(Unit) {
        while (true) {
            val tick = withContext(Dispatchers.IO) {
                val stats = ConfigClient.fetchStats()
                val info = ConfigClient.fetchInfo()
                val ok = stats.isNotBlank() || ConfigClient.ping()
                Triple(stats, info, ok)
            }
            statsJson = tick.first.ifBlank { SettingsStore.getStats(activity) }
            infoJson = tick.second
            connected = tick.third
            // ★ 每 5 秒**自动补推一次设置**。
            //   为什么需要：如果用户改开关时 **B站 没在运行**，端口没人接、这次推送就丢了，
            //   之后再也没机会补上（界面读不到 B站 的目录，注入侧也读不到界面的存储）。
            //   定期重推 = 只要 B站 一起来就自动同步。
            tickCount++
            if (tickCount % 5 == 0) {
                withContext(Dispatchers.IO) { ConfigClient.push(s) }
            }
            delay(1000)
        }
    }

    val info = remember(infoJson) { runCatching { JSONObject(infoJson) }.getOrNull() }
    val cid = info?.optString("cid").orEmpty()

    Scaffold(topBar = { TopAppBar(title = { Text("BiliBTR 网络加速") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------- 连接状态 ----------------
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (connected) "已连接 B站 进程" else "未连接（B站 未运行）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (connected) "设置改动立即生效"
                        else "打开 B站 播放视频后会自动连上，设置在那时生效",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------------- 正在播放 ----------------
            if (connected && (cid.isNotEmpty() || info?.optString("url").orEmpty().isNotEmpty())) {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("正在播放", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (cid.isNotEmpty()) "cid $cid" else "（还没识别到视频）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val used = info?.optString("used").orEmpty()
                        val given = info?.optString("url").orEmpty()
                        if (used.isNotEmpty()) {
                            Text(
                                "实际走：" + shortUrl(used),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (given.isNotEmpty() && given != used) {
                            Text(
                                "B站给的：" + shortUrl(given),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (info?.optBoolean("pinApplied", true) == false) {
                            Text(
                                "⚠ 锁定的节点不在这条流的候选里，本条只能用它自己的地址",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ---------------- 节点选择 ----------------
            NodeCard(info)

            // ---------------- 总开关 ----------------
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用加速", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (s.enabled) "改写播放地址、走本地代理" else "关闭 = B站 完全按原样跑",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.enabled, onCheckedChange = { update(s.copy(enabled = it)) })
                }
            }

            // ---------------- 悬浮球 ----------------
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("播放页悬浮球（3x / 4x）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (s.ball) "播放视频时点一下画面出现，几秒后自动隐藏"
                            else "已关闭，播放页不会有悬浮球",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = s.ball, onCheckedChange = { update(s.copy(ball = it)) })
                }
            }

            // ---------------- 测速面板 ----------------
            SpeedPanel(statsJson)

            // ---------------- 缓冲设置 ----------------
            SectionCard(
                "缓冲设置",
                "线路抖动时，「缓冲」比「并发」更能救体感。0 = 不干预，保持 B站 默认。",
            ) {
                // ⚠️ **单位必须换算**（曾经是个 bug）：
                // 界面显示 **MB**，但底层字段 `bufferSizeKb` 与配置项 `bufsizekb` 都是 **KB**。
                // 原来直接把滑块的 MB 数当 KB 存下去 → "200 MB" 实际只有 **200 KB**
                // （B站 原本是 150 MB），缓冲被压到极小，
                // 导致**缓冲水位（缓冲时长）永远撑不满**，看起来就是"缓冲时长没生效"。
                // 现在在这里做 MB ↔ KB 换算。
                IntSlider("缓冲大小", s.bufferSizeKb / 1024, 0, 200, 0,
                    { v -> update(s.copy(bufferSizeKb = v * 1024)) }) {
                    if (it == 0) "不干预" else "$it MB"
                }
                Text(
                    "实测 B站 默认已经是 150MB，通常不用动。",
                    style = MaterialTheme.typography.bodySmall,
                )
                IntSlider("缓冲时长", s.bufferTimeMs, 0, 60000, 0,
                    { v -> update(s.copy(bufferTimeMs = v)) }) {
                    if (it == 0) "不干预" else "${it / 1000} 秒"
                }
                Text(
                    "这个才是真正有用的旋钮（B站 默认不设水位）。参考值 30~60 秒。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---------------- 性能参数 ----------------
            SectionCard("性能参数", "一般不用改。") {
                IntSlider("并发上限", s.concurrency, 1, 16, 0,
                    { v -> update(s.copy(concurrency = v)) }) { "$it 条" }
                Text(
                    "实际并发由码率自动决定（码率÷8×1.2 ÷ 每条实测速度），这里只是上限。",
                    style = MaterialTheme.typography.bodySmall,
                )
                IntSlider("最小分片", s.minSplitKb, 64, 1024, 0,
                    { v -> update(s.copy(minSplitKb = v)) }) { "$it KB" }
                IntSlider("代理端口", s.port, 1024, 60000, 0,
                    { v -> update(s.copy(port = v)) }) { "$it" }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 节点卡片：自动开关 + 一键测速（全部候选）+ 结果列表（点一下切过去）。
 */
@Composable
private fun NodeCard(info: JSONObject?) {
    var stDone by remember { mutableStateOf(0) }
    var stTotal by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf("") }
    var hosts by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) }

    // 从轮询来的 info 同步状态
    LaunchedEffect(info?.optLong("stAt"), info?.optString("activeHost"), info?.optString("pinned")) {
        if (info != null) {
            auto = info.optBoolean("auto", true)
            pinned = info.optString("pinned").orEmpty()
            stDone = info.optInt("stDone", 0)
            stTotal = info.optInt("stTotal", 0)
            val arr = info.optJSONArray("hosts")
            val list = ArrayList<Triple<String, String, Boolean>>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.optJSONObject(i) ?: continue
                    list.add(
                        Triple(
                            h.optString("h"),
                            h.optString("st"),
                            h.optBoolean("active"),
                        )
                    )
                }
            }
            hosts = list
        }
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("节点", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动选最快", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (auto) "实测哪个快用哪个（推荐）" else "已关闭：只用你选的节点",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { on ->
                        auto = on
                        if (on) pinned = ""
                        Thread { ConfigClient.setAuto(on) }.start()
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val testing = stTotal > 0 && stDone < stTotal
                Button(
                    onClick = {
                        // 只发指令；进度由轮询回来的 stDone/stTotal 驱动（不再固定等 4 秒）
                        Thread { ConfigClient.speedTest() }.start()
                    },
                    enabled = !testing,
                ) {
                    Text(
                        if (testing) "测速中 $stDone/$stTotal"
                        else if (stTotal > 0) "重新测速（共 $stTotal 个节点）"
                        else "对全部节点测速"
                    )
                }
                if (pinned.isNotEmpty()) {
                    Button(onClick = {
                        pinned = ""
                        Thread { ConfigClient.pin("") }.start()
                    }) { Text("取消锁定") }
                }
            }

            if (hosts.isEmpty()) {
                Text(
                    "还没有候选节点。播放视频后点上面按钮。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("点一行即切到该节点（立即对后续请求生效）", style = MaterialTheme.typography.bodySmall)
                hosts.forEach { (host, st, active) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (active) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable {
                                pinned = host
                                auto = false
                                Thread { ConfigClient.pin(host) }.start()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                shortHost(host),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                speedText(st) + when {
                                    active -> "   ← 当前使用"
                                    st.isBlank() && stTotal > 0 && stDone < stTotal -> "   （排队中）"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (host == pinned) {
                            Text("已锁定", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    steps: Int,
    onChange: (Int) -> Unit,
    format: (Int) -> String,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value.toFloat(),
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            onValueChange = { onChange(it.roundToInt()) },
        )
    }
}

@Composable
private fun SpeedPanel(json: String) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("测速面板", style = MaterialTheme.typography.titleMedium)
            val o = runCatching { JSONObject(json) }.getOrNull()
            if (json.isBlank() || o == null) {
                Text(
                    "暂无数据 —— 打开 B站 播放任意视频后，这里会实时刷新。",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("吞吐", fmtBps(o.optLong("throughputBps")))
                Stat("卡顿", "${o.optInt("stallsPerMin")} 次/分")
                Stat("并发", "${o.optInt("concurrency")} 条")
                Stat("TTFB", "${o.optInt("ttfbP50Ms")} ms")
            }
            Text(
                "请求数 ${o.optInt("requests")}　累计 ${fmtBytes(o.optLong("bytes"))}",
                style = MaterialTheme.typography.bodySmall,
            )
            val hosts = o.optJSONArray("hosts")
            if (hosts != null && hosts.length() > 0) {
                Text("本片实际用量", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                for (i in 0 until minOf(hosts.length(), 8)) {
                    val h = hosts.optJSONObject(i) ?: continue
                    Text(
                        "  ${shortHost(h.optString("h"))}  ${h.optLong("n")} 次  ${fmtBytes(h.optLong("b"))}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** `host/path`（**去掉签名 query**：既太长，也不该外露）。 */
private fun shortUrl(u: String): String = runCatching {
    val i = u.indexOf("://")
    val s = if (i > 0) u.substring(i + 3) else u
    val q = s.indexOf('?')
    if (q > 0) s.substring(0, q) else s
}.getOrDefault(u)

private fun shortHost(h: String): String {
    val i = h.indexOf('.')
    return if (i > 0) h.substring(0, i) else h
}

/** 测速结果 "1:12345" → "12 KB/s"。 */
private fun speedText(st: String): String {
    if (st.isBlank()) return "未测速"
    val parts = st.split(":")
    if (parts.size != 2) return "未测速"
    if (parts[0] == "0") return "不可用"
    val bps = parts[1].toLongOrNull() ?: return "未测速"
    return when {
        bps >= 1 shl 20 -> String.format("%.2f MB/s", bps / 1048576.0)
        bps >= 1 shl 10 -> String.format("%.0f KB/s", bps / 1024.0)
        else -> "$bps B/s"
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1 shl 20 -> String.format("%.1f MB", b / 1048576.0)
    b >= 1 shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

private fun fmtBps(b: Long): String = when {
    b >= 1 shl 20 -> String.format("%.2f MB/s", b / 1048576.0)
    b >= 1 shl 10 -> String.format("%.0f KB/s", b / 1024.0)
    else -> "$b B/s"
}
