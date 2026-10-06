package com.shawn.floatprompter.engine

import java.util.regex.Pattern

object ScriptFormatter {

    data class FormatResult(
        val formattedText: String,
        val lines: List<String>,
        val wordCount: Int,
        val estimatedSeconds: Int
    )

    /**
     * 将原始长文本自动整理为适合口播悬浮窗展示的黄金短句格式
     * @param rawText 原始文本
     * @param filterStage 是否过滤掉舞台/动作提示（如括号里的字）
     * @param maxCharsPerLine 每行目标字数（推荐 8~12 字）
     */
    fun formatForTeleprompter(
        rawText: String,
        filterStage: Boolean = true,
        maxCharsPerLine: Int = 11
    ): FormatResult {
        if (rawText.isBlank()) {
            return FormatResult("", emptyList(), 0, 0)
        }

        var text = rawText

        // 1. 过滤 Markdown 标题与装饰符
        text = text.replace(Regex("(?m)^[#\\->*]+\\s*"), "")
        text = text.replace("**", "").replace("__", "")

        // 2. 识别并处理中英文括号中的舞台/神态提示
        val stagePattern = Pattern.compile("（[^）]*）|\\([^)]*\\)|【[^】]*】")
        if (filterStage) {
            text = stagePattern.matcher(text).replaceAll("")
        } else {
            // 如果保留动作，将其独占一行
            text = stagePattern.matcher(text).replaceAll("\n$0\n")
        }

        // 3. 基于标点符号断句（，。！？；…：\n）
        val delimiters = "([，。！？；…\n]+)"
        val rawTokens = text.split(Regex(delimiters))

        val finalLines = mutableListOf<String>()

        for (token in rawTokens) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue

            // 如果这一句太长（超过 maxCharsPerLine），进一步切细
            if (trimmed.length > maxCharsPerLine + 3) {
                val subChunks = splitLongSentence(trimmed, maxCharsPerLine)
                finalLines.addAll(subChunks)
            } else {
                finalLines.add(trimmed)
            }
        }

        // 4. 重建带有节奏空行的悬浮窗文本
        val builder = StringBuilder()
        var chunkCount = 0
        for (line in finalLines) {
            builder.append(line).append("\n")
            chunkCount++
            // 每 4 个短句加一个轻微呼吸空行
            if (chunkCount % 4 == 0) {
                builder.append("\n")
            }
        }

        // 统计汉字与单词数
        val cleanChineseCount = text.count { it in '\u4e00'..'\u9fa5' }
        val englishWords = text.split(Regex("\\s+")).count { it.matches(Regex("[a-zA-Z]+")) }
        val totalWordCount = cleanChineseCount + englishWords
        // 按正常中文口播语速 165 字/分钟 计算预估时长
        val estimatedSec = if (totalWordCount > 0) (totalWordCount * 60 / 165).coerceAtLeast(5) else 0

        return FormatResult(
            formattedText = builder.toString().trimEnd(),
            lines = finalLines,
            wordCount = totalWordCount,
            estimatedSeconds = estimatedSec
        )
    }

    /**
     * 将长句子按语意或字数均匀切开为短句
     */
    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        var remaining = sentence

        // 常见口语连接词断点
        val breakKeywords = listOf("因为", "所以", "但是", "而且", "然后", "结果", "哪怕", "万一", "其实", "也就是说")

        while (remaining.length > maxChars + 3) {
            var cutIndex = -1

            // 优先在连接词前切断
            for (kw in breakKeywords) {
                val pos = remaining.indexOf(kw)
                if (pos in 4..(maxChars + 2)) {
                    cutIndex = pos
                    break
                }
            }

            // 如果没找到连接词，在中点或 maxChars 处硬切
            if (cutIndex == -1) {
                cutIndex = maxChars.coerceAtMost(remaining.length)
            }

            val part = remaining.substring(0, cutIndex).trim()
            if (part.isNotEmpty()) result.add(part)
            remaining = remaining.substring(cutIndex).trim()
        }

        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }

        return result
    }
}
