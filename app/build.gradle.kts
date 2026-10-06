import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---- release 签名 ----
// 口令放在仓库外的 keystore.properties（已 gitignore）；文件不存在时
// release 会打成未签名包 —— 明确报错比默默出一个装不上的包要好。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.lw5.bilibtr"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lw5.bilibtr"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ⚠️ **release 关闭混淆**（重要决定，2026-10-06）。
            //
            // 曾经打开过 AGP 的 `optimization { enable = true; packageScope = setOf("androidx.**","kotlin.**","kotlinx.**") }`，
            // 结果 release 包**一启动就闪退**：
            //     java.lang.IllegalAccessError:
            //       Class kotlin.collections.b extended by class pc is inaccessible
            //       at androidx.lifecycle.ProcessLifecycleInitializer.a
            //       at androidx.startup.InitializationProvider.onCreate
            // 原因是 R8 把 Kotlin 标准库的类混淆到别的包，破坏了**包级（package-private）可见性**。
            //
            // 权衡后决定：**不开混淆**（用户 2026-10-06 明确同意）。
            //   - 收益：混淆只能把包从 ~20 MiB 压到 ~2 MiB；
            //   - 风险：① 上面的包级可见性崩溃；
            //           ② 入口类 `com.lw5.bilibtr.ReconModule` 一旦被改名，
            //              LSPosed 就找不到入口 —— **模块静默失效且不报错**，
            //              极难排查（名字写死在 META-INF/xposed/java_init.list 里）。
            // 对一个工具模块来说，稳定 > 体积。
            //
            // `proguard-rules.pro` 仍然保留：将来若真要开混淆，那套 keep 规则是保命的。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // 调试包也带上版本号后缀，避免和 release 混在一起分不清
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // 【必须】把 libxposed 的三件套原样打进 APK 根目录的 META-INF/xposed/：
            //   java_init.list  —— 入口类清单
            //   module.prop     —— 模块属性（minApiVersion / targetApiVersion / staticScope）
            //   scope.list      —— 作用域（tv.danmaku.bili）
            // 缺了它，模块装上去框架也认不出来。
            //
            // 注意写法：不要写成 excludes += "**"。
            // 官方 libxposed/example 里同时写了 merges 和 excludes 的 "**"，
            // 语义自相矛盾（会把所有 resources 排掉），属于不可照抄的坑。
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    compileOnly("io.github.libxposed:api:102.0.0")
}