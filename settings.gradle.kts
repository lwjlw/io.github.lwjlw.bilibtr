pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 模板原本在这里声明了 foojay-resolver-convention 插件。
// 它的作用是在项目声明了 Java toolchain 而本地缺失时，自动从 foojay.io 下载 JDK。
// 本项目不声明任何 toolchain（编译用 Android Studio 自带的 JBR），
// 所以它没有实际作用，反而多一个潜在的联网变量，已移除。
// 如果将来需要固定 JDK 版本，再把它加回来并配合 java { toolchain { ... } }。

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BiliBTR"
include(":app")
