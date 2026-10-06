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
     * 将长文本台词自动整理为适合口播提词的自然整句格式
     * 核心规则：一句话完整结束后才换行（逗号、顿号绝不拆散），保持语意完整连贯
     * @param rawText 原始文本
     * @param filterStage 是否过滤掉舞台/动作提示（如括号里的动作字样）
     * @param maxSentenceChars 单句最大字数限制（极长且无标点时才平滑分段，默认 60 字）
     */
    fun formatForTeleprompter(
        rawText: String,
        filterStage: Boolean = true,
        maxSentenceChars: Int = 60
    ): FormatResult {
        if (rawText.isBlank()) {
            return FormatResult("", emptyList(), 0, 0)
        }

        var text = rawText

        // 1. 过滤 Markdown 标记与修饰符
        text = text.replace(Regex("(?m)^[#\\->*]+\\s*"), "")
        text = text.replace("**", "").replace("__", "")

        // 2. 识别并过滤中英文括号中的动作神态提示（例如：（看一眼镜头）、(停顿1秒)、【笑】）
        if (filterStage) {
            val stagePattern = Pattern.compile("（[^）]*）|\\([^)]*\\)|【[^】]*】")
            text = stagePattern.matcher(text).replaceAll("")
        }

        // 3. 按原始换行和句子终止符（句号、感叹号、问号、分号）进行自然完整断句
        // 保持逗号、顿号、破折号在整句内，绝不碎片化
        val rawParagraphs = text.lines()
        val finalLines = mutableListOf<String>()

        for (paragraph in rawParagraphs) {
            val trimmedPara = paragraph.trim()
            if (trimmedPara.isEmpty()) continue

            // 使用句子终止标点（。！？；!?）切分段落为完整语义句，同时保留终止标点
            val sentenceTokens = splitBySentenceEnd(trimmedPara)

            for (sentence in sentenceTokens) {
                val cleanSentence = sentence.trim()
                if (cleanSentence.isEmpty()) continue

                // 仅当整句极端冗长（超过 60 字且无断句）时才分行，避免视线过长
                if (cleanSentence.length > maxSentenceChars) {
                    val subChunks = cleanSentence.chunked(maxSentenceChars)
                    finalLines.addAll(subChunks)
                } else {
                    finalLines.add(cleanSentence)
                }
            }
        }

        // 4. 生成换行文本（每句独立成行，保留舒适自然的行间节奏）
        val builder = StringBuilder()
        for (line in finalLines) {
            builder.append(line).append("\n")
        }

        // 5. 统计字数与预估用时
        val cleanChineseCount = text.count { it in '\u4e00'..'\u9fa5' }
        val englishWords = text.split(Regex("\\s+")).count { it.matches(Regex("[a-zA-Z]+")) }
        val totalWordCount = cleanChineseCount + englishWords
        // 按正常中文口播语速 165 字/分钟 计算预估时长
        val estimatedSec = if (totalWordCount > 0) (totalWordCount * 60 / 165).coerceAtLeast(3) else 0

        return FormatResult(
            formattedText = builder.toString().trimEnd(),
            lines = finalLines,
            wordCount = totalWordCount,
            estimatedSeconds = estimatedSec
        )
    }

    /**
     * 将段落按句末标点符号拆分为完整句子，标点保留在句末
     */
    private fun splitBySentenceEnd(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (char in text) {
            current.append(char)
            if (char == '。' || char == '！' || char == '？' || char == '!' || char == '?' || char == '；') {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) {
                    result.add(sentence)
                }
                current.clear()
            }
        }

        val remaining = current.toString().trim()
        if (remaining.isNotEmpty()) {
            result.add(remaining)
        }

        return result
    }
}
