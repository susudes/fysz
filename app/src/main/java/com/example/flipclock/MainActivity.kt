package com.example.flipclock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
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
import android.widget.ScrollView
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
 * 现代、极简、无冗余依赖的纯 Kotlin 原生 Android 液态毛玻璃（Glassmorphism）翻页时钟
 *
 * 视觉与功能核心改版：
 * 1. 彻底去除时间冒号：呈现纯粹纯净的 "XX  XX  XX" 现代时钟画风，水平留白匀称。
 * 2. 界面重心优雅下移：消除顶部拥挤与底部空旷，大幅提升空间呼吸感。
 * 3. 顶部天气升级：【东营区 · 多云 19°C】加粗放大至 16.5sp，清晰醒目。
 * 4. 全新液态毛玻璃风（Liquid Glass）：
 *    - 半透明通透毛玻璃底板（随主题智能透色）
 *    - 1dp 斜向渐变液态折射高光边框（模拟玻璃倒角聚光）
 *    - 18dp 现代化大圆角，极细水平微光接缝，移除生硬转轴钉
 *    - 纯白纯净现代高反差数字
 * 5. 电台频道列表毛玻璃弹窗（Radio Station Dialog）：
 *    - 支持即时唤出全量电台滑动列表，包含实时播放状态高亮、点击即切即播
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
    private var weatherDisplayString = "东营区 · 多云 19°C"
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // 翻牌卡片引用（已彻底移除 ColonView 冒号）
    private lateinit var cardHour: FlipCardView
    private lateinit var cardMinute: FlipCardView
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

    // 悬浮电台控制条与电台弹窗
    private lateinit var radioControlBar: RadioControlBarLayout
    private lateinit var radioStationDialogOverlay: FrameLayout
    private lateinit var radioDialogCard: LinearLayout
    private lateinit var radioDialogScrollView: ScrollView
    private lateinit var radioStationsContainer: LinearLayout
    private lateinit var tvDialogStationCount: TextView

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

        // 1. 全时屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. 隐藏状态栏全屏沉浸
        hideSystemUI()

        // 3. 构建液态毛玻璃响应式布局
        setupUI()

        // 4. 应用默认主题
        applyTheme(currentTheme, showToast = false)
        updateClockData(animate = false)

        // 5. 启动天气轮询与电台数据拉取
        mainHandler.post(weatherRefreshRunnable)
        RadioManager.fetchOnlineStations {
            mainHandler.post {
                updateRadioDialogList()
            }
        }

        // 6. 底部提示 3 秒后优雅淡出
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
     * 搭建视觉平衡与解耦重构的 UI 层次结构
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
                // 如果电台弹窗正在显示，点击背景关闭弹窗；否则切换主题
                if (radioStationDialogOverlay.visibility == View.VISIBLE) {
                    hideRadioStationDialog()
                } else {
                    switchNextTheme()
                }
            }
            true
        }

        // ---------------------------------------------------------------------------------
        // 1. 顶部解耦状态栏（左：加粗大字号天气，右：四大功能图标，右内边距 24dp 彻底防遮挡）
        // ---------------------------------------------------------------------------------
        topBarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((22 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        // 左侧：加粗、加大字号天气显示（16.5sp，清晰醒目）
        tvWeather = TextView(this).apply {
            textSize = 16.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.03f
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
        // 2. 中央核心内容区（重心微调下移，垂直居中增加 topMargin 留白平衡）
        // ---------------------------------------------------------------------------------
        val centerContentWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                // 整体重心微调下移 32dp，平衡上下留白
                topMargin = (32 * density).toInt()
            }
        }

        // A. 纯净无冒号翻牌时钟横向卡片容器 (XX   XX   XX)
        clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        cardHour = FlipCardView(this)
        cardMinute = FlipCardView(this)
        cardSecond = FlipCardView(this)

        clockRow.addView(cardHour)
        clockRow.addView(cardMinute)
        clockRow.addView(cardSecond)
        centerContentWrapper.addView(clockRow)

        // B. 四柱与农历信息区：下移至时钟正下方居中排版
        pillarsInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, (22 * density).toInt(), 0, 0)
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
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        pillarsInfoContainer.addView(tvLunarAndDate)
        centerContentWrapper.addView(pillarsInfoContainer)

        // C. 番茄钟操作按钮区 (专注模式下显示)
        pomodoroControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, (18 * density).toInt(), 0, 0)
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

        // D. 悬浮毛玻璃电台控制条 (点击 📻 图标弹出/隐藏)
        radioControlBar = RadioControlBarLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            translationY = 20 * density
            onOpenListClickListener = {
                showRadioStationDialog()
            }
        }
        centerContentWrapper.addView(radioControlBar)

        rootContainer.addView(centerContentWrapper)

        // ---------------------------------------------------------------------------------
        // 3. 底部提示 HUD
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
                bottomMargin = (18 * density).toInt()
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

        // ---------------------------------------------------------------------------------
        // 4. 电台频道列表毛玻璃弹窗图层 (Radio Station Dialog Overlay)
        // ---------------------------------------------------------------------------------
        setupRadioStationDialog()

        // 布局变动自动重算卡片尺寸
        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            resizeClockCards()
        }

        setContentView(rootContainer)
    }

    /**
     * 构建半透明毛玻璃电台列表弹窗视图
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRadioStationDialog() {
        val density = resources.displayMetrics.density

        radioStationDialogOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            setBackgroundColor(0x88000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    hideRadioStationDialog()
                }
                true
            }
        }

        radioDialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val cardW = (350 * density).toInt()
            val maxCardH = (460 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = (30 * density).toInt()
                bottomMargin = (30 * density).toInt()
            }
            setPadding((20 * density).toInt(), (18 * density).toInt(), (20 * density).toInt(), (18 * density).toInt())
            setOnTouchListener { _, _ -> true } // 消费卡片内部触碰
        }

        // 弹窗顶部栏：标题、频道计数与关闭按钮
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val tvDialogTitle = TextView(this).apply {
            text = "📻 广播电台频道"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleBox.addView(tvDialogTitle)

        tvDialogStationCount = TextView(this).apply {
            text = "中国电台 (已加载 ${RadioManager.getStationCount()} 个) · 点击即刻收听"
            textSize = 11.5f
            alpha = 0.75f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        titleBox.addView(tvDialogStationCount)
        headerLayout.addView(titleBox)

        val btnClose = TextView(this).apply {
            text = "✕"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val p = (8 * density).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { hideRadioStationDialog() }
        }
        headerLayout.addView(btnClose)
        radioDialogCard.addView(headerLayout)

        // 中部分割线
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
        }
        radioDialogCard.addView(divider)

        // 可滑动的电台列表容器 (支持 200+ 电台平滑滚动)
        radioDialogScrollView = ScrollView(this).apply {
            val maxH = (340 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxH
            )
            isVerticalScrollBarEnabled = true
        }

        radioStationsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        radioDialogScrollView.addView(radioStationsContainer)
        radioDialogCard.addView(radioDialogScrollView)

        radioStationDialogOverlay.addView(radioDialogCard)
        rootContainer.addView(radioStationDialogOverlay)

        updateRadioDialogList()
    }

    /**
     * 刷新并填充电台频道列表弹窗项（包含电台流格式与码率标签）
     */
    private fun updateRadioDialogList() {
        radioStationsContainer.removeAllViews()
        val stations = RadioManager.getStations()
        val density = resources.displayMetrics.density
        tvDialogStationCount.text = "中国电台 (已加载 ${stations.size} 个) · 点击即刻收听"

        stations.forEachIndexed { index, station ->
            val isCurrent = (index == RadioManager.getCurrentIndex())
            val isPlayingThis = isCurrent && RadioManager.isPlaying

            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padV = (9 * density).toInt()
                val padH = (12 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * density).toInt()
                }

                // 选中态毛玻璃高光背景
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12f * density
                    if (isCurrent) {
                        setColor(currentTheme.iconBgColor)
                        setStroke((1 * density).toInt(), currentTheme.accentColor)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                background = bg

                setOnClickListener {
                    RadioManager.play(index)
                    hideRadioStationDialog()
                    if (radioControlBar.visibility != View.VISIBLE) {
                        toggleRadioControlBar()
                    }
                }
            }

            // 电台序号标签
            val tvIndex = TextView(this).apply {
                text = String.format(Locale.US, "#%02d", index + 1)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                alpha = if (isCurrent) 1f else 0.5f
                setTextColor(if (isCurrent) currentTheme.accentColor else currentTheme.subTextColor)
                layoutParams = LinearLayout.LayoutParams((34 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            itemView.addView(tvIndex)

            // 中间信息列：名称 + 流格式/码率标签
            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvStationTitle = TextView(this).apply {
                text = station.name
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isCurrent) currentTheme.accentColor else currentTheme.textColor)
            }
            infoLayout.addView(tvStationTitle)

            val tvStationTag = TextView(this).apply {
                text = station.tag
                textSize = 10.5f
                alpha = if (isCurrent) 0.85f else 0.6f
                setTextColor(if (isCurrent) currentTheme.accentColor else currentTheme.subTextColor)
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            infoLayout.addView(tvStationTag)

            itemView.addView(infoLayout)

            // 状态徽标
            val tvBadge = TextView(this).apply {
                text = when {
                    isPlayingThis -> "● 正在直播"
                    isCurrent && RadioManager.isLoading -> "⏳ 缓冲中"
                    isCurrent -> "⏸ 暂停中"
                    else -> "▶ 收听"
                }
                textSize = 11.5f
                setTextColor(if (isCurrent) currentTheme.accentColor else currentTheme.subTextColor)
                alpha = if (isCurrent) 1f else 0.7f
                setPadding((6 * density).toInt(), 0, 0, 0)
            }
            itemView.addView(tvBadge)

            radioStationsContainer.addView(itemView)
        }
    }

    private fun showRadioStationDialog() {
        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxScrollH = if (isLandscape) {
            (screenH * 0.46f).toInt().coerceAtLeast((160 * density).toInt())
        } else {
            (screenH * 0.50f).toInt().coerceIn((240 * density).toInt(), (380 * density).toInt())
        }
        val lp = radioDialogScrollView.layoutParams
        lp.height = maxScrollH
        radioDialogScrollView.layoutParams = lp

        updateRadioDialogList()
        radioStationDialogOverlay.visibility = View.VISIBLE
        radioDialogCard.scaleX = 0.95f
        radioDialogCard.scaleY = 0.95f
        radioStationDialogOverlay.animate()
            .alpha(1f)
            .setDuration(220)
            .start()
        radioDialogCard.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideRadioStationDialog() {
        radioStationDialogOverlay.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                radioStationDialogOverlay.visibility = View.GONE
            }
            .start()
    }

    /**
     * 调优时钟卡片尺寸与间距：横屏 28dp 间距、竖屏 24dp 间距，卡片比例 0.72，呼吸留白舒适
     */
    private fun resizeClockCards() {
        val w = rootContainer.width
        val h = rootContainer.height
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val cardHeight: Int
        val cardWidth: Int
        // 显著拉大间距：横屏 28dp，竖屏 24dp，排版更舒展
        val spacing = if (isLandscape) (28 * density).toInt() else (24 * density).toInt()

        val numCards = if (isPomodoroMode) 2 else 3

        if (isLandscape) {
            // 横屏模式：卡片高度严格占高 48%~50%，无冒号更显宽阔纯净
            var targetH = (h * 0.49f).toInt()
            var targetW = (targetH * 0.72f).toInt()

            val totalReqWidth = numCards * targetW + (numCards - 1) * spacing
            val maxAllowedWidth = (w * 0.88f).toInt()

            if (totalReqWidth > maxAllowedWidth) {
                val scale = maxAllowedWidth.toFloat() / totalReqWidth
                targetH = (targetH * scale).toInt()
                targetW = (targetH * 0.72f).toInt()
            }
            cardHeight = targetH
            cardWidth = targetW
        } else {
            // 竖屏模式：横向三卡片舒展对齐，卡片间距 24dp，留足边距呼吸感
            val maxAllowedWidth = (w * 0.88f).toInt()
            val totalSpacing = (numCards - 1) * spacing
            val availableForCards = maxAllowedWidth - totalSpacing

            var targetW = (availableForCards / numCards)
            var targetH = (targetW / 0.72f).toInt()

            val maxAllowedH = (h * 0.35f).toInt()
            if (targetH > maxAllowedH) {
                targetH = maxAllowedH
                targetW = (targetH * 0.72f).toInt()
            }
            cardHeight = targetH
            cardWidth = targetW
        }

        listOf(cardHour, cardMinute, cardSecond).forEachIndexed { index, card ->
            val lp = card.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(cardWidth, cardHeight)
            lp.width = cardWidth
            lp.height = cardHeight
            // 卡片之间增加优雅间距，首张无左边距
            lp.leftMargin = if (index == 0) 0 else spacing
            card.layoutParams = lp
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

        val calInfo = CalendarAssetsManager.getDayInfo(this, dateKey)

        if (calInfo != null) {
            val dayStemChar = calInfo.dayGz.firstOrNull() ?: '甲'
            val hourPillar = CalendarAssetsManager.calculateHourPillar(dayStemChar, hour)

            tvFourPillars.text = "${calInfo.yearGz}年 · ${calInfo.monthGz}月 · ${calInfo.dayGz}日 · $hourPillar"

            val termSuffix = if (calInfo.solarTerm.isNotEmpty()) " · 【${calInfo.solarTerm}】" else ""
            tvLunarAndDate.text = "${calInfo.lunar} · ${calInfo.weekday} · 【${calInfo.zodiac}年】$termSuffix"
        } else {
            val pillars = GanzhiEngine.calculateFourPillars(cal)
            val lunarStr = LunarEngine.getLunarDateString(year, month, day)
            val sdf = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE)
            tvFourPillars.text = pillars.formattedText
            tvLunarAndDate.text = "$lunarStr · ${sdf.format(cal.time)} · 【${pillars.zodiac}年】"
        }

        // 刷新翻牌数字 (XX  XX  XX)
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

        tvWeather.setTextColor(theme.textColor)
        tvFourPillars.setTextColor(theme.textColor)
        tvLunarAndDate.setTextColor(theme.subTextColor)
        tvBottomHint.setTextColor(theme.subTextColor)

        cardHour.applyTheme(theme)
        cardMinute.applyTheme(theme)
        cardSecond.applyTheme(theme)

        btnRotate.applyTheme(theme)
        btnRadio.applyTheme(theme)
        btnPomodoro.applyTheme(theme)
        btnTheme.applyTheme(theme)

        btnPomodoroToggle.applyTheme(theme)
        btnPomodoroReset.applyTheme(theme)

        radioControlBar.applyTheme(theme)

        // 更新电台毛玻璃卡片背景与颜色
        val dialogBg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(theme.dialogBgColor)
            setStroke((1.2f * density).toInt(), theme.glassBorderTopLeft)
        }
        radioDialogCard.background = dialogBg
        updateRadioDialogList()

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
    val streamUrl: String,
    val tag: String = "网络电台"
)

