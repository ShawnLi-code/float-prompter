package com.shawn.floatprompter.engine

import kotlinx.coroutines.*

class PacedScrollEngine(
    private val onScrollStep: (dy: Int) -> Unit
) {
    private var job: Job? = null
    var isRunning: Boolean = false
        private set

    var speedLevel: Int = 4 // 1 ~ 10

    fun start() {
        if (isRunning) return
        isRunning = true
        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isRunning) {
                // 每 50ms 走一步，根据 speedLevel (1~10) 映射为 1~6 像素
                val dy = (speedLevel * 0.6f).toInt().coerceAtLeast(1)
                onScrollStep(dy)
                delay(50)
            }
        }
    }

    fun pause() {
        isRunning = false
        job?.cancel()
        job = null
    }

    fun toggle(): Boolean {
        if (isRunning) {
            pause()
        } else {
            start()
        }
        return isRunning
    }

    fun destroy() {
        pause()
    }
}
