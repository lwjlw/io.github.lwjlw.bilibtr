package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 探针 1：OkHttp 层。
 *
 * B站 的网络栈 = OkHttp（`okhttp3`）+ 自家封装 `com.bilibili.okretro`。
 * 这里挂两个"所有请求都会经过"的点：
 *   - `okhttp3.Request$Builder#build()`  —— 请求被构造出来的那一刻（栈 = 谁想发这个请求）
 *   - `okhttp3.OkHttpClient#newCall(Request)` —— 请求被交给客户端的那一刻
 *
 * 关心两类：
 *   - 打到媒体域名的请求（如果存在，说明媒体下载在 Java 层 → 拦截点更多）
 *   - 带 Range 头的请求（判断用的是不是标准 HTTP Range）
 *   - playurl 这类接口调用（顺带确认网络栈真的被我们挂上了）
 *
 * 全部脱敏后落盘；签名参数只会以 `key=*` 形式出现。
 */
public final class OkHttpProbe {

    private static final int MAX_FRAMES = 12;

    private OkHttpProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> requestBuilder = Reflect.load(cl, "okhttp3.Request$Builder");
        Class<?> okHttpClient = Reflect.load(cl, "okhttp3.OkHttpClient");
        Class<?> request = Reflect.load(cl, "okhttp3.Request");

        Recon.note("PROBE", "OkHttpProbe: Request$Builder=" + (requestBuilder != null)
                + " OkHttpClient=" + (okHttpClient != null) + " Request=" + (request != null));

        if (requestBuilder != null) {
            Method build = Reflect.exact(requestBuilder, "build");
            Hooks.install(x, build, "okhttp-build", resultHooker("HTTP-BUILD", true));
        }
        // ★ B站 的 Cookie 是在 `Headers.Builder.add("Cookie", ...)` 里加进去的，
        //   在 `Request$Builder.build()` 那一刻还看不到 —— 所以要多挂这一处。
        Class<?> headersBuilder = Reflect.load(cl, "okhttp3.Headers$Builder");
        if (headersBuilder != null) {
            for (Method m : Reflect.declaredNamed(headersBuilder, "add", "addUnsafeNonAscii")) {
                Hooks.install(x, m, "okhttp-hdr-" + m.getName(), chain -> {
                    try {
                        Object n = chain.getArgs().size() > 0 ? chain.getArg(0) : null;
                        Object v = chain.getArgs().size() > 1 ? chain.getArg(1) : null;
                        if (n instanceof String && v instanceof String
                                && "cookie".equalsIgnoreCase((String) n)) {
                            io.github.lwjlw.bilibtr.proxy.StreamMeta.captureAuth((String) v, null);
                        }
                        if (n instanceof String && v instanceof String
                                && "user-agent".equalsIgnoreCase((String) n)) {
                            io.github.lwjlw.bilibtr.proxy.StreamMeta.captureAuth(null, (String) v);
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
        }
        if (okHttpClient != null && request != null) {
            Method newCall = Reflect.exact(okHttpClient, "newCall", request);
            Hooks.install(x, newCall, "okhttp-newcall", requestArgHooker("HTTP-NEWCALL"));
        }
    }

    /** build()：返回的对象就是 Request。 */
    private static Hooker resultHooker(String kind, boolean withRange) {
        return chain -> {
            Object result = chain.proceed();
            try {
                handle(kind, result, withRange);
            } catch (Throwable ignored) {
            }
            return result;
        };
    }

    /** newCall(Request)：第 0 个参数是 Request。 */
    private static Hooker requestArgHooker(String kind) {
        return chain -> {
            Object result = chain.proceed();
            try {
                handle(kind, chain.getArg(0), true);
            } catch (Throwable ignored) {
            }
            return result;
        };
    }

    private static void handle(String kind, Object request, boolean withRange) {
        if (request == null) return;
        String url = urlOf(request);
        if (url == null) return;
        // 把 B站 自己的 Cookie/UA 抄给 StreamMeta —— 我们自己去查标题时要用
        if (url.contains("bilibili.com")) {
            io.github.lwjlw.bilibtr.proxy.StreamMeta.captureAuth(
                    headerOf(request, "Cookie"), headerOf(request, "User-Agent"));
        }
        String range = withRange ? headerOf(request, "Range") : null;

        if (Recon.isVideoByteUrl(url)) {
            String key = Recon.sanitize(url) + "|" + range;
            if (Recon.first(kind + ":media", key)) {
                Recon.record(kind + ":MEDIA",
                        "url=" + Recon.sanitize(url) + " range=" + range
                                + " ua=" + Recon.clip(headerOf(request, "User-Agent"), 40),
                        MAX_FRAMES);
            }
            return;
        }
        if (range != null) {
            String key = Recon.sanitize(url) + "|" + range;
            if (Recon.first(kind + ":range", key)) {
                Recon.record(kind + ":RANGE", "url=" + Recon.sanitize(url) + " range=" + range, MAX_FRAMES);
            }
            return;
        }
        if (Recon.isBiliApi(url)) {
            String key = Recon.sanitize(url);
            if (Recon.first(kind + ":api", key)) {
                Recon.record(kind + ":API", "url=" + Recon.sanitize(url), 6);
            }
        }
    }

    // ------------------------------------------------------------ 反射取值

    private static volatile Method mUrl;
    private static volatile Method mHeaders;
    private static volatile Method mHeadersGet;

    static String urlOf(Object request) {
        try {
            Method m = mUrl;
            if (m == null) {
                m = request.getClass().getMethod("url");
                mUrl = m;
            }
            Object u = m.invoke(request);
            return u == null ? null : u.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static String headerOf(Object request, String name) {
        try {
            Method h = mHeaders;
            if (h == null) {
                h = request.getClass().getMethod("headers");
                mHeaders = h;
            }
            Object headers = h.invoke(request);
            if (headers == null) return null;
            Method g = mHeadersGet;
            if (g == null) {
                g = headers.getClass().getMethod("get", String.class);
                mHeadersGet = g;
            }
            Object v = g.invoke(headers, name);
            return v == null ? null : Recon.clip(v.toString(), 120);
        } catch (Throwable t) {
            return null;
        }
    }
}
