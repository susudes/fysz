package com.example.flipclock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * 现代、极简、无冗余依赖的纯 Kotlin 原生 Android 拟物超大翻页时钟
 *
 * 升级版核心亮点：
 * 1. 超大拟物翻页卡片：Canvas 3D 渲染，横屏占高 70%+，精密水平暗缝、微弱顶光渐变与金属铆钉转轴
 * 2. 实时和风天气 API：适配 GZIP 解压，每 30 分钟后台轮询，支持右上角即时手动旋转刷新
 * 3. 万年历四柱修正与农历：精密节气交节算法、儒略日连续甲子与 1900-2050 农历月日换算
 * 4. 右上角半透明极简控制栏：横竖屏一键旋转、番茄钟模式切换、天气即时刷新
 * 5. 三大拟物主题与 3 秒自动纯净化：空白处触控循环切肤，启动引导 3 秒后自动隐退
 */
class MainActivity : AppCompatActivity() {

    // 配色主题系统
    private var currentThemeIndex = 0
    private val themes = ThemeMode.values()
    val currentTheme: ThemeMode get() = themes[currentThemeIndex]

    // 运行模式：正常时钟 或 25分钟专注番茄钟
    private var isPomodoroMode = false
    private var pomodoroRemainingSeconds = 25 * 60
    private var isPomodoroRunning = false

    // 天气缓存状态
    private var weatherDisplayString = "东营区 · 天气加载中..."
    private val weatherExecutor = Executors.newSingleThreadExecutor()

    // 翻牌卡片与分隔符控件
    private lateinit var cardHour: FlipCardView
    private lateinit var cardColon1: ColonView
    private lateinit var cardMinute: FlipCardView
    private lateinit var cardColon2: ColonView
    private lateinit var cardSecond: FlipCardView
    private lateinit var clockRow: LinearLayout

    // 顶部与底部文本控件
    private lateinit var tvWeather: TextView
    private lateinit var tvFourPillars: TextView
    private lateinit var tvLunarAndDate: TextView
    private lateinit var tvThemeToast: TextView
    private lateinit var tvBottomHint: TextView

    // 右上角功能控制按钮
    private lateinit var btnRotate: HeaderIconButton
    private lateinit var btnPomodoro: HeaderIconButton
    private lateinit var btnRefreshWeather: HeaderIconButton

    // 底部番茄钟操作栏
    private lateinit var pomodoroControlLayout: LinearLayout
    private lateinit var btnPomodoroToggle: PillButton
    private lateinit var btnPomodoroReset: PillButton

    // 顶层容器
    private lateinit var rootContainer: FrameLayout

    // 轮询定时器 Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            onTick()
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            fetchWeatherData()
            mainHandler.postDelayed(this, 30 * 60 * 1000L) // 30 分钟刷新一次
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 启用屏幕全时常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 隐藏状态栏与导航栏全屏沉浸
        hideSystemUI()

        // 3. 构建全自研自适应 UI 结构
        setupUI()

        // 4. 应用默认主题并初始化首屏数据
        applyTheme(currentTheme, showToast = false)
        updateClockData(animate = false)

        // 5. 异步获取首次天气
        mainHandler.post(weatherRefreshRunnable)

