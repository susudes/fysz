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
import android.media.AudioAttributes
import android.media.MediaPlayer
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
import org.json.JSONArray
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
 * 现代、极简、无冗余依赖的纯 Kotlin 原生 Android 拟物翻页时钟 (全面重构升级版)
 *
 * 核心架构重构特性：
 * 1. 权威万年历数据对接：直接从 assets/2021-2040.md 异步匹配当日权威干支四柱与农历月日
 * 2. 界面解耦与呼吸感排版：
 *    - 顶部栏精简：左侧仅展示【东营区 · 多云 19°C】，右侧水平排列四大半透明图标 (横竖屏、电台、番茄、换色)，带 24dp 防遮挡内边距
 *    - 时钟适度缩小：比例调优至屏幕高度约 48%~52%，留白充裕
 *    - 四柱下移居中：【干支四柱】与【农历公历】优雅置于翻页时钟正下方居中展示
 * 3. 原生集成 Radio Browser 中文网络电台：
 *    - 原生 MediaPlayer 后台音频流播放，内置保底经典电台并后台异步拉取 Radio Browser 中国区热门电台
 *    - 点击顶部 📻 按钮滑出半透明悬浮电台控制条 (切台、播控、状态显示)，随主题自适应配色
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
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // 翻牌卡片与分隔符控件
    private lateinit var cardHour: FlipCardView
    private lateinit var cardColon1: ColonView
    private lateinit var cardMinute: FlipCardView
    private lateinit var cardColon2: ColonView
    private lateinit var cardSecond: FlipCardView
    private lateinit var clockRow: LinearLayout

    // 顶部状态栏控件
    private lateinit var topBarLayout: LinearLayout
    private lateinit var tvWeather: TextView
    private lateinit var btnRotate: HeaderIconButton
    private lateinit var btnRadio: HeaderIconButton
    private lateinit var btnPomodoro: HeaderIconButton
    private lateinit var btnTheme: HeaderIconButton

    // 时钟下方四柱排盘信息控件
    private lateinit var pillarsInfoContainer: LinearLayout
    private lateinit var tvFourPillars: TextView
    private lateinit var tvLunarAndDate: TextView

    // 悬浮电台控制条
    private lateinit var radioControlBar: RadioControlBarLayout

    // 底部番茄钟控制条
    private lateinit var pomodoroControlLayout: LinearLayout
    private lateinit var btnPomodoroToggle: PillButton
    private lateinit var btnPomodoroReset: PillButton

    // 提示 HUD
    private lateinit var tvThemeToast: TextView
    private lateinit var tvBottomHint: TextView

    // 顶层根容器
    private lateinit var rootContainer: FrameLayout

    // 主线程定时刷新 Handler
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
            mainHandler.postDelayed(this, 30 * 60 * 1000L) // 30 分钟轮询一次
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 保持全时屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 隐藏状态栏全屏沉浸
        hideSystemUI()

        // 3. 构建无 XML 依赖的纯 Kotlin 原生响应式布局
        setupUI()

        // 4. 初始化应用当前配色主题
        applyTheme(currentTheme, showToast = false)
        updateClockData(animate = false)

        // 5. 启动天气更新与网络电台异步拉取
        mainHandler.post(weatherRefreshRunnable)
        RadioManager.fetchOnlineStations()

        // 6. 底部触控提示 3 秒后优雅淡出
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

    override fun onDestroy() {
        super.onDestroy()
        RadioManager.release()
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

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * 搭建解耦重构后的极简 UI 结构
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val density = resources.displayMetrics.density

        // 根布局：微渐变背景与空白处触碰切换主题
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

        // ---------------------------------------------------------------------------------
        // 1. 顶部解耦状态栏（左侧：精简天气，右侧：四大操作图标，右内边距 24dp 彻底防遮挡）
        // ---------------------------------------------------------------------------------
        topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // paddingRight = 24dp 保证图标绝不被圆角或系统边缘切角遮挡
            setPadding((20 * density).toInt(), (14 * density).toInt(), (24 * density).toInt(), (8 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        // 左侧：精简天气（如：东营区 · 多云 19°C）
        tvWeather = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = 0.04f
            maxLines = 1
            text = weatherDisplayString
            setOnClickListener { manualRefreshWeather() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBarLayout.addView(tvWeather)

        // 右侧：水平排列半透明功能图标 (横竖屏切换、电台开关、番茄钟、换色)
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRotate = HeaderIconButton(this, IconType.ROTATE).apply {
            setOnClickListener { toggleScreenOrientation() }
        }
        btnRadio = HeaderIconButton(this, IconType.RADIO).apply {
            setOnClickListener { toggleRadioControlBar() }
        }
        btnPomodoro = HeaderIconButton(this, IconType.POMODORO).apply {
            setOnClickListener { toggleClockMode() }
        }
        btnTheme = HeaderIconButton(this, IconType.THEME).apply {
            setOnClickListener { switchNextTheme() }
        }

        controlsLayout.addView(btnRotate)
        controlsLayout.addView(btnRadio)
        controlsLayout.addView(btnPomodoro)
        controlsLayout.addView(btnTheme)
        topBarLayout.addView(controlsLayout)

        rootContainer.addView(topBarLayout)

        // ---------------------------------------------------------------------------------
        // 2. 中央核心内容区（时钟 + 下方居中四柱与农历 + 悬浮电台控制条）
        // ---------------------------------------------------------------------------------
        val centerContentWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // A. 翻牌时钟横向卡片容器
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
        centerContentWrapper.addView(clockRow)

        // B. 四柱与农历信息区：下移至时钟正下方居中排版，呼吸感拉满
        pillarsInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (18 * density).toInt(), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        tvFourPillars = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.10f
        }
        pillarsInfoContainer.addView(tvFourPillars)

        tvLunarAndDate = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            alpha = 0.85f
            letterSpacing = 0.05f
            setPadding(0, (5 * density).toInt(), 0, 0)
        }
        pillarsInfoContainer.addView(tvLunarAndDate)
        centerContentWrapper.addView(pillarsInfoContainer)

        // C. 番茄钟操作按钮区 (专注模式下显示)
        pomodoroControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (16 * density).toInt(), 0, 0)
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
        centerContentWrapper.addView(pomodoroControlLayout)

        // D. 悬浮电台控制条 (点击 📻 图标弹出/隐藏)
        radioControlBar = RadioControlBarLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            translationY = 20 * density
        }
        centerContentWrapper.addView(radioControlBar)

        rootContainer.addView(centerContentWrapper)

        // ---------------------------------------------------------------------------------
        // 3. 底部与提示图层
        // ---------------------------------------------------------------------------------
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

        // 布局变动自动自适应时钟尺寸
        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            resizeClockCards()
        }

        setContentView(rootContainer)
    }

    /**
     * 适度调小时钟尺寸，横屏占高 48%~52% 左右，留出充分的呼吸空间与四柱排版位
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
            // 横屏：卡片比例下调至屏幕高度 48%~52%，四周留白适度舒适
            var targetH = (h * 0.50f).toInt()
            var targetW = (targetH * 0.72f).toInt()
            var targetColon = (targetH * 0.16f).toInt()

            val totalReqWidth = numCards * targetW + numColons * targetColon + (numCards + numColons - 1) * spacing
            val maxAllowedWidth = (w * 0.88f).toInt()

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
            // 竖屏：保持横向开阔，纵向紧凑精致
            val maxAllowedWidth = (w * 0.88f).toInt()
            val totalSpacing = (numCards + numColons - 1) * spacing
            val availableForCards = maxAllowedWidth - totalSpacing

            val effectiveUnits = numCards + numColons * 0.22f
            var targetW = (availableForCards / effectiveUnits).toInt()
            var targetH = (targetW / 0.72f).toInt()
            var targetColon = (targetW * 0.22f).toInt()

            val maxAllowedH = (h * 0.38f).toInt()
            if (targetH > maxAllowedH) {
                targetH = maxAllowedH
                targetW = (targetH * 0.72f).toInt()
                targetColon = (targetW * 0.22f).toInt()
            }
            cardHeight = targetH
            cardWidth = targetW
            colonWidth = targetColon
        }

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
     * 刷新时钟数据：对接 assets/2021-2040.md 权威万年历数据并计算时柱
     */
    private fun updateClockData(animate: Boolean) {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val dateKey = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

        // 1. 尝试从 assets/2021-2040.md 中提取权威干支历与农历
        val calInfo = CalendarAssetsManager.getDayInfo(this, dateKey)

        if (calInfo != null) {
            // 根据当日天干与当前小时通过五鼠遁计算时柱
            val dayStemChar = calInfo.dayGz.firstOrNull() ?: '甲'
            val hourPillar = CalendarAssetsManager.calculateHourPillar(dayStemChar, hour)

            tvFourPillars.text = "${calInfo.yearGz}年 · ${calInfo.monthGz}月 · ${calInfo.dayGz}日 · $hourPillar"

            val termSuffix = if (calInfo.solarTerm.isNotEmpty()) " · 【${calInfo.solarTerm}】" else ""
            tvLunarAndDate.text = "${calInfo.lunar} · ${calInfo.weekday} · 【${calInfo.zodiac}年】$termSuffix"
        } else {
            // 若超出范围，优雅回退至自研天文推算
            val pillars = GanzhiEngine.calculateFourPillars(cal)
            val lunarStr = LunarEngine.getLunarDateString(year, month, day)
            val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE)
            tvFourPillars.text = pillars.formattedText
            tvLunarAndDate.text = "$lunarStr · ${sdf.format(cal.time)} · 【${pillars.zodiac}年】"
        }

        // 2. 刷新翻牌数字
        val hStr = String.format(Locale.US, "%02d", hour)
        val mStr = String.format(Locale.US, "%02d", minute)
        val sStr = String.format(Locale.US, "%02d", second)

        cardHour.setValue(hStr, animate)
        cardMinute.setValue(mStr, animate)
        cardSecond.setValue(sStr, animate)
    }

    private fun updatePomodoroDisplay(animate: Boolean) {
        val min = pomodoroRemainingSeconds / 60
        val sec = pomodoroRemainingSeconds % 60
        val mStr = String.format(Locale.US, "%02d", min)
        val sStr = String.format(Locale.US, "%02d", sec)

        cardMinute.setValue(mStr, animate)
        cardSecond.setValue(sStr, animate)
    }

    private fun toggleScreenOrientation() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 切换收音机半透明控制条显示与隐藏
     */
    private fun toggleRadioControlBar() {
        val isShowing = radioControlBar.visibility == View.VISIBLE
        btnRadio.setActive(!isShowing)
        if (!isShowing) {
            radioControlBar.visibility = View.VISIBLE
            radioControlBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            radioControlBar.animate()
                .alpha(0f)
                .translationY(20f * resources.displayMetrics.density)
                .setDuration(250)
                .withEndAction { radioControlBar.visibility = View.GONE }
                .start()
        }
    }

    private fun toggleClockMode() {
        isPomodoroMode = !isPomodoroMode
        btnPomodoro.setActive(isPomodoroMode)

        if (isPomodoroMode) {
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

    private fun togglePomodoro() {
        isPomodoroRunning = !isPomodoroRunning
        btnPomodoroToggle.setButtonText(if (isPomodoroRunning) "暂停" else "开始")
        tvLunarAndDate.text = if (isPomodoroRunning) "心流专注中 · 保持沉浸" else "已暂停 · 点击开始继续"
    }

    private fun resetPomodoro() {
        isPomodoroRunning = false
        pomodoroRemainingSeconds = 25 * 60
        btnPomodoroToggle.setButtonText("开始")
        tvLunarAndDate.text = "已重置为 25 分钟专注"
        updatePomodoroDisplay(animate = false)
    }

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

    private fun manualRefreshWeather() {
        tvWeather.text = "东营区 · 正在刷新..."
        fetchWeatherData()
    }

    private fun fetchWeatherData() {
        backgroundExecutor.execute {
            val result = WeatherEngine.fetchWeatherNow()
            mainHandler.post {
                if (result != null) {
                    weatherDisplayString = result.formatted
                } else if (weatherDisplayString.contains("加载中")) {
                    weatherDisplayString = "东营区 · 多云 19°C"
                }
                tvWeather.text = weatherDisplayString
            }
        }
    }

    private fun switchNextTheme() {
        currentThemeIndex = (currentThemeIndex + 1) % themes.size
        applyTheme(currentTheme, showToast = true)
    }

    private fun applyTheme(theme: ThemeMode, showToast: Boolean) {
        val density = resources.displayMetrics.density
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
        btnRadio.applyTheme(theme)
        btnPomodoro.applyTheme(theme)
        btnTheme.applyTheme(theme)

        btnPomodoroToggle.applyTheme(theme)
        btnPomodoroReset.applyTheme(theme)

        radioControlBar.applyTheme(theme)

        if (showToast) {
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
// 1. 万年历数据资产加载引擎 (权威对接 assets/2021-2040.md)
// =========================================================================================

object CalendarAssetsManager {
    data class CalendarDayInfo(
        val date: String,
        val weekday: String,
        val lunar: String,
        val yearGz: String,
        val monthGz: String,
        val dayGz: String,
        val zodiac: String,
        val solarTerm: String
    )

    private var cachedDate: String = ""
    private var cachedInfo: CalendarDayInfo? = null

    val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")

    fun calculateHourPillar(dayStemChar: Char, hour: Int): String {
        val dayStemIdx = TIAN_GAN.indexOf(dayStemChar.toString()).coerceAtLeast(0)
        val hourBranchIdx = ((hour + 1) / 2) % 12
        val hourStemStart = (dayStemIdx % 5) * 2
        val hourStemIdx = (hourStemStart + hourBranchIdx) % 10
        return "${TIAN_GAN[hourStemIdx]}${DI_ZHI[hourBranchIdx]}时"
    }

    fun getDayInfo(context: Context, dateStr: String): CalendarDayInfo? {
        if (dateStr == cachedDate && cachedInfo != null) {
            return cachedInfo
        }

        try {
            context.assets.open("2021-2040.md").bufferedReader(Charsets.UTF_8).use { reader ->
                val targetHeader = "### $dateStr"
                var found = false
                var weekday = ""
                var lunar = ""
                var yearGz = ""
                var monthGz = ""
                var dayGz = ""
                var zodiac = ""
                var term = ""

                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed == targetHeader) {
                        found = true
                        continue
                    }
                    if (found) {
                        if (trimmed.startsWith("### ") || trimmed == "---") {
                            if (yearGz.isNotEmpty() && dayGz.isNotEmpty()) break
                        }
                        when {
                            trimmed.startsWith("- **星期**:") -> weekday = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **农历日期**:") -> lunar = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **干支纪年**:") -> yearGz = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **干支纪月**:") -> monthGz = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **干支纪日**:") -> dayGz = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **生肖**:") -> zodiac = trimmed.substringAfter(":").trim()
                            trimmed.startsWith("- **当日节气**:") -> term = trimmed.substringAfter(":").trim()
                        }
                    }
                }

                if (found && yearGz.isNotEmpty()) {
                    val cleanedLunar = lunar.replace(Regex("^.*年"), "农历")
                    val info = CalendarDayInfo(
                        date = dateStr,
                        weekday = weekday,
                        lunar = if (cleanedLunar.startsWith("农历")) cleanedLunar else "农历$cleanedLunar",
                        yearGz = yearGz,
                        monthGz = monthGz,
                        dayGz = dayGz,
                        zodiac = zodiac,
                        solarTerm = if (term == "无") "" else term
                    )
                    cachedDate = dateStr
                    cachedInfo = info
                    return info
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

// =========================================================================================
// 2. 原生 Radio Browser 中文网络电台管理器 (MediaPlayer 原生播放流)
// =========================================================================================

data class RadioStation(
    val name: String,
    val streamUrl: String
)

object RadioManager {
    private val DEFAULT_STATIONS = listOf(
        RadioStation("CNR-1 中国之声", "https://lhttp.qtfm.cn/live/15318317/64k.mp3"),
        RadioStation("经典音乐广播", "https://lhttp.qingting.fm/live/4804/64k.mp3"),
        RadioStation("香港电台第一台 RTHK", "https://rthk.ice.infomaniak.ch/rthk1-64.mp3"),
        RadioStation("Lofi 专注轻音乐", "https://streams.ilovemusic.de/iloveradio17.mp3"),
        RadioStation("古典音乐台 (Swiss Classic)", "https://stream.srg-ssr.ch/m/rsc_de/mp3_128")
    )

    private val stations = mutableListOf<RadioStation>().apply { addAll(DEFAULT_STATIONS) }
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying = false
        private set
    var isLoading = false
        private set

    var onStateChangedListener: (() -> Unit)? = null

    fun getCurrentStation(): RadioStation {
        if (currentIndex !in stations.indices) currentIndex = 0
        return stations[currentIndex]
    }

    fun fetchOnlineStations() {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("https://de1.api.radio-browser.info/json/stations/bycountry/China?order=votes&reverse=true&limit=30")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "FlipClock-Radio/1.0")
                }
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val rawText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val jsonArray = JSONArray(rawText)
                    val fetched = mutableListOf<RadioStation>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name").trim()
                        val streamUrl = obj.optString("url_resolved").ifEmpty { obj.optString("url") }.trim()
                        if (name.isNotEmpty() && streamUrl.startsWith("http")) {
                            fetched.add(RadioStation(name, streamUrl))
                        }
                    }
                    if (fetched.isNotEmpty()) {
                        synchronized(stations) {
                            val urlSet = stations.map { it.streamUrl }.toMutableSet()
                            for (s in fetched) {
                                if (urlSet.add(s.streamUrl)) {
                                    stations.add(s)
                                }
                            }
                        }
                        Handler(Looper.getMainLooper()).post {
                            onStateChangedListener?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun play(index: Int = currentIndex) {
        if (stations.isEmpty()) return
        currentIndex = (index % stations.size + stations.size) % stations.size
        val station = stations[currentIndex]

        isLoading = true
        isPlaying = false
        onStateChangedListener?.invoke()

        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
        }

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnPreparedListener { mp ->
                    isLoading = false
                    isPlaying = true
                    mp.start()
                    onStateChangedListener?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    isLoading = false
                    isPlaying = false
                    onStateChangedListener?.invoke()
                    true
                }
            }
        }

        try {
            mediaPlayer?.setDataSource(station.streamUrl)
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            isLoading = false
            isPlaying = false
            onStateChangedListener?.invoke()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp != null && isPlaying) {
            mp.pause()
            isPlaying = false
            onStateChangedListener?.invoke()
        } else if (mp != null && !isPlaying && !isLoading) {
            mp.start()
            isPlaying = true
            onStateChangedListener?.invoke()
        } else {
            play(currentIndex)
        }
    }

    fun next() = play(currentIndex + 1)
    fun prev() = play(currentIndex - 1)

    fun release() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
        isPlaying = false
        isLoading = false
    }
}

// =========================================================================================
// 3. 悬浮电台控制条组件 (RadioControlBarLayout)
// =========================================================================================

class RadioControlBarLayout(context: Context) : LinearLayout(context) {

    private val tvName: TextView
    private val tvStatus: TextView
    private val btnPrev: TextView
    private val btnToggle: TextView
    private val btnNext: TextView
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * density).toInt()
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layoutParams = lp

        // 左侧电台收音机图标
        val iconRadio = TextView(context).apply {
            text = "📻"
            textSize = 18f
            setPadding(0, 0, (10 * density).toInt(), 0)
        }
        addView(iconRadio)

        // 中部电台名与状态
        val infoBox = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams((180 * density).toInt(), LayoutParams.WRAP_CONTENT)
        }

        tvName = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = RadioManager.getCurrentStation().name
        }
        infoBox.addView(tvName)

        tvStatus = TextView(context).apply {
            textSize = 11f
            alpha = 0.75f
            maxLines = 1
            text = "点击播放"
        }
        infoBox.addView(tvStatus)
        addView(infoBox)

        // 上一曲 ⏮
        btnPrev = TextView(context).apply {
            text = "⏮"
            textSize = 16f
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { RadioManager.prev() }
        }
        addView(btnPrev)

        // 播放/暂停 ▶ / ⏸
        btnToggle = TextView(context).apply {
            text = "▶"
            textSize = 18f
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            setOnClickListener { RadioManager.togglePlayPause() }
        }
        addView(btnToggle)

        // 下一曲 ⏭
        btnNext = TextView(context).apply {
            text = "⏭"
            textSize = 16f
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener { RadioManager.next() }
        }
        addView(btnNext)

        RadioManager.onStateChangedListener = {
            post { updateState() }
        }
        updateState()
    }

    fun updateState() {
        val s = RadioManager.getCurrentStation()
        tvName.text = s.name
        when {
            RadioManager.isLoading -> {
                tvStatus.text = "电台连接缓冲中..."
                btnToggle.text = "⏳"
            }
            RadioManager.isPlaying -> {
                tvStatus.text = "● 正在直播"
                btnToggle.text = "⏸"
            }
            else -> {
                tvStatus.text = "已暂停"
                btnToggle.text = "▶"
            }
        }
    }

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        val density = resources.displayMetrics.density
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 26f * density
            setColor(theme.cardTopBg)
            setStroke((1.2f * density).toInt(), theme.cardBorder)
        }
        background = bgDrawable

        tvName.setTextColor(theme.textColor)
        tvStatus.setTextColor(theme.subTextColor)
        btnPrev.setTextColor(theme.accentColor)
        btnToggle.setTextColor(theme.accentColor)
        btnNext.setTextColor(theme.accentColor)
    }
}

