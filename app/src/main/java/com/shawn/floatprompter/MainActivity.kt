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
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shawn.floatprompter.data.PrompterPrefs
import com.shawn.floatprompter.data.SampleScripts
import com.shawn.floatprompter.data.SavedScript
import com.shawn.floatprompter.data.ScriptManager
import com.shawn.floatprompter.databinding.ActivityMainBinding
import com.shawn.floatprompter.engine.ScriptFormatter
import com.shawn.floatprompter.service.FloatPrompterService
import com.shawn.floatprompter.update.UpdateManager
import com.shawn.floatprompter.update.VersionInfo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrompterPrefs
    private lateinit var scriptManager: ScriptManager

    // 麦克风录音权限申请
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "麦克风权限已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予麦克风权限，自动切回定时匀速滚屏", Toast.LENGTH_SHORT).show()
            binding.rbModeSpeed.isChecked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrompterPrefs(this)
        scriptManager = ScriptManager(this)

        initViewsAndSavedData()
        setupListeners()

        // 静默检查更新（不打扰用户，有新版本才弹窗提示）
        silentCheckUpdate()
    }

    private fun initViewsAndSavedData() {
        binding.tvVersionInfo.text = "v${BuildConfig.VERSION_NAME} • 流光提词"

        // 仅载入用户之前编辑的真实草稿，不主动强行注入任何示例文案
        val savedRaw = prefs.rawScript
        binding.etScriptInput.setText(savedRaw)

        // 核心：设置输入框内部独立纵向顺畅滑动（解决外层 NestedScrollView 拦截滑动事件的问题）
        binding.etScriptInput.movementMethod = ScrollingMovementMethod.getInstance()
        binding.etScriptInput.setOnTouchListener { v, event ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

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
        // 0. 权限自检引导（针对 OPPO / 小米等定制系统）
        binding.btnPermissionGuide.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "OPPO 手机请确认【权限】中的【悬浮窗】与【后台弹出界面】均已允许", Toast.LENGTH_LONG).show()
        }

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
                    Toast.makeText(this, "已粘贴并完成整句排版！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. 💾 保存稿件到本地稿件库
        binding.btnSaveScript.setOnClickListener {
            showSaveScriptDialog()
        }

        // 4. 📚 查看并调用历史稿件库
        binding.btnScriptLibrary.setOnClickListener {
            showScriptLibraryDialog()
        }

        // 5. 导入《6个月法则》样本（仅在用户主动点击时才载入）
        binding.btnLoadSample.setOnClickListener {
            binding.etScriptInput.setText(SampleScripts.DEFAULT_SCRIPT_CONTENT)
            autoFormatText()
            Toast.makeText(this, "已载入《6个月法则》示例台词！", Toast.LENGTH_SHORT).show()
        }

        // 6. 清空
        binding.btnClear.setOnClickListener {
            binding.etScriptInput.setText("")
            prefs.rawScript = ""
            prefs.formattedScript = ""
            binding.tvFormatStatus.text = "已清空"
            binding.tvFormatStatus.setTextColor(getColor(R.color.text_muted))
            updateTextStats("")
        }

        // 7. 核心：整句智能排版（一句话讲完才换行，逗号不打碎）
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
                Toast.makeText(
                    this,
                    "💡 国内手机系统（OPPO/小米等）因缺少谷歌语音服务可能无法跟读，强烈推荐使用稳定的【⏱️ 匀速滚屏】",
                    Toast.LENGTH_LONG
                ).show()
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

        // 8. 开启提词并直接打开手机原厂相机（高画质、支持美颜与4K）
        binding.btnLaunchCamera.setOnClickListener {
            if (ensurePrerequisitesReady()) {
                startFloatingService(showBall = false)
                launchDeviceCamera()
            }
        }

        // 9. 仅显示悬浮球（屏幕边缘常驻小圆球，随时轻点弹出）
        binding.btnStartFloat.setOnClickListener {
            if (ensurePrerequisitesReady()) {
                startFloatingService(showBall = true)
                Toast.makeText(this, "🟢 悬浮小球已就绪！轻点屏幕边缘小球即可弹出提词器", Toast.LENGTH_LONG).show()
            }
        }

        // 10. 关闭悬浮窗
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

        binding.tvFormatStatus.text = "✅ 完整整句格式已就绪"
        binding.tvFormatStatus.setTextColor(getColor(R.color.accent_emerald))
        updateTextStats(result.formattedText)

        Toast.makeText(
            this,
            "整句排版完成！共 ${result.wordCount} 字，预计 ${result.estimatedSeconds} 秒",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateTextStats(text: String) {
        val wordCount = text.count { it in '\u4e00'..'\u9fa5' || it.isLetter() }
        val estSeconds = if (wordCount > 0) (wordCount * 60 / 165).coerceAtLeast(3) else 0
        val lineCount = text.lines().count { it.isNotBlank() }

        binding.tvWordCountStat.text = "统计：$wordCount 字 ｜ 预计 $estSeconds 秒 ｜ $lineCount 句"
    }

    // ==================== 💾 稿件库管理交互 ====================

    private fun showSaveScriptDialog() {
        val content = binding.etScriptInput.text.toString().trim()
        if (content.isBlank()) {
            Toast.makeText(this, "台词内容为空，无法保存", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultTitle = content.lines().firstOrNull { it.isNotBlank() }?.take(16)?.trim()
            ?: "台词稿_${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"

        val inputView = EditText(this).apply {
            setText(defaultTitle)
            setSelection(text.length)
            setPadding(48, 36, 48, 36)
            setTextColor(getColor(R.color.text_main))
            textSize = 14f
        }

        AlertDialog.Builder(this)
            .setTitle("💾 保存当前台词稿")
            .setMessage("为这篇台词输入一个标题：")
            .setView(inputView)
            .setPositiveButton("确认保存") { _, _ ->
                val title = inputView.text.toString().trim().ifEmpty { defaultTitle }
                val wordCount = content.count { it in '\u4e00'..'\u9fa5' || it.isLetter() }
                val estSec = if (wordCount > 0) (wordCount * 60 / 165).coerceAtLeast(3) else 0
                scriptManager.saveScript(title, content, wordCount, estSec)
                Toast.makeText(this, "稿件《$title》已成功存入历史库！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showScriptLibraryDialog() {
        val scripts = scriptManager.getAllScripts()
        if (scripts.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📚 历史稿件库")
                .setMessage("暂无保存的稿件记录。\n\n💡 提示：在上方文本框写好或粘贴台词后，点击【💾 保存稿件】即可永久留存，随时一键调用！")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val items = scripts.map { script ->
            val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(script.updatedAt))
            "📝 ${script.title}\n    ${script.wordCount}字 · 约${script.estimatedSeconds}秒 · $dateStr"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📚 历史稿件库 (${scripts.size}篇)")
            .setItems(items) { _, which ->
                val selected = scripts[which]
                showScriptActionDialog(selected)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showScriptActionDialog(script: SavedScript) {
        val preview = if (script.content.length > 100) script.content.take(100) + "..." else script.content
        AlertDialog.Builder(this)
            .setTitle("《${script.title}》")
            .setMessage("字数：${script.wordCount} 字 ｜ 预计用时：${script.estimatedSeconds} 秒\n\n正文预览：\n$preview")
            .setPositiveButton("✅ 载入使用") { _, _ ->
                binding.etScriptInput.setText(script.content)
                prefs.rawScript = script.content
                autoFormatText()
                Toast.makeText(this, "已载入《${script.title}》！", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("🗑️ 删除此稿") { _, _ ->
                scriptManager.deleteScript(script.id)
                Toast.makeText(this, "已删除《${script.title}》", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
            Toast.makeText(this, "请开启【显示在其他应用上层】权限，以在屏幕上显示提词悬浮窗", Toast.LENGTH_LONG).show()
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

    private fun startFloatingService(showBall: Boolean = false) {
        val serviceIntent = Intent(this, FloatPrompterService::class.java).apply {
            action = if (showBall) FloatPrompterService.ACTION_SHOW_BALL else FloatPrompterService.ACTION_SHOW_PROMPTER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    /**
     * 打开手机原厂自带的高清专业相机（杜绝低画质彩信录像模式）
     */
    private fun launchDeviceCamera() {
        val pm = packageManager

        // 优先探测主流手机厂商的原厂相机 App 包名
        val oemCameraPackages = listOf(
            "com.android.camera",               // 小米 / 红米 MIUI & HyperOS 原厂相机
            "com.miui.camera",
            "com.oppo.camera",                  // OPPO / 一加 ColorOS 原厂相机
            "com.oneplus.camera",
            "com.vivo.camera",                  // vivo / iQOO OriginOS 原厂相机
            "com.huawei.camera",                // 华为 HarmonyOS 原厂相机
            "com.hihonor.camera",               // 荣耀 MagicOS 原厂相机
            "com.sec.android.app.camera",       // 三星 OneUI 原厂高清相机
            "com.google.android.GoogleCamera",  // 谷歌 Pixel 原厂相机
            "com.motorola.cameraone",           // 摩托罗拉
            "com.meizu.media.camera",           // 魅族 Flyme
            "com.transsion.camera"              // 传音
        )

        var launched = false
        for (pkg in oemCameraPackages) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    launched = true
                    break
                } catch (_: Exception) {}
            }
        }

        // 如果未命中特定厂商包名，唤起标准的独立静态相机 Intent (非低画质彩信录像 Intent)
        if (!launched) {
            try {
                val stillCameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (stillCameraIntent.resolveActivity(pm) != null) {
                    startActivity(stillCameraIntent)
                    launched = true
                }
            } catch (_: Exception) {}
        }

        if (!launched) {
            Toast.makeText(this, "提词悬浮窗已就绪，请手动点开您的相机App即可", Toast.LENGTH_SHORT).show()
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
