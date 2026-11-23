# HyperRedial — HyperOS 电话 App 通话记录点击逻辑改造 (LSPosed 模块)

把小米/红米电话 App（`com.android.contacts`）的通话记录列表改成 **iOS 逻辑**：

| 操作 | 原版 HyperOS 行为（真机实测） | 本模块行为 |
|---|---|---|
| 点击通话记录**整行** | **直接拨号** | **打开详情页**（未知号码走新建联系人） |
| 点击右侧**按钮** | 打开详情页 | **拨号（回拨）** |
| 右侧按钮**图标** | MIUI 详情图标 | **iOS 风格「圆圈 + 听筒」**，颜色跟随 HyperOS 主题 |
| 右侧按钮**热区** | 192×128 px | **只有圆圈本身（39dp / 120px）**，圈外一律按"点整行"处理 |
| 右侧按钮**按压动画** | 整行涟漪/高亮 | **只有圆圈内泛起涟漪**，整行纹丝不动 |
| **长按**条目 | MIUI 菜单 | MIUI 菜单（**完全不拦截**） |
| 右侧按钮**防误触** | 无（手机放桌上很容易误拨） | **挂断 3s 内 / 被遮挡 / 多指 / 蹭一下就抬 —— 一律不拨** |

> 也就是说：**点条目进详情，点右侧听筒按钮才拨号** —— 与 iOS 通话记录一致。

---

## 0. ⚠️ 实测评测推翻过三次假设（记录以免走回头路）

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

### 0.3 整行涟漪靠"清 pressed 状态"是拦不住的，只能不给它 DOWN

1.1.3 时按钮 `isClickable = false`，触摸全部冒泡到整行，于是整行 `HyperCellLayout`
进入 pressed 状态、画出整行涟漪 —— 点回拨按钮时**整行一起亮**。

试过在子 View 里 `setPressed(false)`、也给图标加自己的涟漪，**整行那层照样会亮**：
MIUI 是在派发阶段就把自己设成 pressed 的，子 View 里的清理盖不住。

唯一有效的办法是**让按钮把这 `DOWN` 消费掉**（`OnTouchListener` 返回 `true`），
整行收不到 `DOWN`，自然不会进 pressed。代价是长按/滚动等也要我们自己照顾：
- 长按 → `row.performLongClick()` 交回宿主，菜单照旧
- 滚动 → RecyclerView 从 `intercept` 拿走手势并给按钮发 `CANCEL`，我们复位即可

实测这三条都成立，见 §6.2 的像素对照表。

---

## 1. 目标环境（已实测确证）

| 项 | 值 |
|---|---|
| 机型 | Xiaomi 25113PN0EC (`pudding`) |
| 系统 | HyperOS **OS4.0.4.0.XPCCNXM** / Android **17 (SDK 37)** |
| 拨号器 | `com.android.contacts` **18.30.00.60** (versionCode 300060, targetSdk 36) |
| APK | `/product/priv-app/MIUIContactsT/MIUIContactsT.apk` |
| 框架 | LSPosed **v2.2.1 (7912)** + KernelSU（已确认支持 Android 9~17）|