object RadioManager {
    private val DEFAULT_STATIONS = listOf(
        RadioStation("CNR-1 中国之声", "https://lhttp.qtfm.cn/live/15318317/64k.mp3", "MP3 · 64k · 央广"),
        RadioStation("经典音乐广播", "https://lhttp.qingting.fm/live/4804/64k.mp3", "MP3 · 64k · 音乐"),
        RadioStation("香港电台第一台 RTHK", "https://rthk.ice.infomaniak.ch/rthk1-64.mp3", "MP3 · 64k · 综合"),
        RadioStation("Lofi 专注轻音乐", "https://streams.ilovemusic.de/iloveradio17.mp3", "MP3 · 128k · 治愈"),
        RadioStation("古典音乐台 (Swiss Classic)", "https://stream.srg-ssr.ch/m/rsc_de/mp3_128", "MP3 · 128k · 古典")
    )

    private val stations = mutableListOf<RadioStation>().apply { addAll(DEFAULT_STATIONS) }
    private var currentIndex = 0
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying: Boolean = false
    var isLoading: Boolean = false

    var onStateChangedListener: (() -> Unit)? = null

    fun getStations(): List<RadioStation> = stations
    fun getStationCount(): Int = stations.size
    fun getCurrentIndex(): Int = currentIndex