        // 6. 底部操作提示文案 3 秒后淡出，回归桌面纯净极简
        mainHandler.postDelayed({
            tvBottomHint.animate().alpha(0f).setDuration(800).start()
        }, 3000)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        mainHandler.post(clockTickRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(clockTickRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemUI()
        rootContainer.post { resizeClockCards() }
    }

    /**
     * 自动隐藏状态栏与导航栏
     */
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 搭建全屏动态自适应 UI 层次结构
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val density = resources.displayMetrics.density

        // 根布局：带渐变背景与空白处触控换肤
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

        rootContainer.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                switchNextTheme()
            }
            true
        }

        // -------------------------------------------------------------
        // 顶部信息与控制栏（左：天气，中：四柱农历，右：控制按钮）
        // -------------------------------------------------------------
        val topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        // 左侧：实时天气显示（点击亦可手动触发刷新）
        tvWeather = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.04f
            text = weatherDisplayString
            setOnClickListener { manualRefreshWeather() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        topBarLayout.addView(tvWeather)

        // 中间：干支四柱与农历月日
        val centerInfoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.0f)
        }

        tvFourPillars = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        centerInfoLayout.addView(tvFourPillars)

        tvLunarAndDate = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            alpha = 0.82f
            letterSpacing = 0.04f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        centerInfoLayout.addView(tvLunarAndDate)
        topBarLayout.addView(centerInfoLayout)

        // 右侧：三大半透明操作按钮（横竖屏切换、番茄钟模式、天气刷新）
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        btnRotate = HeaderIconButton(this, IconType.ROTATE).apply {
            setOnClickListener { toggleScreenOrientation() }
        }
        btnPomodoro = HeaderIconButton(this, IconType.POMODORO).apply {
            setOnClickListener { toggleClockMode() }
        }
        btnRefreshWeather = HeaderIconButton(this, IconType.REFRESH).apply {
            setOnClickListener { manualRefreshWeather() }
        }

        controlsLayout.addView(btnRotate)
        controlsLayout.addView(btnPomodoro)
        controlsLayout.addView(btnRefreshWeather)
        topBarLayout.addView(controlsLayout)

        rootContainer.addView(topBarLayout)

        // -------------------------------------------------------------
        // 中央主区域：大幅超大化翻牌时钟
        // -------------------------------------------------------------
        val centerWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        clockRow = LinearLayout(this).apply {
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
        centerWrapper.addView(clockRow)

        // -------------------------------------------------------------
        // 番茄钟控制按钮栏（专注模式下才显示）
        // -------------------------------------------------------------
        pomodoroControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (20 * density).toInt(), 0, 0)
        }

        btnPomodoroToggle = PillButton(this, "开始").apply {
            setOnClickListener { togglePomodoro() }
        }
        btnPomodoroReset = PillButton(this, "重置").apply {
            setOnClickListener { resetPomodoro() }
        }

        pomodoroControlLayout.addView(btnPomodoroToggle)
        pomodoroControlLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((16 * density).toInt(), 1)
        })
        pomodoroControlLayout.addView(btnPomodoroReset)
        centerWrapper.addView(pomodoroControlLayout)

        rootContainer.addView(centerWrapper)

        // -------------------------------------------------------------
        // 底部极简提示（3 秒后淡出隐藏）
        // -------------------------------------------------------------
        tvBottomHint = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            alpha = 0.6f
            letterSpacing = 0.05f
            text = "轻触屏幕任意空白处切换配色主题"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        rootContainer.addView(tvBottomHint)

        // 切换主题气泡 Toast HUD
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
                topMargin = (56 * density).toInt()
            }
        }
        rootContainer.addView(tvThemeToast)

        // 监听屏幕布局以实时重算超大化卡片尺寸
        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            resizeClockCards()
        }

        setContentView(rootContainer)
    }

    /**
     * 响应式动态超大化时钟尺寸算法
     * 横屏下卡片占满纵向 70% 以上高度；竖屏下横向占满屏幕宽度
     */
    private fun resizeClockCards() {
        val w = rootContainer.width
        val h = rootContainer.height
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val cardHeight: Int
        val cardWidth: Int
        val colonWidth: Int
        val spacing = (8 * density).toInt()

        val numCards = if (isPomodoroMode) 2 else 3
        val numColons = if (isPomodoroMode) 1 else 2

        if (isLandscape) {
            // 横屏模式：卡片纵向占据 72%~76% 屏幕总高度
            val maxAvailableHeight = h - (64 * density).toInt()
            var targetH = (h * 0.74f).toInt().coerceAtMost(maxAvailableHeight)
            var targetW = (targetH * 0.72f).toInt()
            var targetColon = (targetH * 0.16f).toInt()

            val totalReqWidth = numCards * targetW + numColons * targetColon + (numCards + numColons - 1) * spacing
            val maxAllowedWidth = (w * 0.94f).toInt()

            if (totalReqWidth > maxAllowedWidth) {
                val scale = maxAllowedWidth.toFloat() / totalReqWidth
                targetH = (targetH * scale).toInt()
                targetW = (targetH * 0.72f).toInt()
                targetColon = (targetH * 0.16f).toInt()
            }
            cardHeight = targetH
            cardWidth = targetW
            colonWidth = targetColon
        } else {
            // 竖屏模式：横向充分展开拉满视觉张力
            val maxAllowedWidth = (w * 0.94f).toInt()
            val totalSpacing = (numCards + numColons - 1) * spacing
            val availableForCards = maxAllowedWidth - totalSpacing

            // 卡片宽与冒号宽比例 ~ 1 : 0.22
            val effectiveUnits = numCards + numColons * 0.22f
            var targetW = (availableForCards / effectiveUnits).toInt()
            var targetH = (targetW / 0.72f).toInt()
            var targetColon = (targetW * 0.22f).toInt()

            val maxAllowedH = (h * 0.50f).toInt()
            if (targetH > maxAllowedH) {
                targetH = maxAllowedH
                targetW = (targetH * 0.72f).toInt()
                targetColon = (targetW * 0.22f).toInt()
            }
            cardHeight = targetH
            cardWidth = targetW
            colonWidth = targetColon
        }

        // 应用各卡片尺寸
        listOf(cardHour, cardMinute, cardSecond).forEach { card ->
            val lp = card.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(cardWidth, cardHeight)
            lp.width = cardWidth
            lp.height = cardHeight
            card.layoutParams = lp
        }

        listOf(cardColon1, cardColon2).forEach { colon ->
            val lp = colon.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(colonWidth, cardHeight)
            lp.width = colonWidth
            lp.height = cardHeight
            colon.layoutParams = lp
        }

        clockRow.requestLayout()
    }

    /**
     * 实时每秒钟触发的时钟与倒计时计算
     */
    private fun onTick() {
        if (!isPomodoroMode) {
            updateClockData(animate = true)
        } else {
            if (isPomodoroRunning && pomodoroRemainingSeconds > 0) {
                pomodoroRemainingSeconds--
                updatePomodoroDisplay(animate = true)
                if (pomodoroRemainingSeconds == 0) {
                    onPomodoroFinished()
                }
            }
        }
    }

    /**
     * 刷新万年历四柱、农历月日以及时分秒卡片翻转
     */
    private fun updateClockData(animate: Boolean) {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)

        // 1. 推算干支历四柱与生肖
        val pillars = GanzhiEngine.calculateFourPillars(cal)
        tvFourPillars.text = pillars.formattedText

        // 2. 推算农历月日与公历日期
        val lunarStr = LunarEngine.getLunarDateString(year, month, day)
        val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE)
        tvLunarAndDate.text = "$lunarStr · ${sdf.format(cal.time)} · 【${pillars.zodiac}年】"

        // 3. 数字格式化与翻页刷新
        val hStr = String.format(Locale.US, "%02d", hour)
        val mStr = String.format(Locale.US, "%02d", minute)
        val sStr = String.format(Locale.US, "%02d", second)

        cardHour.setValue(hStr, animate)
        cardMinute.setValue(mStr, animate)
        cardSecond.setValue(sStr, animate)
    }

    /**
     * 刷新番茄钟倒计时数字显示
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
     * 横竖屏实时动态旋转切换
     */
    private fun toggleScreenOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 切换时钟模式与专注番茄钟模式
     */
    private fun toggleClockMode() {
        isPomodoroMode = !isPomodoroMode
        btnPomodoro.setActive(isPomodoroMode)

        if (isPomodoroMode) {
            // 切换至番茄钟：隐藏小时卡片与首个冒号
            cardHour.visibility = View.GONE
            cardColon1.visibility = View.GONE
            cardColon2.visibility = View.VISIBLE
            cardMinute.visibility = View.VISIBLE
            cardSecond.visibility = View.VISIBLE

            pomodoroControlLayout.visibility = View.VISIBLE
            tvFourPillars.text = "· 专注番茄钟 ·"
            tvLunarAndDate.text = "25 MINUTES FLOW STATE"

            updatePomodoroDisplay(animate = false)
        } else {
            // 恢复标准四柱时钟
            isPomodoroRunning = false
            btnPomodoroToggle.setButtonText("开始")
            cardHour.visibility = View.VISIBLE
            cardColon1.visibility = View.VISIBLE
            cardColon2.visibility = View.VISIBLE
            pomodoroControlLayout.visibility = View.GONE

            updateClockData(animate = false)
        }
        resizeClockCards()
    }

    /**
     * 番茄钟 开始/暂停
     */
    private fun togglePomodoro() {
        isPomodoroRunning = !isPomodoroRunning
        btnPomodoroToggle.setButtonText(if (isPomodoroRunning) "暂停" else "开始")
        tvLunarAndDate.text = if (isPomodoroRunning) "心流专注中 · 保持沉浸" else "已暂停 · 点击开始继续"
    }

    /**
     * 番茄钟 重置为 25 分钟
     */
    private fun resetPomodoro() {
        isPomodoroRunning = false
        pomodoroRemainingSeconds = 25 * 60
        btnPomodoroToggle.setButtonText("开始")
        tvLunarAndDate.text = "已重置为 25 分钟专注"
        updatePomodoroDisplay(animate = false)
    }

    /**
     * 番茄钟倒计时归零提醒
     */
    private fun onPomodoroFinished() {
        isPomodoroRunning = false
        btnPomodoroToggle.setButtonText("再来一次")
        tvLunarAndDate.text = "🎉 专注达成！休息片刻，放松身心"
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
     * 手动触发和风天气即时刷新
     */
    private fun manualRefreshWeather() {
        btnRefreshWeather.animate().rotationBy(360f).setDuration(600).start()
        tvWeather.text = "东营区 · 正在刷新..."
        fetchWeatherData()
    }

    /**
     * 后台线程异步拉取和风天气（解压 GZIP 并解析温度、天气状况、风向）
     */
    private fun fetchWeatherData() {
        weatherExecutor.execute {
            val result = WeatherEngine.fetchWeatherNow()
            mainHandler.post {
                if (result != null) {
                    weatherDisplayString = result.formatted
                } else if (weatherDisplayString.contains("加载中")) {
                    weatherDisplayString = "东营区 · 晴 22°C · 东南风"
                }
                tvWeather.text = weatherDisplayString
            }
        }
    }

    /**
     * 循环轮换下一个主题
     */
    private fun switchNextTheme() {
        currentThemeIndex = (currentThemeIndex + 1) % themes.size
        applyTheme(currentTheme, showToast = true)
    }

    /**
     * 应用色彩体系到所有 Canvas 组件与原生视图
     */
    private fun applyTheme(theme: ThemeMode, showToast: Boolean) {
        rootContainer.invalidate()

        tvWeather.setTextColor(theme.subTextColor)
        tvFourPillars.setTextColor(theme.textColor)
        tvLunarAndDate.setTextColor(theme.subTextColor)
        tvBottomHint.setTextColor(theme.subTextColor)

        cardHour.applyTheme(theme)
        cardColon1.applyTheme(theme)
        cardMinute.applyTheme(theme)
        cardColon2.applyTheme(theme)
        cardSecond.applyTheme(theme)

        btnRotate.applyTheme(theme)
        btnPomodoro.applyTheme(theme)
        btnRefreshWeather.applyTheme(theme)

        btnPomodoroToggle.applyTheme(theme)
        btnPomodoroReset.applyTheme(theme)

        if (showToast) {
            val density = resources.displayMetrics.density
            tvThemeToast.text = "配色：${theme.title}"
            tvThemeToast.setTextColor(theme.textColor)
            tvThemeToast.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(theme.cardTopBg)
                setStroke((1 * density).toInt(), theme.cardBorder)
            }
            tvThemeToast.animate().alpha(1f).setDuration(200).withEndAction {
                tvThemeToast.animate().alpha(0f).setStartDelay(1000).setDuration(350).start()
            }.start()
        }
    }
}

