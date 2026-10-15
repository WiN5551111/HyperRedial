// 仓库配置：优先走阿里云镜像（国内直连、快），官方源兜底。
// Xposed API 只有官方源提供，必须保留。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 阿里云镜像：AGP / Kotlin / AndroidX 都能从这里拿到
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        // 官方源兜底
        google()
        mavenCentral()
        // Xposed API（de.robv.android.xposed:api:82）只有官方源有
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "HyperRedial"
include(":app")
