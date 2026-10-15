package com.hyperredial

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * HyperRedial —— 把 HyperOS 电话 App 的通话记录改成 iOS 逻辑。
 *
 * ══════════════ 真机实测确认的原版行为 ══════════════
 * 设备：Xiaomi 17 / HyperOS 4.0 / com.android.contacts 18.30.00.55 / Android 17
 *
 *   点【整行】      → 直接拨号        ← 改掉：变成进详情
 *   点【右侧按钮】  → 打开详情页      ← 改掉：变成拨号
 *   长按            → MIUI 原生菜单    ← 完全不拦截
 *
 * ══════════════ 目标行为（iOS 逻辑）══════════════
 *
 *   点【整行】      → 打开详情页（未知号码走新建联系人）
 *   点【右侧按钮】  → **拨号**（回拨）
 *   长按            → MIUI 原生菜单
 *   右侧按钮图标    → iOS 风格听筒
 *   右侧按钮热区    → 向外放大，更好点
 *
 * ══════════════ 实测确证的调用链 ══════════════
 *
 *   点整行：View.performClick
 *     → DialerCallVH.onClick(View)          入参 = HyperCellLayout#7f0a016d
 *        → BaseVH.onClick(View)             ★ 整行点击的唯一出口
 *             → OnViewClickedListener.a(pos) → DialerRecyclerAdapter.b
 *                  → z9/e.d("phone_cn", lambda) → r0 → M0 → DialerVHUtil.b → 拨号
 *
 *   点按钮：DialerCallVH.onClick(View)       入参 = FrameLayout#7f0a04e0
 *        → 另一条分支（原本打开详情），**不经过** BaseVH.onClick
 *
 * 所以改造非常干净：
 *   - 整行：在 BaseVH.onClick 里吃掉原生拨号，改成开详情
 *   - 按钮：在 DialerCallVH.onClick 里把原本的"开详情"换成调 DialerVHUtil.b 拨号
 *
 * 所有 hook 都有异常兜底：类/方法找不到只写日志，不会让电话 App 崩溃。
 */
class HyperRedialHook : IXposedHookLoadPackage {

    /** 详情按钮的 View 实例（热区放大与图标替换用）。 */
    @Volatile private var detailButton: View? = null

    /** 宿主 Context。 */
    @Volatile private var hostContext: Context? = null

    /** 通话记录 adapter 实例：拨号需要它的 Context 与事件来源字符串。 */
    @Volatile private var callLogAdapter: Any? = null
    @Volatile private var callLogAdapterCls: Class<*>? = null

    @Volatile private var loggedFirstRowClick = false
    @Volatile private var loggedFirstDial = false
    @Volatile private var loggedFirstIcon = false
    @Volatile private var loggedFirstHitArea = false

