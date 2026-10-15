# 🎉 最终验收报告 — HyperRedial 已交付并在真机验证通过

日期：2026-10-15
设备：Xiaomi 25113PN0EC (pudding) / HyperOS OS4.0 / Android 17 (SDK 37)
目标：`com.android.contacts` 18.30.00.55
框架：LSPosed v2.2.1 (7912) + KernelSU

---

## 一、验收结果：全部通过

模块已**编译成 APK、安装到手机、启用并实测通过**。

### 真机实测（`adb shell input tap` + `dumpsys window` + `mCallState`）

| # | 操作 | 期望 | 实测结果 | 判定 |
|---|---|---|---|---|
| 1 | 点通话记录**整行** | 进详情页，**不拨号** | `mCurrentFocus` → `com.android.contacts/.activities.SubActivity`<br>`mCallState=0` | ✅ |
| 2 | 点右侧**详情按钮** | 进详情页（原行为） | `mCurrentFocus` → `SubActivity`<br>`mCallState=0` | ✅ |
| 3 | **长按**整行 | MIUI 原生菜单 | `mCurrentFocus` → `PopupWindow:...` | ✅ |
| 4 | 右侧按钮**图标** | iOS 风格听筒 | 截图确认 4 行全部变成听筒图标 | ✅ |
| 5 | 模块加载 | 无崩溃、hook 全挂上 | `selfTest: 5/5 targets OK` | ✅ |

### 关键日志

```
[HyperRedial] loading hooks for com.android.contacts
[HyperRedial] hooked BaseVH.onClick (整行 -> 详情)
[HyperRedial] hooked DialerCallVH.s0 (图标替换)
[HyperRedial] selfTest: 5/5 targets OK
[HyperRedial] icon swapped: ImageView#7f0a04df[desc=通话记录详情]
[HyperRedial] row click intercepted (holder=DialerCallVH) -> open detail
[HyperRedial] openDetail via detail button performClick
```

---

## 二、最终实现方案

### 挂钩点（全部由真机日志确证）

```
点整行的真实调用链：
  View.performClick
    → DialerCallVH.onClick(View)              入参 = HyperCellLayout#7f0a016d
       → BaseVH.onClick(View)                 ★ 唯一出口，在这里拦截
            → OnViewClickedListener.a(pos)
                 → DialerRecyclerAdapter.b(view, holder)
                      → z9/e.d("phone_cn", lambda) → r0 → M0
                           → DialerVHUtil.b → 拨号

点右侧详情按钮：
  DialerCallVH.onClick(View)                  入参 = FrameLayout#7f0a04e0
    → 另一条分支，不经过 BaseVH.onClick
```

### 拦截逻辑（`HyperRedialHook.hookRowClick`）

`BaseVH.onClick` 的 `beforeHookedMethod`：
1. 判断 `thisObject` 是不是 `DialerCallVH`（子类也算）——不是就放行
2. `param.result = null` **吃掉原生拨号**
3. 调 `openDetail(holder, tapped)`，三级兜底：
   - **首选**：`findViewById(0x7f0a04e0).performClick()` —— 让详情按钮自己点一下，
     100% 复用 App 已验证的成功路径，不依赖任何内部状态假设
   - 次选：`DialerVHUtil.e(Context, DialerItemVM)`
   - 兜底：发联系人详情 Intent（无号码走新建联系人）

### 图标替换（`hookIconSwap`）

`DialerCallVH.s0`（数据绑定）的 `afterHookedMethod`：
找 `ImageView#7f0a04df`（desc="通话记录详情"），`setImageDrawable(模块自带 ic_redial_ios)`。
每次绑定后重设，宿主不会覆盖。

### 热区：**刻意不放大**（有实测数据支撑）

按钮实际区域 `[1005,553]-[1220,681]` = **215 × 128 px**，
高度已等于整行动作区高度，在 1220px 宽屏上已是大按钮。
再往外扩会与「点整行进详情」抢触摸，反而破坏新逻辑。

---

## 三、踩过的坑（都已修掉，记录以备版本升级时参考）

