# 上手：装 Android Studio 并编译出 APK

目标：产出 `app-debug.apk`，然后我用 adb 帮你装到手机上测。

全程大约 **15~25 分钟**，其中大部分是下载时间。

---

## 第 0 步：确认一件事（很重要）

你机器上现在的 `java` 是 **JDK 24**。

> ⚠️ Android Gradle Plugin 8.7.x **不支持 JDK 24**，直接用会编译失败。

**不用管它**。Android Studio 自带一个 JDK（JBR 21），Gradle 会用 Studio 自带的那个，
不会用你系统里的 JDK 24。所以只要**通过 Android Studio 编译**就没问题。

（如果你以后想用命令行编译，才需要单独装 JDK 21。）

---

## 第 1 步：下载 Android Studio

官方下载页：https://developer.android.com/studio

- 选 **Windows 64-bit** 的 `.exe`（约 1.2 GB）
- 如果官网打不开，用国内镜像：https://developer.android.google.cn/studio

> 如果下载很慢：Android Studio 安装包本身可以离线拷，但它启动后**必须联网**
> 下载 Android SDK 和 Gradle 依赖，这一步绕不过去。

---

## 第 2 步：安装

双击安装包，一路 Next。安装向导里：

| 选项 | 选什么 |
|---|---|
| Install Type | **Standard**（不要选 Custom） |
| UI Theme | 随便 |
| SDK Components | **全部勾选**（Android SDK / SDK Platform / AVD 都留着） |

「Standard」会自动帮你下好 SDK 和 JDK，省掉最容易出错的环节。

---

## 第 3 步：首次启动后的 SDK 设置

打开 Android Studio → 如果弹「Welcome」向导，直接 **Cancel / Close** 掉，
我们不需要新建项目，直接开现有工程。

然后 `File → Settings → Languages & Frameworks → Android SDK`，确认：

**SDK Platforms 页勾选：**
- ☑ **Android 16.0 (API 36)**  ← 这个必须有（工程 compileSdk = 36）
- ☑ Android SDK Platform-Tools

**SDK Tools 页勾选：**
- ☑ Android SDK Build-Tools
- ☑ Android SDK Command-line Tools (latest)
- ☑ Android Emulator（可选，你不用模拟器可以不勾）

点 **Apply** → 等它下完。

> 如果你在 SDK Platforms 里只能看到 API 35 或更低，也没关系——
> 告诉我，我把工程改成 `compileSdk = 35`。这个改动不影响功能。

---

## 第 4 步：打开工程

Android Studio → **Open** → 选择这个目录：

```
<项目目录>
```

（选到 `HyperRedial` 这一层，不要选到 `app` 里面）

首次打开会提示 **Trust Project** → 点 **Trust**。

然后会自动 **Gradle Sync**，右下角有进度条。第一次要下载
AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.9 + Xposed API，**约 5~10 分钟**。

### Sync 成功的样子
底部状态栏出现 `Gradle sync finished`，左侧 `Project` 面板能看到
`app/src/main/java/com/hyperredial/` 下面三个 `.kt` 文件。

---

## 第 5 步：编译 APK

菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**

等 1~3 分钟，右下角弹出 **APK(s) generated successfully**，
点弹窗里的 **locate** 就能打开产物目录。

产物路径：
```
<项目目录>\app\build\outputs\apk\debug\app-debug.apk
```

**到这一步就把这个路径告诉我**，我接手用 adb 装到手机上、
在 LSPosed 里启用、然后抓日志验证功能。

---

## 出错了怎么办

**先说结论：下面这几种报错都是"预期内"的，贴给我我马上改工程，你不需要自己折腾。**

我已经核对过 Google 官方 SDK 仓库（`dl.google.com/android/repository/repository2-3.xml`），
里面**确实有** `android-36`、`android-36.1`、`android-37.0/.1/.2` 这些平台，所以 API 36 一定装得到。

### ⚠️ 已修复：`Plugin [id: 'com.android.application', version: '8.7.3'] was not found`

**这个报错我已经修好了。** 你现在只需在 Android Studio 里点一次：

```
File → Sync Project with Gradle Files
```

再 `Build → Build Bundle(s)/APK(s) → Build APK(s)` 即可。

**原因**：原来用的是
```kotlin
plugins { id("com.android.application") version "8.7.3" apply false }
```
这种写法要求 Gradle 解析「插件标记」（plugin marker）构件
`com.android.application:com.android.application.gradle.plugin:8.7.3`，
在有代理 / 国内网络 / 镜像的环境下这一步很容易失败——报错里只列
`Google / MavenRepo / Gradle Central Plugin Repository` 就是它的典型特征。

**修法**：改成 `buildscript` + `classpath`，直接解析真实构件，只依赖普通 Maven 仓库：
```kotlin
// build.gradle.kts
buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google(); mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    }
}
```
我已用**全新 Gradle 缓存 + 走本地代理** 完整验证过这条路能跑通
（AGP、Kotlin 全套、Xposed API 全部从阿里云下载成功，`BUILD SUCCESSFUL`）。

### 其它常见报错

| 报错 | 原因 | 怎么处理 |
|---|---|---|
| `Unsupported class file major version 69` / `IllegalArgumentException: 25.0.3` | Android Studio 自带 JBR 是 **Java 25**，Gradle 8.9 内置的 Kotlin 版本解析器不认识 | `File→Settings→Build,Execution,Deployment→Build Tools→Gradle→Gradle JDK` 选 **jbr-21**（或 `%USERPROFILE%\.jdks\jbr-21.0.11`） |
| `Could not initialize native services` | Gradle native 库初始化失败（受限环境） | 一般 Studio 里不会遇到；命令行时把 `GRADLE_USER_HOME` 指到可写目录 |
| `Failed to find Build Tools revision 34.0.0` | AGP 8.7.3 默认要 34.0.0，本机只有 36.0.0/37.0.0 | **已修**：工程里写了 `buildToolsVersion = "36.0.0"` |
| `Unable to create debug keystore in C:\Users\...\.android` | 该目录不可写 | **已修**：工程回退到 `<keystore 目录>\debug.keystore` |
| `Could not resolve de.robv.android.xposed:api:82` | `api.xposed.info` 这个源不稳 | **贴给我**，我把 Xposed API 的 stub 直接放进工程（最彻底，不再依赖外部仓库） |
| `Gradle sync failed: ... timeout` | 首次拉依赖网络慢 | 等它重试；或在 `~/.gradle/gradle.properties` 里确认代理 `127.0.0.1:<port>` 可用 |
| `dl.google.com` 超时 | 本机必须走代理 | 已在 `settings.gradle.kts` 里把阿里云镜像放在最前，官方源只做兜底 |

> **重要**：**不要**去设置 `systemProp.http.nonProxyHosts` 绕过代理 ——
> 这台机器直连外网是死的，必须走 `127.0.0.1:<port>`。

**贴报错时说清是哪个阶段**：`Gradle Sync` 阶段 还是 `Build APK` 阶段。

---

## 附：我这边会做什么（你不用操作）

APK 编译出来后，在工程目录跑一条命令即可，剩下全自动：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1
```

这个脚本会自动：找 APK → 检查设备/锁屏 → `adb install -r` →
提示你在 LSPosed 里启用并勾作用域 → 清日志、重启电话 App →
抓 `HyperRedial` 日志 → **直接给结论**。

脚本我已经实测过（用 `-NoInstall -SkipWait` 干跑，不装东西、不动手机），
判定逻辑正确：喂给它原版 MIUI APK 时它如实报"模块未加载"。

**前提：手机保持 USB 连接且解锁。**