    /** 递归保护：模拟点击详情按钮打开详情时，避免再次进入按钮分支。 */
    @Volatile private var inDetailOpen = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Targets.HOST_PACKAGE) return
        if (lpparam.processName != Targets.HOST_PACKAGE) {
            Log.i("skip secondary process: ${lpparam.processName}")
            return
        }

        Log.i("loading hooks for ${lpparam.packageName}")
        val loader = lpparam.classLoader

        hookAdapterCache(loader)
        hookRowClick(loader)      // 整行 -> 详情
        hookButtonDial(loader)    // 右侧按钮 -> 拨号
        hookButtonDecor(loader)   // 图标 + 热区
        selfTest(loader)
    }

    // ==================================================================
    // 0) 缓存 adapter（拨号要用它的 Context 和事件来源字符串）
    // ==================================================================
    private fun hookAdapterCache(loader: ClassLoader) {
        val cls = Reflect.classOrNull(loader, Targets.CLS_DIALER_RECYCLER_ADAPTER) ?: run {
            Log.e("DialerRecyclerAdapter not found; 目标 App 版本可能已变")
            return
        }
        callLogAdapterCls = cls
        runCatching {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    callLogAdapter = param.thisObject
                }
            })
            Log.i("hooked DialerRecyclerAdapter.<init> (adapter cache)")
        }.onFailure { Log.e("hookAllConstructors(adapter) failed", it) }
    }

    // ==================================================================
    // 1) 整行点击 -> 打开详情
    // ==================================================================
    private fun hookRowClick(loader: ClassLoader) {
        val baseCls = Reflect.classOrNull(loader, Targets.CLS_BASE_VH) ?: run {
            Log.e("BaseVH not found; 目标 App 版本可能已变")
            return
        }
        val method = Reflect.methodOrNull(baseCls, Targets.M_ON_CLICK, View::class.java) ?: run {
            Log.e("BaseVH.onClick(View) not found")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val holder = param.thisObject
                    // 只处理通话记录行（联系人行/搜索行有自己的 VH，行为不改）
                    if (!isCallRowHolder(holder)) return

                    val tapped = param.args.getOrNull(0) as? View
                    hostContext = tapped?.context ?: hostContext

                    // 吃掉原生"整行拨号"
                    param.result = null

                    if (!loggedFirstRowClick) {
                        loggedFirstRowClick = true
                        Log.i("row click -> open detail")
                    }
                    openDetail(holder, tapped)
                } catch (t: Throwable) {
                    Log.e("hookRowClick body failed", t)
                }
            }
        })
        Log.i("hooked BaseVH.onClick (整行 -> 详情)")
    }

    /**
     * 打开详情。
     *
     * 实测结论：直接调 `DialerVHUtil.e(Context, DialerItemVM)` 会"成功返回但什么都不做"
     * （活动栈里不出现详情 Activity），说明它内部有状态分支。
     * 而"模拟点击整行容器"能让 App 自己的 `DialerCallVH.onClick` 走完整分支、
     * 真正打开详情页（实测 mCurrentFocus -> SubActivity）。
     * 所以这里复用整行自己的 onClick。
     */
    /**
     * 打开详情。
     *
     * ⚠️ 实测经验（重要，别走回头路）：
     *   ✅ 让"详情按钮"自己 performClick()  —— 真正打开详情页（mCurrentFocus -> SubActivity）
     *   ❌ 直接调 DialerVHUtil.e(...)        —— 成功返回但活动栈里什么也没有
     *   ❌ 让"整行容器"performClick()         —— 同样打不开
     *
     * 原因：详情页是在 DialerCallVH.onClick 的**按钮分支**里打开的，
     * 该分支只有在被点的 view.getId() == 0x7f0a04e0 时才会走到。
     * 所以整行点击必须"借用按钮的点击"才能进详情。
     */
    private fun openDetail(holder: Any?, tapped: View?) {
        if (inDetailOpen) return
        inDetailOpen = true
        try {
            val itemView = itemViewOf(holder)
            val button = (itemView?.let { Views.findById(it, Targets.ID_DETAIL_BUTTON) })
                ?: detailButton
            if (button != null) {
                detailButton = button
                val ok = runCatching { button.performClick() }
                    .onFailure { Log.e("detail button performClick failed", it) }
                    .getOrDefault(false)
                if (ok) {
                    Log.i("openDetail via detail button performClick")
                    return
                }
            }
            Log.e("openDetail fell back to Intent")
            tapped?.let { fallbackDetailIntent(it, Reflect.readField(holder, "d0")) }
        } finally {
            inDetailOpen = false
        }
    }

    // ==================================================================
    // 2) 右侧按钮 -> 拨号（回拨）
    //
    //    按钮点击走 DialerCallVH.onClick 里"非 super"的那条分支（原本打开详情）。
    //    实测该分支入参 view.getId() == 0x7f0a04e0。
    //    我们在 beforeHookedMethod 里把它吃掉，改成调 DialerVHUtil.b 真正拨号。
    // ==================================================================
    private fun hookButtonDial(loader: ClassLoader) {
        val cls = Reflect.classOrNull(loader, Targets.CLS_DIALER_CALL_VH) ?: run {
            Log.e("DialerCallVH not found")
            return
        }
        val method = Reflect.methodOrNull(cls, Targets.M_ON_CLICK, View::class.java) ?: run {
            Log.e("DialerCallVH.onClick(View) not found")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val tapped = param.args.getOrNull(0) as? View ?: return
                    // 只有点在右侧按钮上才接管；整行点击走 BaseVH，不经过这里
                    val isButton = tapped.id == Targets.ID_DETAIL_BUTTON
                    if (!isButton) return

                    // 递归保护：openDetail 会模拟点击详情按钮，
                    // 那会再次进入本 hook；用这个标志避免死循环。
                    if (inDetailOpen) return

                    hostContext = tapped.context ?: hostContext
                    val holder = param.thisObject
                    val item = Reflect.readField(holder, "d0")
                    if (item == null) {
                        Log.e("button dial: holder.d0 is null; 放行原生行为")
                        return
                    }

                    // 吃掉原生"打开详情"，改成拨号
                    param.result = null
                    if (!loggedFirstDial) {
                        loggedFirstDial = true
                        Log.i("button click -> dial (item=${item.javaClass.simpleName})")
                    }
                    if (!dial(holder, tapped, item)) {
                        Log.e("dial failed (未发起通话)")
                    }
                } catch (t: Throwable) {
                    Log.e("hookButtonDial body failed", t)
                }
            }
        })
        Log.i("hooked DialerCallVH.onClick (按钮 -> 拨号)")
    }

    /**
     * 真正发起回拨：DialerVHUtil.b(Context, DialerItemVM, String)。
     * 第三个参数是埋点用的"事件来源"，从 adapter.y 取；取不到就传空串。
     *
     * 注意：如果第一次调用没起效（App 可能把它当成"进入选中态"而不是拨号），
     * 就置 dialMode 并模拟一次整行点击（即 App 的第二击语义 = 打开详情）。
     */
    private fun dial(holder: Any?, anchor: View, item: Any): Boolean {
        val adapter = callLogAdapter
        val ctx: Context? = (adapter?.let { Reflect.readField(it, "z") } as? Context)
            ?: anchor.context ?: hostContext
        if (ctx == null) {
            Log.e("dial: no Context")
            return false
        }
        val loader = item.javaClass.classLoader ?: return false
        val utilCls = Reflect.classOrNull(loader, Targets.CLS_DIALER_VH_UTIL) ?: return false
        val m = Reflect.methodOrNull(
            utilCls, Targets.M_DIAL, Context::class.java, item.javaClass, String::class.java
        ) ?: run {
            Log.e("DialerVHUtil.b(Context, DialerItemVM, String) not found")
            return false
        }
        val event = (adapter?.let { Reflect.readField(it, "y") } as? String) ?: ""
        return runCatching { m.invoke(null, ctx, item, event) }
            .onFailure { Log.e("DialerVHUtil.b threw", it) }
            .isSuccess
    }

    /**
     * 最终兜底：直接发联系人详情 Intent。
     * 有号码 -> 联系人详情；无号码 -> 新建联系人。
     */
    private fun fallbackDetailIntent(anchor: View, item: Any? = null) {
        runCatching {
            val ctx = anchor.context ?: return
            val number = readString(item, "number") ?: readString(item, "y")
                ?: readString(item, "v")
            val intent = if (!number.isNullOrBlank()) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.withAppendedPath(
                        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                        Uri.encode(number)
                    )
                )
            } else {
                Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Log.i("fallback detail intent: $intent")
        }.onFailure { Log.e("fallbackDetailIntent failed", it) }
    }

    private fun readString(item: Any?, methodName: String): String? {
        if (item == null) return null
        val m = runCatching {
            XposedHelpers.findMethodExactIfExists(item.javaClass, methodName)
        }.getOrNull() ?: return null
        return runCatching { m.invoke(item) as? String }.getOrNull()
    }

    /** 判断这个 ViewHolder 是不是通话记录行（DialerCallVH 及其子类）。 */
    private fun isCallRowHolder(holder: Any?): Boolean {
        if (holder == null) return false
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && depth < 8) {
            if (c.name == Targets.CLS_DIALER_CALL_VH) return true
            c = c.superclass
            depth++
        }
        return false
    }

    // ==================================================================
    // 3) 右侧按钮：换 iOS 听筒图标 + 放大热区
    //
    //    既然这个按钮现在是"拨号"，放大热区就是有意义的需求了：
    //    实测按钮区域 [1005,553]-[1220,681] = 215x128px（已在整行高度内），
    //    这里仅向左扩 8dp、并保证高度不小于 48dp，避免与"点整行进详情"抢触摸。
    // ==================================================================
    private fun hookButtonDecor(loader: ClassLoader) {
        val cls = Reflect.classOrNull(loader, Targets.CLS_DIALER_CALL_VH) ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, Targets.M_BIND, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val itemView = itemViewOf(param.thisObject) ?: return
                        val button = Views.findById(itemView, Targets.ID_DETAIL_BUTTON) ?: return
                        detailButton = button
                        applyDialIcon(button)
                        applyHitArea(button)
                    } catch (t: Throwable) {
                        Log.e("hookButtonDecor body failed", t)
                    }
                }
            })
            Log.i("hooked DialerCallVH.s0 (图标 + 热区)")
        }.onFailure { Log.e("hookAllMethods(s0) failed", it) }
    }

    private fun applyDialIcon(button: View) {
        val icon = findIcon(button) ?: return
        val d = IconSwap.moduleDialIcon(button.context) ?: return
        // 关键：必须上色。模块图标是纯黑 vector，深色模式下会完全看不见。
        // IconSwap.applyTint 优先沿用按钮原有的 tint，拿不到就按明暗模式自动选黑白。
        IconSwap.applyTint(icon)
        if (icon.drawable === d) return
        runCatching {
            icon.setImageDrawable(d)
            if (!loggedFirstIcon) {
                loggedFirstIcon = true
                Log.i("icon swapped: ${describe(icon)}")
            }
        }.onFailure { Log.e("icon swap failed", it) }
    }

    private fun applyHitArea(button: View) {
        val density = button.context.resources.displayMetrics.density
        val extra = (8 * density).toInt()      // 向外扩 8dp
        val minH = (48 * density).toInt()      // 高度至少 48dp
        Views.enlargeHitArea(button, extra, 0, minH) {
            if (!loggedFirstHitArea) {
                loggedFirstHitArea = true
                Log.i("hit area expanded: $it")
            }
        }
    }

    /** 按钮里的图标：优先用带"通话记录详情"描述的 ImageView，否则取第一个。 */
    private fun findIcon(button: View): ImageView? {
        if (button is ImageView) return button
        if (button !is android.view.ViewGroup) return null
        var first: ImageView? = null
        for (i in 0 until button.childCount) {
            val c = button.getChildAt(i)
            if (c is ImageView) {
                if (c.contentDescription?.toString() == Targets.DESC_DETAIL_BUTTON) return c
                if (first == null) first = c
            }
        }
        return first
    }

    /** 从 RecyclerView.ViewHolder 取 itemView（其字段名混淆为 'a'）。 */
    private fun itemViewOf(holder: Any?): View? {
        if (holder == null) return null
        if (holder is View) return holder
        (Reflect.readField(holder, "a") as? View)?.let { return it }
        var c: Class<*>? = holder.javaClass
        var depth = 0
        while (c != null && depth < 8) {
            for (f in c.declaredFields) {
                if (View::class.java.isAssignableFrom(f.type)) {
                    val v = runCatching { f.isAccessible = true; f.get(holder) as? View }.getOrNull()
                    if (v != null) return v
                }
            }
            c = c.superclass
            depth++
        }
        return null
    }

    // ==================================================================
    // 4) 启动自检 + 日志辅助
    // ==================================================================
    private fun selfTest(loader: ClassLoader) {
        val results = mutableListOf<Pair<String, Boolean>>()

        val callVh = Reflect.classOrNull(loader, Targets.CLS_DIALER_CALL_VH)
        val baseVh = Reflect.classOrNull(loader, Targets.CLS_BASE_VH)
        val util = Reflect.classOrNull(loader, Targets.CLS_DIALER_VH_UTIL)
        val vm = Reflect.classOrNull(loader, Targets.CLS_DIALER_ITEM_VM)
        val uiSettings = Reflect.classOrNull(loader, Targets.CLS_DIALER_UI_SETTINGS)

        results += "DialerCallVH" to (callVh != null)
        results += "BaseVH.onClick(View)" to
                (Reflect.methodOrNull(baseVh, Targets.M_ON_CLICK, View::class.java) != null)
        results += "DialerCallVH.onClick(View)" to
                (Reflect.methodOrNull(callVh, Targets.M_ON_CLICK, View::class.java) != null)
        results += "DialerCallVH.s0(...)" to
                (Reflect.methodOrNull(
                    callVh, Targets.M_BIND, vm, uiSettings,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                ) != null)
        results += "DialerVHUtil.e(Context,DialerItemVM)" to
                (Reflect.methodOrNull(util, Targets.M_OPEN_DETAIL, Context::class.java, vm) != null)
        results += "DialerVHUtil.b(Context,DialerItemVM,String) [拨号]" to
                (Reflect.methodOrNull(
                    util, Targets.M_DIAL, Context::class.java, vm, String::class.java
                ) != null)

        val pass = results.count { it.second }
        Log.i("selfTest: $pass/${results.size} targets OK")
        results.filterNot { it.second }.forEach { (name, _) -> Log.e("selfTest: MISSING -> $name") }
    }

    /** 把 View 描述成一行日志，便于真机对账。 */
    private fun describe(v: View?): String {
        if (v == null) return "null"
        val sb = StringBuilder()
        sb.append(v.javaClass.simpleName)
        if (v.id != View.NO_ID) sb.append('#').append(Integer.toHexString(v.id))
        v.contentDescription?.let { sb.append("[desc=").append(it).append(']') }
        return sb.toString()
    }
}