> 已在 **18.30.00.55** 和 **18.30.00.60** 两个版本上都实测通过。
> 版本差异与适配方法见 [第 8 节「版本适配」](#8-版本适配)。

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
├── Utils.kt              Log / 反射缓存 / View 工具 / 图标加载（日/夜两套）
└── HyperRedialHook.kt    hook（整行/按钮/图标+热区+按压涟漪）+ 启动自检
```

### Hook 1 — 整行点击 → 详情（落点在圆圈里 → 拨号）

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
3. **落点落在听筒圆圈里 → 拨号**（`isTapOnIcon` + `dialFromRow`，见 Hook 3）
4. 否则 `openDetail()`：**让右侧按钮自己 `performClick()`** ——
   这是唯一能真正打开详情的路径（原因见第 0.2 节）
5. 都失败才退回"发联系人详情 Intent"

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

### Hook 3 — 图标 + 图标级热区 + 图标级按压涟漪

`DialerCallVH.s0(...)`（数据绑定）的 `afterHookedMethod`，每次绑定都重新套一遍
（RecyclerView 会复用 ViewHolder，不能只做一次）：

- **图标**：找 `ImageView#7f0a04df`，换成模块自带的 `ic_redial_ios`
  —— 照**联系人详情页那颗绿色电话按钮** 1:1 复刻：淡绿圆底盘 + 实心绿听筒
  （浅色 `#ECF9ED` / `#61CA51`，深色 `#283528` / `#66CF59`，走 `drawable-night`）。
  几何取自真机截图量测：底盘 ⌀19.9，听筒包围盒 8.57 = **盘径的 0.43**
  （详情页实测 0.433），包围盒中心精确落在 (12,12)。
  **坐标全部是烘焙好的绝对值**（不用 `<group>`）：Android 的 VGroup 是
  postScale 再 postTranslate（平移不参与缩放），SVG/CSS 里 translate 却会跟着
  scale 一起缩 —— 同一份数字在两边结果不同，1.1.0 的 +0.5/+0.4 偏移就是这么来的。
- **不着色**：图标自带配色，换图后把 `imageTintList` 清空。
  这台机器上宿主给的 tint 解析出来是**全透明**（`host=#00000000`），
  留着会把整个图标涂没 —— 1.1.1 之前"浅色看不见"的真正原因。
  （1.1.1 曾用"按背景对比度挑黑/白"的兜底方案，1.1.2 改成固定配色后整套逻辑删掉。）
- **热区**：不再是"放大"，而是**收敛到圆圈本身**：
  1. 按钮 `isClickable = false` —— 圈外的触摸冒泡到整行 → 详情
  2. 按钮自己挂 `OnTouchListener`：落点在圈里就 `return true` **消费掉这次手势**
     （圈外 `return false`，照旧冒泡，整行该有的反馈一点不少）
  3. 圈内 `UP` → `performClick()` → 宿主 `onClick` → 我们的 hook → 拨号
  4. 圈内长按 → `row.performLongClick()` 交回宿主，MIUI 原生菜单照旧
  5. 兜底：万一某版布局让按钮收不到 `DOWN`，再用整行落点 vs
     `Views.iconScreenRect()`（图标实际着墨区域的屏幕矩形）判定一次

- **按压动画只画在圆圈里**（1.1.4）：给图标挂一个**圆形 mask 的 `RippleDrawable`
  前景**，涟漪半径被 mask 限死在圆盘内，不会漫到圈外。

  > **关键**：光有涟漪还不够 —— 必须同时让按钮**消费掉 `DOWN`**。
  > 因为 MIUI 的 `HyperCellLayout` 是在派发阶段就把自己设成 pressed 的，
  > 只要整行还收得到 `DOWN`，整行那层涟漪/高亮就会画出来（这正是 1.1.3 的问题）。
  > 按钮 `return true` 之后整行根本收不到 `DOWN`，整行涟漪自然不会出现。
  >
  > 实测（同一像素点采样，主题浅/深各一组）：
  >
  > | 操作 | 圆盘中心 | 整行背景 |
  > |---|---|---|
  > | 静止（浅） | `238,249,238` | `255,255,255` |
  > | **按住图标**（浅） | **`195,203,194`** ↓ | `255,255,255` ← 不动 |
  > | 按住行中部（浅） | `238,249,238` | `235,235,235` ↓ |
  > | 静止（深） | `43,53,41` | `0,0,0` |
  > | **按住图标**（深） | **`90,98,89`** ↑ | `0,0,0` ← 不动 |
  > | 按住行中部（深） | `17,21,16` | `8,8,8` ↓ |
  >
  > 浅色变暗、深色变亮 = 涟漪色取的是 `?android:attr/colorControlHighlight`；
  > 取不到时按夜晚模式回退到 `#40FFFFFF` / `#33000000`。
  > 涟漪色要跟着 `uiMode` 重新解析 —— 列表复用 View，不重装会在切主题后
  > 残留上一套主题的涟漪色（深色下挂黑色涟漪 = 看不见）。

> **为什么不用 `TouchDelegate`**：它只能把热区**放大**，没法缩小；放大后按钮还会
> 跟"点整行进详情"抢触摸。这里反过来把按钮自己的点击关掉，让按钮按落点决定
> 吃不吃这次手势，命中圈内才消费。这样"拨号/详情"只有一条出口，
> 不会出现两种点击同时触发。

### Hook 4 — 防误触（"这一下该不该算数"）

1.1.4 之前只回答了"点在哪"：把拨号热区收窄到圆圈里。但**手机放到桌上时，
桌面、手掌、机身边缘的电容耦合照样能在圆圈里产生一次完全正常的按下** ——
位置是对的，模块看到的就是一次合法点击，于是照拨。热区再小也拦不住这件事。

所以 1.1.5 加了一层上下文判断（`AntiMistouch.kt`），任一命中就**把这次手势吞掉**：

| 判断 | 触发条件 | 拦的是什么 |
|---|---|---|
| **挂断冷却** | 通话结束后 3 秒内 | 挂断 → 顺手把手机放下那一下 |
| **遮挡拦截** | 接近传感器被挡住 | 兜里、包里、屏幕朝下扣在桌上 |
| **幽灵触点** | 多指按下 / 按压短于 50ms | 手掌、桌面耦合、蹭一下 |

**关键：拦下时必须自己把事件消费掉，不能放行。** 放行会让事件冒泡到整行，
而整行点击的判定同样是"落点在圆里就拨号"，等于什么都没拦。
所以拦下时 `OnTouchListener` 整段返回 `true`，手势直接丢弃 ——
不拨号，也不跳详情页（避免幽灵触点把页面乱跳）。

> ⚠️ 挂断用 `AudioManager.mode` 判断：`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`
> 回到 `MODE_NORMAL` 即视为挂断，**不需要任何权限**。
> API 31+ 走 `addOnModeChangedListener` 实时回调；低版本在每次触摸时采样兜底。

> ⚠️ 接近传感器要用 `getDefaultSensor(TYPE_PROXIMITY, wakeUp = false)`。
> 这台机器上 `getDefaultSensor(type)` 给的是 **Wakeup 版**，每次事件都会唤醒 AP。
> 另外监听器必须**用字段持有强引用** —— 交给 SensorManager 的匿名对象可能被 GC，
> 表现是"日志说已启用、`dumpsys sensorservice` 里却看不到连接"（这个坑踩过）。

> ⚠️ 兜底路径也要拦。按钮万一没接住手势（比如某版布局把按钮移出触摸路径），
> 事件会走"整行点击 -> `isTapOnIcon` -> 拨号"，所以 `hookRowClick` 里
> 判定命中圆圈后**先过一遍守卫**再拨。

**可调参数**（都在 `AntiMistouch.Companion`）：

| 常量 | 默认 | 说明 |
|---|---|---|
| `HANGUP_COOLDOWN_MS` | `3000` | 嫌碍事就调小；设 `0` 关掉冷却 |
| `MIN_PRESS_MS` | `50` | 设 `0` 关掉"蹭一下就抬"过滤（手速极快的人可能需要） |
| `BLOCK_WHEN_COVERED` | `true` | 遮挡拦截开关 |
| `BLOCK_WHEN_FLAT` | `false` | 平放桌面拦截。默认关：摊在桌上想回拨是正常需求，开了之后必须先拿起手机 |

对应日志（`adb logcat | grep anti-mistouch`）：

```
[anti-mistouch] 挂断冷却已启用：3000ms（AudioManager 回调）
[anti-mistouch] 遮挡拦截已启用（proximity maxRange=5.0）
[anti-mistouch] 通话结束 -> 开始 3000ms 防误触冷却
[anti-mistouch] 拦住一次回拨：刚挂断 330ms，冷却中
[anti-mistouch] 拦住一次回拨：按压仅 4ms（疑似蹭到）
```

### 启动自检
`selfTest` 加载时核对 **6 个**目标符号并打印 `selfTest: N/6 targets OK`，
App 升级后一眼能看出哪一项丢了。

### 安全性
所有 hook 都做了异常兜底：类/方法找不到只写日志，**不会**抛异常导致电话 App 崩溃。
所有反射结果有缓存，不在点击热路径上重复查找。

> ⚠️ **模块实例是在 zygote 的 `nativeSpecializeAppProcess` 阶段构造的**，
> 那一刻主线程 Looper 还没 `prepare()`，`Looper.getMainLooper()` 返回 `null`。
> 所以**字段初始化里绝对不能 `new Handler(...)`** —— 会 NPE 让整个模块加载失败
> （日志是 `Failed to load class com.hyperredial.HyperRedialHook`，
> 而不是模块自己的日志，很容易被误判成"模块没装"）。
> 需要 Handler 就用 `by lazy`，推迟到第一次触摸回调（那时一定在主线程且 Looper 就绪）。
> 同理，字段初始化里也不要碰任何依赖 Looper / 主线程的东西。

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

Windows 上用 `gradlew.bat`：
```powershell
$env:JAVA_HOME    = "$env:USERPROFILE\.jdks\jbr-21.0.11"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# debug 包
.\gradlew.bat :app:assembleDebug

# release 包（正式签名，需先跑 make-keystore.ps1）
# 注意：PowerShell 里 -P 参数【必须加引号】，否则 1.0.0 里的
# .0.0 会被当成另一个任务名，报 "Task '.0.0' not found"
.\gradlew.bat :app:assembleRelease '-PversionName=1.1.4'
```

> ⚠️ **这个引号坑踩过**：PowerShell 会把 `-PversionName=1.0.0` 按点号拆开，
> 变成 `-PversionName=1` 和 `.0.0` 两个参数。**必须写成 `'-PversionName=1.0.0'`。**
>
> `versionCode` 不用传，由版本名自动推导（`major*10000 + minor*100 + patch`）。

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

## 6. 真机验证（✅ 已于 2026-10-15 在本机实测通过，1.1.4 于 2026-11-04 复测）

> 结论：**点整行 → 进详情且不拨号；点右侧圆圈 → 拨号；圈外空白不误触；
> 长按 → MIUI 原生菜单；图标 → iOS 风格「圆圈 + 听筒」；
> 按压涟漪只出现在圆圈里，整行不动。**
> 完整证据见 `docs/FINAL-REPORT.md`（1.0.x 版本）。1.1.1 已于 2026-11-04 用 adb
> 在 HyperOS 4.0 / Android 17 上复测通过，实测数据：

| 项目 | 实测值（1.1.4，与详情页同款图标） |
|---|---|
| 图标尺寸 | 视图 `121×121px`，圆盘 ⌀`120px`（39dp @3.077），右边距 71px |
| 听筒大小 | `51×51px` = **盘径的 0.429**（详情页 0.433），中心与盘心重合 |
| 配色 · 浅色 | 盘 `#ECF9ED` / 听筒 `#61CA51` |
| 配色 · 深色 | 盘 `#283528` / 听筒 `#66CF59` |
| 涟漪色 | 浅色 `#33000000` / 深色 `#40FFFFFF`（取 `colorControlHighlight`，取不到用这个兜底） |
| 落点判定 | `tap check: point=(1172,616) icon=Rect(1029,557-1149,677) -> miss` |
| 点圆圈 | `InCallActivity`，`mCallState=2` |
| 点按钮内、圆圈外 | `SubActivity`（详情），`mCallState=0` |
| 点整行中部 | `SubActivity`（详情），不拨号 |
| 按住图标 | 圆盘 `238,249,238 → 195,203,194`，**整行背景不动** |
| 按住行中部 | 整行 `255,255,255 → 235,235,235`（原生行为保留） |
| 长按整行 / 长按图标 | MIUI 原生菜单（发送短信/拨打IP电话/复制号码/删除…） |
| 自检 | `selfTest: 6/6 targets OK`（宿主 18.30.00.60） |

### 6.1 看模块是否加载
```bash
adb logcat -c
adb shell am force-stop com.android.contacts
adb shell am start -a android.intent.action.DIAL -n com.android.contacts/.activities.TwelveKeyDialer
adb logcat | findstr HyperRedial
```
实测输出（1.1.4）：
```
[HyperRedial] loading hooks for com.android.contacts
[HyperRedial] hooked DialerRecyclerAdapter.<init> (adapter cache)
[HyperRedial] hooked BaseVH.onClick (整行 -> 详情 / 图标 -> 拨号)
[HyperRedial] hooked DialerCallVH.onClick (按钮 -> 拨号)
[HyperRedial] hooked DialerCallVH.s0 (图标 + 图标级热区)
[HyperRedial] selfTest: 6/6 targets OK
[HyperRedial] dial icon loaded: theme=day size=120x120px
[HyperRedial] icon ripple color=#33000000
[HyperRedial] hit area limited to icon: button=FrameLayout#7f0a04e0 icon=0x0 row=HyperCellLayout#7f0a016d
[HyperRedial] icon ready: view=121x121 intrinsic=120x120 scaleType=FIT_XY
```
（`icon=0x0` 是绑定那一刻还没布局，正常现象；坐标判定在触摸时才取。）
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
| 3 | 点右侧按钮图标圆圈 | **拨号** | ✅ `InCallActivity`，`mCallState=2` |
| 4 | 长按整行 | MIUI 原生菜单 | ✅ `PopupWindow` |
| 5 | 长按图标圆圈 | MIUI 原生菜单 | ✅ `PopupWindow`（1.1.4 起，圈内长按交回宿主） |
| 6 | 右侧按钮图标 | iOS「圆圈 + 听筒」 | ✅ 已替换（1.1.2 起为详情页同款绿盘听筒，固定配色不依赖 tint） |
| 7 | 点按钮内、圆圈外的空白区 | 进详情，**不误触拨号** | ✅ `SubActivity`，`mCallState=0` |
| 8 | **按住图标** | **只有圆圈内起涟漪，整行不变** | ✅ 见下方像素对照表（1.1.4） |
| 9 | **按住行中部** | **整行高亮（原生行为保留）** | ✅ 见下方像素对照表 |
| 10 | 按住图标滑出圈外再松手 | 什么都不发生 | ✅ 不拨号、不进详情 |
| 11 | 从图标列起手滑动列表 | 正常滚动，不误拨 | ✅ |
| 12 | 模块自检 | 目标齐全 | ✅ `selfTest: 6/6 targets OK` |
| 13 | 挂断后 3 秒内点圆圈 | **不拨号** | ✅ `拦住一次回拨：刚挂断 330ms` |
| 14 | 瞬时触点（约 4ms） | 不拨号 | ✅ `拦住一次回拨：按压仅 4ms` |
| 15 | 冷却期间点整行中部 | 仍进详情（冷却只拦拨号） | ✅ `openDetail via detail button performClick` |
| 16 | 挡住接近传感器后点圆圈 | 不拨号 | ⏳ 需人工遮挡确认（日志同上） |

**按压动画像素对照（同一坐标采样，`screencap` 逐帧比对）**

| 场景 | 圆盘中心 `(1060,616)` | 整行背景 `(700,616)` |
|---|---|---|
| 浅色 · 静止 | `238,249,238` | `255,255,255` |
| 浅色 · **按住图标** | **`195,203,194`**（变暗 = 涟漪） | `255,255,255`（**不动**）|
| 浅色 · 按住行中部 | `238,249,238`（不动） | `235,235,235`（整行高亮）|
| 深色 · 静止 | `43,53,41` | `0,0,0` |
| 深色 · **按住图标** | **`90,98,89`**（变亮 = 涟漪） | `0,0,0`（**不动**）|
| 深色 · 按住行中部 | `17,21,16`（不动） | `8,8,8`（整行高亮）|

对应日志：
```
[HyperRedial] hooked BaseVH.onClick (整行 -> 详情 / 图标 -> 拨号)
[HyperRedial] hooked DialerCallVH.onClick (按钮 -> 拨号)
[HyperRedial] hooked DialerCallVH.s0 (图标 + 图标级热区)
[HyperRedial] selfTest: 6/6 targets OK
[HyperRedial] dial icon loaded: theme=day size=120x120px
[HyperRedial] icon ripple color=#33000000
[HyperRedial] icon ready: view=121x121 intrinsic=120x120 scaleType=FIT_XY
[HyperRedial] hit area limited to icon: button=FrameLayout#7f0a04e0 icon=0x0 row=HyperCellLayout#7f0a016d

# 点整行（圆圈外）：
[HyperRedial] tap check: point=(1172,616) icon=Rect(1029, 557 - 1149, 677) -> miss
[HyperRedial] row click -> open detail
[HyperRedial] openDetail via detail button performClick

# 点圆圈：
[HyperRedial] button click -> dial (item=DialerItemVM)
[HyperRedial] dial via DialerVHUtil.e(Context,DialerItemVM,String) [新版]
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

1. **图标热区偏小（有意为之）**：按钮容器实测 `192×128px`，但只有那个
   39dp（120px）的圆盘算拨打，其余区域都当"点整行"处理。
   嫌太小的话，改 `Views.iconScreenRect()` 里返回的 `Rect` 往外 inset 几 dp 即可
   （注意别把 inset 加到超过按钮边界，否则又会跟整行抢触摸）。
2. **图标配色是写死的**：完全照详情页那颗按钮取色（淡绿盘 + 绿听筒），
   不跟随系统主题色/动态取色。原因是宿主给的 tint 是透明的，不能信任。
   想改色直接改 `res/drawable{,-night}/ic_redial_ios.xml` 里的两个 `fillColor`
   —— 两份只有颜色不同，改尺寸记得同步。
3. **只改通话记录行**：`DialerContactVH`（联系人行）/ `DialerStrangerVH`（陌生号码行）未改动。
4. **拨号走 App 默认/上次用过的 SIM**：没有弹 SIM 选择。需要的话可以改走带卡选择的路径。
5. 系统/App 升级后类名方法名若变化，改 `Targets.kt` 即可；启动日志的 `selfTest`
   会直接指出哪一项丢了。详见下一节。

---

## 8. 版本适配

小米会在系统更新里顺带升级电话 App，**方法名/签名可能变化**，模块就会静默失效
（表现为"点了没反应"，日志里没有 `[HyperRedial]`）。本节记录已适配过的版本
和排查方法。

### 8.1 已适配的版本差异

| 功能 | 18.30.00.55 (300055) | 18.30.00.60 (300060) |
|---|---|---|
| **拨号** | `DialerVHUtil.b(Context, DialerItemVM, String)` | `DialerVHUtil.e(Context, DialerItemVM, String)` |
| 打开详情 | `DialerVHUtil.e(Context, DialerItemVM)` | `DialerVHUtil.g(Context, DialerItemVM)` |
| `DialerCallVH.onClick(View)` | 不变 | 不变 |
| `BaseVH.onClick(View)` | 不变 | 不变 |
| `DialerCallVH.s0(DialerItemVM, DialerUISettings, ZZ)` | 不变 | 不变 |
| `DialerCallVH.d0 : DialerItemVM` | 不变 | 不变 |
| `DialerRecyclerAdapter.y` (事件名) / `.z` (Context) | 不变 | 不变 |
| 按钮 id `0x7f0a04e0` | 不变 | 不变 |

**代码已自适应**：`dial()` 会按 `e` → `b` 的顺序尝试，哪个存在就用哪个，
两个版本都能工作。日志会明确打出实际命中哪一个：

```
[HyperRedial] dial via DialerVHUtil.e(Context,DialerItemVM,String) [新版]
```

### 8.2 怎么判定是哪个方法（不用逐个反编译）

拨号和详情两个方法可以用**字符串特征**区分，搜 dex 里的常量即可：

| 特征字符串 | 含义 |
|---|---|
| `key_click_calllog_redial` | 这个方法路径里含**拨号** |
| `key_click_calllog_detail` | 这个方法路径里含**打开详情** |
| `ContactsModuelUtil;->c(` | 真正发起呼叫（`CallIntentBuilder`） |
| `vnd.android.cursor.item/contact` | 打开联系人详情 |

### 8.3 升级后失效的排查顺序

1. **先看模块有没有加载**
   ```bash
   adb logcat -c && adb shell am force-stop com.android.contacts
   adb shell am start -n com.android.contacts/.activities.TwelveKeyDialer
   adb logcat | findstr HyperRedial
   ```
   - **一条日志都没有** → 不是代码问题，见 8.4（LSPosed 路径失效）
   - 有 `selfTest: N/M targets OK` 且 N<M → 看紧跟的 `selfTest: MISSING -> xxx`

2. **对着新 APK 核验挂钩点**
   ```bash
   # 拉出当前 APK
   adb shell pm path com.android.contacts
   adb pull <上面输出的路径> dialer.apk
   ```
   然后解开看 `classes*.dex` 里 `DialerVHUtil` 还有哪些方法：
   ```text
   # 用本仓库用过的思路：列出 DialerVHUtil 的全部方法签名
   # 关注形如 (Context, DialerItemVM, String)V 的那几个，
   # 再按 8.2 的字符串特征判断哪个是拨号、哪个是详情
   ```

3. **改 `Targets.kt` 里的常量**，重新编译安装即可。

### 8.4 更新 APK 后模块不加载（很常见）

Android 每次（重新）安装都会把 APK 放到**新的** `data/app` 目录：

```
/data/app/~~NKd7WGd8DFHbqXnk83JpPQ==/com.hyperredial-.../base.apk
/data/app/~~wQ6pH9JXarazVV4szVwREA==/com.hyperredial-.../base.apk   <- 更新后
```

而 LSPosed 的数据库里记的是**绝对路径**，旧路径失效后模块就加载不了。
现象是"模块明明装着，但 logcat 里一条 `[HyperRedial]` 都没有"。

**两种解法（任选其一）**：

**A. 用仓库里的脚本（推荐，自动重启并验证）**
```powershell
powershell -ExecutionPolicy Bypass -File .\fix-lsposed-path.ps1
```

**B. 手动**
打开 LSPosed 管理器 → 模块 → 把 HyperRedial 的开关**关一下再打开** →
然后 `adb shell am force-stop com.android.contacts`。
（LSPosed 会重新读取 apk 路径。）
