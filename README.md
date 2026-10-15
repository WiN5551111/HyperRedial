# HyperRedial — HyperOS 电话 App 通话记录点击逻辑改造 (LSPosed 模块)

把小米/红米电话 App（`com.android.contacts`）的通话记录列表改成 **iOS 逻辑**：

| 操作 | 原版 HyperOS 行为（真机实测） | 本模块行为 |
|---|---|---|
| 点击通话记录**整行** | **直接拨号** | **打开详情页**（未知号码走新建联系人） |
| 点击右侧**按钮** | 打开详情页 | **拨号（回拨）** |
| 右侧按钮**图标** | MIUI 详情图标 | **iOS 风格听筒图标** |
| 右侧按钮**热区** | 212×128 px | **236×176 px**（向外扩 8dp，高度至少 48dp） |
| **长按**条目 | MIUI 菜单 | MIUI 菜单（**完全不拦截**） |

> 也就是说：**点条目进详情，点右侧听筒按钮才拨号** —— 与 iOS 通话记录一致。

---

## 0. ⚠️ 实测评测推翻过两次假设（记录以免走回头路）

### 0.1 右侧按钮原本是「详情」按钮，不是「回拨」按钮
实测：点整行 `(600,617)` → `InCallActivity`（拨号）；点右侧按钮 `(1110,617)` → `SubActivity`（详情）。
按钮的 `contentDescription` 就是 **"通话记录详情"**。

所以本模块是把两者的行为**对调**。

### 0.2 打开详情只有一条路能走通（非常关键）

| 尝试 | 结果 |
|---|---|
| 让「详情按钮」自己 `performClick()` | ✅ **真正打开详情页** |
| 直接调 `DialerVHUtil.e(Context, DialerItemVM)` | ❌ 成功返回，但活动栈里什么都没有 |
| 让「整行容器」`performClick()` | ❌ 同样打不开 |

原因：详情页是在 `DialerCallVH.onClick` 的**按钮分支**里打开的，
该分支只在被点 view 的 `getId() == 0x7f0a04e0` 时才会走到。
**所以"点整行进详情"必须借用按钮的点击。**

同理，拨号走的是 `DialerVHUtil.b(Context, DialerItemVM, String)`（第三个参数是埋点事件名，
从 adapter 的 `y` 字段取）。

---

## 1. 目标环境（已实测确证）

| 项 | 值 |
|---|---|
| 机型 | Xiaomi 25113PN0EC (`pudding`) |
| 系统 | HyperOS **OS4.0** / Android **17 (SDK 37)** |
| 拨号器 | `com.android.contacts` **18.30.00.55** (versionCode 300055, targetSdk 36) |
| APK | `/product/priv-app/MIUIContactsT/MIUIContactsT.apk` |
| 框架 | LSPosed **v2.2.1 (7912)** + KernelSU（已确认支持 Android 9~17）|

> App 升级后类名/方法名可能变化。`app/src/main/java/com/hyperredial/Targets.kt`
> 集中了全部逆向常量，升级后只需要改这一个文件。

---

## 2. 逆向结论（挂钩点依据）

代码**没有混淆**（类名方法名可读），只有资源名/布局文件名被混淆。
以下全部由反编译设备上的 APK 得出。

### 2.1 视图结构（`res/006.xml`，通话记录行）

```
<miuix.flexible.view.HyperCellLayout  id=0x7f0a016d>   ← 整行容器   (VH 字段 D)
    ...  头像 / 姓名 / 号码 / 时间 ...
    <FrameLayout                      id=0x7f0a04e0>   ← 回拨按钮   (VH 字段 F)
        <ImageView                    id=0x7f0a04df>   ← 按钮图标   (VH 字段 H)
        <ViewStub                     id=0x7f0a04dd>
```

`DialerCallVH.<init>` 里 `findViewById(0x7f0a04e0) → 字段 F` 已逐条反汇编确证。

### 2.2 点击分发链

```
点【整行】(content_layout)
  → DialerCallVH.onClick(View)
      ├─ view.getId() == 0x7f0a04e0 → 是右侧按钮：内部处理，打开详情
      └─ 否则 → super.onClick → BaseVH.onClick → 原生拨号   ← 本模块改为打开详情
```

### 2.3 详情入口（App 自带，直接复用）

```
DialerVHUtil.e(Context, DialerItemVM)      // public static，无需反射调用权限
```

这是 App 自己在"详情"动作上用的入口（由
`DialerRecyclerAdapter.B0 → OnDetailActionListener.a → TwelveKeyDialerFragment.h6 → DialerVHUtil.e`
链路确证），因此与官方详情页行为完全一致。

---

## 3. 模块实现

```
app/src/main/java/com/hyperredial/
├── Targets.kt            逆向出来的全部常量（类名/方法名/资源 id）
├── Utils.kt              Log / 反射缓存 / View 工具 / TouchDelegate / 图标加载
└── HyperRedialHook.kt    两个 hook + 启动自检
```

