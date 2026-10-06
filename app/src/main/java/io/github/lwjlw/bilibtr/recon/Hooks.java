package io.github.lwjlw.bilibtr.recon;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * hook 安装的唯一入口。
 *
 * 规矩：
 *   - ExceptionMode.PROTECTIVE —— 我们的 hooker 抛异常时宿主照常跑，绝不让侦察代码搞崩 B站；
 *   - 每个 hook 都 setId，方便以后热重载原子替换；
 *   - 安装失败只记一笔，绝不往外抛（宿主是别人的进程）。
 */
public final class Hooks {

    private Hooks() {
    }

    public static boolean install(XposedInterface x, Executable target, String id, Hooker hooker) {
        if (target == null) {
            Recon.note("HOOK-SKIP", id + " (target not found)");
            return false;
        }
        try {
            x.hook(target)
                    .setId(id)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            Recon.note("HOOK-OK", id + " -> " + target);
            return true;
        } catch (Throwable t) {
            Recon.note("HOOK-FAIL", id + " -> " + target + " : " + t);
            return false;
        }
    }
}
