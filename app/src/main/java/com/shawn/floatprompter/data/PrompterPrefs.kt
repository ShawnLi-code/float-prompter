package com.shawn.floatprompter.data

import android.content.Context
import android.content.SharedPreferences

class PrompterPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("float_prompter_prefs", Context.MODE_PRIVATE)

    var rawScript: String
        get() = prefs.getString("raw_script", "") ?: ""
        set(value) = prefs.edit().putString("raw_script", value).apply()

    var formattedScript: String
        get() = prefs.getString("formatted_script", "") ?: ""
        set(value) = prefs.edit().putString("formatted_script", value).apply()

    var scrollSpeed: Int
        get() = prefs.getInt("scroll_speed", 4) // 1 ~ 10
        set(value) = prefs.edit().putInt("scroll_speed", value).apply()

    var opacity: Int
        get() = prefs.getInt("opacity", 80) // 20 ~ 100
        set(value) = prefs.edit().putInt("opacity", value).apply()

    var fontSizeSp: Float
        get() = prefs.getFloat("font_size", 19f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    var isVoiceMode: Boolean
        get() = prefs.getBoolean("is_voice_mode", false)
        set(value) = prefs.edit().putBoolean("is_voice_mode", value).apply()

    var filterStageDirections: Boolean
        get() = prefs.getBoolean("filter_stage_directions", true)
        set(value) = prefs.edit().putBoolean("filter_stage_directions", value).apply()

    var windowWidthDp: Int
        get() = prefs.getInt("window_width_dp", 240) // 窄窗设计
        set(value) = prefs.edit().putInt("window_width_dp", value).apply()
}
