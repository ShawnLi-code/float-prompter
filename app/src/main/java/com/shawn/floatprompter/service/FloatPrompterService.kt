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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.shawn.floatprompter.MainActivity
import com.shawn.floatprompter.R
import com.shawn.floatprompter.data.PrompterPrefs
import com.shawn.floatprompter.databinding.LayoutFloatingPrompterBinding
import com.shawn.floatprompter.engine.PacedScrollEngine
import com.shawn.floatprompter.engine.VoiceFollowEngine

class FloatPrompterService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: LayoutFloatingPrompterBinding
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: PrompterPrefs

    private var pacedScrollEngine: PacedScrollEngine? = null
    private var voiceFollowEngine: VoiceFollowEngine? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PrompterPrefs(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()
        createFloatingWindow()
        setupEngines()
    }

    private fun startForegroundNotification() {
        val channelId = "float_prompter_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "悬浮提词服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("流光提词运行中")
            .setContentText("悬浮窗正在显示，可自由拖拽录像")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createFloatingWindow() {
        binding = LayoutFloatingPrompterBinding.inflate(LayoutInflater.from(this))

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val widthPx = (prefs.windowWidthDp * density).toInt()

        params = WindowManager.LayoutParams(
            widthPx,
            (380 * density).toInt(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUS_WINDOW or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认居中靠顶，对准前置摄像头
            x = (displayMetrics.widthPixels - widthPx) / 2
            y = (60 * density).toInt()
            alpha = prefs.opacity / 100f
        }

        // 加载排版后的台词
        val scriptText = prefs.formattedScript.ifEmpty { prefs.rawScript }
        binding.tvPrompterContent.text = scriptText
        binding.tvPrompterContent.textSize = prefs.fontSizeSp

        setupTouchEvents()
        setupControlClicks()

        windowManager.addView(binding.root, params)
    }

    private fun setupTouchEvents() {
        binding.layoutDragBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(binding.root, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControlClicks() {
        // 关闭悬浮窗
        binding.btnCloseFloat.setOnClickListener {
            stopSelf()
        }

        // 展开/收起小设置栏
        binding.btnToggleSettings.setOnClickListener {
            val isGone = binding.layoutMiniSettings.visibility == View.GONE
            binding.layoutMiniSettings.visibility = if (isGone) View.VISIBLE else View.GONE
        }

        // 透明度切换
        fun setAlpha(value: Float, intPercent: Int) {
            params.alpha = value
            prefs.opacity = intPercent
            windowManager.updateViewLayout(binding.root, params)
        }
        binding.btnAlphaLow.setOnClickListener { setAlpha(0.35f, 35) }
        binding.btnAlphaMed.setOnClickListener { setAlpha(0.65f, 65) }
        binding.btnAlphaHigh.setOnClickListener { setAlpha(0.92f, 92) }

        // 速度调节
        binding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
        binding.btnSpeedUp.setOnClickListener {
            if (prefs.scrollSpeed < 10) {
                prefs.scrollSpeed++
                binding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
                pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            }
        }
        binding.btnSpeedDown.setOnClickListener {
            if (prefs.scrollSpeed > 1) {
                prefs.scrollSpeed--
                binding.tvSpeedValue.text = "${prefs.scrollSpeed}档"
                pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            }
        }

        // 播放与暂停切换
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }

        // 双击或点击复位到顶部
        binding.btnResetToTop.setOnClickListener {
            binding.scrollViewPrompter.smoothScrollTo(0, 0)
        }
    }

    private fun setupEngines() {
        val lines = prefs.formattedScript.lines().filter { it.isNotBlank() }

        if (prefs.isVoiceMode) {
            binding.tvModeIndicator.text = "🎙️ 语音跟读"
            binding.rowSpeedControl.visibility = View.GONE
            voiceFollowEngine = VoiceFollowEngine(this, lines) { lineIndex ->
                // 计算该行的大致纵坐标并平滑滚动
                val lineTop = (lineIndex * 40 * resources.displayMetrics.density).toInt()
                binding.scrollViewPrompter.smoothScrollTo(0, lineTop)
            }
            voiceFollowEngine?.start()
            binding.btnPlayPause.text = "⏸"
        } else {
            binding.tvModeIndicator.text = "⏱️ 匀速"
            binding.rowSpeedControl.visibility = View.VISIBLE
            pacedScrollEngine = PacedScrollEngine { dy ->
                binding.scrollViewPrompter.smoothScrollBy(0, dy)
            }
            pacedScrollEngine?.speedLevel = prefs.scrollSpeed
            binding.btnPlayPause.text = "▶"
        }
    }

    private fun togglePlayPause() {
        if (prefs.isVoiceMode) {
            // 语音模式切换
            binding.btnPlayPause.text = if (binding.btnPlayPause.text == "▶") "⏸" else "▶"
        } else {
            // 匀速模式切换
            val isNowPlaying = pacedScrollEngine?.toggle() == true
            binding.btnPlayPause.text = if (isNowPlaying) "⏸" else "▶"
            binding.btnPlayPause.setTextColor(
                if (isNowPlaying) getColor(R.color.accent_amber) else getColor(R.color.accent_emerald)
            )
        }
    }

    override fun onDestroy() {
        pacedScrollEngine?.destroy()
        voiceFollowEngine?.destroy()
        if (::binding.isInitialized) {
            windowManager.removeView(binding.root)
        }
        super.onDestroy()
    }
}