    fun getCurrentStation(): RadioStation {
        if (currentIndex !in stations.indices) currentIndex = 0
        return stations[currentIndex]
    }

    fun fetchOnlineStations(onLoaded: (() -> Unit)? = null) {
        Executors.newSingleThreadExecutor().execute {
            val endpoints = listOf(
                "https://de1.api.radio-browser.info/json/stations/bycountry/China?order=clickcount&reverse=true",
                "https://nl1.api.radio-browser.info/json/stations/bycountry/China?order=clickcount&reverse=true",
                "https://at1.api.radio-browser.info/json/stations/bycountry/China?order=clickcount&reverse=true"
            )
            for (endpoint in endpoints) {
                try {
                    val url = URL(endpoint)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 10000
                        setRequestProperty("User-Agent", "FlipClock-Radio/1.0")
                    }
                    if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                        val rawText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val jsonArray = JSONArray(rawText)
                        val fetched = mutableListOf<RadioStation>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.optString("name").trim()
                            val streamUrl = obj.optString("url_resolved").trim().ifBlank { obj.optString("url").trim() }
                            var codec = obj.optString("codec").trim().uppercase(Locale.ROOT)
                            if (codec == "UNKNOWN") codec = ""
                            val bitrate = obj.optInt("bitrate", 0)
                            val tag = when {
                                codec.isNotEmpty() && bitrate > 0 -> "$codec · ${bitrate}k"
                                codec.isNotEmpty() -> codec
                                bitrate > 0 -> "${bitrate}k"
                                else -> "网络流"
                            }
                            // 仅保留 name.isNotBlank() 且 streamUrl.isNotBlank() 的有效电台流
                            if (name.isNotBlank() && streamUrl.isNotBlank() && streamUrl.startsWith("http")) {
                                fetched.add(RadioStation(name, streamUrl, tag))
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
                                onLoaded?.invoke()
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun play(index: Int = currentIndex) {
        if (stations.isEmpty()) return
        currentIndex = (index % stations.size + stations.size) % stations.size
        val station = stations[currentIndex]

        RadioManager.isLoading = true
        RadioManager.isPlaying = false
        onStateChangedListener?.invoke()

        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
        }

        if (mediaPlayer == null) {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setOnPreparedListener { mp ->
                RadioManager.isLoading = false
                RadioManager.isPlaying = true
                mp.start()
                RadioManager.onStateChangedListener?.invoke()
            }
            player.setOnErrorListener { _, _, _ ->
                RadioManager.isLoading = false
                RadioManager.isPlaying = false
                RadioManager.onStateChangedListener?.invoke()
                true
            }
            mediaPlayer = player
        }

        try {
            mediaPlayer?.setDataSource(station.streamUrl)
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            RadioManager.isLoading = false
            RadioManager.isPlaying = false
            onStateChangedListener?.invoke()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer
        if (mp != null && RadioManager.isPlaying) {
            mp.pause()
            RadioManager.isPlaying = false
            onStateChangedListener?.invoke()
        } else if (mp != null && !RadioManager.isPlaying && !RadioManager.isLoading) {
            mp.start()
            RadioManager.isPlaying = true
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
        RadioManager.isPlaying = false
        RadioManager.isLoading = false
    }
}

// =========================================================================================
// 3. 悬浮毛玻璃电台控制条组件 (RadioControlBarLayout)
// =========================================================================================

class RadioControlBarLayout(context: Context) : LinearLayout(context) {

    private val tvName: TextView
    private val tvStatus: TextView
    private val btnPrev: TextView
    private val btnToggle: TextView
    private val btnNext: TextView
    private var theme: ThemeMode = ThemeMode.DARK_VINTAGE

    var onOpenListClickListener: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (18 * density).toInt()
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layoutParams = lp

        // 左侧电台收音机图标（点击可弹出频道列表）
        val iconRadio = TextView(context).apply {
            text = "📻"
            textSize = 18f
            setPadding(0, 0, (10 * density).toInt(), 0)
            setOnClickListener { onOpenListClickListener?.invoke() }
        }
        addView(iconRadio)

        // 中部电台名与状态（点击展开频道列表）
        val infoBox = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams((170 * density).toInt(), LayoutParams.WRAP_CONTENT)
            setOnClickListener { onOpenListClickListener?.invoke() }
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
            text = "点击展开列表 ▾"
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
                tvStatus.text = "电台缓冲中... ▾"
                btnToggle.text = "⏳"
            }
            RadioManager.isPlaying -> {
                tvStatus.text = "● 正在直播 ▾"
                btnToggle.text = "⏸"
            }
            else -> {
                tvStatus.text = "已暂停 · 点此切台 ▾"
                btnToggle.text = "▶"
            }
        }
    }

    fun applyTheme(newTheme: ThemeMode) {
        this.theme = newTheme
        val density = resources.displayMetrics.density
        val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24f * density
            setColor(theme.cardTopBg)
            setStroke((1.2f * density).toInt(), theme.glassBorderTopLeft)
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
// 4. 全新液态毛玻璃风（Liquid Glass）3D 折叠翻牌组件
// =========================================================================================

/**
 * 拟物翻牌机械插值器：
 * 前半程（0° -> -90°）：重力自由下落加速 (Accelerate)
 * 后半程（90° -> 0°）：机械卡扣归位阻尼减速 (Decelerate)
 */
class SplitFlapInterpolator : TimeInterpolator {
    override fun getInterpolation(input: Float): Float {
        return if (input < 0.5f) {
            val t = input * 2f
            0.5f * Math.pow(t.toDouble(), 1.6).toFloat()
        } else {
            val t = (input - 0.5f) * 2f
            0.5f * (1f + (1f - Math.pow((1f - t).toDouble(), 1.6).toFloat()))
        }
    }
}

class FlipCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var oldValue: String = "00"
    private var newValue: String = "00"
    private var flipProgress: Float = 1.0f
    private var isAnimating: Boolean = false

    private var currentTheme: ThemeMode = ThemeMode.DARK_VINTAGE

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isFilterBitmap = true
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        isAntiAlias = true
        isSubpixelText = true
        isFilterBitmap = true
        style = Paint.Style.FILL
        strokeWidth = 0f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isFilterBitmap = true
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
    }

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
        if (value == newValue && !isAnimating) return

        if (!animate) {
            animator?.cancel()
            isAnimating = false
            oldValue = value
            newValue = value
            flipProgress = 1.0f
            rotationX = 0f
            alpha = 1.0f
            transformMatrix.reset()
            invalidate()
            return
        }

        if (isAnimating) {
            animator?.cancel()
            oldValue = newValue
        } else {
            oldValue = newValue
        }

        newValue = value
        isAnimating = true
        flipProgress = 0.0f

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
            duration = 600L // 550ms ~ 650ms 机械下坠质感时长
            interpolator = SplitFlapInterpolator()
            addUpdateListener {
                flipProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    flipProgress = 1.0f
                    oldValue = newValue
                    rotationX = 0f
                    alpha = 1.0f
                    transformMatrix.reset()
                    invalidate()
                }

                override fun onAnimationCancel(animation: Animator) {
                    isAnimating = false
                    flipProgress = 1.0f
                    oldValue = newValue
                    rotationX = 0f
                    alpha = 1.0f
                    transformMatrix.reset()
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val cornerRadius = 18f * density
        val centerY = h / 2f
        val slitHalf = 1.0f * density

        // 现代利落无衬线粗体，适度收紧字体尺寸至 0.58f，在毛玻璃卡片内留足舒适呼吸留白
        textPaint.textSize = h * 0.58f
        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f

        // 彻底根除鬼影：一旦动画结束或处于静态，仅由一套干净单一的静态组件呈现当前数字
        if (!isAnimating || flipProgress >= 1.0f) {
            // 1. 静态上半部 (当前数字)
            canvas.save()
            canvas.clipRect(0f, 0f, w, centerY - slitHalf)
            drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = true)
            textPaint.color = currentTheme.textColor
            canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
            canvas.restore()

            // 2. 静态下半部 (当前数字)
            canvas.save()
            canvas.clipRect(0f, centerY + slitHalf, w, h)
            drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = false)
            textPaint.color = currentTheme.textColor
            canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
            canvas.restore()

            // 3. 中缝微光接缝
            drawSeamLines(canvas, w, centerY, slitHalf, density)
            return
        }

        // ==================== 翻转过渡期间（0f < progress < 1f）====================
        // 采用远焦视距参数 -48f * density（彻底消除近景透视失真导致的拉伸降采样模糊与发虚）
        val cameraDistance = -48f * density

        // 1. 底层上半部 (展开的新数字底板)
        canvas.save()
        canvas.clipRect(0f, 0f, w, centerY - slitHalf)
        drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = true)
        textPaint.color = currentTheme.textColor
        canvas.drawText(newValue, w / 2f, textBaseline, textPaint)
        canvas.restore()

