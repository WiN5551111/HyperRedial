package com.hyperredial

/**
 * 所有"从 APK 逆向出来"的常量集中在这里。
 *
 * 全部数值来自对设备上 com.android.contacts 18.30.00.55 (HyperOS OS4.0 / Android 17)
 * 的 MIUIContactsT.apk 反编译结果，详见 docs/FINDINGS.md。
 * App 升级后这些 class/method 名可能变化——每一处使用都有空值保护，不会导致崩溃。
 */
object Targets {

    const val TAG = "HyperRedial"

    /** Xposed 模块自身包名 */
    const val MODULE_PACKAGE = "com.hyperredial"

    /** 目标：小米/红米电话 App */
    const val HOST_PACKAGE = "com.android.contacts"

    // ---------------------------------------------------------------- 类名

    const val CLS_DIALER_CALL_VH =
        "com.android.contacts.dialer.list.VH.DialerCallVH"

    const val CLS_BASE_VH =
        "com.android.contacts.widget.recyclerView.BaseVH"

    const val CLS_DIALER_RECYCLER_ADAPTER =
        "com.android.contacts.dialer.list.DialerRecyclerAdapter"

    /**
     * 内部类。Kotlin 里字符串的 "$" 不能用反斜杠转义（那会得到字面反斜杠，
     * 导致类名变成 "A\$B" 而永远找不到），用 \u0024 最稳妥。
     */
    const val CLS_ON_ITEM_VIEW_CLICKED_LISTENER =
        "com.android.contacts.dialer.list.DialerRecyclerAdapter\u0024OnItemViewClickedListener"

    const val CLS_DIALER_ITEM_VM =
        "com.android.contacts.dialer.list.DialerItemVM"

    /** s0 的第三个参数类型，用于精确匹配绑定方法签名。 */
    const val CLS_DIALER_UI_SETTINGS =
        "com.android.contacts.dialer.list.DialerUISettings"

    const val CLS_DIALER_VH_UTIL =
        "com.android.contacts.dialer.list.DialerVHUtil"

    const val CLS_DIALER_LIST_ITEM_SECONDARY_ACTION =
        "com.android.contacts.calllog.DialerListItemSecondaryAction"

    // ------------------------------------------------------- 资源 id（视图）

    /**
     * 通话记录行右侧"详情"按钮的容器。
     *
     * ⚠️ 真机实测（Xiaomi 17 / HyperOS 4.0）修正了最初的判断：
     *   点【整行】      -> 直接拨号        （这是要改掉的行为）
     *   点【右侧按钮】  -> 打开详情页（SubActivity，desc="通话记录详情"）
     *
     * 也就是说这个按钮**原本就是详情按钮**，不是回拨按钮。方案因此改为：
     *   - 整行点击  -> 改成打开详情
     *   - 右侧按钮  -> 保持原样（继续打开详情）
     *
     * 这个 id 仍然有用：用它把按钮 View 实例**在运行时捕获**下来，
     * 从而不依赖任何资源名（本 APK 的资源名被混淆，arsc 解析不可靠）。
     */
    const val ID_DETAIL_BUTTON = 0x7f0a04e0

    /**
     * 按钮图标。同一个控件在通话记录页是"详情"图标。
     * 保留常量以备需要替换图标时使用。
     */
    const val ID_DETAIL_BUTTON_ICON = 0x7f0a04df

    /**
     * 整行容器 id（DialerCallVH.<init> 里 findViewbyId(0x7f0a016d) -> 字段 D）。
     * 仅作为识别"这是通话记录行"的辅助信号。
     */
    const val ID_ROW_CONTAINER = 0x7f0a016d

    // ---------------------------------------------- 运行时识别（不依赖资源名）

    /**
     * 按钮的 contentDescription。uiautomator dump 实测值。
     * 作为 findViewById 捕获失败时的兜底识别手段。
     */
    const val DESC_DETAIL_BUTTON = "通话记录详情"

    // ------------------------------------------------------------ 方法名

    /** DialerCallVH.onClick(Landroid/view/View;)V —— 整行点击入口 */
    const val M_ON_CLICK = "onClick"

    /** DialerCallVH.s0(DialerItemVM, DialerUISettings, ZZ)V —— 绑定数据 */
    const val M_BIND = "s0"

    /** DialerRecyclerAdapter.K0(I)DialerItemVM; —— 按 position 取数据 */
    const val M_GET_ITEM_BY_POS = "K0"

    /** OnItemViewClickedListener.a(I)V —— 整行点击回调（带 position） */
    const val M_ON_ITEM_CLICKED = "a"

    /** DialerVHUtil.e(Context, DialerItemVM)V —— 打开联系人详情（App 自带入口） */
    const val M_OPEN_DETAIL = "e"

    /**
     * DialerVHUtil.b(Context, DialerItemVM, String)V —— 发起回拨（真正的拨号）。
     * 第三个参数是"事件来源"字符串（埋点用），从 adapter 的 y 字段取。
     */
    const val M_DIAL = "b"

    /** DialerListItemSecondaryAction.getDetailActionView()ImageView; */
    const val M_GET_DETAIL_ACTION_VIEW = "getDetailActionView"
}
