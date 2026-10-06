package com.shawn.floatprompter

import android.Manifest
import android.app.ProgressDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shawn.floatprompter.data.PrompterPrefs
import com.shawn.floatprompter.data.SampleScripts
import com.shawn.floatprompter.databinding.ActivityMainBinding
import com.shawn.floatprompter.engine.ScriptFormatter
import com.shawn.floatprompter.service.FloatPrompterService
import com.shawn.floatprompter.update.UpdateManager
import com.shawn.floatprompter.update.VersionInfo
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrompterPrefs

    // 麦克风录音权限申请
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已开启，可使用语音跟读", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予麦克风权限，将使用定时匀速滚屏", Toast.LENGTH_SHORT).show()
            binding.rbModeSpeed.isChecked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrompterPrefs(this)

        initViewsAndSavedData()
        setupListeners()

        // 静默检查更新（不打扰用户，有新版本才弹窗提示）
        silentCheckUpdate()
    }

    private fun initViewsAndSavedData() {
        binding.tvVersionInfo.text = "v${BuildConfig.VERSION_NAME} • 流光提词"

        // 默认载入用户之前的草稿或《6个月法则》口播文案
        val savedRaw = prefs.rawScript.ifEmpty { SampleScripts.DEFAULT_SCRIPT_CONTENT }
        binding.etScriptInput.setText(savedRaw)

        binding.seekBarSpeed.progress = prefs.scrollSpeed
        binding.tvSpeedDisplay.text = "${prefs.scrollSpeed} 档"

        binding.seekBarOpacity.progress = prefs.opacity
        binding.tvOpacityDisplay.text = "${prefs.opacity}%"

        if (prefs.isVoiceMode) {
            binding.rbModeVoice.isChecked = true
            binding.layoutSpeedSettings.visibility = View.GONE
        } else {
            binding.rbModeSpeed.isChecked = true
            binding.layoutSpeedSettings.visibility = View.VISIBLE
        }

        updateTextStats(savedRaw)
    }

    private fun setupListeners() {
        // 1. 检查更新按钮
        binding.btnCheckUpdate.setOnClickListener {
            checkUpdateManual()
        }

        // 2. 从剪贴板粘贴
        binding.btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val pasted = clipData.getItemAt(0).text?.toString() ?: ""
                if (pasted.isNotBlank()) {
                    binding.etScriptInput.setText(pasted)
                    autoFormatText()
                    Toast.makeText(this, "已粘贴并自动完成窄窗排版！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. 导入《6个月法则》样本
        binding.btnLoadSample.setOnClickListener {
            binding.etScriptInput.setText(SampleScripts.DEFAULT_SCRIPT_CONTENT)
            autoFormatText()
            Toast.makeText(this, "已载入《6个月法则》口播稿！", Toast.LENGTH_SHORT).show()
        }

        // 4. 清空
        binding.btnClear.setOnClickListener {
            binding.etScriptInput.setText("")
            prefs.rawScript = ""
            prefs.formattedScript = ""
            updateTextStats("")
        }

        // 5. 核心：智能整理排版
        binding.btnAutoFormat.setOnClickListener {
            autoFormatText()
        }

        // 文本输入实时监听统计
        binding.etScriptInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateTextStats(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 模式切换
        binding.rgScrollMode.setOnCheckedChangeListener { _, checkedId ->
            val isVoice = checkedId == R.id.rbModeVoice
            prefs.isVoiceMode = isVoice
            binding.layoutSpeedSettings.visibility = if (isVoice) View.GONE else View.VISIBLE

            if (isVoice) {
                checkAudioPermission()
            }
        }

        // 语速滑块
        binding.seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.scrollSpeed = progress.coerceAtLeast(1)
                binding.tvSpeedDisplay.text = "${prefs.scrollSpeed} 档"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 透明度滑块
        binding.seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.opacity = progress
                binding.tvOpacityDisplay.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 6. 开启提词并直接打开相机开拍（核心场景一键触达）
        binding.btnLaunchCamera.setOnClickListener {
            if (ensurePrerequisitesReady()) {
                startFloatingService()
                launchDeviceCamera()
            }
        }

        // 7. 仅开启悬浮提词（常驻小圆球与大窗）
        binding.btnStartFloat.setOnClickListener {
            if (ensurePrerequisitesReady()) {
                startFloatingService()
                Toast.makeText(this, "🟢 悬浮提词已就绪！点击屏幕边缘小球随时展开", Toast.LENGTH_LONG).show()
            }
        }

        // 8. 关闭悬浮窗
        binding.btnStopFloat.setOnClickListener {
            val stopIntent = Intent(this, FloatPrompterService::class.java).apply {
                action = FloatPrompterService.ACTION_STOP
            }
            startService(stopIntent)
            Toast.makeText(this, "悬浮提词器已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoFormatText() {
        val raw = binding.etScriptInput.text.toString()
        if (raw.isBlank()) {
            Toast.makeText(this, "请先输入或粘贴台词稿", Toast.LENGTH_SHORT).show()
            return
        }

        val filterStage = binding.cbFilterStage.isChecked
        val result = ScriptFormatter.formatForTeleprompter(raw, filterStage = filterStage)

        prefs.rawScript = raw
        prefs.formattedScript = result.formattedText
        binding.etScriptInput.setText(result.formattedText)

        binding.tvFormatStatus.text = "✅ 窄窗格式已就绪"
        binding.tvFormatStatus.setTextColor(getColor(R.color.accent_emerald))
        updateTextStats(result.formattedText)

        Toast.makeText(
            this,
            "排版完成！共 ${result.wordCount} 字，预计 ${result.estimatedSeconds} 秒",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateTextStats(text: String) {
        val wordCount = text.count { it in '\u4e00'..'\u9fa5' || it.isLetter() }
        val estSeconds = if (wordCount > 0) (wordCount * 60 / 165).coerceAtLeast(3) else 0
        val lineCount = text.lines().count { it.isNotBlank() }

        binding.tvWordCountStat.text = "统计：$wordCount 字 ｜ 预计 $estSeconds 秒 ｜ $lineCount 行"
    }

    private fun ensurePrerequisitesReady(): Boolean {
        val content = binding.etScriptInput.text.toString()
        if (content.isBlank()) {
            Toast.makeText(this, "台词内容不能为空！", Toast.LENGTH_SHORT).show()
            return false
        }

        // 自动排版并持久化
        if (prefs.formattedScript.isBlank() || prefs.rawScript != content) {
            val result = ScriptFormatter.formatForTeleprompter(
                content,
                filterStage = binding.cbFilterStage.isChecked
            )
            prefs.rawScript = content
            prefs.formattedScript = result.formattedText
        }

        // 检查悬浮窗权限（SYSTEM_ALERT_WINDOW）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请开启【显示在其他应用上层】权限，以在相机上方提词", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }

        // 检查麦克风权限（仅在语音跟读模式下需要）
        if (prefs.isVoiceMode && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAudioPermission()
            return false
        }

        return true
    }

    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatPrompterService::class.java).apply {
            action = FloatPrompterService.ACTION_SHOW_PROMPTER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun launchDeviceCamera() {
        try {
            val videoCaptureIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(videoCaptureIntent)
        } catch (_: Exception) {
            try {
                val stillCameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(stillCameraIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法自动打开系统相机，请手动切换到相机", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ==================== 应用内一键升级功能 ====================

    private fun checkUpdateManual() {
        Toast.makeText(this, "正在检查 GitHub 最新版本...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = UpdateManager.checkLatestVersion(BuildConfig.VERSION_NAME)
            result.onSuccess { info ->
                if (info.hasNewVersion) {
                    showUpdateDialog(info)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "已是最新版本 (v${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                Toast.makeText(
                    this@MainActivity,
                    "检查更新失败: ${err.message ?: "网络超时"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun silentCheckUpdate() {
        lifecycleScope.launch {
            val result = UpdateManager.checkLatestVersion(BuildConfig.VERSION_NAME)
            result.onSuccess { info ->
                if (info.hasNewVersion) {
                    showUpdateDialog(info)
                }
            }
        }
    }

    private fun showUpdateDialog(info: VersionInfo) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${info.versionName} 🚀")
            .setMessage("【更新日志】\n${info.releaseNotes}\n\n💡 提示：应用内直接覆盖升级，所有设置与已授权权限完全保留，无需重新配置！")
            .setPositiveButton("立即更新") { _, _ ->
                startDownloadAndInstall(info.downloadUrl)
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(true)
            .show()
    }

    private fun startDownloadAndInstall(downloadUrl: String) {
        // Android 8.0+ 检查未知来源安装权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "请开启【允许安装来自此来源的应用】以完成覆盖升级", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
        }

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("正在下载最新安装包")
            setMessage("国内高速镜像加速下载中...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = UpdateManager.downloadAndInstallApk(
                activity = this@MainActivity,
                downloadUrl = downloadUrl,
                onProgress = { percent ->
                    progressDialog.progress = percent
                    progressDialog.setMessage("已下载 $percent%...")
                }
            )

            progressDialog.dismiss()

            result.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "下载失败: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
