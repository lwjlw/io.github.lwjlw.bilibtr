package io.github.lwjlw.bilibtr;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import io.github.lwjlw.bilibtr.proxy.ProxyConfig;
import io.github.lwjlw.bilibtr.recon.AppHolder;
import io.github.lwjlw.bilibtr.recon.Hooks;
import io.github.lwjlw.bilibtr.recon.Recon;
import io.github.lwjlw.bilibtr.recon.Reflect;
import io.github.lwjlw.bilibtr.recon.Sink;
import io.github.lwjlw.bilibtr.recon.probes.AssetRewriteProbe;
import io.github.lwjlw.bilibtr.recon.probes.BodyProbe;
import io.github.lwjlw.bilibtr.recon.probes.DumpProbe;
import io.github.lwjlw.bilibtr.recon.probes.JavaUrlProbe;
import io.github.lwjlw.bilibtr.recon.probes.OkHttpProbe;
import io.github.lwjlw.bilibtr.recon.probes.PlayerProbe;
import io.github.lwjlw.bilibtr.recon.probes.RewriteProbe;
import io.github.lwjlw.bilibtr.recon.probes.BufferTuneProbe;
import io.github.lwjlw.bilibtr.recon.probes.SpeedBallProbe;
import io.github.lwjlw.bilibtr.recon.probes.SpeedOverrideProbe;
import io.github.lwjlw.bilibtr.recon.probes.SpeedProbe;

/**
 * 阶段 A：侦察模块入口。
 *
 * 目标（唯一）：搞清楚 B站 9.8.0 里「谁决定了播放器最终请求哪个媒体 URL」，
 * 并回答三个决定本地代理方案生死的问题：
 *   1) 媒体 URL 能不能在 Java 层拿到、能拿到就该能改；
 *   2) 媒体请求是 Java 层发的还是 native 发的；
 *   3) playurl 返回的 DASH 结构里有哪些 host / 参数。
 *
 * 设计原则：
 *   - **不硬编码 B站 类名**（9.8.0 混淆、版本一升就废），只挂 JDK/OkHttp/IJK 这些第三方符号；
 *   - 一切 URL 落盘前先脱敏（签名参数抹成 key=*），绝不外泄签名地址；
 *   - 所有 hook 走 PROTECTIVE，异常绝不影响宿主播放。
 *
 * 输出：logcat（TAG=BTR-Recon）+ 宿主外部私有目录
 *   /sdcard/Android/data/tv.danmaku.bili/files/btr-recon/recon-<pid>.log
 */
public class ReconModule extends XposedModule {

    private static final String TAG = Recon.TAG;

    /** 每个进程只装一次。 */
    private volatile boolean probesInstalled = false;

