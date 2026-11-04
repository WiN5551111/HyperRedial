package com.hyperredial

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.view.MotionEvent
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
 *   右侧按钮图标    → iOS 风格「圆圈 + 听筒」(ic_redial_ios)，颜色跟随 HyperOS 主题
 *   右侧按钮热区    → **收敛到圆圈本身**：圈外一律当成"点整行"（详情），不再外扩
 *   右侧按钮按压    → **涟漪只画在圆圈里**，不再整行高亮
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

    /** 详情按钮的 View 实例（图标替换与热区判定用）。 */
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
    @Volatile private var loggedFirstTapCheck = false
    @Volatile private var loggedFirstRipple = false
    @Volatile private var loggedDialTarget = false

    /** 递归保护：模拟点击详情按钮打开详情时，避免再次进入按钮分支。 */
    @Volatile private var inDetailOpen = false

    /**
     * 图标尺寸（dp）—— 与「联系人详情页」那颗绿色按钮一致。
     *
     * 详情页那颗按钮实测 **120px**；电话 App 的有效密度是 3.077（492dpi，
     * 不是设备的 520dpi/3.25 —— 用 3.25 算会得到 114px，短 6px）。
     * 120 / 3.077 = 39dp。
     */
    private val ICON_SIZE_DP = 39f

    /**
     * 图标距按钮右边缘的距离（dp）。
     * 按钮槽位实测只有 194px 宽，120px 的图标 + 23dp(71px) 右边距刚好用满，
     * 也就是在"和详情页一样大"的前提下能往左让到的极限。
     */
    private val ICON_RIGHT_MARGIN_DP = 23f

    /**
     * 最近一次 ACTION_DOWN 的落点，key = 该行的右侧按钮，value = [rawX, rawY, uptimeMillis]。
     *
     * 为什么要记这个：按钮被设成不可点击后，触摸会冒泡到整行、由整行的点击统一处理，
     * 而 onClick 里拿不到坐标。所以"按下"的时候先把落点存下来，
     * 等整行点击回来时再跟图标的屏幕矩形比一比，判断这一下到底点在圈里还是圈外。
     *
     * 用 rawX/rawY（屏幕坐标）而不是事件内的 x/y，是因为图标矩形也是用
     * getLocationOnScreen 取的，两边同一坐标系，省掉一层层 left/top 的换算。
     */
    private val lastDown = java.util.WeakHashMap<View, FloatArray>()

    /**
     * 长按计时用的主线程 Handler（全模块共用一个）。
     * 列表每绑一行都会新建 IconPressTouch，若每个实例各带一个 Handler
     * 就会在滚动时产生无谓的对象分配。
     *
     * ⚠️ 必须 lazy：模块实例是在 zygote 的 nativeSpecializeAppProcess 阶段构造的，
     * 那一刻主线程 Looper 还没 prepare，`Looper.getMainLooper()` 返回 null，
     * 直接 `Handler(...)` 会 NPE 让整个模块加载失败（1.1.4 踩过）。
     * lazy 把构造推迟到第一次触摸回调 —— 那时一定在主线程且 Looper 已就绪。
     */
    private val uiHandler by lazy {
        android.os.Handler(android.os.Looper.getMainLooper())
    }

    /** 落点数据超过这个时间就当无效（防止残留数据把"点空白"误判成"点图标"）。 */
    private val tapStaleMs = 1500L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Targets.HOST_PACKAGE) return
        if (lpparam.processName != Targets.HOST_PACKAGE) {
            Log.i("skip secondary process: ${lpparam.processName}")
            return
        }

        Log.i("loading hooks for ${lpparam.packageName}")
        val loader = lpparam.classLoader

        hookAdapterCache(loader)
        hookRowClick(loader)      // 整行 -> 详情（落在图标圈里则拨号）
        hookButtonDial(loader)    // 右侧按钮 -> 拨号（兜底路径）
        hookButtonDecor(loader)   // 图标 + 图标级热区
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
    // 1) 整行点击 -> 详情（落点在图标圈里 -> 拨号）
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
                    // openDetail() 里会回放一次按钮点击，那次点击也会走到这里：
                    // 直接放行，让原生分支把详情页打开（否则会自己把自己吃掉）。
                    if (inDetailOpen) return

                    val holder = param.thisObject
                    // 只处理通话记录行（联系人行/搜索行有自己的 VH，行为不改）
                    if (!isCallRowHolder(holder)) return

                    val tapped = param.args.getOrNull(0) as? View
                    hostContext = tapped?.context ?: hostContext

                    // 吃掉原生"整行拨号"
                    param.result = null

                    // 落点在圆圈里 -> 拨号；否则继续走详情
                    if (isTapOnIcon(holder) && dialFromRow(holder)) return

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
        Log.i("hooked BaseVH.onClick (整行 -> 详情 / 图标 -> 拨号)")
    }

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
     *
     * 注：按钮现在被设成 isClickable=false（为了不让它吃掉圈外的触摸），
     * 但 performClick() 只看有没有 OnClickListener，跟 clickable 标志无关，
     * 所以这条路依然有效 —— 实测无需改成反射调用。
     */
    private fun openDetail(holder: Any?, tapped: View?) {
        if (inDetailOpen) return
        inDetailOpen = true
        try {
            val button = buttonOf(holder)
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
    //
    //    注：按钮现在是 isClickable=false，真实触摸不会走到这里（走第 1 节的
    //    "整行点击 + 落点判定"）。这条 hook 仍然保留：一是 openDetail 回放点击时
    //    需要靠 inDetailOpen 放行，二是万一某版 App 把按钮改成别的方式触发，
    //    这里还能兜住。
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
        val event = (adapter?.let { Reflect.readField(it, "y") } as? String) ?: ""

        // 签名在 App 版本之间变过，按顺序试，谁在就用谁：
        //   18.30.00.60+ : e(Context, DialerItemVM, String)   实测 = 拨号
        //   18.30.00.55  : b(Context, DialerItemVM, String)   旧版拨号
        val candidates = listOf(
            Targets.M_DIAL_NEW to "e(Context,DialerItemVM,String) [新版]",
            Targets.M_DIAL to "b(Context, DialerItemVM,String) [旧版]"
        )
        for ((name, label) in candidates) {
            val m = Reflect.methodOrNull(
                utilCls, name, Context::class.java, item.javaClass, String::class.java
            ) ?: continue
            val ok = runCatching { m.invoke(null, ctx, item, event) }
                .onFailure { Log.e("$label threw", it) }
                .isSuccess
            if (ok) {
                if (!loggedDialTarget) {
                    loggedDialTarget = true
                    Log.i("dial via DialerVHUtil.$label")
                }
                return true
            }
        }
        Log.e(
            "dial: 没有可用的拨号方法（试过 " +
                    candidates.joinToString { it.first } + "）。" +
                    "App 可能又改了方法名，见 README「版本适配」"
        )
        return false
    }

    /** 从"整行点击"这条路上发起拨号；失败返回 false，让调用方退回打开详情。 */
    private fun dialFromRow(holder: Any): Boolean {
        val button = buttonOf(holder) ?: run {
            Log.e("icon tap: 找不到按钮，退回详情")
            return false
        }
        val item = Reflect.readField(holder, "d0") ?: run {
            Log.e("icon tap: holder.d0 为 null，退回详情")
            return false
        }
        if (!dial(holder, button, item)) return false
        if (!loggedFirstDial) {
            loggedFirstDial = true
            Log.i("icon tap -> dial (item=${item.javaClass.simpleName})")
        }
        return true
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
    // 3) 右侧按钮：换「圆圈听筒」图标 + 把响应范围收进圆圈里 + 让按压动画只作用于圆圈
    //
    //    需求：整行 = 详情，只有"听筒外面那个圈"才算拨打，且按下时涟漪只出现在按钮上。
    //    实测按钮容器 192x128px，圆圈只有 121px，差着一截，
    //    所以这里不是"放大热区"，而是反过来"缩到图标里"。
    //
    //    做法（不用 TouchDelegate，也不 hook 任何框架方法）：
    //      a. 按钮 isClickable=false —— 点在按钮空白处时事件冒泡到整行，
    //         走原生整行点击 => 我们的 hook => 打开详情。
    //      b. 给图标挂一个圆形 mask 的 RippleDrawable 前景 —— 涟漪的半径被
    //         mask 限制在圆盘内，永远不会漫到圆圈外面。
    //      c. 按钮自己挂 OnTouchListener：落点在圈里就 return true 消费掉这次手势。
    //         ★ 这是"动画只在按钮上"的关键：整行收不到 DOWN 就不会进入 pressed
    //         状态，那层整行涟漪/高亮也就画不出来（实测圆盘 238->194，行背景 255 不动）。
    //         落点在圈外则 return false，事件照旧冒泡，整行该有的反馈一点不少。
    //      d. 圈内长按仍然交回宿主（row.performLongClick），MIUI 原生菜单照旧。
    //      e. 兜底：万一某版布局让按钮收不到 DOWN，再用"落点 vs 图标矩形"判定一次。
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
                        applyIconOnlyHitArea(button, itemView)
                    } catch (t: Throwable) {
                        Log.e("hookButtonDecor body failed", t)
                    }
                }
            })
            Log.i("hooked DialerCallVH.s0 (图标 + 图标级热区)")
        }.onFailure { Log.e("hookAllMethods(s0) failed", it) }
    }

    private fun applyDialIcon(button: View) {
        val icon = findIcon(button) ?: return
        val d = IconSwap.moduleDialIcon(button.context) ?: return
        // 已经是我们这份图标（ConstantState 相同）就不用重复设置，省一次重绘。
        if (icon.drawable?.constantState !== d.constantState) {
            runCatching { icon.setImageDrawable(d) }
                .onFailure { Log.e("icon swap failed", it) }
        }
        // 图标自带配色（绿盘 + 绿听筒），宿主的 tint 必须清掉：
        // 这台机器上它解析出来是全透明的，留着会把图标整个涂没。
        // 放在 setImageDrawable 之后 —— ImageView 换图时会把旧 tint 套到新 drawable 上。
        if (icon.imageTintList != null) {
            runCatching { icon.imageTintList = null }.onFailure { Log.e("clear tint failed", it) }
        }
        resizeIcon(icon)
        if (!loggedFirstIcon) {
            loggedFirstIcon = true
            icon.post {
                Log.i(
                    "icon ready: view=${icon.width}x${icon.height}" +
                            " intrinsic=${icon.drawable?.intrinsicWidth}x${icon.drawable?.intrinsicHeight}" +
                            " scaleType=${icon.scaleType}"
                )
            }
        }
    }

    /**
     * 让图标和「联系人详情页」那颗绿色按钮一样大，并整体往左让开一点。
     *
     * 详情页实测 ⌀120px；这里按宿主的有效密度（3.077）折算成 [ICON_SIZE_DP]，
     * 因为 LayoutParams 的 dp 就是用宿主密度换算的。
     * 注意 drawable 自己写的 37dp 是另一个密度空间（资源密度 3.25）的 intrinsic，
     * 两者都落在 120px，别去"对齐"它们，会变成 114px / 127px。
     * 原布局里这个 ImageView 只有 24dp，光放大 drawable 是不够的
     * （FIT_CENTER 会被视图边界压回去），必须连 LayoutParams 一起改。
     *
     * 靠右对齐 + 固定右边距来定位（而不是 translationX）：
     * 这样 `getLocationOnScreen()` 拿到的位置就是真实位置，
     * 点击判定用的 `Views.iconScreenRect()` 不会和看到的图形错位。
     */
    private fun resizeIcon(icon: ImageView) {
        runCatching {
            val density = icon.resources.displayMetrics.density
            val size = (ICON_SIZE_DP * density).toInt()
            val margin = (ICON_RIGHT_MARGIN_DP * density).toInt()

            // 列表每绑定一次都会走这里，尺寸已经对了就直接返回，免得每次 requestLayout
            icon.layoutParams?.let { cur ->
                val marginLp = cur as? android.view.ViewGroup.MarginLayoutParams
                if (cur.width == size && cur.height == size &&
                    (marginLp == null || marginLp.rightMargin == margin) &&
                    icon.scaleType == ImageView.ScaleType.FIT_XY &&
                    icon.paddingLeft == 0 && icon.paddingTop == 0 &&
                    icon.paddingRight == 0 && icon.paddingBottom == 0
                ) return
            }

            val parent = icon.parent as? android.view.ViewGroup ?: return

            if (parent is android.widget.FrameLayout) {
                val lp = icon.layoutParams as? android.widget.FrameLayout.LayoutParams
                    ?: android.widget.FrameLayout.LayoutParams(size, size)
                lp.width = size
                lp.height = size
                lp.leftMargin = 0
                lp.topMargin = 0
                lp.bottomMargin = 0
                lp.rightMargin = margin
                lp.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.RIGHT
                icon.layoutParams = lp
            } else {
                val lp = icon.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
                lp.width = size
                lp.height = size
                lp.rightMargin = margin
                icon.layoutParams = lp
            }
            // 关键：ImageView 自带内边距会把可用区域压小，drawable 再大也画不出来
            // （实测 114px 的视图里只画出 20px）。清掉 padding + FIT_XY 强制铺满。
            icon.setPadding(0, 0, 0, 0)
            icon.scaleType = ImageView.ScaleType.FIT_XY
        }.onFailure { Log.e("resize icon failed", it) }
    }

    /**
     * 把点击响应范围收敛到图标本身，并让按压动画只出现在图标上。
     *
     * @param button  右侧按钮容器（FrameLayout#7f0a04e0）
     * @param itemView 整行根视图
     */
    private fun applyIconOnlyHitArea(button: View, itemView: View) {
        val icon = findIcon(button)

        // a) 圈内要有自己的涟漪：挂在图标的前景上（前景层盖在图片之上，
        //    且 bounds 就是图标本身，圆盘刚好铺满），按下去才看得见。
        if (icon != null) applyIconRipple(icon)

        // b) 按钮不参与点击/长按：圈外的触摸继续冒泡给整行，交给原生处理
        if (button.isClickable) button.isClickable = false
        if (button.isLongClickable) button.isLongClickable = false

        // c) ★ 圈内由按钮自己吃掉这次手势。
        //
        //    这是"动画只出现在按钮上"的关键：手指按在圈里时按钮返回 true，
        //    这一次 DOWN 就不会再往上分发给整行 —— HyperCellLayout 收不到
        //    DOWN 就不会进入 pressed 状态，整行那层涟漪/高亮也就不会画出来。
        //    圈外返回 false，事件照旧冒泡，整行该有的反馈一点不少。
        val row = Views.findById(itemView, Targets.ID_ROW_CONTAINER) ?: itemView
        button.setOnTouchListener(IconPressTouch(button, icon, row, itemView))

        // d) 兜底：万一某版布局让按钮不在触摸路径上（收不到 DOWN），
        //    仍按"落点是否在圈里"来判定。这一段只旁观不消费，返回 false。
        row.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                synchronized(lastDown) {
                    lastDown[button] = floatArrayOf(
                        e.rawX,
                        e.rawY,
                        android.os.SystemClock.uptimeMillis().toFloat()
                    )
                }
            }
            false
        }

        if (!loggedFirstHitArea) {
            loggedFirstHitArea = true
            Log.i(
                "hit area limited to icon: button=${describe(button)}" +
                        " icon=${icon?.let { "${it.width}x${it.height}" }} row=${describe(row)}"
            )
        }
    }

    /**
     * 图标级的按压处理：只在"圈里"接管手势，并给出只作用于图标的按压反馈。
     *
     * 状态机：
     *   DOWN 圈内 -> 消费，图标进入按压态
     *   MOVE 移出圈 -> 取消按压态（但手势仍归我们，避免事件流断在半路）
     *   UP   圈内且未取消 -> 走 button.performClick() -> 宿主 onClick -> 我们的 hook 拨号
     *   UP   圈外/已取消 -> 什么都不做
     *   CANCEL（多半是列表开始滚动被 RecyclerView 拦截）-> 复位
     *
     * 为什么不直接让按钮 clickable：那样整个按钮（194x128）都会吃掉触摸，
     * 圈外那圈空白就点不到"整行 -> 详情"了。这里要的是"位置收敛 + 动画收敛"。
     */
    private inner class IconPressTouch(
        private val button: View,
        private val icon: ImageView?,
        private val row: View,
        private val itemView: View
    ) : View.OnTouchListener {

        private var active = false
        private var canceled = false
        private var longPressed = false
        private var downX = 0f
        private var downY = 0f

        private val longPressRunnable = Runnable {
            if (!active || canceled) return@Runnable
            longPressed = true
            // 先撤掉按压涟漪，再把长按交给宿主 —— 圈内长按仍然要能出 MIUI 原生菜单
            setPressed(false)
            runCatching {
                button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            val shown = runCatching { row.performLongClick() }.getOrDefault(false) ||
                    runCatching { itemView.performLongClick() }.getOrDefault(false)
            if (!shown) Log.e("long press on icon: 原生菜单没有响应")
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            return try {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        active = false
                        canceled = false
                        longPressed = false
                        if (!inside(e)) return false      // 圈外：交回整行
                        active = true
                        downX = e.rawX
                        downY = e.rawY
                        setPressed(true)
                        uiHandler.postDelayed(
                            longPressRunnable,
                            android.view.ViewConfiguration.getLongPressTimeout().toLong()
                        )
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!active) return false
                        // 手指挪出触摸容差就当取消：长按菜单不该因为手抖就弹出来
                        if (!longPressed && movedBeyondSlop(e)) uiHandler.removeCallbacks(longPressRunnable)
                        val inside = inside(e)
                        if (!inside && !canceled) {
                            canceled = true
                            setPressed(false)
                        } else if (inside && canceled) {
                            canceled = false
                            setPressed(true)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!active) return false
                        active = false
                        uiHandler.removeCallbacks(longPressRunnable)
                        setPressed(false)
                        if (!canceled && !longPressed && inside(e)) {
                            // 复用宿主自己的 onClick（hook 里已把它改成拨号），
                            // 这样点击音效、无障碍语义都不用我们操心。
                            v.performClick()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (!active) return false
                        active = false
                        canceled = false
                        longPressed = false
                        uiHandler.removeCallbacks(longPressRunnable)
                        setPressed(false)
                        true
                    }

                    else -> active
                }
            } catch (t: Throwable) {
                Log.e("icon touch failed", t)
                uiHandler.removeCallbacks(longPressRunnable)
                false
            }
        }

        private fun inside(e: MotionEvent): Boolean =
            Views.iconScreenRect(icon)?.contains(e.rawX.toInt(), e.rawY.toInt()) == true

        private fun movedBeyondSlop(e: MotionEvent): Boolean {
            val slop = android.view.ViewConfiguration.get(button.context).scaledTouchSlop
            return kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop
        }

        /**
         * 只驱动图标这一个 View 的 pressed 状态：
         * 前景的 RippleDrawable 会据此从按下的那个点开始扩散，
         * 半径受圆形 mask 限制，绝不会画到圆圈外面去。
         */
        private fun setPressed(pressed: Boolean) {
            val ic = icon ?: return
            runCatching {
                if (pressed) {
                    val loc = IntArray(2)
                    ic.getLocationOnScreen(loc)
                    (ic.foreground as? RippleDrawable)?.setHotspot(
                        downX - loc[0],
                        downY - loc[1]
                    )
                }
                ic.isPressed = pressed
            }.onFailure { Log.e("set icon pressed failed", it) }
        }
    }

    /** 已经装在图标上的涟漪色，用于主题切换后重新着色（列表复用 View，不重装会残留旧主题色）。 */
    private val rippleColors = java.util.WeakHashMap<ImageView, Int>()

    /** 给图标装一个圆形涟漪前景（只在圈内扩散）。 */
    private fun applyIconRipple(icon: ImageView) {
        val color = rippleColor(icon.context)
        // 颜色没变就复用；变了（浅色 <-> 深色）必须重装 ——
        // 否则深色下还挂着浅色主题那层黑涟漪，等于看不见。
        if (icon.foreground is RippleDrawable && rippleColors[icon] == color) return
        runCatching {
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)   // mask 只取 alpha，颜色本身不显示
            }
            icon.foreground = RippleDrawable(ColorStateList.valueOf(color), null, mask)
            rippleColors[icon] = color
            if (!loggedFirstRipple) {
                loggedFirstRipple = true
                Log.i("icon ripple color=#${Integer.toHexString(color)}")
            }
        }.onFailure { Log.e("install icon ripple failed", it) }
    }

    /**
     * 涟漪颜色：优先取宿主主题的 colorControlHighlight，
     * 让按压色和 HyperOS 其它控件保持一致（浅色下偏黑、深色下偏白）。
     */
    private fun rippleColor(ctx: Context): Int {
        val tv = android.util.TypedValue()
        val resolved = runCatching {
            ctx.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)
        }.getOrDefault(false)
        if (resolved &&
            tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT &&
            Color.alpha(tv.data) > 0
        ) {
            return tv.data
        }
        val night = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) 0x40FFFFFF else 0x33000000
    }

    /** 上一次 ACTION_DOWN 是否落在"圆圈听筒"的图形范围内。 */
    private fun isTapOnIcon(holder: Any?): Boolean {
        val button = buttonOf(holder) ?: return false
        val rec = synchronized(lastDown) { lastDown.remove(button) } ?: return false
        if (android.os.SystemClock.uptimeMillis() - rec[2].toLong() > tapStaleMs) return false
        val icon = findIcon(button) ?: return false
        val rect = Views.iconScreenRect(icon) ?: return false
        val hit = rect.contains(rec[0].toInt(), rec[1].toInt())
        if (!loggedFirstTapCheck) {
            loggedFirstTapCheck = true
            Log.i(
                "tap check: point=(${rec[0].toInt()},${rec[1].toInt()})" +
                        " icon=$rect -> ${if (hit) "hit" else "miss"}"
            )
        }
        return hit
    }

    /** 当前行里的右侧按钮；拿不到就用最后见过的那个。 */
    private fun buttonOf(holder: Any?): View? {
        val itemView = itemViewOf(holder)
        return (itemView?.let { Views.findById(it, Targets.ID_DETAIL_BUTTON) }) ?: detailButton
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

        // 每一项都单独兜底：某一条抛出异常不能让整个自检中断
        // （早期版本就因为 methodOrNull 的 NPE 让 selfTest 直接崩在中间）
        fun check(label: String, block: () -> Boolean) {
            val ok = runCatching(block).getOrDefault(false)
            results += label to ok
        }

        check("DialerCallVH") { callVh != null }
        check("BaseVH.onClick(View)") {
            Reflect.methodOrNull(baseVh, Targets.M_ON_CLICK, View::class.java) != null
        }
        check("DialerCallVH.onClick(View)") {
            Reflect.methodOrNull(callVh, Targets.M_ON_CLICK, View::class.java) != null
        }
        check("DialerCallVH.s0(...)") {
            Reflect.methodOrNull(
                callVh, Targets.M_BIND, vm, uiSettings,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ) != null
        }
        // 拨号方法：新版 e(...) 或旧版 b(...)，任一存在即算通过
        check("DialerVHUtil 拨号方法 (e/b + String)") {
            Reflect.methodOrNull(
                util, Targets.M_DIAL_NEW, Context::class.java, vm, String::class.java
            ) != null || Reflect.methodOrNull(
                util, Targets.M_DIAL, Context::class.java, vm, String::class.java
            ) != null
        }
        // 详情入口：新版 g(...) 或旧版 e(...)，仅作参考
        check("DialerVHUtil 详情方法 (g/e)") {
            Reflect.methodOrNull(util, Targets.M_OPEN_DETAIL_NEW, Context::class.java, vm) != null ||
                    Reflect.methodOrNull(
                        util, Targets.M_OPEN_DETAIL, Context::class.java, vm, String::class.java
                    ) != null ||
                    Reflect.methodOrNull(util, Targets.M_OPEN_DETAIL, Context::class.java, vm) != null
        }

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
