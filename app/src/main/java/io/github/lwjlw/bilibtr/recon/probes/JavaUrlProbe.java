package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 探针 3：Java 层"URL 字符串"的全网兜底。
 *
 * 即便媒体字节由 native 拉取，**URL 字符串迟早要在 Java 层出现一次**
 * （要么构造 URL，要么给 Uri，要么递给 JNI）。这是不依赖任何 B站 类名的兜底网：
 *   - `java.net.URL(String)`      构造
 *   - `java.net.URL#openConnection()`
 *   - `android.net.Uri#parse(String)`
 *
 * 命中就记 **调用栈** —— 栈顶那一帧就是"决定这个媒体地址的人"，
 * 也就是阶段 A 要找的接入点。
 */
public final class JavaUrlProbe {

    private JavaUrlProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> url = Reflect.load(cl, "java.net.URL");
        Class<?> uri = Reflect.load(cl, "android.net.Uri");

        Constructor<?> urlCtor = Reflect.ctor(url, String.class);
        if (urlCtor != null) {
            Hooks.install(x, urlCtor, "java-url-ctor", ctorHooker("JAVA:URL-CTOR"));
        } else {
            Recon.note("PROBE", "JavaUrlProbe: 没找到 java.net.URL(String)");
        }

        Method open = url == null ? null : Reflect.exact(url, "openConnection");
        if (open != null) {
            Hooks.install(x, open, "java-url-open", selfHooker("JAVA:URL-OPEN"));
        }

        Method parse = uri == null ? null : Reflect.exact(uri, "parse", String.class);
        if (parse != null) {
            Hooks.install(x, parse, "android-uri-parse", argZeroHooker("JAVA:URI-PARSE"));
        } else {
            Recon.note("PROBE", "JavaUrlProbe: 没找到 android.net.Uri.parse(String)");
        }
    }

    /** 构造函数：第 0 个参数是 URL 字符串。 */
    private static Hooker ctorHooker(String kind) {
        return chain -> {
            try {
                Object a0 = chain.getArg(0);
                if (a0 instanceof String) hit(kind, (String) a0);
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
    }

    /** 静态方法：第 0 个参数是字符串。 */
    private static Hooker argZeroHooker(String kind) {
        return chain -> {
            try {
                Object a0 = chain.getArg(0);
                if (a0 instanceof String) hit(kind, (String) a0);
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
    }

    /** 实例方法：this 就是 URL。 */
    private static Hooker selfHooker(String kind) {
        return chain -> {
            try {
                Object self = chain.getThisObject();
                if (self != null) hit(kind, self.toString());
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        };
    }

    private static void hit(String kind, String s) {
        if (!fastLooksLikeMedia(s)) return;
        if (!Recon.isVideoByteUrl(s)) return;
        String key = Recon.sanitize(s);
        if (!Recon.first(kind, key)) return;
        Recon.record(kind, "url=" + key, 14);
    }

    /**
     * 极廉价预筛：Uri.parse 调用量很大（UI 到处在用），
     * 所以先用若干 indexOf 把 99.9% 的调用挡在正则和栈提取之前。
     */
    private static boolean fastLooksLikeMedia(String s) {
        if (s == null || s.length() < 20) return false;
        if (s.charAt(0) != 'h') return false;   // 媒体地址一定是 http(s)://
        return s.contains("/upgcxcode/")
                || s.contains(".m4s")
                || s.contains("bilivideo")
                || s.contains("akamaized")
                || s.contains("szbdyd")
                || s.contains("xycdn")
                || s.contains("mountaintoys")
                || s.contains("nexusedgeio")
                || s.contains("ahdohpiechei")
                || (s.contains("hdslb") && (s.contains(".m4s") || s.contains(".mp4") || s.contains(".flv")));
    }
}
