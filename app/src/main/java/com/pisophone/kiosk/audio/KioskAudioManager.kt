package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Facade coordinating Text-To-Speech alert messages and real-time audio sound synthesis.
 */
class KioskAudioManager(
    context: Context,
    private val scope: CoroutineScope
) {

    private val ttsEngine = KioskTtsEngine(
        context = context,
        onTtsStart = { /* No-op */ },
        onTtsFinish = { /* No-op */ },
        onFallbackTone = { freq, dur -> playSynthesizedTone(freq, dur) }
    )

    fun initAudioEngine() {
        ttsEngine.initTts()
    }

    fun speakWarning(text: String) {
        ttsEngine.speakWarning(text)
    }

    fun playCoinSound() {
        scope.launch(Dispatchers.Default) {
            playSynthesizedTone(987, 80)
            kotlinx.coroutines.delay(85)
            playSynthesizedTone(1318, 150)
        }
    }

    fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        scope.launch(Dispatchers.Default) {
            try {
                val sampleRate = 8000
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                if (numSamples <= 0) return@launch
                val sample = DoubleArray(numSamples)
                val generatedSnd = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    sample[i] = Math.sin(2 * Math.PI * i / (sampleRate.toDouble() / freqHz))
                    generatedSnd[i] = (sample[i] * 32767).toInt().toShort()
                }

                val audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    generatedSnd.size * 2,
                    AudioTrack.MODE_STATIC
                )
                audioTrack.write(generatedSnd, 0, generatedSnd.size)
                audioTrack.play()

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        audioTrack.stop()
                        audioTrack.release()
                    } catch (e: Exception) {}
                }, durationMs + 100L)
            } catch (e: Exception) {
                android.util.Log.e("KioskAudioManager", "Failed to play tone: ${e.message}")
            }
        }
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
        scope.launch(Dispatchers.Default) {
            for (i in 0 until 3) {
                playSynthesizedTone(523, 120)
                kotlinx.coroutines.delay(160)
            }
        }
    }

    fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.Default) {
            playSynthesizedTone(880, 100)
            kotlinx.coroutines.delay(120)
            playSynthesizedTone(880, 100)
        }
    }

    fun shutdown() {
        ttsEngine.shutdown()
    }
}
