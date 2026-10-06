package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 探针 2：响应体扫描 —— 用来 dump playurl 的结果。
 *
 * 为什么要这么绕：B站 9.8.0 的 playurl 走的是 **gRPC（bilibili.app.playurl.v1）+ protobuf**，
 * 没有现成的 JSON 可以解析，而且我们**不硬编码 B站 类名**，所以没法挂"playurl 回调"。
 *
 * 于是改成从数据本身下手：凡是 Java 层读到的响应体（bytes()/string()），
 * 一律扫一遍里面的 `http(s)://`，只要命中"视频字节地址"就记下来。
 * 好处：
 *   - protobuf / JSON / 纯文本通吃；
 *   - 记录里带上调用栈 → 顺带告诉我们 **谁在消费 playurl 响应**（阶段 A 的核心问题之一）。
 *
 * 反过来，如果整场播放下来这里一条媒体直链都没有，那说明 URL 是在 native 层拼的，
 * 代理方案的接入点就得另找 —— 这本身也是关键结论。
 */
public final class BodyProbe {

    /** 单个响应体最多扫这么多字节（DASH/playurl 响应都在几十 KB 量级）。 */
    private static final int MAX_SCAN = 512 * 1024;
    /** 一次最多记多少个直链。 */
    private static final int MAX_URLS = 16;

    private BodyProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> responseBody = Reflect.load(cl, "okhttp3.ResponseBody");
        if (responseBody == null) {
            Recon.note("PROBE", "BodyProbe: okhttp3.ResponseBody 不存在，跳过");
            return;
        }
        List<Method> methods = Reflect.declaredNamed(responseBody, "bytes", "string");
        for (Method m : methods) {
            Hooks.install(x, m, "http-body-" + m.getName(), hooker(m.getName()));
        }
    }

    private static Hooker hooker(String name) {
        return chain -> {
            Object result = chain.proceed();
            try {
                if (result instanceof byte[]) {
                    scanBytes((byte[]) result, name);
                } else if (result instanceof String) {
                    scanText((String) result, name, ((String) result).length());
                }
            } catch (Throwable ignored) {
            }
            return result;
        };
    }

    private static void scanBytes(byte[] b, String via) {
        if (b == null || b.length == 0) return;
        int n = Math.min(b.length, MAX_SCAN);
        // 标题必须按 UTF-8 解（中文否则是乱码）；URL 扫描仍走 ISO-8859-1 那条
        io.github.lwjlw.bilibtr.proxy.StreamMeta.scanBytes(b, n);
        // URL 是纯 ASCII，用 ISO-8859-1 逐字节映射最安全（不会因为非法 UTF-8 丢字符）
        scanText(new String(b, 0, n, StandardCharsets.ISO_8859_1), via, b.length);
    }

    private static void scanText(String text, String via, int fullLen) {
        if (text == null || text.length() < 16) return;
        // 注意：标题**不在这里**扫 —— 这条路径的 text 是 ISO-8859-1 解出来的，
        // 中文会变乱码。标题走 scanBytes()（UTF-8），见 scanBytes()。

        if (text.indexOf("http") < 0) return;   // 廉价预筛，绝大多数响应体到此为止

        List<String> media = Recon.videoUrlsIn(text, MAX_URLS);
        boolean playUrl = Recon.looksLikePlayUrl(text);

        if (media.isEmpty()) {
            if (playUrl && Recon.first("BODY:PLAYURL-EMPTY", Recon.clip(text, 300))) {
                Recon.record("BODY:PLAYURL-NO-DIRECT",
                        "via=" + via + " len=" + fullLen
                                + " 响应体像 playurl 但没扫到视频直链（可能是相对路径或已加密字段）",
                        10);
            }
            return;
        }
        String key = Recon.join(media, "|");
        if (!Recon.first("BODY:URLS", key)) return;

        Recon.record("BODY:VIDEO-URLS",
                "via=" + via + " len=" + fullLen + " playurl=" + playUrl
                        + " n=" + media.size() + " urls=" + Recon.join(media, " , "),
                12);
        // 单独一行给"节点画像"：阶段 B 的节点表就是从这里长出来的
        Recon.note("BODY:HOSTS", "distinct=" + Recon.distinctHosts(media));
    }
}