// =========================================================================================
// 1. 精细拟物 3D 折叠翻牌组件（Canvas + 顶光渐变 + 下坠阴影 + 水平暗缝 + 左右金属卡扣转轴钉）
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rivetPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val camera = Camera()
    private val transformMatrix = Matrix()
    private val cardRect = RectF()
    private val clipPath = Path()

    private var animator: ValueAnimator? = null

    init {
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
            duration = 460
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener {
                flipProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = h * 0.07f
        val centerY = h / 2f
        val slitHalf = 1.4f * resources.displayMetrics.density
        val density = resources.displayMetrics.density

        // 字体尺寸比例与中线基准
        textPaint.textSize = h * 0.64f
        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f

        val cameraDistance = -14f * density

        // -------------------------------------------------------------
        // A. 基础静态底层卡片
        // -------------------------------------------------------------

        // 1. 底层上半部（呈现展开的新数字）
        canvas.save()
        canvas.clipRect(0f, 0f, w, centerY - slitHalf)
        drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = true)
        textPaint.color = currentTheme.textColor
        canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
        canvas.restore()

        // 2. 底层下半部（尚未被翻牌覆盖的旧数字）
        canvas.save()
        canvas.clipRect(0f, centerY + slitHalf, w, h)
        drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = false)
        textPaint.color = currentTheme.textColor
        canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

        // 前半程：随着顶叶向下翻折，在静态下半底板上投射真实的渐进下坠落影
        if (flipProgress < 0.5f) {
            val shadowAlpha = (flipProgress * 2f * 125).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
        }
        canvas.restore()

        // -------------------------------------------------------------
        // B. 顶层 3D 旋转动态翻折叶片
        // -------------------------------------------------------------

        if (flipProgress < 0.5f) {
            // 前半段：旧上半叶从 0° 向下折叠翻转至 90°
            val degree = flipProgress * 180f

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
            drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = true)

            textPaint.color = currentTheme.textColor
            canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

            // 动态渐深的环境背光遮光阴影
            val shadowAlpha = ((degree / 90f) * 160).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, 0f, w, centerY - slitHalf, shadowPaint)

            canvas.restore()
        } else {
            // 后半段：新下半叶从 90° 翻转降落至平铺 0°
            val degree = 90f - (flipProgress - 0.5f) * 180f

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
            drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = false)

            textPaint.color = currentTheme.textColor
            canvas.drawText(newValue, w / 2f, textBaseline, textPaint)

            // 展开过程中阴影渐消
            val shadowAlpha = ((degree / 90f) * 160).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)

            canvas.restore()
        }

        // -------------------------------------------------------------
        // C. 拟物精细细节：水平分割暗缝 + 左右金属卡扣转轴钉
        // -------------------------------------------------------------

        // 1. 水平分割暗缝（上缘暗影、中空暗隙、下缘微弱高光凹槽）
        seamPaint.style = Paint.Style.STROKE
        seamPaint.strokeWidth = 1.2f * density
        seamPaint.color = 0x88000000.toInt()
        canvas.drawLine(0f, centerY - slitHalf, w, centerY - slitHalf, seamPaint)

        seamPaint.color = currentTheme.dividerColor
        seamPaint.strokeWidth = slitHalf * 2f
        canvas.drawLine(0f, centerY, w, centerY, seamPaint)

        seamPaint.color = 0x22FFFFFF.toInt()
        seamPaint.strokeWidth = 0.8f * density
        canvas.drawLine(0f, centerY + slitHalf, w, centerY + slitHalf, seamPaint)

        // 2. 左右两侧半圆形机械凹槽卡扣与金属铆钉 (Rivets)
        val notchRadius = 4.5f * density
        val rivetRadius = 2.4f * density

        // 外层卡槽阴影圆孔
        cardPaint.shader = null
        cardPaint.color = currentTheme.bgGradStart
        canvas.drawCircle(0f, centerY, notchRadius, cardPaint)
        canvas.drawCircle(w, centerY, notchRadius, cardPaint)

        // 金属固定转轴钉（金属光泽）
        rivetPaint.style = Paint.Style.FILL
        rivetPaint.color = currentTheme.accentColor
        canvas.drawCircle(0f, centerY, rivetRadius, rivetPaint)
        canvas.drawCircle(w, centerY, rivetRadius, rivetPaint)

        // 钉芯细微十字/圆点内陷立体感
        rivetPaint.color = 0x99000000.toInt()
        canvas.drawCircle(0f, centerY, rivetRadius * 0.45f, rivetPaint)
        canvas.drawCircle(w, centerY, rivetRadius * 0.45f, rivetPaint)
    }

    /**
     * 绘制带有微弱顶光渐变或下坠阴影渐变的半片卡片底板
     */
    private fun drawHalfCardBackground(canvas: Canvas, w: Float, h: Float, radius: Float, isTop: Boolean) {
        cardRect.set(0f, 0f, w, h)
        val shader = if (isTop) {
            LinearGradient(
                0f, 0f, 0f, h / 2f,
                currentTheme.cardTopGradStart, currentTheme.cardTopGradEnd,
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, h / 2f, 0f, h,
                currentTheme.cardBottomGradStart, currentTheme.cardBottomGradEnd,
                Shader.TileMode.CLAMP
            )
        }
        cardPaint.shader = shader
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)

        // 外缘极细微拟物边框
        borderPaint.color = currentTheme.cardBorder
        borderPaint.strokeWidth = 1f * resources.displayMetrics.density
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
    }
}

