package com.example.flipclock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * 极简、现代、零冗余依赖的纯 Kotlin 原生 Android 拟物翻页时钟
 *
 * 包含：
 * 1. 拟物 3D 折叠 Canvas 翻页时钟组件 (FlipCardView)
 * 2. 纯轻量万年历四柱（干支历：年柱、月柱、日柱、时柱）自研天文学节气推算算法
 * 3. 专注番茄钟（25分钟经典倒计时、启动/暂停/重置、倒计时触觉震动反馈）
 * 4. 三大精美拟物主题（深色复古、宣纸古韵、黑金赛博），点击空白处无缝轮换
 * 5. 全屏沉浸式无边框、常亮防休眠 (FLAG_KEEP_SCREEN_ON) 与横竖屏自适应
 */
class MainActivity : AppCompatActivity() {

    // 当前主题模式
    private var currentThemeIndex = 0
    private val themes = ThemeMode.values()
    val currentTheme: ThemeMode get() = themes[currentThemeIndex]

    // 运行模式：正常时钟 或 专注番茄钟
    private var isPomodoroMode = false
    private var pomodoroRemainingSeconds = 25 * 60
    private var isPomodoroRunning = false

    // 翻牌卡片引用
    private lateinit var cardHour: FlipCardView
    private lateinit var cardColon1: ColonView
    private lateinit var cardMinute: FlipCardView
    private lateinit var cardColon2: ColonView
    private lateinit var cardSecond: FlipCardView

    // 界面文本控件
    private lateinit var tvFourPillars: TextView
    private lateinit var tvGregorianDate: TextView
    private lateinit var tvThemeToast: TextView
    private lateinit var tvBottomHint: TextView
    private lateinit var btnModeSwitch: ModeSwitchButton
    private lateinit var pomodoroControlLayout: LinearLayout
    private lateinit var btnPomodoroToggle: PillButton
    private lateinit var btnPomodoroReset: PillButton
    private lateinit var rootContainer: FrameLayout

    // 主定时循环 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            onTick()
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 全屏沉浸模式
        hideSystemUI()

        // 3. 构建全自研 UI 视图层
        setupUI()

