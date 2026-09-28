package com.pisophone.kiosk.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Facade coordinating Text-To-Speech alert messages and hardware-ducked synthesized audio tracks.
 */
class KioskAudioManager(
    context: Context,
    scope: CoroutineScope
) {
    private val synthesizer = KioskSoundSynthesizer(context, scope)

    private val ttsEngine = KioskTtsEngine(
        context = context,
        onTtsStart = { /* No-op: keep background countdown chime loop playing continuously under speech counts */ },
        onTtsFinish = { /* No-op */ },
        onFallbackTone = { freqHz, durationMs -> synthesizer.playSynthesizedTone(freqHz, durationMs) }
    )

    fun initAudioEngine() {
        ttsEngine.initTts()
        synthesizer.initAudioEngine()
    }

    fun speakWarning(text: String) {
        ttsEngine.speakWarning(text)
    }

    fun playCoinSound() {
        synthesizer.playCoinSound()
    }

    fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        synthesizer.playSynthesizedTone(freqHz, durationMs)
    }

    fun playPcmBuffer(buffer: ShortArray, sampleRate: Int, durationMs: Int? = null) {
        synthesizer.playPcmBuffer(buffer, sampleRate, durationMs)
    }

    fun startWaitingMusic() {
        synthesizer.startWaitingMusic { ttsEngine.isSpeaking() }
    }

    fun stopWaitingMusic() {
        synthesizer.stopWaitingMusic()
    }

    fun playAnnoyingLowBatteryTone() {
        synthesizer.playAnnoyingLowBatteryTone()
    }

    fun playHighBatteryAttentionTone() {
        synthesizer.playHighBatteryAttentionTone()
    }

    fun shutdown() {
        ttsEngine.shutdown()
        synthesizer.release()
    }
}
