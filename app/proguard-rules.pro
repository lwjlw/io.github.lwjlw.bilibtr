# ============================================================================
#  BiliBTR R8 规则
#
#  ⚠️ 最重要的一条：**入口类名不能改**。
#  LSPosed 是通过 `META-INF/xposed/java_init.list` 里的**字符串**来加载入口类的
#  （本模块写的是 `com.lw5.bilibtr.ReconModule`）。R8 一旦把它混淆成 `a.b.c`，
#  框架就找不到入口，**模块装上去完全没反应**（而且不报错，极难查）。
# ============================================================================

# 入口类：名字与公开无参构造器都必须保留
-keep class com.lw5.bilibtr.ReconModule { *; }
-keepnames class com.lw5.bilibtr.ReconModule
-keepclassmembers class com.lw5.bilibtr.ReconModule {
    public <init>();
}

# 任何 XposedModule 子类都同样受框架按名字加载影响，统一保留
-keep class * extends io.github.libxposed.api.XposedModule { *; }

# libxposed API 由框架在运行时提供（compileOnly），不要警告
-dontwarn io.github.libxposed.**

# 探针里用反射按方法名调用宿主/框架的成员，这些名字是**字符串**，R8 看不到引用
# （注意：这些反射目标都在宿主 APK 里，不是本模块的类，所以无需 keep 本模块；
#   这里保留的是给 R8 的"不要因为找不到引用就报错"提示。）
-dontwarn tv.danmaku.ijk.media.player.**
-dontwarn okhttp3.**
-dontwarn okio.**