        // 4. 首次加载应用主题与数据
        applyTheme(currentTheme, showToast = false)
        updateClockData(animate = false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mainHandler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(tickRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    /**
     * 自动隐藏状态栏与导航栏，边缘滑动仅临时显现
     */
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 构建无 XML 依赖的极简轻快 UI 布局
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val density = resources.displayMetrics.density

        rootContainer = object : FrameLayout(this) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    currentTheme.bgGradStart, currentTheme.bgGradEnd,
                    Shader.TileMode.CLAMP
                )
                bgPaint.shader = shader
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            }
        }.apply {
            setWillNotDraw(false)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 轻触屏幕空白处切换配色主题
        rootContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                switchNextTheme()
            }
            true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部四柱天干地支排盘
        tvFourPillars = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        contentLayout.addView(tvFourPillars)

        // 顶部公历日期与星期副标题
        tvGregorianDate = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, 0, 0, (28 * density).toInt())
        }
        contentLayout.addView(tvGregorianDate)

        // 翻页时钟卡片横向容器
        val clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        cardHour = FlipCardView(this)
        cardColon1 = ColonView(this)
        cardMinute = FlipCardView(this)
        cardColon2 = ColonView(this)
        cardSecond = FlipCardView(this)

        clockRow.addView(cardHour)
        clockRow.addView(cardColon1)
        clockRow.addView(cardMinute)
        clockRow.addView(cardColon2)
        clockRow.addView(cardSecond)
        contentLayout.addView(clockRow)

        // 番茄钟控制按钮区（开始 / 暂停 / 重置）
        pomodoroControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (24 * density).toInt(), 0, 0)
        }

        btnPomodoroToggle = PillButton(this, "开始").apply {
            setOnClickListener { togglePomodoro() }
        }
        btnPomodoroReset = PillButton(this, "重置").apply {
            setOnClickListener { resetPomodoro() }
        }

        pomodoroControlLayout.addView(btnPomodoroToggle)
        pomodoroControlLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), 1)
        })
        pomodoroControlLayout.addView(btnPomodoroReset)
        contentLayout.addView(pomodoroControlLayout)

        // 底部温和操作引导文案
        tvBottomHint = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.55f
            letterSpacing = 0.05f
            text = "轻触屏幕任意空白处切换配色主题"
            setPadding(0, (28 * density).toInt(), 0, 0)
        }
        contentLayout.addView(tvBottomHint)

        rootContainer.addView(contentLayout)

        // 右上角模式切换按钮（时钟 / 番茄钟）
        btnModeSwitch = ModeSwitchButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                (44 * density).toInt(),
                (44 * density).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (20 * density).toInt()
                rightMargin = (20 * density).toInt()
            }
            setOnClickListener { toggleClockMode() }
        }
        rootContainer.addView(btnModeSwitch)

        // 主题切换 HUD 气泡提示
        tvThemeToast = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (20 * density).toInt()
            }
        }
        rootContainer.addView(tvThemeToast)

        setContentView(rootContainer)
    }

    /**
     * 每秒触发的时钟与倒计时刷新逻辑
     */
    private fun onTick() {
        if (!isPomodoroMode) {
            updateClockData(animate = true)
        } else {
            if (isPomodoroRunning) {
                if (pomodoroRemainingSeconds > 0) {
                    pomodoroRemainingSeconds--
                    updatePomodoroDisplay(animate = true)
                    if (pomodoroRemainingSeconds == 0) {
                        onPomodoroFinished()
                    }
                }
            }
        }
    }

    /**
     * 刷新万年历四柱及常规时钟数据
     */
    private fun updateClockData(animate: Boolean) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        // 1. 推算干支历四柱
        val pillars = GanzhiEngine.calculateFourPillars(cal)
        tvFourPillars.text = pillars.formattedText

        // 2. 公历年月日与农历干支生肖说明
        val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE)
        tvGregorianDate.text = "${sdf.format(cal.time)} 【${pillars.zodiac}年】"

        // 3. 翻折数字刷新
        val hStr = String.format(Locale.US, "%02d", hour)
        val mStr = String.format(Locale.US, "%02d", minute)
        val sStr = String.format(Locale.US, "%02d", second)

        cardHour.setValue(hStr, animate)
        cardMinute.setValue(mStr, animate)
        cardSecond.setValue(sStr, animate)
    }

    /**
     * 刷新番茄钟倒计时数据
     */
    private fun updatePomodoroDisplay(animate: Boolean) {
        val min = pomodoroRemainingSeconds / 60
        val sec = pomodoroRemainingSeconds % 60
        val mStr = String.format(Locale.US, "%02d", min)
        val sStr = String.format(Locale.US, "%02d", sec)

        cardMinute.setValue(mStr, animate)
        cardSecond.setValue(sStr, animate)
    }

    /**
     * 切换时钟模式与专注番茄钟模式
     */
    private fun toggleClockMode() {
        isPomodoroMode = !isPomodoroMode
        btnModeSwitch.setPomodoroActive(isPomodoroMode)

        if (isPomodoroMode) {
            // 切换为番茄钟：隐藏小时卡片，仅保留 [分:秒]
            cardHour.visibility = View.GONE
            cardColon1.visibility = View.GONE
            cardColon2.visibility = View.VISIBLE
            cardMinute.visibility = View.VISIBLE
            cardSecond.visibility = View.VISIBLE

            pomodoroControlLayout.visibility = View.VISIBLE
            tvFourPillars.text = "· 专注番茄钟 ·"
            tvGregorianDate.text = "25 MINUTES FOCUS TIMER"
            tvBottomHint.text = "沉浸心流，专注当下"

            updatePomodoroDisplay(animate = false)
        } else {
            // 恢复标准四柱时钟模式
            isPomodoroRunning = false
            btnPomodoroToggle.setButtonText("开始")
            cardHour.visibility = View.VISIBLE
            cardColon1.visibility = View.VISIBLE
            cardColon2.visibility = View.VISIBLE
            pomodoroControlLayout.visibility = View.GONE
            tvBottomHint.text = "轻触屏幕任意空白处切换配色主题"

            updateClockData(animate = false)
        }
    }

    /**
     * 番茄钟开始 / 暂停
     */
    private fun togglePomodoro() {
        isPomodoroRunning = !isPomodoroRunning
        btnPomodoroToggle.setButtonText(if (isPomodoroRunning) "暂停" else "开始")
        tvBottomHint.text = if (isPomodoroRunning) "专注进行中..." else "已暂停，点击继续"
    }

    /**
     * 番茄钟重置回 25 分钟
     */
    private fun resetPomodoro() {
        isPomodoroRunning = false
        pomodoroRemainingSeconds = 25 * 60
        btnPomodoroToggle.setButtonText("开始")
        tvBottomHint.text = "已重置为 25 分钟专注"
        updatePomodoroDisplay(animate = false)
    }

    /**
     * 番茄钟倒计时结束提醒
     */
    private fun onPomodoroFinished() {
        isPomodoroRunning = false
        btnPomodoroToggle.setButtonText("再来一次")
        tvBottomHint.text = "🎉 专注达成！休息片刻，劳逸结合"

        // 触觉震动提示
        vibrateCompletion()
    }

    private fun vibrateCompletion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                v?.vibrate(longArrayOf(0, 400, 200, 400), -1)
            }
        } catch (_: Exception) {}
    }

    /**
     * 循环切换下一个主题配色
     */
    private fun switchNextTheme() {
        currentThemeIndex = (currentThemeIndex + 1) % themes.size
        applyTheme(currentTheme, showToast = true)
    }

    /**
     * 应用色彩空间并刷新各组件
     */
    private fun applyTheme(theme: ThemeMode, showToast: Boolean) {
        rootContainer.invalidate()

        tvFourPillars.setTextColor(theme.textColor)
        tvGregorianDate.setTextColor(theme.subTextColor)
        tvBottomHint.setTextColor(theme.subTextColor)

        cardHour.applyTheme(theme)
        cardColon1.applyTheme(theme)
        cardMinute.applyTheme(theme)
        cardColon2.applyTheme(theme)
        cardSecond.applyTheme(theme)

        btnModeSwitch.applyTheme(theme)
        btnPomodoroToggle.applyTheme(theme)
        btnPomodoroReset.applyTheme(theme)

        if (showToast) {
            tvThemeToast.text = "主题：${theme.title}"
            tvThemeToast.setTextColor(theme.textColor)
            tvThemeToast.setBackgroundColor(theme.cardTopBg)
            tvThemeToast.animate().alpha(1f).setDuration(250).withEndAction {
                tvThemeToast.animate().alpha(0f).setStartDelay(1200).setDuration(400).start()
            }.start()
        }
    }
}

