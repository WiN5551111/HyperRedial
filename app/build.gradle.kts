// 插件由根项目的 buildscript classpath 提供（见 ../build.gradle.kts），
// 这里不需要（也不能再写）version，避免走插件标记解析。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 版本号：优先取 -PversionName= / -PversionCode=，否则用下面的默认值 ──
val appVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0"
val appVersionCode: Int = ((project.findProperty("versionCode") as String?) ?: "1").toInt()

// ── 签名信息查找 ──
// 刻意不使用 java.util.Properties：在 Kotlin DSL 里 `java` 会被解析成 Gradle 的
// java 扩展而不是包名，容易踩坑。这里直接读文本手动解析，零歧义。
//
// 查找顺序：
//   1. -PkeyStore= / -PkeyStorePassword= / -PkeyAlias= / -PkeyPassword=
//   2. keystore.properties（已被 .gitignore 忽略，仅本机存在）
//   3. 环境变量 KEYSTORE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD（供 CI 用）
fun readKeystoreProps(): Map<String, String> {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return emptyMap()
    val out = mutableMapOf<String, String>()
    f.readLines().forEach { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return@forEach
        val i = t.indexOf('=')
        if (i > 0) out[t.substring(0, i).trim()] = t.substring(i + 1).trim()
    }
    return out
}

val ksProps = readKeystoreProps()

fun cfg(propName: String, propsKey: String, envName: String): String? =
    (project.findProperty(propName) as String?)
        ?: ksProps[propsKey]
        ?: System.getenv(envName)

android {
    namespace = "com.hyperredial"
    compileSdk = 36

    // 显式指定 Build Tools：AGP 8.7.3 默认要 34.0.0，
    // 本机 SDK 里装的是 36.0.0 / 37.0.0，不指定会报
    // "Failed to find Build Tools revision 34.0.0"。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hyperredial"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // ── 签名 ──
    // release 签名信息从 -P 参数 / keystore.properties / 环境变量 中查找，
    // 都不写死在仓库里。找不到时回退到 debug 签名，保证 assembleRelease 本地也能跑通。
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

        create("release") {
            val ksPath = cfg("keyStore", "storeFile", "KEYSTORE")
            // keystore.properties 里的路径是相对【工程根目录】写的，
            // 不能直接用 file()（那是相对 app/ 模块目录）。
            val ksFile = ksPath?.let { rootProject.file(it) }
            if (ksFile != null && ksFile.exists()) {
                storeFile = ksFile
                storePassword = cfg("keyStorePassword", "storePassword", "KEYSTORE_PASSWORD")
                keyAlias = cfg("keyAlias", "keyAlias", "KEY_ALIAS")
                keyPassword = cfg("keyPassword", "keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有正式签名就用正式的，否则退回 debug 签名（并在日志里提示）
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "[HyperRedial] 未找到 release 签名配置，release 包将使用 debug 签名。" +
                            " 运行 ./make-keystore.ps1 生成正式密钥后再打包即可。"
                )
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // LSPosed 模块不需要 lint：lintVital 会拖慢 CI 构建，而且在受限环境下
    // 常因无法写 lint 输出目录而直接让 assembleRelease 失败。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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

// 把 APK 复制成一个带版本号、方便上传到 GitHub Release 的文件名
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "release") return@onVariants
        // 注意：不要用 variant.outputs...versionName —— 那是个 Provider，
        // 在 doLast 里直接插值会打印成 "property(java.lang.String, fixed(...))"。
        // 默认版本号由上面的 -PversionName / 默认值决定，直接用那个变量。
        val vName = appVersionName
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}" }
            .configureEach {
                doLast {
                    val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
                    val src = dir.listFiles()?.firstOrNull {
                        it.name.endsWith(".apk") && !it.name.startsWith("HyperRedial-v")
                    }
                    if (src != null) {
                        val dst = File(dir, "HyperRedial-v$vName.apk")
                        src.copyTo(dst, overwrite = true)
                        logger.lifecycle("[HyperRedial] release APK -> ${dst.absolutePath}")
                    }
                }
            }
    }
}

dependencies {
    // Xposed / LSPosed API：仅编译期需要，运行期由框架提供
    compileOnly("de.robv.android.xposed:api:82")
}
