package com.hyperredial

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** 日志 + 反射小工具，所有调用都做异常兜底，绝不因模块自身问题导致电话 App 崩溃。 */
object Log {
    private const val PREFIX = "[HyperRedial] "

    fun i(msg: String) {
        runCatching { XposedBridge.log(PREFIX + msg) }
    }

    fun e(msg: String, t: Throwable? = null) {
        runCatching {
            val detail = t?.let { " -> ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            XposedBridge.log(PREFIX + msg + detail)
            if (t != null) XposedBridge.log(t)
        }
    }
}

object Reflect {

    /**
     * 已解析方法缓存，避免每次点击都做反射查找。
     *
     * ⚠️ 注意：只能用非 null 值填这个 map。
     * ConcurrentHashMap 不允许 null 值，而 getOrPut 的 lambda 一旦返回 null
     * 就会在内部执行 put(key, null) 并抛 NullPointerException。
     * 早期版本正是踩了这个坑：方法找不到时 NPE 被上层 try/catch 吞掉，
     * 表现为"点了没反应"（selfTest 里则直接中断整个自检）。
     * 所以找不到结果时**不写缓存**。
     */
    private val methodCache = ConcurrentHashMap<String, Method>()

    fun classOrNull(loader: ClassLoader, name: String): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, loader) }.getOrNull()

    /**
     * 查找精确签名的静态/实例方法。
     *
     * 参数类型声明为可空：调用方经常是从 findClassIfExists 拿到的 Class<*>?。
     */
    fun methodOrNull(cls: Class<*>?, name: String, vararg paramTypes: Class<*>?): Method? {
        if (cls == null) return null
        // 只缓存"命中"的结果；未命中不缓存（避免上面说的 null 值问题）
        if (paramTypes.any { it == null }) {
            // 有 null 类型参数时无法拼出确定 key，直接查、不缓存
            return runCatching {
                XposedHelpers.findMethodExactIfExists(cls, name, *paramTypes)
            }.getOrNull()
        }
        val key = cls.name + "#" + name + paramTypes.joinToString(",") { it!!.name }
        methodCache[key]?.let { return it }
        val found = runCatching {
            XposedHelpers.findMethodExactIfExists(cls, name, *paramTypes)
        }.getOrNull() ?: return null
        methodCache[key] = found
        return found
    }

    /**
     * 调用某个 public static 方法。
     * @param receiver 为 null 表示静态调用
     */
    fun call(target: Any?, method: Method?, vararg args: Any?): Any? {
        if (method == null) return null
        return runCatching { method.invoke(target, *args) }
            .onFailure { Log.e("invoke ${method.name} failed", it) }
            .getOrNull()
    }

    fun readField(holder: Any?, fieldName: String): Any? {
        if (holder == null) return null
        return runCatching { XposedHelpers.getObjectField(holder, fieldName) }.getOrNull()
    }
}

object Views {

    /** 收集 view 自身及其所有子孙的 id。 */
    fun collectIds(v: View?, out: MutableSet<Int>, depth: Int = 0) {
        if (v == null || depth > 32) return
        out.add(v.id)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectIds(v.getChildAt(i), out, depth + 1)
        }
    }

    /** view 是否位于某个指定 id 的容器内（含自身）。 */
    fun isInside(tapped: View?, containerId: Int): Boolean {
        var cur: View? = tapped
        var depth = 0
        while (cur != null && depth < 32) {
            if (cur.id == containerId) return true
            val parent = cur.parent
            cur = if (parent is View) parent else null
            depth++
        }
        return false
    }

    fun findById(root: View?, id: Int): View? {
        if (root == null) return null
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    /**
     * 「图标真正画出来的那块区域」在**屏幕坐标系**里的矩形，用于把点击热区收敛到图标上。
     *
     * 为什么不用 ImageView 的 bounds：按钮容器实测 215x128px，而图标只有 24dp
     * （约 84px），ImageView 往往被撑满整个按钮，按 bounds 算等于没收敛。
     * 所以这里按 drawable 的固有尺寸（居中等比摆放）算出真正着墨的那块，
     * ImageView 比图标还小的时候再夹回自身。
     *
     * 用屏幕坐标（getLocationOnScreen）而不是相对坐标，是为了跟
     * MotionEvent.rawX/rawY 直接比较，不必一层层往上累加 left/top。
     *
     * @return null 表示还没布局（宽高为 0）或取不到位置，调用方按"没命中"处理
     */
    fun iconScreenRect(icon: View?): Rect? {
        if (icon == null || icon.width <= 0 || icon.height <= 0) return null
        val d = (icon as? ImageView)?.drawable
        val iw = d?.intrinsicWidth ?: 0
        val ih = d?.intrinsicHeight ?: 0
        val w = if (iw in 1 until icon.width) iw else icon.width
        val h = if (ih in 1 until icon.height) ih else icon.height
        val loc = IntArray(2)
        val ok = runCatching { icon.getLocationOnScreen(loc); true }.getOrDefault(false)
        if (!ok) return null
        val cx = loc[0] + icon.width / 2f
        val cy = loc[1] + icon.height / 2f
        return Rect(
            (cx - w / 2f).roundToInt(),
            (cy - h / 2f).roundToInt(),
            (cx + w / 2f).roundToInt(),
            (cy + h / 2f).roundToInt()
        )
    }
}

