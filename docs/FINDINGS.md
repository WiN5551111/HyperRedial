# HyperOS 4 电话 App 回拨逻辑改造 —— 逆向 + 真机实测结论

设备：Xiaomi 25113PN0EC (pudding) / HyperOS OS4.0 / Android 17 (SDK 37)
拨号器：`com.android.contacts` 18.30.00.55 (versionCode 300055, targetSdk 36)
APK：`/product/priv-app/MIUIContactsT/MIUIContactsT.apk`（19,880,968 B，单一 APK，无 split）
框架：LSPosed **v2.2.1 (7912)** + KernelSU

---

## 0. 两条最重要的结论

### 0.1 代码没有混淆
类名方法名完全可读（`DialerCallVH`、`TwelveKeyDialerFragment`、`DialerVHUtil` …）。
**但资源名（R.id 名字）和布局文件名被混淆了**，`resources.arsc` 里所有 id 条目的
key 索引都塌缩到同一个字符串，所以**不能靠资源名做判断**。

### 0.2 ⚠️ 实测推翻了最初的假设
用 `uiautomator dump` + `input tap` + 监听 `mCurrentFocus` / `mCallState` 实测：

| 点击位置 | 原版行为 | 证据 |
|---|---|---|
| **整行**（`content_layout`） | **直接拨号** | 点 (600,617) 后 `mCurrentFocus` → `com.android.incallui/.InCallActivity`，`mCallState=2` |
| **右侧按钮**（`secondary_action_view`，desc=**"通话记录详情"**） | **打开详情页** | 点 (1110,617) 后 `mCurrentFocus` → `com.android.contacts/.activities.SubActivity`，未发起通话 |

也就是说：**右侧那个按钮原本就是「详情」按钮，不是「回拨」按钮。**
详情页内容是：头像 / 号码 / 通话按钮 / 短信按钮 / 该号码的通话记录列表 / 加入黑名单。

**因此正确的改造方向是：把「整行点击」从拨号改成进详情，右侧详情按钮保持原样。**

---

## 1. 通话记录行的运行时结构（uiautomator dump 实测）

屏幕 1220×2656。一行高 **214px**，右侧动作区高 128px：

```
RecyclerView #recyclerlist
  ViewGroup #content_layout                    [0,510][1220,724]  [clickable]  ← 整行
    FrameLayout #avatar                        [87,555][211,679]
      QuickContactBadge                                          [clickable]
    LinearLayout #text_content                 [261,553][538,623]
      LinearLayout
        TextView #name "中国联通"
        ImageView #type_icon desc="去电"
    ViewGroup                                  [261,623][375,681]
      TextView #relative_time "17:02"
    FrameLayout #secondary_action_view         [1005,553][1220,681]  [clickable]  ← 右侧按钮
      ImageView #secondary_action_iv  desc="通话记录详情"            [clickable]
```

> 注：dump 里显示的 `resource-id` 是运行时的名字，
> 但 arsc 解析不可靠，所以模块**不使用资源名做判断**。

---

## 2. 类结构

| 类 | 作用 |
|---|---|
| `com.android.contacts.activities.TwelveKeyDialer` | 拨号器主 Activity |
| `com.android.contacts.list.TwelveKeyDialerFragment` | 主 Fragment（含通话记录页） |
| `...dialer.list.DialerRecyclerAdapter` | 通话记录 Adapter（多类型 VH） |
| `...dialer.list.VH.DialerCallVH` | **通话记录行** ViewHolder（核心） |
| `...dialer.list.VH.DialerContactVH` | 联系人行 ViewHolder |
| `...dialer.list.VH.DialerStrangerVH` | 陌生号码行 ViewHolder |
| `...widget.recyclerView.BaseVH` | VH 基类，转发 click/longClick |

### `DialerCallVH` 字段映射（`<init>` 里 findViewById 确证）

| 字段 | 类型 | 资源 id | 用途 |
|---|---|---|---|
| `C` | `Context` | — | 宿主 Context |
| `D` | `View` | `0x7f0a016d` | 整行容器（还传给 `BaseVH.Q(view)`） |
| `F` | `View` | **`0x7f0a04e0`** | **右侧详情按钮** |
| `G` | `DialerListItemSecondaryAction` | ViewStub 惰性 inflate | 多选模式的勾选/详情控件 |
| `H` | `ImageView` | `0x7f0a04df` | 按钮图标 |
| `d0` | `DialerItemVM` | — | 当前行数据对象 |