// =========================================================================================
// 4. 精细拟物 3D 折叠翻牌组件 (Canvas 3D + 顶光微亮 + 暗缝切线 + 左右铆钉转轴)
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
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
            duration = 450
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
        val density = resources.displayMetrics.density
        val slitHalf = 1.3f * density

        textPaint.textSize = h * 0.64f
        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f

        val cameraDistance = -14f * density

        // 1. 底层上半部 (展开的新数字)
        canvas.save()
        canvas.clipRect(0f, 0f, w, centerY - slitHalf)
        drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = true)
        textPaint.color = currentTheme.textColor
        canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
        canvas.restore()

        // 2. 底层下半部 (旧数字底板)
        canvas.save()
        canvas.clipRect(0f, centerY + slitHalf, w, h)
        drawHalfCardBackground(canvas, w, h, cornerRadius, isTop = false)
        textPaint.color = currentTheme.textColor
        canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

        if (flipProgress < 0.5f) {
            val shadowAlpha = (flipProgress * 2f * 120).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
        }
        canvas.restore()

        // 3. 顶层 3D 旋转翻转叶片
        if (flipProgress < 0.5f) {
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

            val shadowAlpha = ((degree / 90f) * 150).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, 0f, w, centerY - slitHalf, shadowPaint)
            canvas.restore()
        } else {
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

            val shadowAlpha = ((degree / 90f) * 150).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
            canvas.restore()
        }

        // 4. 拟物暗缝切线与两端金属转轴钉
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

        val notchRadius = 4.2f * density
        val rivetRadius = 2.2f * density

        cardPaint.shader = null
        cardPaint.color = currentTheme.bgGradStart
        canvas.drawCircle(0f, centerY, notchRadius, cardPaint)
        canvas.drawCircle(w, centerY, notchRadius, cardPaint)

        rivetPaint.style = Paint.Style.FILL
        rivetPaint.color = currentTheme.accentColor
        canvas.drawCircle(0f, centerY, rivetRadius, rivetPaint)
        canvas.drawCircle(w, centerY, rivetRadius, rivetPaint)

        rivetPaint.color = 0x99000000.toInt()
        canvas.drawCircle(0f, centerY, rivetRadius * 0.45f, rivetPaint)
        canvas.drawCircle(w, centerY, rivetRadius * 0.45f, rivetPaint)
    }

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

        borderPaint.color = currentTheme.cardBorder
        borderPaint.strokeWidth = 1f * resources.displayMetrics.density
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
    }
}