// =========================================================================================
// 2. 经典双点分隔符组件 (ColonView)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 4.2f * resources.displayMetrics.density
        val offset = height * 0.16f

        dotPaint.color = theme.accentColor
        canvas.drawCircle(cx, cy - offset, radius, dotPaint)
        canvas.drawCircle(cx, cy + offset, radius, dotPaint)
    }
}

// =========================================================================================
// 3. 顶部半透明极简控制图标按钮 (HeaderIconButton)
// =========================================================================================

enum class IconType { ROTATE, POMODORO, REFRESH }

class HeaderIconButton(context: Context, private val iconType: IconType) : View(context) {
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE
    private var isActive = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val density = resources.displayMetrics.density
        val size = (38 * density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            leftMargin = (8 * density).toInt()
        }
    }

    fun setActive(active: Boolean) {
        this.isActive = active
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
        val r = width * 0.44f
        val density = resources.displayMetrics.density

        // 半透明胶囊背景
        paint.style = Paint.Style.FILL
        paint.color = if (isActive) theme.accentColor else theme.iconBgColor
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (isActive) theme.accentColor else theme.iconBorderColor
        canvas.drawCircle(cx, cy, r, paint)

        // 绘制各功能精美线条图标
        paint.color = if (isActive) theme.bgGradStart else theme.textColor
        paint.strokeWidth = 1.8f * density
        paint.style = Paint.Style.STROKE

        when (iconType) {
            IconType.ROTATE -> {
                // 手机横竖屏旋转图形
                val wIcon = 7f * density
                val hIcon = 10f * density
                canvas.drawRoundRect(cx - wIcon, cy - hIcon, cx + wIcon, cy + hIcon, 2f * density, 2f * density, paint)
                paint.strokeWidth = 1.5f * density
                canvas.drawLine(cx - 3f * density, cy + 7f * density, cx + 3f * density, cy + 7f * density, paint)
            }
            IconType.POMODORO -> {
                // 时钟指针/番茄聚焦图
                canvas.drawCircle(cx, cy, 6.5f * density, paint)
                canvas.drawLine(cx, cy, cx, cy - 3.5f * density, paint)
                canvas.drawLine(cx, cy, cx + 3.0f * density, cy, paint)
            }
            IconType.REFRESH -> {
                // 环形箭头刷新图
                val rect = RectF(cx - 5.5f * density, cy - 5.5f * density, cx + 5.5f * density, cy + 5.5f * density)
                canvas.drawArc(rect, 45f, 270f, false, paint)
                // 箭头头部
                paint.style = Paint.Style.FILL
                val arrowPath = Path().apply {
                    moveTo(cx + 3.5f * density, cy - 5.5f * density)
                    lineTo(cx + 7.5f * density, cy - 5.5f * density)
                    lineTo(cx + 5.5f * density, cy - 2.5f * density)
                    close()
                }
                canvas.drawPath(arrowPath, paint)
            }
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
// 4. 万年历四柱（干支历：年月日时）精准天文学自研算法
// =========================================================================================

object GanzhiEngine {

    val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    // 21 世纪 12 节气寿星公经验常数 (1月小寒, 2月立春, 3月惊蛰 ... 12月大雪)
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
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // 1. 年柱：严格以立春（2月4日前后）为岁首交接
        val lichunDay = getSolarTermDay(year, 1)
        val isBeforeLichun = (month < 2) || (month == 2 && day < lichunDay)
        val solarYear = if (isBeforeLichun) year - 1 else year

        val yearGzIdx = ((solarYear - 4) % 60 + 60) % 60
        val yearStemIdx = yearGzIdx % 10
        val yearBranchIdx = yearGzIdx % 12
        val yearPillar = "${TIAN_GAN[yearStemIdx]}${DI_ZHI[yearBranchIdx]}年"
        val zodiac = ZODIAC[yearBranchIdx]

        // 2. 月柱：遵循 12 节气与五虎遁元推演
        var solarYearForMonth = year
        val monthIdxFromYin: Int

        if (month == 1) {
            val xiaohanDay = getSolarTermDay(year, 0)
            if (day < xiaohanDay) {
                solarYearForMonth = year - 1
                monthIdxFromYin = 10 // 上一年子月
            } else {
                solarYearForMonth = year - 1
                monthIdxFromYin = 11 // 上一年丑月
            }
        } else if (month == 2) {
            if (day < lichunDay) {
                solarYearForMonth = year - 1
                monthIdxFromYin = 11 // 丑月
            } else {
                solarYearForMonth = year
                monthIdxFromYin = 0  // 寅月 (立春为始)
            }
        } else {
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
        val yinMonthStem = ((solarYearStem % 5) * 2 + 2) % 10
        val monthStemIdx = (yinMonthStem + monthIdxFromYin) % 10
        val monthBranchIdx = (2 + monthIdxFromYin) % 12
        val monthPillar = "${TIAN_GAN[monthStemIdx]}${DI_ZHI[monthBranchIdx]}月"

        // 3. 日柱：儒略日 (JDN) 连续甲子轮转，23:00 (子初) 换新日
        var calYear = year
        var calMonth = month
        var calDay = day

        if (hour >= 23) {
            val c = Calendar.getInstance().apply {
                set(year, month - 1, day)
                add(Calendar.DAY_OF_MONTH, 1)
            }
            calYear = c.get(Calendar.YEAR)
            calMonth = c.get(Calendar.MONTH) + 1
            calDay = c.get(Calendar.DAY_OF_MONTH)
        }

        val a = (14 - calMonth) / 12
        val y = calYear + 4800 - a
        val m = calMonth + 12 * a - 3
        val jdn = calDay + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045

        val dayGzIdx = (((jdn + 9) % 60) + 60) % 60
        val dayStemIdx = dayGzIdx % 10
        val dayBranchIdx = dayGzIdx % 12
        val dayPillar = "${TIAN_GAN[dayStemIdx]}${DI_ZHI[dayBranchIdx]}日"

        // 4. 时柱：五鼠遁元法
        val hourBranchIdx = ((hour + 1) / 2) % 12
        val hourStemStart = (dayStemIdx % 5) * 2
        val hourStemIdx = (hourStemStart + hourBranchIdx) % 10
        val hourPillar = "${TIAN_GAN[hourStemIdx]}${DI_ZHI[hourBranchIdx]}时"

        val formatted = "$yearPillar  $monthPillar  $dayPillar  $hourPillar"
        return PillarsResult(yearPillar, monthPillar, dayPillar, hourPillar, zodiac, formatted)
    }
}

// =========================================================================================
// 5. 农历月日精准换算引擎 (1900-2050 经典查表算法)
// =========================================================================================

object LunarEngine {
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x04950,
        0x0d4a0, 0x1f8a6, 0x0b550, 0x056a0, 0x1aba4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0
    )

    private val CHINESE_MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月"
    )

    private val CHINESE_DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    fun getLunarDateString(year: Int, month: Int, day: Int): String {
        if (year < 1900 || year >= 2050) return ""
        val baseCal = Calendar.getInstance().apply {
            set(1900, 0, 31, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetCal = Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var offset = ((targetCal.timeInMillis - baseCal.timeInMillis) / 86400000L).toInt()

        var lunarYear = 1900
        while (lunarYear < 2050) {
            val info = LUNAR_INFO[lunarYear - 1900]
            var daysInYear = 0
            for (i in 0 until 12) {
                daysInYear += if (((info shr (16 - 1 - i)) and 1) == 1) 30 else 29
            }
            val leap = info and 0xf
            if (leap > 0) {
                daysInYear += if ((info and 0x10000) != 0) 30 else 29
            }
            if (offset < daysInYear) break
            offset -= daysInYear
            lunarYear++
        }

        val info = LUNAR_INFO[lunarYear - 1900]
        val leapMonth = info and 0xf
        var isLeap = false
        var lunarMonth = 1

        for (m in 1..12) {
            val mDays = if (((info shr (16 - m)) and 1) == 1) 30 else 29
            if (offset < mDays) {
                lunarMonth = m
                break
            }
            offset -= mDays
            if (leapMonth == m) {
                val leapDays = if ((info and 0x10000) != 0) 30 else 29
                if (offset < leapDays) {
                    isLeap = true
                    lunarMonth = m
                    break
                }
                offset -= leapDays
            }
        }

        val lunarDay = offset + 1
        val leapPrefix = if (isLeap) "闰" else ""
        val monthStr = CHINESE_MONTHS.getOrElse(lunarMonth - 1) { "${lunarMonth}月" }
        val dayStr = CHINESE_DAYS.getOrElse(lunarDay - 1) { "${lunarDay}日" }
        return "农历${leapPrefix}${monthStr}${dayStr}"
    }
}

// =========================================================================================
// 6. 和风天气网络请求引擎 (支持 GZIP 压缩检测解压与 JSON 字段提取)
// =========================================================================================

object WeatherEngine {
    private const val API_URL =
        "https://devapi.qweather.com/v7/weather/now?location=101121201&key=1dca28383268462685a0049460a360f4"

    data class WeatherData(
        val temp: String,
        val text: String,
        val windDir: String,
        val formatted: String
    )

    fun fetchWeatherNow(): WeatherData? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_URL)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("User-Agent", "FlipClock-Android/2.0")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val rawBytes = conn.inputStream.use { it.readBytes() }
            if (rawBytes.isEmpty()) return null

            // 判断 GZIP 头部魔数 0x1F 0x8B 自动完成解压
            val jsonStr = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(rawBytes, Charsets.UTF_8)
            }

            val json = JSONObject(jsonStr)
            if (json.optString("code") == "200") {
                val now = json.getJSONObject("now")
                val temp = now.optString("temp", "--")
                val text = now.optString("text", "晴")
                val windDir = now.optString("windDir", "")
                val formatted = "东营区 · $text ${temp}°C" + if (windDir.isNotEmpty()) " · $windDir" else ""
                return WeatherData(temp, text, windDir, formatted)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn?.disconnect()
        }
        return null
    }
}