---

## 3. 点击分发链（逐条反汇编确证）

### 3.1 `DialerCallVH.<init>`
```
0x0070: findViewById(0x7f0a016d) -> 字段 D
0x007c: findViewById(0x7f0a04e0) -> 字段 F
0x0085: findViewById(0x7f0a04df) -> 字段 H
0x00d4: F.setOnClickListener(this)      ← 第 1 次（字节码偏移 212）
0x00d7: F.setOnLongClickListener(this)
0x00dc: D.setOnClickListener(this)      ← 第 2 次（字节码偏移 220）
```
这两次调用的顺序是**模块捕获按钮实例的依据**（先设的是按钮）。

### 3.2 `DialerCallVH.onClick(View)`
```
if (f0) { ... 语音留言相关 ... }
if (d0.M() && C != null) { M0(C); return }        // M0 = 打开来电拦截设置
if (view.getId() != 0x7f0a04e0) {                 // ← 不是按钮
    super.onClick(view)                            //   → BaseVH.onClick → 拨号
    return
}
// 是按钮：内部处理（打开详情）
... adapter.B0(d0) ...
```

### 3.3 详情入口（App 自带，**public static**，无需反射权限）
```
DialerVHUtil.e(Context, DialerItemVM)
```
调用链：`DialerRecyclerAdapter.B0 → OnDetailActionListener.a → w6/x6
→ TwelveKeyDialerFragment.h6 → DialerVHUtil.e`

### 3.4 `DialerListItemSecondaryAction`（`com.android.contacts.calllog`）
- `getDetailActionView() : ImageView` — 多选模式下的详情图标（`setClickable(false)`）
- `getCheckBox() : CheckBox` — 多选模式的勾选框
- `a()` / `b()` / `c()` — 把上面两个控件 addView 进自己

---

## 4. 模块挂钩点

| Hook | 位置 | 做什么 |
|---|---|---|
| 0 | `DialerRecyclerAdapter.<init>` | 缓存 adapter 实例（`thisObject` 拿不到 adapter） |
| 1 | `View.setOnClickListener`（过滤 `thisObject is DialerCallVH`） | 捕获右侧按钮的 View 实例，不依赖资源名 |
| 2 | `DialerCallVH.onClick(View)` | 点整行 → 吃掉拨号、改开详情；点按钮 → 放行 |

识别按钮的两种手段（互为兜底）：
1. 与 Hook 1 捕获到的实例比对（沿父链向上找）
2. `contentDescription == "通话记录详情"`

---

## 5. 工具链（本仓库自带，不依赖 jadx）

因为本机 shell 出网被沙箱拦死、且没有 Android SDK，为了完成逆向，
我在 `<work 目录>` 下自己实现了：

| 文件 | 作用 |
|---|---|
| `dexparse.py` | DEX 解析：字符串池 / 类型 / proto / 方法 / 字段 / class_data |
| `dasm.py` | **DEX 字节码反汇编器**（按官方格式表，含全部 opcode 宽度） |
| `arsc.py` | `resources.arsc` 解析（含小米魔改的 288 字节 package header 探测） |
| `axml.py` | 编译后 XML（AXML）解析，能还原布局树 |
| `export_classes.py` | 导出全部 13363 个类的类名 → 方法签名表 |
| `verify_module.py` | **把模块用到的每个符号对着 APK 逐条校验**（17/17 通过）|

踩过的坑（都已在代码里修掉）：
- `proto_id_item` 是 12 字节；`type_list` 元素 2 字节
- **指令宽度单位是 16-bit code unit，不是字节**（最初按字节算，全表错位）
- `if-eqz` / `if-nez` 等 21t 格式是 2 code unit
- `RecyclerView.ViewHolder` **不是** `View`（`thisObject as? View` 恒为 null）
- Kotlin 字符串里 `"\$"` 会得到**字面反斜杠**，内部类名必须写 `\u0024`
- arsc 的资源名解析不可靠 → 模块改为运行时捕获 View 实例，不依赖资源名

---

## 6. 还缺什么

**编译**。本机没有 Android SDK / Gradle，且 shell 出网被沙箱完全拦死
（`curl`、`Invoke-WebRequest` 一律失败），所以拿不到 AGP / Gradle / Xposed API。
需要你装 Android Studio（见 `docs/BUILD.md`），之后我接手用 adb 安装 + 抓日志验证。
