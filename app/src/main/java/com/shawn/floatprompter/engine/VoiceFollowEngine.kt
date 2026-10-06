package com.shawn.floatprompter.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceFollowEngine(
    private val context: Context,
    private val lines: List<String>,
    private val onLineScrolled: (targetLineIndex: Int) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLineIndex = 0

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.d("VoiceFollowEngine", "SpeechRecognizer error: $error")
                        // 遇到静音或网络超时，若仍在开启状态则自动重连
                        if (isListening) {
                            startListeningInternal()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        handleSpokenText(results)
                        if (isListening) {
                            startListeningInternal()
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        handleSpokenText(partialResults)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun handleSpokenText(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return

        val spoken = matches[0].replace(Regex("\\s+"), "")
        if (spoken.length < 2) return

        // 在当前行及其后 5 行内做局部关键词比对
        val searchRange = (currentLineIndex until (currentLineIndex + 5).coerceAtMost(lines.size))
        for (i in searchRange) {
            val candidate = lines[i].replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "")
            if (candidate.length >= 2 && spoken.contains(candidate.take(4))) {
                currentLineIndex = i
                onLineScrolled(i)
                break
            }
        }
    }

    fun start() {
        isListening = true
        startListeningInternal()
    }

    private fun startListeningInternal() {
        if (!isListening || recognizer == null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("VoiceFollowEngine", "Failed to start listening", e)
        }
    }

    fun stop() {
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
    }

    fun destroy() {
        stop()
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }
}
