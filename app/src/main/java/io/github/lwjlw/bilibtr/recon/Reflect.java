package io.github.lwjlw.bilibtr.recon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 反射小工具。
 *
 * 铁律：**不硬编码混淆过的 B站 类名/方法名**（9.8.0 混淆过，版本一升就失效）。
 * 这里只做两件与 B站 无关的事：
 *   - 按名字找 JDK / OkHttp / IJK 这些**第三方**的公开符号（它们名字稳定）；
 *   - 找不到就安静跳过，绝不抛异常打断宿主。
 */
public final class Reflect {

    private Reflect() {
    }

    /** 反射调一个无参方法（拿不到就返回 null，绝不抛）。 */
    public static Object call(Object target, String method) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Method exact(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        try {
            return c.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Constructor<?> ctor(Class<?> c, Class<?>... params) {
        if (c == null) return null;
        try {
            return c.getDeclaredConstructor(params);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找出类（含父类）里所有指定名字的声明方法，按 名字+参数表 去重。
     * 用于 `setDataSource*` 这类一次要覆盖多个重载的场景。
     */
    public static List<Method> declaredNamed(Class<?> c, String... names) {
        List<Method> out = new ArrayList<>();
        if (c == null) return out;
        Set<String> want = new HashSet<>(Arrays.asList(names));
        Set<String> seen = new LinkedHashSet<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Method[] ms;
            try {
                ms = k.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : ms) {
                if (!want.contains(m.getName())) continue;
                StringBuilder sig = new StringBuilder(m.getName()).append('(');
                for (Class<?> p : m.getParameterTypes()) sig.append(p.getName()).append(',');
                sig.append(')');
                if (seen.add(sig.toString())) out.add(m);
            }
        }
        return out;
    }

    /** 方法签名，用于日志里说明"我们挂上了哪个重载"。 */
    public static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /** 安全调用无参方法并转字符串。 */
    public static String callToString(Object target, String method) {
        if (target == null) return "null";
        try {
            Method m = target.getClass().getMethod(method);
            Object r = m.invoke(target);
            return r == null ? "null" : r.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