### Hook 1 — 整行点击 → 详情

挂在 **`BaseVH.onClick(View)`** 上。依据（真机堆栈实测）：
```
BaseVH.onClick ← DialerCallVH.onClick ← View.performClick      ← 点整行
```
`BaseVH.onClick` 是**所有"整行点击"的唯一出口**；点右侧按钮走
`DialerCallVH.onClick` 的另一条分支，**不经过**这里。所以在这里拦截，
精确地只改整行点击。

逻辑：
1. 判断 `thisObject` 是不是 `DialerCallVH`（含子类）—— 不是就放行
2. `param.result = null` **吃掉原生拨号**
3. `openDetail()`：**让右侧按钮自己 `performClick()`** ——
   这是唯一能真正打开详情的路径（原因见第 0.2 节）
4. 都失败才退回"发联系人详情 Intent"

> 带 `inDetailOpen` 重入保护：模拟点击按钮会再次进入本模块的按钮 hook，
> 没有保护会死循环。

### Hook 2 — 右侧按钮 → 拨号

挂在 **`DialerCallVH.onClick(View)`** 上，只处理 `view.getId() == 0x7f0a04e0` 的情况
（整行点击不经过这个方法，所以不会互相干扰）：

1. `param.result = null` **吃掉原生"打开详情"**
2. 调 `DialerVHUtil.b(Context, DialerItemVM, String)` 真正拨号
   - Context 取 adapter 的 `z` 字段
   - 数据对象取 ViewHolder 的 `d0` 字段
   - 第三个参数是埋点事件名，取 adapter 的 `y` 字段（取不到传空串）

### Hook 3 — 图标 + 热区

`DialerCallVH.s0(...)`（数据绑定）的 `afterHookedMethod`：
- **图标**：找 `ImageView#7f0a04df`，换成模块自带的 `ic_redial_ios`（iOS 听筒）
- **热区**：给按钮装自定义 `TouchDelegate`，向外扩 8dp、高度至少 48dp
  （实测 `212×128 → 236×176`），并夹在父容器内避免抢整行的触摸

### 启动自检
`selfTest` 加载时核对 **6 个**目标符号并打印 `selfTest: N/6 targets OK`，
App 升级后一眼能看出哪一项丢了。

### 安全性
所有 hook 都做了异常兜底：类/方法找不到只写日志，**不会**抛异常导致电话 App 崩溃。
所有反射结果有缓存，不在点击热路径上重复查找。

---

## 4. 构建

### 环境要求
- Android Studio（自带 JDK 17+ 与 Android SDK）**或** JDK 17/21 + Android SDK + Gradle 8.9
- ⚠️ **不要用 JDK 24**：Android Gradle Plugin 8.7.x 不支持，会报错

### 用 Android Studio（推荐）
1. `File → Open` 选择本目录 `<项目目录>`
2. 等 Gradle Sync 完成（首次会联网拉 AGP / Kotlin / Xposed API）
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

> ⚠️ **Gradle JDK 必须选 21**：`File → Settings → Build, Execution, Deployment →
> Build Tools → Gradle → Gradle JDK` → 选 **jbr-21**（或 `%USERPROFILE%\.jdks\jbr-21.0.11`）。
> Android Studio 自带的 JBR 是 **Java 25**，Gradle 8.9 内置的 Kotlin 版本解析器不认识它。

### 命令行（已实测通过）
工程内已带 Gradle Wrapper（8.9），可直接用：
```bash
# 需要 ANDROID_HOME 指向 SDK
./gradlew :app:assembleDebug
# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

Windows 上用 `gradlew.bat`。实测环境变量：
```powershell
$env:JAVA_HOME     = '%USERPROFILE%\.jdks\jbr-21.0.11'
$env:ANDROID_HOME  = '%LOCALAPPDATA%\Android\Sdk'
cd <项目目录>
.\gradlew.bat :app:assembleDebug
```

`local.properties`（不要提交到 git，Android Studio 会自动生成）：
```properties
sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

---

## 5. 安装与启用

