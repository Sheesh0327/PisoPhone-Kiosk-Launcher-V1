package com.pisophone.kiosk.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Facade coordinating Text-To-Speech alert messages.
 */
class KioskAudioManager(
    context: Context,
    scope: CoroutineScope
) {

    private val ttsEngine = KioskTtsEngine(
        context = context,
        onTtsStart = { /* No-op */ },
        onTtsFinish = { /* No-op */ }
    )

    fun initAudioEngine() {
        ttsEngine.initTts()
    }

    fun speakWarning(text: String) {
        ttsEngine.speakWarning(text)
    }

    fun playCoinSound() {
        // No-op
    }

    fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        // No-op
    }

    fun playPcmBuffer(buffer: ShortArray, sampleRate: Int, durationMs: Int? = null) {
        // No-op
    }

    fun startWaitingMusic() {
        // No-op
    }

    fun stopWaitingMusic() {
        // No-op
    }

    fun playAnnoyingLowBatteryTone() {
        // No-op
    }

    fun playHighBatteryAttentionTone() {
        // No-op
    }

    fun shutdown() {
        ttsEngine.shutdown()
    }
}
