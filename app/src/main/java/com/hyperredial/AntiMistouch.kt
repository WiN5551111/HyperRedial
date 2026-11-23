package com.hyperredial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs

/**
 * 防误触守卫 —— 回答「这一下该不该算数」。
 *
 * 为什么需要它：1.1.4 之前的模块只收窄了热区，也就是只回答「点在哪」。
 * 但手机放到桌上时，桌面、手掌、机身边缘的电容耦合照样能在圆圈里
 * 产生一次**完全正常的按下**，模块看到的是一次合法点击，于是照拨。
 * 热区再小也拦不住这个 —— 因为位置本来就是对的。
 *
 * 所以这里补三个「上下文」判断，任一命中就由按钮把这次手势吞掉（不拨、也不跳页面）：
 *
 *   1. 挂断冷却：通话刚结束的 [HANGUP_COOLDOWN_MS] 内不拨。
 *      针对「挂断 -> 顺手把手机放下」这一下，这是最常见的误触时刻。
 *      通话状态用 AudioManager.mode 判断（MODE_IN_CALL / MODE_IN_COMMUNICATION
 *      回到 MODE_NORMAL 即视为挂断），不需要任何权限。
 *
 *   2. 遮挡拦截：接近传感器被挡住时不拨。
 *      覆盖兜里、包里、屏幕朝下扣在桌上这些场景。
 *
 *   3. 幽灵触点：多指按下、或按压时长短于 [MIN_PRESS_MS] 的不算数。
 *      桌面耦合、手掌握持造成的误触往往是多指、且极短。
 *
 * 另留一个默认关闭的 [BLOCK_WHEN_FLAT]（平放桌面拦截），
 * 打开后「手机摊在桌上」时也不响应回拨 —— 代价是摊着就没法回拨，得先拿起来。
 *
 * 全部逻辑都做了异常兜底：传感器/音频服务取不到只会让对应那一条失效，
 * 不会影响正常的拨号、详情、长按菜单。
 */
internal class AntiMistouch private constructor(context: Context) {

    companion object {
        /** 挂断后这段时间内不拨号（毫秒）。嫌碍事就调小，设 0 等于关掉冷却。 */
        const val HANGUP_COOLDOWN_MS = 3_000L

        /** 按压短于这个时长按「蹭到」处理（毫秒）。设 0 等于关掉这条。 */
        const val MIN_PRESS_MS = 50L

        /** 接近传感器被遮挡（兜里 / 包里 / 屏幕朝下扣在桌上）时是否禁拨。 */
        const val BLOCK_WHEN_COVERED = true

        /**
         * 手机平放在桌面时是否禁拨。
         * 默认关：摊在桌上想回拨是正常需求；开了以后必须先把手机拿起来。
         */
        const val BLOCK_WHEN_FLAT = false

        /** 判定「平放」的重力偏差容差（m/s²）。 */
        private const val FLAT_TOLERANCE = 1.5f

        @Volatile
        private var instance: AntiMistouch? = null

        fun of(context: Context): AntiMistouch =
            instance ?: synchronized(this) {
                instance ?: AntiMistouch(context).also { instance = it }
            }
    }

    private val app: Context = context.applicationContext ?: context

    // ── 通话状态 ────────────────────────────────────────────
    /** 上一次看到的音频模式，用来识别「通话 -> 普通」这一次跳变。 */
    @Volatile private var lastMode = Int.MIN_VALUE

    /** 最近一次判定为「通话结束」的时刻（elapsedRealtime）。 */
    @Volatile private var lastCallEndAt = 0L

    private var audioWatched = false

    // ── 接近传感器 ──────────────────────────────────────────
    @Volatile private var covered = false
    @Volatile private var proximityWatched = false
    @Volatile private var proximityAvailable = true

    /**
     * 必须持有监听器的强引用。
     * 交给 SensorManager 的匿名对象如果没人引用，可能被 GC 掉，
     * 表现是"注册成功、日志正常，但系统里根本看不到这条连接"（实测踩过）。
     */
    private var proximityListener: SensorEventListener? = null

    // ── 加速度计（只在 BLOCK_WHEN_FLAT 打开时才注册）─────────
    @Volatile private var gravityZ = Float.NaN
    private var accelWatched = false

    // ── 日志限流 ────────────────────────────────────────────
    @Volatile private var lastBlockLogAt = 0L

    /**
     * 开始监听。幂等，可以每次绑定行时都调。
     * 必须在主线程调用（传感器注册要用调用线程的 Looper）。
     */
    fun start() {
        watchAudioMode()
        if (BLOCK_WHEN_COVERED) watchProximity()
        if (BLOCK_WHEN_FLAT) watchAccel()
    }

    // ==================================================================
    // 对外：这一下该不该拦
    // ==================================================================

    /**
     * 手势形状不对（多指之类）。返回 null 表示形状没问题。
     * 按压时长在 ACTION_UP 时单独判断，见 [isTooShort]。
     */
    fun ghostReason(e: MotionEvent): String? {
        if (e.pointerCount > 1) return "多指按下 pointerCount=${e.pointerCount}"
        return null
    }

    /** 按压时长是否短到不像是故意的（蹭一下）。 */
    fun isTooShort(durationMs: Long): Boolean =
        MIN_PRESS_MS > 0 && durationMs in 0 until MIN_PRESS_MS