/** 图标相关的辅助。 */
object IconSwap {

    /**
     * 日/夜两套图标的 ConstantState 缓存。
     *
     * 刻意不缓存 Drawable 实例：同一个 Drawable 被多个 ImageView 共用时，
     * bounds / callback 会互相打架（列表滚动时表现为某个图标位置错乱）。
     * 缓存 ConstantState 后每个 ImageView 都能 newDrawable() 出一份独立实例，
     * 又省掉每次绑定都 createPackageContext + 解析资源 id 的开销。
     *
     * 分两格是因为图标自带配色：浅色用 res/drawable/，深色用 res/drawable-night/，
     * 必须按当前 uiMode 分别缓存，否则切主题后会拿到上一套颜色。
     */
    @Volatile private var dayState: Drawable.ConstantState? = null
    @Volatile private var nightState: Drawable.ConstantState? = null
    private var loggedLoad = false

    /**
     * 取模块自带的「绿色电话」图标 —— 和联系人详情页那颗按钮同款：
     * 淡绿圆底盘 + 实心绿听筒（浅色 #ECF9ED/#61CA51，深色 #283528/#66CF59）。
     *
     * 注意：这份图标是**固定配色**的，不要再拿 ImageView 的 tint 去刷它 ——
     * 宿主的 tint 在这台机器上是全透明，会把整个图标涂没（1.1.1 踩过）。
     */
    fun moduleDialIcon(hostContext: Context?): Drawable? {
        val ctx = hostContext ?: return null
        val night = isNightMode(ctx)

        val cached = if (night) nightState else dayState
        cached?.let { st ->
            runCatching { st.newDrawable() }.getOrNull()?.let { return it.mutate() }
        }

        // 方式一：用宿主 Context 创建模块 Context（推荐，vector 能正确加载）
        var d = load(ctx, Context.CONTEXT_IGNORE_SECURITY)
        // 方式二：加上 CONTEXT_INCLUDE_CODE，部分 ROM 需要
        if (d == null) {
            d = load(ctx, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        }
        if (d == null) {
            Log.e("unable to load module drawable ic_redial_ios")
            return null
        }
        val st = runCatching { d.constantState }.getOrNull()
        if (night) nightState = st else dayState = st
        if (!loggedLoad) {
            loggedLoad = true
            Log.i(
                "dial icon loaded: theme=${if (night) "night" else "day"}" +
                        " size=${d.intrinsicWidth}x${d.intrinsicHeight}px"
            )
        }
        return d.mutate()
    }

    private fun isNightMode(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun load(host: Context, flags: Int): Drawable? = runCatching {
        val moduleCtx = host.createPackageContext(Targets.MODULE_PACKAGE, flags)
        val id = moduleCtx.resources.getIdentifier(
            "ic_redial_ios", "drawable", Targets.MODULE_PACKAGE
        )
        if (id == 0) return@runCatching null
        moduleCtx.getDrawable(id)
    }.onFailure { Log.e("load module dial icon failed", it) }.getOrNull()
}
