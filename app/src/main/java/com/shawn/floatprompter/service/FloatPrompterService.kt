package com.shawn.floatprompter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.shawn.floatprompter.MainActivity
import com.shawn.floatprompter.R
import com.shawn.floatprompter.data.PrompterPrefs
import com.shawn.floatprompter.databinding.LayoutFloatingBallBinding
import com.shawn.floatprompter.databinding.LayoutFloatingPrompterBinding
import com.shawn.floatprompter.engine.PacedScrollEngine
import com.shawn.floatprompter.engine.VoiceFollowEngine
import kotlin.math.abs

class FloatPrompterService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prompterBinding: LayoutFloatingPrompterBinding
    private lateinit var ballBinding: LayoutFloatingBallBinding
    private lateinit var prompterParams: WindowManager.LayoutParams
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var prefs: PrompterPrefs

    private var isPrompterAttached = false
    private var isBallAttached = false

    private var pacedScrollEngine: PacedScrollEngine? = null
    private var voiceFollowEngine: VoiceFollowEngine? = null

    // 提词大窗拖拽坐标
    private var prompterInitialX = 0
    private var prompterInitialY = 0
    private var prompterTouchX = 0f
    private var prompterTouchY = 0f

    // 悬浮小球拖拽与点击判定
    private var ballDownX = 0f
    private var ballDownY = 0f
    private var ballStartParamX = 0
    private var ballStartParamY = 0
    private var ballDownTime = 0L

    companion object {
        private const val TAG = "FloatPrompterService"
        const val ACTION_SHOW_BALL = "com.shawn.floatprompter.SHOW_BALL"
        const val ACTION_SHOW_PROMPTER = "com.shawn.floatprompter.SHOW_PROMPTER"
        const val ACTION_STOP = "com.shawn.floatprompter.STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PrompterPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()
        initViewsAndParams()
        setupEngines()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BALL -> {
                showBall()
            }
            ACTION_SHOW_PROMPTER -> {
                showPrompter()
            }
            else -> {
                showBall()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "float_prompter_foreground_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "流光提词器服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮窗与灵动小圆球在相机上层持续显示"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatPrompterService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        // 使用合规的单色矢量图标，避免 BadForegroundServiceNotificationException 崩溃
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("流光提词运行中 🟢")
            .setContentText("悬浮窗/小球已就绪，点击通知返回主页")
            .setSmallIcon(R.drawable.ic_notification_prompter)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭悬浮窗", stopIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
            try {
                startForeground(1001, notification)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "startForeground fallback error: ${fallbackEx.message}")
            }
        }
    }

    private fun initViewsAndParams() {
        try {
            // 使用 ContextThemeWrapper 包裹 Context，防止布局中找不到主题样式导致 InflateException 崩溃
            val themedContext = ContextThemeWrapper(this, R.style.Theme_FloatPrompter)
            val layoutInflater = LayoutInflater.from(themedContext)
            prompterBinding = LayoutFloatingPrompterBinding.inflate(layoutInflater)
            ballBinding = LayoutFloatingBallBinding.inflate(layoutInflater)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val displayMetrics = resources.displayMetrics
            val density = displayMetrics.density
            val screenWidth = displayMetrics.widthPixels

            // 提词器大窗宽度：默认占屏幕约 85%，最大 360dp
            val targetWidthDp = (screenWidth / density * 0.88f).toInt().coerceIn(260, 360)
            val prompterWidth = (targetWidthDp * density).toInt()

            // 1. 提词器大窗布局参数 (居中靠顶，对齐前置摄像头)
            prompterParams = WindowManager.LayoutParams(
                prompterWidth,
                (360 * density).toInt(),
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - prompterWidth) / 2
                y = (70 * density).toInt()
                alpha = (prefs.opacity / 100f).coerceIn(0.25f, 1.0f)
            }

            // 2. 灵动小圆球布局参数 (贴屏幕右侧边缘)
            val ballSize = (56 * density).toInt()
            ballParams = WindowManager.LayoutParams(
                ballSize,
                ballSize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - ballSize - (10 * density).toInt()
                y = (180 * density).toInt()
            }

            setupPrompterControls()
            setupBallTouch()
        } catch (e: Throwable) {
            Log.e(TAG, "initViewsAndParams failed: ${e.message}", e)
        }
    }

    @Synchronized
    private fun showPrompter() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            Toast.makeText(this, "未开启悬浮窗权限，无法展示提词窗", Toast.LENGTH_SHORT).show()
            return
        }

        if (isBallAttached) {
            try {
                windowManager.removeView(ballBinding.root)
            } catch (e: Exception) {
                Log.w(TAG, "remove ball failed: ${e.message}")
            }
            isBallAttached = false
        }

        if (!isPrompterAttached && ::prompterBinding.isInitialized) {
            try {
                // 刷新最新台词与字号
                val scriptText = prefs.formattedScript.ifEmpty { prefs.rawScript }
                prompterBinding.tvPrompterContent.text = scriptText
                prompterBinding.tvPrompterContent.textSize = prefs.fontSizeSp
                prompterParams.alpha = (prefs.opacity / 100f).coerceIn(0.25f, 1.0f)

                windowManager.addView(prompterBinding.root, prompterParams)
                isPrompterAttached = true
                Log.d(TAG, "Prompter window successfully attached to WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "addView prompter failed: ${e.message}", e)
                Toast.makeText(this, "显示提词窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Synchronized
    private fun showBall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            Toast.makeText(this, "未开启悬浮窗权限，无法展示悬浮球", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPrompterAttached) {
            try {
                windowManager.removeView(prompterBinding.root)
            } catch (e: Exception) {
                Log.w(TAG, "remove prompter failed: ${e.message}")
            }
            isPrompterAttached = false
        }

        if (!isBallAttached && ::ballBinding.isInitialized) {
            try {
                windowManager.addView(ballBinding.root, ballParams)
                isBallAttached = true
                Log.d(TAG, "Floating ball successfully attached to WindowManager")
            } catch (e: Exception) {
                Log.e(TAG, "addView ball failed: ${e.message}", e)
                Toast.makeText(this, "显示悬浮球失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBallTouch() {
        ballBinding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ballDownX = event.rawX
                    ballDownY = event.rawY
                    ballStartParamX = ballParams.x
                    ballStartParamY = ballParams.y
                    ballDownTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    ballParams.x = ballStartParamX + (event.rawX - ballDownX).toInt()
                    ballParams.y = ballStartParamY + (event.rawY - ballDownY).toInt()
                    if (isBallAttached) {
                        try {
                            windowManager.updateViewLayout(ballBinding.root, ballParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - ballDownX)
                    val deltaY = abs(event.rawY - ballDownY)
                    val duration = System.currentTimeMillis() - ballDownTime

                    if (deltaX < 20 && deltaY < 20 && duration < 350) {
                        // 单击小圆球 -> 瞬间弹起提词大窗！
                        showPrompter()
                    } else {
                        // 拖动松手 -> 自动平滑吸附到最近屏幕左边缘或右边缘
                        val screenWidth = resources.displayMetrics.widthPixels
                        val density = resources.displayMetrics.density
                        val ballSize = (56 * density).toInt()
                        val padding = (8 * density).toInt()

                        if (ballParams.x + ballSize / 2 < screenWidth / 2) {
                            ballParams.x = padding
                        } else {
                            ballParams.x = screenWidth - ballSize - padding
                        }
                        if (isBallAttached) {
                            try {
                                windowManager.updateViewLayout(ballBinding.root, ballParams)
                            } catch (_: Exception) {}
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPrompterControls() {
        // 拖动栏
        prompterBinding.layoutDragBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    prompterInitialX = prompterParams.x
                    prompterInitialY = prompterParams.y
                    prompterTouchX = event.rawX
                    prompterTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    prompterParams.x = prompterInitialX + (event.rawX - prompterTouchX).toInt()
                    prompterParams.y = prompterInitialY + (event.rawY - prompterTouchY).toInt()
                    if (isPrompterAttached) {
                        try {
                            windowManager.updateViewLayout(prompterBinding.root, prompterParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

        // 收起为小圆球
        prompterBinding.btnMinimizeFloat.setOnClickListener {
            showBall()
        }

        // 完全关闭悬浮窗
        prompterBinding.btnCloseFloat.setOnClickListener {
            stopSelf()
        }

        // 展开/收起微调栏
        prompterBinding.btnToggleSettings.setOnClickListener {
            val isGone = prompterBinding.layoutMiniSettings.visibility == View.GONE
            prompterBinding.layoutMiniSettings.visibility = if (isGone) View.VISIBLE else View.GONE
        }

        // 透明度快捷设置
        fun setAlpha(value: Float, intPercent: Int) {
            prompterParams.alpha = value
            prefs.opacity = intPercent
            if (isPrompterAttached) {
                try {
                    windowManager.updateViewLayout(prompterBinding.root, prompterParams)
                } catch (_: Exception) {}
            }
        }
        prompterBinding.btnAlphaLow.setOnClickListener { setAlpha(0.35f, 35) }
        prompterBinding.btnAlphaMed.setOnClickListener { setAlpha(0.65f, 65) }
        prompterBinding.btnAlphaHigh.setOnClickListener { setAlpha(0.92f, 92) }

        // 速度调节
        prompterBinding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
        prompterBinding.btnSpeedUp.setOnClickListener {
            if (prefs.scrollSpeed < 10) {
                prefs.scrollSpeed++
                prompterBinding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
                pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            }
        }
        prompterBinding.btnSpeedDown.setOnClickListener {
            if (prefs.scrollSpeed > 1) {
                prefs.scrollSpeed--
                prompterBinding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
                pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            }
        }

        // 播放与暂停切换
        prompterBinding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        // 复位到顶部
        prompterBinding.btnResetToTop.setOnClickListener {
            prompterBinding.scrollViewPrompter.smoothScrollTo(0, 0)
        }
    }

    private fun setupEngines() {
        val lines = prefs.formattedScript.lines().filter { it.isNotBlank() }

        if (prefs.isVoiceMode) {
            prompterBinding.tvModeIndicator.text = "🎙️ 语音跟读"
            prompterBinding.rowSpeedControl.visibility = View.GONE
            voiceFollowEngine = VoiceFollowEngine(this, lines) { lineIndex ->
                val lineTop = (lineIndex * 40 * resources.displayMetrics.density).toInt()
                prompterBinding.scrollViewPrompter.smoothScrollTo(0, lineTop)
            }
            voiceFollowEngine?.start()
            prompterBinding.btnPlayPause.text = "⏸"
        } else {
            prompterBinding.tvModeIndicator.text = "⏱️ 匀速"
            prompterBinding.rowSpeedControl.visibility = View.VISIBLE
            pacedScrollEngine = PacedScrollEngine { dy ->
                prompterBinding.scrollViewPrompter.smoothScrollBy(0, dy)
            }
            pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            prompterBinding.btnPlayPause.text = "▶"
        }
    }

    private fun togglePlayPause() {
        if (prefs.isVoiceMode) {
            prompterBinding.btnPlayPause.text = if (prompterBinding.btnPlayPause.text == "▶") "⏸" else "▶"
        } else {
            val isNowPlaying = pacedScrollEngine?.toggle() == true
            prompterBinding.btnPlayPause.text = if (isNowPlaying) "⏸" else "▶"
            prompterBinding.btnPlayPause.setTextColor(
                if (isNowPlaying) getColor(R.color.accent_amber) else getColor(R.color.accent_emerald)
            )
        }
    }

    override fun onDestroy() {
        pacedScrollEngine?.destroy()
        voiceFollowEngine?.destroy()
        if (isPrompterAttached && ::prompterBinding.isInitialized) {
            try { windowManager.removeView(prompterBinding.root) } catch (_: Exception) {}
            isPrompterAttached = false
        }
        if (isBallAttached && ::ballBinding.isInitialized) {
            try { windowManager.removeView(ballBinding.root) } catch (_: Exception) {}
            isBallAttached = false
        }
        super.onDestroy()
    }
}