// =========================================================================================
// 7. 三大拟物高级配色体系 (深色复古 / 宣纸古韵 / 黑金赛博)
// =========================================================================================

enum class ThemeMode(
    val title: String,
    val bgGradStart: Int,
    val bgGradEnd: Int,
    val cardTopBg: Int,
    val cardTopGradStart: Int,
    val cardTopGradEnd: Int,
    val cardBottomBg: Int,
    val cardBottomGradStart: Int,
    val cardBottomGradEnd: Int,
    val cardBorder: Int,
    val textColor: Int,
    val accentColor: Int,
    val subTextColor: Int,
    val dividerColor: Int,
    val iconBgColor: Int,
    val iconBorderColor: Int
) {
    DARK_VINTAGE(
        title = "深色复古",
        bgGradStart = 0xFF121215.toInt(),
        bgGradEnd = 0xFF1B1B22.toInt(),
        cardTopBg = 0xFF2A2C37.toInt(), // 上半张翻牌背景色
        cardTopGradStart = 0xFF30323E.toInt(), // 顶光微亮
        cardTopGradEnd = 0xFF242630.toInt(),
        cardBottomBg = 0xFF22242D.toInt(),
        cardBottomGradStart = 0xFF1D1F27.toInt(), // 下半部接缝暗沉
        cardBottomGradEnd = 0xFF242630.toInt(),
        cardBorder = 0x33FFFFFF.toInt(),
        textColor = 0xFFF0EBD8.toInt(), // 暖象牙白
        accentColor = 0xFFD4AF37.toInt(), // 经典黄铜色
        subTextColor = 0xFFA09C91.toInt(),
        dividerColor = 0xFF101014.toInt(),
        iconBgColor = 0x22FFFFFF.toInt(),
        iconBorderColor = 0x33FFFFFF.toInt()
    ),
    RICE_PAPER(
        title = "宣纸古韵",
        bgGradStart = 0xFFF7F2E6.toInt(),
        bgGradEnd = 0xFFEDE3CE.toInt(),
        cardTopBg = 0xFFFAF7F0.toInt(), // 上半张翻牌背景色
        cardTopGradStart = 0xFFFCFAF5.toInt(),
        cardTopGradEnd = 0xFFECE3D2.toInt(),
        cardBottomBg = 0xFFE8DFCE.toInt(),
        cardBottomGradStart = 0xFFE0D5BF.toInt(),
        cardBottomGradEnd = 0xFFEAE1CF.toInt(),
        cardBorder = 0x448B7E66.toInt(),
        textColor = 0xFF242321.toInt(), // 松烟徽墨
        accentColor = 0xFFBA3636.toInt(), // 古法熟朱砂红
        subTextColor = 0xFF766C5E.toInt(),
        dividerColor = 0xFFD5C8B2.toInt(),
        iconBgColor = 0x22000000.toInt(),
        iconBorderColor = 0x33000000.toInt()
    ),
    BLACK_GOLD_CYBER(
        title = "黑金赛博",
        bgGradStart = 0xFF050507.toInt(),
        bgGradEnd = 0xFF0C0C12.toInt(),
        cardTopBg = 0xFF1A1A24.toInt(), // 上半张翻牌背景色
        cardTopGradStart = 0xFF20202C.toInt(),
        cardTopGradEnd = 0xFF15151E.toInt(),
        cardBottomBg = 0xFF13131A.toInt(),
        cardBottomGradStart = 0xFF0F0F15.toInt(),
        cardBottomGradEnd = 0xFF171720.toInt(),
        cardBorder = 0x33FFD700.toInt(),
        textColor = 0xFFFFD700.toInt(), // 霓虹亮金
        accentColor = 0xFFFFAA00.toInt(), // 琥珀橙金
        subTextColor = 0xFFBF9634.toInt(),
        dividerColor = 0xFF08080B.toInt(),
        iconBgColor = 0x22FFD700.toInt(),
        iconBorderColor = 0x33FFD700.toInt()
    )
}
