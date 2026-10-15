package com.hyperredial

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

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

    /** 已解析方法缓存，避免每次点击都做反射查找。 */
    private val methodCache = ConcurrentHashMap<String, Method?>()

    fun classOrNull(loader: ClassLoader, name: String): Class<*>? =
        runCatching { XposedHelpers.findClassIfExists(name, loader) }.getOrNull()

    /**
     * 查找精确签名的静态/实例方法。
     *
     * 参数类型声明为可空：调用方经常是从 findClassIfExists 拿到的 Class<*>?，
     * 传进来后由 findMethodExactIfExists 自己在运行时判空/报错，
     * 这样调用点不必到处写 !!。
     */
    fun methodOrNull(cls: Class<*>?, name: String, vararg paramTypes: Class<*>?): Method? {
        if (cls == null) return null
        val key = cls.name + "#" + name + paramTypes.joinToString(",") { it?.name ?: "null" }
        return methodCache.getOrPut(key) {
            runCatching { XposedHelpers.findMethodExactIfExists(cls, name, *paramTypes) }.getOrNull()
        }
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

    /** 已经打过日志的 View，避免每次绑定时刷屏。 */
    private val logOnce = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

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
     * 放大 [target] 的点击热区。
     *
     * 做法：把 target 在父容器坐标系里的矩形向外扩张 extraPx，
     * 再通过 TouchDelegate 让父容器把落在扩张区域内的触摸转交给 target。
     *
     * 注意：TouchDelegate 只在"扩张后的矩形大于 view 自身矩形"时才有意义，
     * 因此这里保证扩张量至少为 1px。
     *
     * @param onDone 成功设置后回调，参数是 "from=WxH to=WxH" 的描述文本
     */
    fun enlargeHitArea(
        target: View?,
        extraPx: Int,
        minWidthPx: Int = 0,
        minHeightPx: Int = 0,
        onDone: ((String) -> Unit)? = null
    ) {
        if (target == null) return
        val parent = target.parent as? ViewGroup ?: return
        val extra = if (extraPx > 0) extraPx else 1
        parent.post {
            runCatching {
                val rect = Rect()
                target.getHitRect(rect)
                if (rect.isEmpty) return@runCatching

                val out = Rect(rect)
                out.left -= extra
                out.top -= extra
                out.right += extra
                out.bottom += extra

                // 需要保证命中区域不小于设定值（居中扩张）
                if (minWidthPx > 0 && out.width() < minWidthPx) {
                    val grow = (minWidthPx - out.width() + 1) / 2
                    out.left -= grow
                    out.right += grow
                }
                if (minHeightPx > 0 && out.height() < minHeightPx) {
                    val grow = (minHeightPx - out.height() + 1) / 2
                    out.top -= grow
                    out.bottom += grow
                }

                // 夹在父容器范围内，避免触摸被邻居抢走
                out.left = maxOf(out.left, 0)
                out.top = maxOf(out.top, 0)
                out.right = minOf(out.right, parent.width)
                out.bottom = minOf(out.bottom, parent.height)

                if (out.width() <= rect.width() && out.height() <= rect.height()) {
                    Log.i("hit area unchanged (already large enough): view=${target.id.toString(16)}")
                    return@runCatching
                }

                parent.touchDelegate = IosStyleTouchDelegate(out, rect, target)
                val desc = "view=${target.id.toString(16)} " +
                        "from=${rect.width()}x${rect.height()} to=${out.width()}x${out.height()}"
                if (logOnce.add(target)) {
                    Log.i("hit area expanded: $desc")
                }
                onDone?.invoke(desc)
            }.onFailure { Log.e("enlargeHitArea failed", it) }
        }
    }

    /**
     * 复刻 iOS 的手感：落在"扩张后的矩形"里就算命中按钮。
     *
     * 关键点：TouchDelegate 的 onTouchEvent 只会在坐标落在 delegate 矩形内时被父容器调用，
     * 此时事件坐标已经不在 target 自己的范围内，所以这里直接转发即可，**不能再判一次 original**，
     * 否则会出现一次点击被派发两次（打开两次详情/拨号两次）。
     */
    private class IosStyleTouchDelegate(
        private val expanded: Rect,
        private val original: Rect,
        private val target: View
    ) : TouchDelegate(expanded, target) {

        private var claimed = false

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (!claimed) {
                if (expanded.contains(event.x.toInt(), event.y.toInt())) {
                    claimed = true
                } else {
                    return false
                }
            }

            val forwarded = android.view.MotionEvent.obtain(event)
            forwarded.setLocation(
                event.x - original.left,
                event.y - original.top
            )
            val handled = target.dispatchTouchEvent(forwarded)
            forwarded.recycle()

            val action = event.actionMasked
            if (action == android.view.MotionEvent.ACTION_UP ||
                action == android.view.MotionEvent.ACTION_CANCEL
            ) {
                claimed = false
            }
            return handled
        }
    }
}

/** 图标相关的辅助。 */
object IconSwap {

    private var cached: Drawable? = null

    /** 取模块自带的 iOS 风格拨号图标。 */
    fun moduleDialIcon(hostContext: Context?): Drawable? {
        cached?.let { return it }
        val ctx = hostContext ?: return null

        // 方式一：用宿主 Context 创建模块 Context（推荐，vector 能正确加载）
        load(ctx, Context.CONTEXT_IGNORE_SECURITY)?.let { cached = it; return it }
        // 方式二：加上 CONTEXT_INCLUDE_CODE，部分 ROM 需要
        load(ctx, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)?.let {
            cached = it
            return it
        }
        Log.e("unable to load module drawable ic_redial_ios")
        return null
    }

    private fun load(host: Context, flags: Int): Drawable? = runCatching {
        val moduleCtx = host.createPackageContext(Targets.MODULE_PACKAGE, flags)
        val id = moduleCtx.resources.getIdentifier(
            "ic_redial_ios", "drawable", Targets.MODULE_PACKAGE
        )
        if (id == 0) return@runCatching null
        moduleCtx.getDrawable(id)
    }.onFailure { Log.e("load module dial icon failed", it) }.getOrNull()
}