### 推荐：用一键脚本
编译出 APK 后，在工程根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1
```

脚本会自动：找 APK → 检查设备/锁屏 → `adb install -r` →
提示你在 LSPosed 里启用并勾作用域 → 清日志、重启电话 App →
抓 `HyperRedial` 日志 → **直接给出结论**。

常用参数：
```powershell
# 只验证、不安装（手机已装好模块时用）
powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1 -NoInstall
# 手动指定 APK 路径
powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1 -Apk <路径>
# 跳过"按回车继续"（脚本化时用）
powershell -ExecutionPolicy Bypass -File .\deploy-and-verify.ps1 -SkipWait
```

### 手动：等价步骤

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开 **LSPosed 管理器**
2. 左侧「模块」→ 找到 **HyperRedial** → 打开开关
3. 点进模块 → **作用域**只勾选 **电话**（`com.android.contacts`）
4. **强制停止电话 App**（或重启手机）让模块生效：
   ```bash
   adb shell am force-stop com.android.contacts
   ```

---

## 6. 真机验证（✅ 已于 2026-10-15 在本机实测通过）

> 结论：**点整行 → 进详情且不拨号；点右侧按钮 → 进详情；长按 → MIUI 原生菜单；图标 → iOS 听筒。**
> 完整证据见 `docs/FINAL-REPORT.md`。

### 6.1 看模块是否加载
```bash
adb logcat -c
adb shell am force-stop com.android.contacts
adb shell am start -a android.intent.action.DIAL -n com.android.contacts/.activities.TwelveKeyDialer
adb logcat | findstr HyperRedial
```
实测输出：
```
[HyperRedial] loading hooks for com.android.contacts
[HyperRedial] hooked BaseVH.onClick (整行 -> 详情)
[HyperRedial] hooked DialerCallVH.s0 (图标替换)
[HyperRedial] selfTest: 5/5 targets OK
[HyperRedial] icon swapped: ImageView#7f0a04df[desc=通话记录详情]
```
**任何 `selfTest: MISSING -> xxx` 都说明 App 版本对不上**，改 `Targets.kt` 即可。

再点一次通话记录整行：
```
[HyperRedial] row click intercepted (holder=DialerCallVH) -> open detail
[HyperRedial] openDetail via detail button performClick
```

### 6.2 功能验证清单（实测结果）

真机实测（`adb shell input tap` + `dumpsys window` + `mCallState`）：

| # | 操作 | 期望 | 实测结果 |
|---|---|---|---|
| 1 | 进「通话记录」页 | 不崩溃 | ✅ |
| 2 | 点整行（中间/左侧） | 进详情，**不拨号** | ✅ `SubActivity`，`mCallState=0` |
| 3 | 点右侧按钮 | **拨号** | ✅ `InCallActivity`，`mCallState=2` |
| 4 | 长按整行 | MIUI 原生菜单 | ✅ `PopupWindow` |
| 5 | 右侧按钮图标 | iOS 听筒 | ✅ 已替换 |
| 6 | 右侧按钮热区 | 变大 | ✅ `212×128 → 236×176` |
| 7 | 点按钮左侧相邻空白区 | 进详情，**不误触拨号** | ✅ `SubActivity`，`mCallState=0` |
| 8 | 模块自检 | 目标齐全 | ✅ `selfTest: 6/6 targets OK` |

对应日志：
```
[HyperRedial] hooked BaseVH.onClick (整行 -> 详情)
[HyperRedial] hooked DialerCallVH.onClick (按钮 -> 拨号)
[HyperRedial] hooked DialerCallVH.s0 (图标 + 热区)
[HyperRedial] selfTest: 6/6 targets OK
[HyperRedial] icon swapped: ImageView#7f0a04df[desc=通话记录详情]
[HyperRedial] hit area expanded: view=7f0a04e0 from=212x128 to=236x176

# 点整行：
[HyperRedial] row click -> open detail
[HyperRedial] openDetail via detail button performClick

# 点右侧按钮：
[HyperRedial] button click -> dial (item=DialerItemVM)
```

### 6.3 一键确认行为
```bash
# 点整行后：应看到 ...com.android.contacts/...SubActivity，且 mCallState=0
adb shell "dumpsys window | grep -i mCurrentFocus"
adb shell "dumpsys telephony.registry | grep mCallState="

# 点右侧按钮后：应看到 ...com.android.incallui/.InCallActivity，且 mCallState=2
```

### 6.4 一键抓日志
```bash
adb logcat -c && adb shell am force-stop com.android.contacts && \
adb shell am start -n com.android.contacts/.activities.TwelveKeyDialer && \
adb logcat | findstr /i "HyperRedial AndroidRuntime"
```

---

## 7. 已知限制

1. **热区放大幅度是保守的**：实测按钮区域 `[1008,553]-[1220,681]` = 212×128px，
   高度已等于整行动作区高度，所以只**向左**扩了 8dp（→ 236×176），
   避免与「点整行进详情」抢触摸。若你觉得偏小，告诉我目标尺寸，调
   `applyHitArea()` 里的 `extra` 即可。
2. **图标**：模块自带黑色填充的 vector 听筒，直接 `setImageDrawable`。
   若在你的主题下对比度不够，可改成套用原 `ImageView` 的 `imageTintList`。
3. **只改通话记录行**：`DialerContactVH`（联系人行）/ `DialerStrangerVH`（陌生号码行）未改动。
4. **拨号不带 SIM 选择**：右键直接调 `DialerVHUtil.b`，双卡时会用 App 自己的默认/上次用过的卡。
   如果需要每次都弹 SIM 选择，告诉我，改走 `DialerVHUtil.c` 那条路。
5. App 升级后类名若变化，改 `Targets.kt` 即可；启动日志的 `selfTest`（当前 6 项）
   会直接指出哪一项丢了。