        // 2. 底层下半部 (旧数字底板)
        canvas.save()
        canvas.clipRect(0f, centerY + slitHalf, w, h)
        drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = false)
        textPaint.color = currentTheme.textColor
        canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

        // 底板落影 (前半程逐渐加深)
        if (flipProgress < 0.5f) {
            val shadowAlpha = (flipProgress * 2f * 95).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
        }
        canvas.restore()

        // 3. 顶层 3D 旋转活动卡片 (Flap)
        if (flipProgress < 0.5f) {
            // 前半程：旧数字上半叶重力下折叠 (0° -> -90°)
            val phaseProgress = flipProgress / 0.5f
            val degree = phaseProgress * 90f

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
            drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = true)
            textPaint.color = currentTheme.textColor
            canvas.drawText(oldValue, w / 2f, textBaseline, textPaint)

            // 下折时的背光阴影
            val shadowAlpha = (phaseProgress * 110).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, 0f, w, centerY - slitHalf, shadowPaint)
            canvas.restore()
        } else {
            // 后半程：新数字下半叶展开卡扣归位 (90° -> 0°)
            val phaseProgress = (flipProgress - 0.5f) / 0.5f
            val degree = 90f - phaseProgress * 90f

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
            drawLiquidGlassCardHalf(canvas, w, h, cornerRadius, isTop = false)
            textPaint.color = currentTheme.textColor
            canvas.drawText(newValue, w / 2f, textBaseline, textPaint)

            // 归位时的阴影淡出
            val shadowAlpha = ((1f - phaseProgress) * 110).toInt().coerceIn(0, 255)
            shadowPaint.alpha = shadowAlpha
            canvas.drawRect(0f, centerY + slitHalf, w, h, shadowPaint)
            canvas.restore()
        }

        // 4. 中缝微光暗缝
        drawSeamLines(canvas, w, centerY, slitHalf, density)
    }

    private fun drawSeamLines(canvas: Canvas, w: Float, centerY: Float, slitHalf: Float, density: Float) {
        seamPaint.style = Paint.Style.STROKE
        seamPaint.strokeWidth = 1f * density
        seamPaint.color = 0x44000000.toInt()
        canvas.drawLine(0f, centerY - slitHalf, w, centerY - slitHalf, seamPaint)

        seamPaint.color = currentTheme.dividerColor
        seamPaint.strokeWidth = slitHalf * 2f
        canvas.drawLine(0f, centerY, w, centerY, seamPaint)

        seamPaint.color = currentTheme.glassBorderTopLeft
        seamPaint.strokeWidth = 0.7f * density
        canvas.drawLine(0f, centerY + slitHalf, w, centerY + slitHalf, seamPaint)
    }

    /**
     * 绘制带有微透高斯毛玻璃渐变与斜向液态折射高光边框的半叶卡片
     */
    private fun drawLiquidGlassCardHalf(canvas: Canvas, w: Float, h: Float, radius: Float, isTop: Boolean) {
        cardRect.set(0f, 0f, w, h)

        // 1. 半透明微透毛玻璃底色
        val glassShader = if (isTop) {
            LinearGradient(
                0f, 0f, 0f, h / 2f,
                currentTheme.glassCardStart, currentTheme.glassCardEnd,
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, h / 2f, 0f, h,
                currentTheme.glassCardEnd, currentTheme.glassCardStart,
                Shader.TileMode.CLAMP
            )
        }
        cardPaint.shader = glassShader
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint)

        // 2. 细腻的液态高光细边框（斜向渐变白光：模拟物理玻璃边缘倒角折射光）
        val borderShader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                currentTheme.glassBorderTopLeft,     // 强聚光角
                currentTheme.glassBorderMid,         // 柔和微透区
                currentTheme.glassBorderBottomRight  // 底部反光光斑
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        borderPaint.shader = borderShader
        borderPaint.strokeWidth = 1.2f * resources.displayMetrics.density
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
    }
}