// =========================================================================================
// 1. 拟物 3D 折叠翻页卡片组件 (Canvas + Camera 矩阵旋转 + 动效遮光)
// =========================================================================================

class FlipCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var oldValue: String = "00"
    private var newValue: String = "00"
    private var flipProgress: Float = 1.0f

    private var currentTheme: ThemeMode = ThemeMode.DARK_VINTAGE

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val camera = Camera()
    private val transformMatrix = Matrix()
    private val cardRect = RectF()

    private var animator: ValueAnimator? = null

    init {
        // 允许直接触碰触发重绘
        setWillNotDraw(false)
    }

    fun applyTheme(theme: ThemeMode) {
        this.currentTheme = theme
        invalidate()
    }

    fun setValue(value: String, animate: Boolean) {
        if (value == newValue && flipProgress >= 1f) return

        if (!animate) {
            animator?.cancel()
            oldValue = value
            newValue = value
            flipProgress = 1.0f
            invalidate()
            return
        }

        oldValue = newValue
        newValue = value
        flipProgress = 0.0f

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                flipProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        // 推荐卡片标准尺寸（自适应不同屏幕）
        val defWidth = (100 * density).toInt()
        val defHeight = (130 * density).toInt()
        setMeasuredDimension(
            resolveSize(defWidth, widthMeasureSpec),
            resolveSize(defHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = h * 0.08f
        val centerY = h / 2f
        val slitHalf = 1.2f * resources.displayMetrics.density

        // 设置文本字号自适应
        textPaint.textSize = h * 0.62f
        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f

        // 相机镜头纵深距（避免透视过度畸变）
        val cameraDistance = -12f * resources.displayMetrics.density

        // -------------------------------------------------------------
        // 底层静态底板绘制
        // -------------------------------------------------------------

        // 1. 底层上半部：翻折后呈现的新数字 (New Value)
        canvas.save()
        canvas.clipRect(0f, 0f, w, centerY - slitHalf)
        cardPaint.color = currentTheme.cardTopBg
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

        textPaint.color = currentTheme.textColor
        canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
        canvas.restore()

        // 2. 底层下半部：尚未被覆盖的旧数字 (Old Value)
        canvas.save()
        canvas.clipRect(0f, centerY + slitHalf, w, h)
        cardPaint.color = currentTheme.cardBottomBg
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

        textPaint.color = currentTheme.textColor
        canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

        // 随翻牌落下，投射在下半底板上的渐进物理阴影
        if (flipProgress < 0.5f) {
            val shadowAlpha = (flipProgress * 2f * 110).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
        }
        canvas.restore()

        // -------------------------------------------------------------
        // 顶层 3D 动态旋转叶片绘制
        // -------------------------------------------------------------

        if (flipProgress < 0.5f) {
            // 前半程：旧上半叶从 0° 向下折叠翻转至 90°
            val degree = flipProgress * 180f // 0° -> 90°

            canvas.save()
            camera.save()
            camera.setLocation(0f, 0f, cameraDistance)
            camera.rotateX(-degree)
            camera.getMatrix(transformMatrix)
            camera.restore()

            transformMatrix.preTranslate(-w / 2f, -centerY)
            transformMatrix.postTranslate(w / 2f, centerY)
            canvas.concat(transformMatrix)

            canvas.clipRect(0f, 0f, w, centerY - slitHalf)
            cardPaint.color = currentTheme.cardTopBg
            cardRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

            textPaint.color = currentTheme.textColor
            canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

            // 倾斜背光遮罩阴影
            val shadowAlpha = ((degree / 90f) * 150).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, 0f, w, centerY - slitHalf, shadowPaint)

            canvas.restore()

        } else {
            // 后半程：新下半叶从 90° 翻转降落至平铺 0°
            val degree = 90f - (flipProgress - 0.5f) * 180f // 90° -> 0°

            canvas.save()
            camera.save()
            camera.setLocation(0f, 0f, cameraDistance)
            camera.rotateX(degree)
            camera.getMatrix(transformMatrix)
            camera.restore()

            transformMatrix.preTranslate(-w / 2f, -centerY)
            transformMatrix.postTranslate(w / 2f, centerY)
            canvas.concat(transformMatrix)

            canvas.clipRect(0f, centerY + slitHalf, w, h)
            cardPaint.color = currentTheme.cardBottomBg
            cardRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint)

            textPaint.color = currentTheme.textColor
            canvas.drawText(newValue, w / 2f, textBaseline, textPaint)

            // 翻平过程中阴影淡出
            val shadowAlpha = ((degree / 90f) * 150).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)

            canvas.restore()
        }

        // -------------------------------------------------------------
        // 拟物精修细节：中央折叠凹槽与左右转轴卡槽
        // -------------------------------------------------------------

        // 中央分界缝隙阴影线
        seamPaint.strokeWidth = slitHalf * 2f
        seamPaint.color = currentTheme.dividerColor
        canvas.drawLine(0f, centerY, w, centerY, seamPaint)

        // 左右侧边圆弧形固定机械凹槽 (Split-Flap Hinge Notch)
        val notchRadius = 3.5f * resources.displayMetrics.density
        cardPaint.color = currentTheme.bgGradStart
        canvas.drawCircle(0f, centerY, notchRadius, cardPaint)
        canvas.drawCircle(w, centerY, notchRadius, cardPaint)
    }
}