    /**
     * 上下文不允许拨号时返回原因，允许时返回 null。
     * 只在 ACTION_DOWN 调一次即可（手势期间上下文不会突变）。
     */
    fun blockReason(): String? {
        sampleAudioMode()

        val now = SystemClock.elapsedRealtime()
        if (HANGUP_COOLDOWN_MS > 0 && lastCallEndAt > 0) {
            val elapsed = now - lastCallEndAt
            if (elapsed in 0 until HANGUP_COOLDOWN_MS) {
                return "刚挂断 ${elapsed}ms，冷却中"
            }
        }

        if (BLOCK_WHEN_COVERED && proximityAvailable && covered) {
            return "接近传感器被遮挡（兜里/包里/屏幕朝下）"
        }

        if (BLOCK_WHEN_FLAT && isFlat()) {
            return "手机平放桌面"
        }

        return null
    }

    /** 记一条「拦住了」的日志（限流，1 秒最多一条）。 */
    fun noteBlocked(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockLogAt < 1_000) return
        lastBlockLogAt = now
        Log.i("[anti-mistouch] 拦住一次回拨：$reason")
    }

    // ==================================================================
    // 通话状态
    // ==================================================================

    private fun watchAudioMode() {
        if (audioWatched) return
        audioWatched = true
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) {
                Log.e("[anti-mistouch] 取不到 AudioManager，挂断冷却不可用")
                return
            }
            lastMode = am.mode

            // API 31+ 有回调；低版本靠 blockReason() 里采样兜底，只是没那么即时。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.addOnModeChangedListener(
                    app.mainExecutor,
                    AudioManager.OnModeChangedListener { mode -> onAudioMode(mode) }
                )
                Log.i("[anti-mistouch] 挂断冷却已启用：${HANGUP_COOLDOWN_MS}ms（AudioManager 回调）")
            } else {
                Log.i("[anti-mistouch] 挂断冷却已启用：${HANGUP_COOLDOWN_MS}ms（采样模式）")
            }
        }.onFailure { Log.e("[anti-mistouch] 初始化通话监听失败", it) }
    }

    /** 只在触摸时兜底采样：低版本没有回调，靠它发现「通话 -> 普通」的跳变。 */
    private fun sampleAudioMode() {
        runCatching {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val m = am.mode
            if (m != lastMode) onAudioMode(m)
        }.onFailure { Log.e("[anti-mistouch] 采样通话状态失败", it) }
    }

    private fun isInCallMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

    private fun onAudioMode(mode: Int) {
        val prev = lastMode
        lastMode = mode
        if (mode == AudioManager.MODE_NORMAL && isInCallMode(prev)) {
            lastCallEndAt = SystemClock.elapsedRealtime()
            Log.i("[anti-mistouch] 通话结束 -> 开始 ${HANGUP_COOLDOWN_MS}ms 防误触冷却")
        }
    }

    // ==================================================================
    // 传感器
    // ==================================================================

    private fun sensorManager(): SensorManager? =
        app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private fun watchProximity() {
        if (proximityWatched) return
        proximityWatched = true
        runCatching {
            val sm = sensorManager()
            // 优先要非 wakeup 的那个：这台机器上 getDefaultSensor(type) 给的是 Wakeup 版，
            // 每来一次事件都会唤醒 AP。接近传感器虽然是 on-change（事件很少），
            // 但我们只是想知道"挡没挡住"，不需要唤醒能力，能省则省。
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)
                ?: sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (sm == null || sensor == null) {
                proximityAvailable = false
                Log.i("[anti-mistouch] 没有接近传感器，遮挡拦截不可用")
                return
            }
            val max = sensor.maximumRange
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    // 多数机型的接近传感器是 0/最大值 两种取值，小于最大值即视为被遮挡
                    val v = e.values.firstOrNull() ?: return
                    covered = v < max
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            proximityListener = listener
            // 返回值一定要看：false 表示系统没接这条注册（之前只打日志不看返回值，
            // 结果日志说"已启用"，实际 sensorservice 里根本没有这条连接）
            val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            if (ok) {
                Log.i("[anti-mistouch] 遮挡拦截已启用（proximity maxRange=$max）")
            } else {
                proximityAvailable = false
                Log.e("[anti-mistouch] registerListener 返回 false，遮挡拦截不可用")
            }
        }.onFailure {
            proximityAvailable = false
            Log.e("[anti-mistouch] 注册接近传感器失败", it)
        }
    }

    private fun watchAccel() {
        if (accelWatched) return
        accelWatched = true
        runCatching {
            val sm = sensorManager()
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm == null || sensor == null) {
                Log.i("[anti-mistouch] 没有加速度计，平放拦截不可用")
                return
            }
            sm.registerListener(object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    gravityZ = e.values.getOrNull(2) ?: Float.NaN
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("[anti-mistouch] 平放拦截已启用")
        }.onFailure { Log.e("[anti-mistouch] 注册加速度计失败", it) }
    }

    /** 手机是否平放（屏幕朝上或朝下）。拿在手里时 |z| 会明显小于 9.8。 */
    private fun isFlat(): Boolean {
        val z = gravityZ
        if (z.isNaN()) return false
        return abs(abs(z) - SensorManager.GRAVITY_EARTH) < FLAT_TOLERANCE
    }
}