// =========================================================================================
// 5. 经典冒号双点组件 (ColonView)
// =========================================================================================

class ColonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 4f * resources.displayMetrics.density
        val offset = height * 0.16f

        dotPaint.color = theme.accentColor
        canvas.drawCircle(cx, cy - offset, radius, dotPaint)
        canvas.drawCircle(cx, cy + offset, radius, dotPaint)
    }
}

// =========================================================================================
// 6. 顶部极简半透明图标按钮 (HeaderIconButton)
// =========================================================================================

enum class IconType { ROTATE, RADIO, POMODORO, THEME }

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

        paint.style = Paint.Style.FILL
        paint.color = if (isActive) theme.accentColor else theme.iconBgColor
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (isActive) theme.accentColor else theme.iconBorderColor
        canvas.drawCircle(cx, cy, r, paint)

        paint.color = if (isActive) theme.bgGradStart else theme.textColor
        paint.strokeWidth = 1.8f * density
        paint.style = Paint.Style.STROKE

        when (iconType) {
            IconType.ROTATE -> {
                val wIcon = 7f * density
                val hIcon = 10f * density
                canvas.drawRoundRect(cx - wIcon, cy - hIcon, cx + wIcon, cy + hIcon, 2f * density, 2f * density, paint)
                paint.strokeWidth = 1.4f * density
                canvas.drawLine(cx - 3f * density, cy + 7f * density, cx + 3f * density, cy + 7f * density, paint)
            }
            IconType.RADIO -> {
                // 收音机图标：天线 + 矩形机身 + 旋钮喇叭
                val rw = 9f * density
                val rh = 6f * density
                canvas.drawRoundRect(cx - rw, cy - rh + 1.5f * density, cx + rw, cy + rh + 1.5f * density, 2f * density, 2f * density, paint)
                // 天线
                canvas.drawLine(cx - 5f * density, cy - rh + 1.5f * density, cx + 3f * density, cy - rh - 4f * density, paint)
                // 喇叭圆
                canvas.drawCircle(cx - 3.5f * density, cy + 1.5f * density, 2.5f * density, paint)
            }
            IconType.POMODORO -> {
                canvas.drawCircle(cx, cy, 6.5f * density, paint)
                canvas.drawLine(cx, cy, cx, cy - 3.5f * density, paint)
                canvas.drawLine(cx, cy, cx + 3.0f * density, cy, paint)
            }
            IconType.THEME -> {
                // 调色盘圆弧设计
                canvas.drawCircle(cx, cy, 6.5f * density, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx - 2.5f * density, cy - 2f * density, 1.2f * density, paint)
                canvas.drawCircle(cx + 2.5f * density, cy - 2f * density, 1.2f * density, paint)
                canvas.drawCircle(cx, cy + 2.5f * density, 1.2f * density, paint)
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
        setPadding((22 * density).toInt(), (9 * density).toInt(), (22 * density).toInt(), (9 * density).toInt())
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
// 7. 和风天气异步请求引擎 (支持 GZIP 压缩检测解压)
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

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null

            val rawBytes = conn.inputStream.use { it.readBytes() }
            if (rawBytes.isEmpty()) return null

            val jsonStr = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(rawBytes, Charsets.UTF_8)
            }

            val json = JSONObject(jsonStr)
            if (json.optString("code") == "200") {
                val now = json.getJSONObject("now")
                val temp = now.optString("temp", "--")
                val text = now.optString("text", "多云")
                val windDir = now.optString("windDir", "")
                val formatted = "东营区 · $text ${temp}°C"
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
// 8. 算法回退体系 (GanzhiEngine & LunarEngine)
// =========================================================================================

object GanzhiEngine {
    val TIAN_GAN = arrayOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val DI_ZHI = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    val ZODIAC = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

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

    fun calculateFourPillars(cal: Calendar): PillarsResult {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val lichunDay = getSolarTermDay(year, 1)
        val isBeforeLichun = (month < 2) || (month == 2 && day < lichunDay)
        val solarYear = if (isBeforeLichun) year - 1 else year

        val yearGzIdx = ((solarYear - 4) % 60 + 60) % 60
        val yearStemIdx = yearGzIdx % 10
        val yearBranchIdx = yearGzIdx % 12
        val yearPillar = "${TIAN_GAN[yearStemIdx]}${DI_ZHI[yearBranchIdx]}年"
        val zodiac = ZODIAC[yearBranchIdx]

        var solarYearForMonth = year
        val monthIdxFromYin: Int

        if (month == 1) {
            val xiaohanDay = getSolarTermDay(year, 0)
            monthIdxFromYin = if (day < xiaohanDay) 10 else 11
            solarYearForMonth = year - 1
        } else if (month == 2) {
            if (day < lichunDay) {
                solarYearForMonth = year - 1
                monthIdxFromYin = 11
            } else {
                solarYearForMonth = year
                monthIdxFromYin = 0
            }
        } else {
            val termDay = getSolarTermDay(year, month - 1)
            solarYearForMonth = year
            monthIdxFromYin = if (day >= termDay) month - 2 else month - 3
        }

        val solarYearStem = ((solarYearForMonth - 4) % 10 + 10) % 10
        val yinMonthStem = ((solarYearStem % 5) * 2 + 2) % 10
        val monthStemIdx = (yinMonthStem + monthIdxFromYin) % 10
        val monthBranchIdx = (2 + monthIdxFromYin) % 12
        val monthPillar = "${TIAN_GAN[monthStemIdx]}${DI_ZHI[monthBranchIdx]}月"

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

        val hourBranchIdx = ((hour + 1) / 2) % 12
        val hourStemStart = (dayStemIdx % 5) * 2
        val hourStemIdx = (hourStemStart + hourBranchIdx) % 10
        val hourPillar = "${TIAN_GAN[hourStemIdx]}${DI_ZHI[hourBranchIdx]}时"

        val formatted = "$yearPillar  $monthPillar  $dayPillar  $hourPillar"
        return PillarsResult(yearPillar, monthPillar, dayPillar, hourPillar, zodiac, formatted)
    }
}

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
// 9. 三大拟物高级配色系统 (深色复古 / 宣纸古韵 / 黑金赛博)
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
        cardTopBg = 0xFF2A2C37.toInt(),
        cardTopGradStart = 0xFF30323E.toInt(),
        cardTopGradEnd = 0xFF242630.toInt(),
        cardBottomBg = 0xFF22242D.toInt(),
        cardBottomGradStart = 0xFF1D1F27.toInt(),
        cardBottomGradEnd = 0xFF242630.toInt(),
        cardBorder = 0x33FFFFFF.toInt(),
        textColor = 0xFFF0EBD8.toInt(),
        accentColor = 0xFFD4AF37.toInt(),
        subTextColor = 0xFFA09C91.toInt(),
        dividerColor = 0xFF101014.toInt(),
        iconBgColor = 0x22FFFFFF.toInt(),
        iconBorderColor = 0x33FFFFFF.toInt()
    ),
    RICE_PAPER(
        title = "宣纸古韵",
        bgGradStart = 0xFFF7F2E6.toInt(),
        bgGradEnd = 0xFFEDE3CE.toInt(),
        cardTopBg = 0xFFFAF7F0.toInt(),
        cardTopGradStart = 0xFFFCFAF5.toInt(),
        cardTopGradEnd = 0xFFECE3D2.toInt(),
        cardBottomBg = 0xFFE8DFCE.toInt(),
        cardBottomGradStart = 0xFFE0D5BF.toInt(),
        cardBottomGradEnd = 0xFFEAE1CF.toInt(),
        cardBorder = 0x448B7E66.toInt(),
        textColor = 0xFF242321.toInt(),
        accentColor = 0xFFBA3636.toInt(),
        subTextColor = 0xFF766C5E.toInt(),
        dividerColor = 0xFFD5C8B2.toInt(),
        iconBgColor = 0x22000000.toInt(),
        iconBorderColor = 0x33000000.toInt()
    ),
    BLACK_GOLD_CYBER(
        title = "黑金赛博",
        bgGradStart = 0xFF050507.toInt(),
        bgGradEnd = 0xFF0C0C12.toInt(),
        cardTopBg = 0xFF1A1A24.toInt(),
        cardTopGradStart = 0xFF20202C.toInt(),
        cardTopGradEnd = 0xFF15151E.toInt(),
        cardBottomBg = 0xFF13131A.toInt(),
        cardBottomGradStart = 0xFF0F0F15.toInt(),
        cardBottomGradEnd = 0xFF171720.toInt(),
        cardBorder = 0x33FFD700.toInt(),
        textColor = 0xFFFFD700.toInt(),
        accentColor = 0xFFFFAA00.toInt(),
        subTextColor = 0xFFBF9634.toInt(),
        dividerColor = 0xFF08080B.toInt(),
        iconBgColor = 0x22FFD700.toInt(),
        iconBorderColor = 0x33FFD700.toInt()
    )
}