// =========================================================================================
// 2. 时钟双点冒号分隔符组件 (ColonView)
// =========================================================================================

class ColonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defWidth = (20 * density).toInt()
        val defHeight = (130 * density).toInt()
        setMeasuredDimension(
            resolveSize(defWidth, widthMeasureSpec),
            resolveSize(defHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 4f * resources.displayMetrics.density
        val offset = 18f * resources.displayMetrics.density

        dotPaint.color = theme.accentColor
        canvas.drawCircle(cx, cy - offset, radius, dotPaint)
        canvas.drawCircle(cx, cy + offset, radius, dotPaint)
    }
}

// =========================================================================================
// 3. 专注模式切换按钮与胶囊控制按钮
// =========================================================================================

class ModeSwitchButton(context: Context) : View(context) {
    private var isPomodoro = false
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setPomodoroActive(active: Boolean) {
        isPomodoro = active
        invalidate()
    }

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = width.coerceAtMost(height) * 0.45f

        // 背景圆环
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 2f * resources.displayMetrics.density
        iconPaint.color = theme.accentColor
        canvas.drawCircle(cx, cy, r, iconPaint)

        // 内部图标：番茄钟绘制倒三角/沙漏或钟表指针
        iconPaint.style = Paint.Style.FILL
        if (!isPomodoro) {
            // 时钟小表盘指针
            canvas.drawCircle(cx, cy, 2.5f * resources.displayMetrics.density, iconPaint)
            iconPaint.strokeWidth = 2.2f * resources.displayMetrics.density
            canvas.drawLine(cx, cy, cx, cy - r * 0.5f, iconPaint)
            canvas.drawLine(cx, cy, cx + r * 0.4f, cy, iconPaint)
        } else {
            // 番茄钟聚焦图标：实心聚焦点
            canvas.drawCircle(cx, cy, r * 0.42f, iconPaint)
        }
    }
}