    public ReconModule() {
        // 必须保留公开无参构造器：框架通过反射实例化入口类。
        super();
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log("onModuleLoaded  process=" + param.getProcessName()
                + "  isSystemServer=" + param.isSystemServer());
        Sink.write("onModuleLoaded process=" + param.getProcessName() + " v=" + Recon.VERSION);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        log("onPackageLoaded  pkg=" + param.getPackageName()
                + "  isFirstPackage=" + param.isFirstPackage());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        log("onPackageReady  pkg=" + param.getPackageName()
                + "  classLoader=" + param.getClassLoader());
        Sink.write("onPackageReady pkg=" + param.getPackageName()
                + " first=" + param.isFirstPackage() + " cl=" + param.getClassLoader());

        if (!Recon.HOST_PKG.equals(param.getPackageName())) return;
        if (probesInstalled) return;
        probesInstalled = true;

        ClassLoader cl = param.getClassLoader();
        try {
            installAppCapture(cl);
            // ---- 阶段 A 的侦察探针：**默认关闭**（需要在 btr.conf 里 recon=1 才装）----
            // 为什么默认关：它们挂在 OkHttp / URL / 播放器的高频路径上（尤其 BodyProbe
            // 要在响应体上做扫描），日常使用纯属白耗 CPU。只有重新做侦察时才需要打开。
            if (ProxyConfig.reconProbes()) {
                OkHttpProbe.install(this, cl);
                BodyProbe.install(this, cl);
                JavaUrlProbe.install(this, cl);
                PlayerProbe.install(this, cl);
                // DumpProbe 也是纯侦察：它会**反射遍历并打印**这些类的字段/方法，
                // 还会在播放器 item 的方法上挂观测 hook —— 日常使用没有意义，只在 recon=1 时装。
                DumpProbe.install(this, cl);
                Sink.write("recon probes ON (recon=1)");
            }
            // 阶段 A.5：URL 改写到本地代理（开关在 btr.conf，默认关）
            RewriteProbe.install(this, cl);
            // 阶段 A.5 第三步：真正的注入点（mediaAssetToJson / setDataSourceJson）
            AssetRewriteProbe.install(this, cl);
            // 阶段 C1：测速埋点（播放器侧口径，直连/代理都可采）
            SpeedProbe.install(this, cl);
            // 测试工具：强制倍速（btr.conf 里 speed=2.0 才生效）
            SpeedOverrideProbe.install(this, cl);
            // 用户点名的功能：缓冲大小 / 缓冲时长（btr.conf 或界面里配）
            BufferTuneProbe.install(this, cl);
            // 播放页悬浮球：3x / 4x 倍速
            SpeedBallProbe.install(this, cl);
            // 注：TitleProbe（抓视频标题）**已不再安装** —— 标题功能放弃了（B站 反爬拦住），
            // 而它 hook 的 Activity.setTitle/setTaskDescription 是每个 Activity 都会走的路径。
            // 阶段 D：把设置/测速打通（设置由界面 provider 提供，测速数据推回界面）
            try {
                io.github.lwjlw.bilibtr.proxy.SettingsClient.get();   // 预热，避免首次播放时阻塞
                io.github.lwjlw.bilibtr.proxy.ConfigServer.start();   // 界面通道（回环 TCP）
                io.github.lwjlw.bilibtr.proxy.StatsPusher.start();
            } catch (Throwable ignored) {
            }
            Sink.write("probes installed in " + param.getPackageName()
                    + " (host=" + AppHolder.hostVersion() + ")"
                    + " rewriteEnabled=" + ProxyConfig.enabled());
        } catch (Throwable t) {
            log("probe install failed", t);
            Sink.write("PROBE-INSTALL-FAIL " + t);
        }
    }

    /**
     * 拿宿主 Application：hook Instrumentation#callApplicationOnCreate。
     *
     * 为什么必须拿：落盘目录、宿主版本号都要 Context，
     * 而 onPackageReady 阶段只有 ClassLoader、没有 Context。
     * 这个方法随宿主版本变化的概率极低（framework 的稳定公开 API）。
     */
    private void installAppCapture(ClassLoader cl) {
        Class<?> instrumentation = Reflect.load(cl, "android.app.Instrumentation");
        java.lang.reflect.Method m = Reflect.exact(instrumentation,
                "callApplicationOnCreate", Application.class);
        Hooks.install(this, m, "app-oncreate", chain -> {
            try {
                Object a = chain.getArg(0);
                if (a instanceof Application) {
                    AppHolder.set((Application) a);
                    Sink.write("HOST-APP pkg=" + ((Application) a).getPackageName()
                            + " proc=" + ((Application) a).getProcessName()
                            + " host=" + AppHolder.hostVersion()
                            + " dir=" + AppHolder.reconDir());
                }
            } catch (Throwable ignored) {
            }
            return chain.proceed();
        });
    }

    /** 同时写 logcat 和框架日志，便于 adb logcat / 框架日志界面抓取。 */
    private void log(String message) {
        Log.i(TAG, message);
        try {
            log(Log.INFO, TAG, message);
        } catch (Throwable ignored) {
            // 框架日志不可用时不影响 logcat 输出
        }
    }

    private void log(String message, Throwable t) {
        Log.e(TAG, message, t);
        try {
            log(Log.ERROR, TAG, message + " : " + t, t);
        } catch (Throwable ignored) {
        }
    }
}
