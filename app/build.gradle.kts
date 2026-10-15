// 插件由根项目的 buildscript classpath 提供（见 ../build.gradle.kts），
// 这里不需要（也不能再写）version，避免走插件标记解析。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hyperredial"
    compileSdk = 36

    // 显式指定 Build Tools：AGP 8.7.3 默认要 34.0.0，
    // 但本机 SDK 里装的是 36.0.0 / 37.0.0，不指定会报
    // "Failed to find Build Tools revision 34.0.0"。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hyperredial"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // 调试签名：默认用 ~/.android/debug.keystore（Android Studio 的正常行为）。
    // 如果它不存在或不可写（受限环境），退回到工程内的 keystore，
    // 可用 -PdebugKeystore=<path> 覆盖。
    signingConfigs {
        getByName("debug") {
            val fallback = rootProject.file("../androidhome/debug.keystore")
            val configured = (project.findProperty("debugKeystore") as String?)?.let { file(it) }
            val chosen = configured ?: fallback
            if (chosen.exists()) {
                storeFile = chosen
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
        }
    }
}

dependencies {
    // Xposed / LSPosed API：仅编译期需要，运行期由框架提供
    compileOnly("de.robv.android.xposed:api:82")
}
