package com.shawn.floatprompter.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class SavedScript(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val wordCount: Int,
    val estimatedSeconds: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

class ScriptManager(context: Context) {
    private val prefs = context.getSharedPreferences("saved_scripts_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAllScripts(): List<SavedScript> {
        val json = prefs.getString("script_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedScript>>() {}.type
            gson.fromJson<List<SavedScript>>(json, type).sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveScript(title: String, content: String, wordCount: Int, estimatedSeconds: Int): SavedScript {
        val list = getAllScripts().toMutableList()
        // 查找是否已存在相同标题的稿件，有则更新
        val existingIndex = list.indexOfFirst { it.title == title }
        val newScript = SavedScript(
            id = if (existingIndex >= 0) list[existingIndex].id else UUID.randomUUID().toString(),
            title = title.ifBlank { "未命名稿件 " + System.currentTimeMillis().toString().takeLast(4) },
            content = content,
            wordCount = wordCount,
            estimatedSeconds = estimatedSeconds,
            updatedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            list[existingIndex] = newScript
        } else {
            list.add(0, newScript)
        }

        persistList(list)
        return newScript
    }

    fun deleteScript(id: String) {
        val list = getAllScripts().filter { it.id != id }
        persistList(list)
    }

    private fun persistList(list: List<SavedScript>) {
        val json = gson.toJson(list)
        prefs.edit().putString("script_list", json).apply()
    }
}