class PillButton(context: Context, private var btnText: String) : TextView(context) {
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE

    init {
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val density = resources.displayMetrics.density
        setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
        text = btnText
    }

    fun setButtonText(text: String) {
        this.btnText = text
        this.text = text
    }

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        setTextColor(theme.bgGradStart)
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 30f * resources.displayMetrics.density
            setColor(theme.accentColor)
        }
        background = drawable
    }
}

// =========================================================================================
// 4. 万年历四柱（天干地支干支历）纯算天文学算法引擎
// =========================================================================================

object GanzhiEngine {

    val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    // 21 世纪 (2000-2099) 12 节气（月令节）寿星公定气常数系数
    // 1月:小寒, 2月:立春, 3月:惊蛰, 4月:清明, 5月:立夏, 6月:芒种,
    // 7月:小暑, 8月:立秋, 9月:白露, 10月:寒露, 11月:立冬, 12月:大雪
    private val SOLAR_TERMS_C = doubleArrayOf(
        5.4055, 3.87, 5.63, 4.81, 5.52, 5.678,
        7.108, 7.5, 7.646, 8.318, 7.438, 7.18
    )

    data class PillarsResult(
        val yearPillar: String,
        val monthPillar: String,
        val dayPillar: String,
        val hourPillar: String,
        val zodiac: String,
        val formattedText: String
    )