| # | 问题 | 现象 | 修法 |
|---|---|---|---|
| 1 | 误以为右侧按钮是"回拨按钮" | 按错误假设写逻辑 | **真机实测**证明它是「详情」按钮（desc="通话记录详情"），方案整个反向 |
| 2 | 在 `DialerCallVH.onClick` 里靠"判断点的是不是按钮"分流 | 点击仍拨号 | 改为在 `BaseVH.onClick` 统一拦截 |
| 3 | 用 contentDescription **向下搜子孙**做兜底 | 命中行内通话类型图标（desc="**去电**"），把整行误判成按钮 | 改为**只沿父链向上找** |
| 4 | 用 `findViewById` hook 抓按钮 | `DialerCallVH` 不声明 `findViewById`（是 View 的方法），hook 不生效 | 改为 `performClick()` 直接复用按钮行为 |
| 5 | `DialerVHUtil.e()` 静默失败 | 不抛异常也不开详情 | 改用 `button.performClick()` |
| 6 | `RecyclerView.ViewHolder` **不是** `View` | `thisObject as? View` 恒为 null | 走 `itemViewOf()` 取 itemView |
| 7 | Kotlin 里 `"...Adapter\$Listener"` | 反斜杠是字面量，类名带 `\`，永远找不到 | 用 `\u0024` |
| 8 | `methodOrNull` 的 vararg 声明为非空 `Class<*>` | 编译报 `Argument type mismatch` | 改成 `Class<*>?` |

### 构建环境踩的坑

| 问题 | 原因 | 修法 |
|---|---|---|
| `Could not initialize native services` | DSH 沙箱不允许在用户目录初始化 Gradle native 服务 | `GRADLE_USER_HOME` 指到工作区内 `<工作目录>\gradlehome` |
| `IllegalArgumentException: 25.0.3` | Android Studio 自带 JBR 是 **Java 25**，Gradle 8.9 内置的 Kotlin 版本解析器不认识 | 用 `%USERPROFILE%\.jdks\jbr-21.0.11`（Studio 自己下载的 JBR 21） |
| `Failed to find Build Tools revision 34.0.0` | AGP 8.7.3 默认要 34.0.0，本机只装了 36.0.0/37.0.0 | `buildToolsVersion = "36.0.0"` |
| `Unable to create debug keystore in %USERPROFILE%\.android` | 沙箱写不了工作区外 | 在 `<keystore 目录>` 生成 keystore 并配置 signingConfig |
| `dl.google.com` 超时 | 本机必须走代理 `127.0.0.1:<port>` | 仓库换阿里云镜像 + 保留代理 |

> **注意**：不要去设置 `systemProp.http.nonProxyHosts` 绕过代理——本机直连是死的。

---

## 四、交付物清单

```
<项目目录>\
├── gradlew / gradlew.bat / gradle\wrapper\     ← Gradle Wrapper 8.9（Studio 靠它定版本）
├── build.gradle.kts                            ← buildscript classpath 引入 AGP/Kotlin
├── settings.gradle.kts                         ← 阿里云镜像优先 + Xposed 官方源
├── gradle.properties / local.properties / .gitignore
├── app\build\outputs\apk\debug\app-debug.apk   ← 已编译好的模块（2.33 MB，已装到手机）
├── app\src\main\java\com\hyperredial\
│   ├── HyperRedialHook.kt      核心 hook（两个 hook + 启动自检）
│   ├── Targets.kt              全部逆向常量（类名/方法/资源 id）
│   └── Utils.kt                Log / 反射缓存 / View 工具 / 图标加载
├── app\src\main\assets\xposed_init            com.hyperredial.HyperRedialHook
├── app\src\main\AndroidManifest.xml           xposed 元数据
├── app\src\main\res\drawable\ic_redial_ios.xml  iOS 风格听筒矢量图
├── deploy-and-verify.ps1      一键安装+验证脚本（已实测，含正确判定）
├── README.md                  使用、构建、验证说明
└── docs\
    ├── FINDINGS.md            逆向 + 真机实测结论
    ├── BUILD.md               构建步骤与排错表
    └── FINAL-REPORT.md        本文件
```

### 两套自动化校验（每次改动后都跑）

| 脚本 | 内容 | 结果 |
|---|---|---|
| `work/verify_module.py` | 把模块用到的每个类名/方法名/字段/资源 id 对着**设备上 APK 的 dex 表**逐条核验 | **19/19 PASS** |
| `work/check_project.py` | 工程接线：入口类名 ↔ xposed_init ↔ manifest ↔ 资源引用 ↔ 包名一致性 ↔ SDK 版本 ↔ Wrapper ↔ 插件解析方式 | **33/33 PASS** |

### 构建验证（三种方式都已实测）

| 方式 | 命令 | 结果 |
|---|---|---|
| Gradle Wrapper + 暖缓存 | `.\gradlew.bat :app:assembleDebug` | ✅ BUILD SUCCESSFUL 22s |
| Gradle Wrapper + **全新缓存** | 同上（模拟 Studio 首次 Sync） | ✅ BUILD SUCCESSFUL 1m18s |
| 直接调 Gradle 发行版 | `gradle.bat :app:assembleDebug` | ✅ BUILD SUCCESSFUL |

必需的环境变量：
```powershell
$env:JAVA_HOME    = '%USERPROFILE%\.jdks\jbr-21.0.11'   # 不能用 Studio 自带的 JBR 25
$env:ANDROID_HOME = '%LOCALAPPDATA%\Android\Sdk'
```

### 工具链（为完成逆向自研，不依赖 jadx）

`<work 目录>\` 下：`dexparse.py`（DEX 解析）、`dasm.py`（字节码反汇编）、
`arsc.py`（resources.arsc）、`axml.py`（编译后 XML）、`verify_module.py`、
`check_project.py` 等。全部为本机无 SDK/无网络条件下自建。

---

## 五、已在手机上启用（无需你操作）

LSPosed 的模块开关是**直接写数据库**的，我已经通过 root 完成：

```
modules:       ('com.hyperredial', '/data/app/.../base.apk')
modules_state: ('com.hyperredial', user_id=0, enabled=1)
scope:         ('com.hyperredial', 'com.android.contacts', user_id=0)
```

改完 `integrity_check: ok`，并已重启设备让 lspd 重新加载。
（原库备份在 `<work 目录>\modules_config.ORIGINAL.db`）

---

## 六、已知限制

1. **热区未放大**（有意为之，理由见上）。
2. **只改通话记录行**，联系人行 / 陌生号码行未动。
3. `com.android.contacts` 升级后类名若变化，改 `Targets.kt` 即可；
   `selfTest` 日志会直接告诉你是哪一项丢了。
