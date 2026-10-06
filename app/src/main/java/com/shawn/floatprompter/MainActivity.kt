package com.shawn.floatprompter

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shawn.floatprompter.data.PrompterPrefs
import com.shawn.floatprompter.data.SampleScripts
import com.shawn.floatprompter.databinding.ActivityMainBinding
import com.shawn.floatprompter.engine.ScriptFormatter
import com.shawn.floatprompter.service.FloatPrompterService

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

        initSavedData()
        setupListeners()
    }

    private fun initSavedData() {
        // 如果本地没有内容，默认填入 Shawn 的《6个月法则》口播文案
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
        // 从剪贴板粘贴
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

        // 导入《6个月法则》样本
        binding.btnLoadSample.setOnClickListener {
            binding.etScriptInput.setText(SampleScripts.DEFAULT_SCRIPT_CONTENT)
            autoFormatText()
            Toast.makeText(this, "已载入《6个月法则》口播稿！", Toast.LENGTH_SHORT).show()
        }

        // 清空
        binding.btnClear.setOnClickListener {
            binding.etScriptInput.setText("")
            prefs.rawScript = ""
            prefs.formattedScript = ""
            updateTextStats("")
        }

        // 核心：智能整理排版
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

        // 启动悬浮窗
        binding.btnStartFloat.setOnClickListener {
            startFloatingPrompter()
        }

        // 关闭悬浮窗
        binding.btnStopFloat.setOnClickListener {
            stopService(Intent(this, FloatPrompterService::class.java))
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
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

        // 保存排版结果
        prefs.rawScript = raw
        prefs.formattedScript = result.formattedText
        binding.etScriptInput.setText(result.formattedText)

        binding.tvFormatStatus.text = "✅ 窄窗格式已就绪"
        binding.tvFormatStatus.setTextColor(getColor(R.color.accent_emerald))
        updateTextStats(result.formattedText)

        Toast.makeText(
            this,
            "排版完成！共 ${result.wordCount} 字，约 ${result.estimatedSeconds} 秒",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateTextStats(text: String) {
        val wordCount = text.count { it in '\u4e00'..'\u9fa5' || it.isLetter() }
        val estSeconds = if (wordCount > 0) (wordCount * 60 / 165).coerceAtLeast(3) else 0
        val lineCount = text.lines().count { it.isNotBlank() }

        binding.tvWordCountStat.text = "统计：$wordCount 字 ｜ 预计 $estSeconds 秒 ｜ $lineCount 行"
    }

    private fun startFloatingPrompter() {
        val content = binding.etScriptInput.text.toString()
        if (content.isBlank()) {
            Toast.makeText(this, "台词内容不能为空！", Toast.LENGTH_SHORT).show()
            return
        }

        // 确保格式化已保存
        if (prefs.formattedScript.isBlank() || prefs.rawScript != content) {
            val result = ScriptFormatter.formatForTeleprompter(
                content,
                filterStage = binding.cbFilterStage.isChecked
            )
            prefs.rawScript = content
            prefs.formattedScript = result.formattedText
        }

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启【显示在其他应用上层】悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 检查麦克风权限
        if (prefs.isVoiceMode && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkAudioPermission()
            return
        }

        // 启动服务
        val serviceIntent = Intent(this, FloatPrompterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "悬浮窗已开启！可打开相机直接开拍", Toast.LENGTH_SHORT).show()

        // 自动回到手机主屏幕，方便直接点开相机录制
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