    /**
     * 计算指定公历年份与月份的“节”日期
     */
    private fun getSolarTermDay(year: Int, monthIndex0: Int): Int {
        val y = year % 100
        val c = SOLAR_TERMS_C[monthIndex0]
        return (y * 0.2422 + c - (y - 1) / 4).toInt()
    }

    /**
     * 实时推算四柱天干地支
     */
    fun calculateFourPillars(cal: Calendar): PillarsResult {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1 // 1..12
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // -------------------------------------------------------------
        // 1. 年柱推算：严格以立春（2月4日前后）为岁首交接
        // -------------------------------------------------------------
        val lichunDay = getSolarTermDay(year, 1) // 2月立春 (索引 1)
        val isBeforeLichun = (month < 2) || (month == 2 && day < lichunDay)
        val solarYear = if (isBeforeLichun) year - 1 else year

        val yearGzIdx = ((solarYear - 4) % 60 + 60) % 60
        val yearStemIdx = yearGzIdx % 10
        val yearBranchIdx = yearGzIdx % 12
        val yearPillar = "${TIAN_GAN[yearStemIdx]}${DI_ZHI[yearBranchIdx]}年"
        val zodiac = ZODIAC[yearBranchIdx]

        // -------------------------------------------------------------
        // 2. 月柱推算：依据 12 节气交节，结合“五虎遁元”求月干
        // -------------------------------------------------------------
        var solarYearForMonth = year
        val monthIdxFromYin: Int // 以寅月(正月)为 0, 卯月为 1, ..., 丑月为 11

        if (month == 1) {
            val xiaohanDay = getSolarTermDay(year, 0)
            if (day < xiaohanDay) {
                solarYearForMonth = year - 1
                monthIdxFromYin = 10 // 属于上一年子月 (大雪至小寒)
            } else {
                solarYearForMonth = year - 1
                monthIdxFromYin = 11 // 属于上一年丑月 (小寒至立春)
            }
        } else if (month == 2) {
            if (day < lichunDay) {
                solarYearForMonth = year - 1
                monthIdxFromYin = 11 // 丑月
            } else {
                solarYearForMonth = year
                monthIdxFromYin = 0  // 寅月 (立春至惊蛰)
            }
        } else {
            // 3月至12月
            val termDay = getSolarTermDay(year, month - 1)
            if (day >= termDay) {
                solarYearForMonth = year
                monthIdxFromYin = month - 2
            } else {
                solarYearForMonth = year
                monthIdxFromYin = month - 3
            }
        }

        val solarYearStem = ((solarYearForMonth - 4) % 10 + 10) % 10
        // 五虎遁元口诀：甲己之年丙作首、乙庚之岁戊为头、丙辛必定寻庚起、丁壬壬位顺行流、若问戊癸何方发甲寅之上好追求
        val yinMonthStem = ((solarYearStem % 5) * 2 + 2) % 10
        val monthStemIdx = (yinMonthStem + monthIdxFromYin) % 10
        val monthBranchIdx = (2 + monthIdxFromYin) % 12
        val monthPillar = "${TIAN_GAN[monthStemIdx]}${DI_ZHI[monthBranchIdx]}月"

        // -------------------------------------------------------------
        // 3. 日柱推算：儒略日 (JDN) 连续甲子无间断轮转，子初（23:00）起新日
        // -------------------------------------------------------------
        var calYear = year
        var calMonth = month
        var calDay = day

        // 传统天文学子初换日（夜子时 23:00 起算翌日）
        if (hour >= 23) {
            val c = Calendar.getInstance().apply {
                set(year, month - 1, day)
                add(Calendar.DAY_OF_MONTH, 1)
            }
            calYear = c.get(Calendar.YEAR)
            calMonth = c.get(Calendar.MONTH) + 1
            calDay = c.get(Calendar.DAY_OF_MONTH)
        }

        // 高精度格里高利历通算 JDN
        val a = (14 - calMonth) / 12
        val y = calYear + 4800 - a
        val m = calMonth + 12 * a - 3
        val jdn = calDay + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045

        // 基准点锚定：2000-01-01 (JDN=2451545) 为戊午日 (索引 54)
        val dayGzIdx = (((jdn + 9) % 60) + 60) % 60
        val dayStemIdx = dayGzIdx % 10
        val dayBranchIdx = dayGzIdx % 12
        val dayPillar = "${TIAN_GAN[dayStemIdx]}${DI_ZHI[dayBranchIdx]}日"

        // -------------------------------------------------------------
        // 4. 时柱推算：五鼠遁元法（由日干起时柱）
        // -------------------------------------------------------------
        // 每 2 小时一个时辰：23-1 为子(0), 1-3 为丑(1), ... 21-23 为亥(11)
        val hourBranchIdx = ((hour + 1) / 2) % 12

        // 五鼠遁元：甲己还加甲、乙庚丙作初、丙辛从戊起、丁壬庚子居、戊癸何方发壬子是真途
        val hourStemStart = (dayStemIdx % 5) * 2
        val hourStemIdx = (hourStemStart + hourBranchIdx) % 10
        val hourPillar = "${TIAN_GAN[hourStemIdx]}${DI_ZHI[hourBranchIdx]}时"

        val formatted = "$yearPillar  $monthPillar  $dayPillar  $hourPillar"
        return PillarsResult(yearPillar, monthPillar, dayPillar, hourPillar, zodiac, formatted)
    }
}

