package io.github.lwjlw.bilibtr.recon.probes;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import io.github.lwjlw.bilibtr.proxy.StreamMeta;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;

/**
 * 抓视频标题（给测速面板显示"正在播放什么"）。
 *
 * ## 为什么不用响应体
 * 试过两条路都不行：
 * ① 从 API 响应体里扫 `"title"`：**`x/web-interface/view` 的响应体压根没走
 *    `ResponseBody.string()/bytes()`**（实测一条都没扫到），而别的接口又带 bvid，
 *    会把 UI 文案（"配置互动组件"）误当成标题。
 * ② 让界面按 cid 去公开接口查：能用，但要额外网络、还可能被风控。
 *
 * ## 正解：**Activity 标题**
 * B站 打开视频页时会设置 Activity 标题 —— 这正是**最近任务列表里显示的那个名字**。
 * 直接拦 `Activity.setTitle` / `setTaskDescription` 即可，**零额外请求、零解析**。
 *
 * 只认"看起来像标题"的（长度合理、不是 App 名、不是纯英文包名），
 * 并且**后设置的覆盖先设置的**（进入视频页时会重新设一次）。
 */
public final class TitleProbe {

    /** 明显不是视频标题的（App 名、页面名）直接忽略。 */
    private static final String[] DENY = {
            "bilibili", "哔哩哔哩", "首页", "推荐", "动态", "我的", "搜索", "消息", "设置"
    };

    private TitleProbe() {
    }

    public static void install(XposedInterface x, ClassLoader cl) {
        Class<?> activity = Reflect.load(cl, "android.app.Activity");
        int n = 0;
        if (activity != null) {
            for (Method m : Reflect.declaredNamed(activity, "setTitle")) {
                Hooks.install(x, m, "title-set-" + m.getParameterCount(), chain -> {
                    try {
                        Object a = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                        if (a instanceof CharSequence) consider(a.toString());
                        else if (a instanceof Integer) { /* 资源 id，跳过 */ }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                n++;
            }
            // 最近任务里的名字（更可靠：进入视频页时一定会设）
            for (Method m : Reflect.declaredNamed(activity, "setTaskDescription")) {
                Hooks.install(x, m, "title-task", chain -> {
                    try {
                        Object a = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                        if (a != null) {
                            Object label = Reflect.call(a, "getLabel");
                            if (label instanceof CharSequence) consider(label.toString());
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                n++;
            }
        }
        Recon.note("PROBE", "TitleProbe: 挂了 " + n + " 个标题入口（Activity.setTitle / setTaskDescription）");
    }

    private static void consider(String t) {
        if (t == null) return;
        String s = t.trim();
        if (s.length() < 4 || s.length() > 120) return;
        String low = s.toLowerCase();
        for (String d : DENY) {
            if (low.equals(d.toLowerCase())) return;
        }
        // 纯 ASCII 且像包名/类名的，跳过
        if (s.matches("[a-zA-Z0-9_.]+") && s.contains(".")) return;
        StreamMeta.setTitle(s);
    }
}