// =========================================================================================
// 5. 顶部半透明极简控制图标 (HeaderIconButton)
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
                val rw = 9f * density
                val rh = 6f * density
                canvas.drawRoundRect(cx - rw, cy - rh + 1.5f * density, cx + rw, cy + rh + 1.5f * density, 2f * density, 2f * density, paint)
                canvas.drawLine(cx - 5f * density, cy - rh + 1.5f * density, cx + 3f * density, cy - rh - 4f * density, paint)
                canvas.drawCircle(cx - 3.5f * density, cy + 1.5f * density, 2.5f * density, paint)
            }
            IconType.POMODORO -> {
                canvas.drawCircle(cx, cy, 6.5f * density, paint)
                canvas.drawLine(cx, cy, cx, cy - 3.5f * density, paint)
                canvas.drawLine(cx, cy, cx + 3.0f * density, cy, paint)
            }
            IconType.THEME -> {
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
// 6. 和风天气异步请求引擎 (支持 GZIP 压缩自动解压)
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
// 7. 算法回退体系 (GanzhiEngine & LunarEngine)
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
// 8. 三大液态毛玻璃高级配色系统 (极夜晶玻 / 暖玉流光 / 赛博琉璃)
// =========================================================================================

enum class ThemeMode(
    val title: String,
    val bgGradStart: Int,
    val bgGradEnd: Int,
    val cardTopBg: Int,
    val cardBottomBg: Int,
    val glassCardStart: Int,
    val glassCardEnd: Int,
    val glassBorderTopLeft: Int,
    val glassBorderMid: Int,
    val glassBorderBottomRight: Int,
    val cardBorder: Int,
    val textColor: Int,
    val accentColor: Int,
    val subTextColor: Int,
    val dividerColor: Int,
    val iconBgColor: Int,
    val iconBorderColor: Int,
    val dialogBgColor: Int
) {
    DARK_VINTAGE(
        title = "极夜晶玻",
        bgGradStart = 0xFF0D0E12.toInt(),
        bgGradEnd = 0xFF151620.toInt(),
        cardTopBg = 0x22FFFFFF.toInt(),
        cardBottomBg = 0x14FFFFFF.toInt(),
        glassCardStart = 0x24FFFFFF.toInt(), // 液态通透微光
        glassCardEnd = 0x0AFFFFFF.toInt(),
        glassBorderTopLeft = 0x66FFFFFF.toInt(), // 倒角高光
        glassBorderMid = 0x15FFFFFF.toInt(),
        glassBorderBottomRight = 0x33FFFFFF.toInt(),
        cardBorder = 0x33FFFFFF.toInt(),
        textColor = 0xFFFFFFFF.toInt(), // 纯净极简纯白
        accentColor = 0xFFE5C07B.toInt(), // 柔和流光金
        subTextColor = 0xFFB0B4C2.toInt(),
        dividerColor = 0x22FFFFFF.toInt(),
        iconBgColor = 0x1AFFFFFF.toInt(),
        iconBorderColor = 0x33FFFFFF.toInt(),
        dialogBgColor = 0xE6161722.toInt()
    ),
    RICE_PAPER(
        title = "暖玉流光",
        bgGradStart = 0xFFF5EFE2.toInt(),
        bgGradEnd = 0xFFE8DDC6.toInt(),
        cardTopBg = 0x99FFFFFF.toInt(),
        cardBottomBg = 0x55FFFFFF.toInt(),
        glassCardStart = 0xAAFFFFFF.toInt(), // 暖玉透白
        glassCardEnd = 0x66FFFFFF.toInt(),
        glassBorderTopLeft = 0xEEFFFFFF.toInt(),
        glassBorderMid = 0x44C2B49D.toInt(),
        glassBorderBottomRight = 0x99FFFFFF.toInt(),
        cardBorder = 0x55C2B49D.toInt(),
        textColor = 0xFF1F1D1A.toInt(), // 徽墨深灰
        accentColor = 0xFFBA3636.toInt(), // 古法熟朱砂红
        subTextColor = 0xFF6D6353.toInt(),
        dividerColor = 0x22000000.toInt(),
        iconBgColor = 0x22000000.toInt(),
        iconBorderColor = 0x33000000.toInt(),
        dialogBgColor = 0xF2FAF6EE.toInt()
    ),
    BLACK_GOLD_CYBER(
        title = "赛博琉璃",
        bgGradStart = 0xFF040406.toInt(),
        bgGradEnd = 0xFF0A0A10.toInt(),
        cardTopBg = 0x26FFD700.toInt(),
        cardBottomBg = 0x121A1A26.toInt(),
        glassCardStart = 0x22FFD700.toInt(), // 琥珀烟晶琉璃
        glassCardEnd = 0x08181828.toInt(),
        glassBorderTopLeft = 0x88FFD700.toInt(),
        glassBorderMid = 0x18FFD700.toInt(),
        glassBorderBottomRight = 0x44FFD700.toInt(),
        cardBorder = 0x44FFD700.toInt(),
        textColor = 0xFFFFD700.toInt(), // 霓虹亮金
        accentColor = 0xFFFFAA00.toInt(),
        subTextColor = 0xFFC9A23E.toInt(),
        dividerColor = 0x33FFD700.toInt(),
        iconBgColor = 0x20FFD700.toInt(),
        iconBorderColor = 0x44FFD700.toInt(),
        dialogBgColor = 0xE60E0E16.toInt()
    )
}