// =========================================================================================
// 5. 三大拟物配色系统 (Theme Palettes)
// =========================================================================================

enum class ThemeMode(
    val title: String,
    val bgGradStart: Int,
    val bgGradEnd: Int,
    val cardTopBg: Int,
    val cardBottomBg: Int,
    val textColor: Int,
    val accentColor: Int,
    val subTextColor: Int,
    val dividerColor: Int
) {
    DARK_VINTAGE(
        title = "深色复古",
        bgGradStart = 0xFF141418.toInt(),
        bgGradEnd = 0xFF1C1D24.toInt(),
        cardTopBg = 0xFF2A2C37.toInt(),
        cardBottomBg = 0xFF22242D.toInt(),
        textColor = 0xFFF0EBD8.toInt(), // 暖象牙白
        accentColor = 0xFFD4AF37.toInt(), // 经典黄铜色
        subTextColor = 0xFFA09C91.toInt(),
        dividerColor = 0xFF121317.toInt()
    ),
    RICE_PAPER(
        title = "宣纸古韵",
        bgGradStart = 0xFFF6F1E5.toInt(),
        bgGradEnd = 0xFFEDE4D1.toInt(),
        cardTopBg = 0xFFFAF7F0.toInt(),
        cardBottomBg = 0xFFE8DFCE.toInt(),
        textColor = 0xFF242321.toInt(), // 松烟徽墨
        accentColor = 0xFFBA3636.toInt(), // 古法朱砂红
        subTextColor = 0xFF7C7265.toInt(),
        dividerColor = 0xFFD8CEBD.toInt()
    ),
    BLACK_GOLD_CYBER(
        title = "黑金赛博",
        bgGradStart = 0xFF060608.toInt(),
        bgGradEnd = 0xFF0E0E14.toInt(),
        cardTopBg = 0xFF1A1A24.toInt(),
        cardBottomBg = 0xFF13131A.toInt(),
        textColor = 0xFFFFD700.toInt(), // 霓虹亮金
        accentColor = 0xFFFFAA00.toInt(), // 琥珀橙金
        subTextColor = 0xFFBF9634.toInt(),
        dividerColor = 0xFF09090D.toInt()
    )
}
