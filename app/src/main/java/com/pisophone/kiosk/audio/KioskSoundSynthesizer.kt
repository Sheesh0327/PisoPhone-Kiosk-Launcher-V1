package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * High-fidelity, studio-grade audio synthesizer for the PisoPhone Kiosk.
 * Generates crystal-clean, click-free acoustic chimes, arcade coin sounds,
 * and a synchronized, elegant payment countdown tone loop without phase distortion.
 */
class KioskSoundSynthesizer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "KioskSoundSynthesizer"
        private const val SAMPLE_RATE = 44100 // Standard CD-quality 44.1kHz PCM
    }

    private var coinAudioTrack: AudioTrack? = null
    private var waitingMusicTrack: AudioTrack? = null
    @Volatile private var isWaitingMusicDesired = false
    private var waitingMusicJob: Job? = null
    private val audioLock = Any()
    private var precomputedWaitingBuffer: ShortArray? = null

    fun initAudioEngine() {
        // Pre-warm the synthesized coin audio track and waiting chime buffer
        scope.launch(Dispatchers.IO) {
            try {
                getOrCreateCoinAudioTrack()
                if (precomputedWaitingBuffer == null) {
                    precomputedWaitingBuffer = generateWaitingMusicBuffer()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio pre-warm error: ${e.message}")
            }
        }
    }

    /**
     * Crisp, metallic arcade coin drop sound effect:
     * Two harmonically rich bells (B5 987.8Hz -> E6 1318.5Hz) with continuous phase,
     * smooth attack windows, and natural acoustic ringdown.
     */
    private fun getOrCreateCoinAudioTrack(): AudioTrack? {
        if (coinAudioTrack != null) return coinAudioTrack
        synchronized(audioLock) {
            if (coinAudioTrack != null) return coinAudioTrack
            try {
                val sampleRate = SAMPLE_RATE
                val durationSec = 0.32
                val numSamples = (durationSec * sampleRate).toInt()
                val buffer = ShortArray(numSamples)

                val note1DurationSec = 0.07
                val note1Samples = (note1DurationSec * sampleRate).toInt()
                val f1 = 987.77   // B5
                val f2 = 1318.51  // E6

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    var sampleVal: Double

                    if (i < note1Samples) {
                        val tNote = t
                        // 4ms smooth cosine attack
                        val attack = if (tNote < 0.004) Math.sin((tNote / 0.004) * (Math.PI / 2.0)) else 1.0
                        val decay = 1.0 - (tNote / note1DurationSec) * 0.20
                        val wave = 0.70 * Math.sin(2.0 * Math.PI * f1 * tNote) +
                                   0.20 * Math.sin(2.0 * Math.PI * (f1 * 2.0) * tNote)
                        sampleVal = wave * attack * decay
                    } else {
                        val tNote = t - note1DurationSec
                        // 3ms smooth cosine attack from 0
                        val attack = if (tNote < 0.003) Math.sin((tNote / 0.003) * (Math.PI / 2.0)) else 1.0
                        val decay = Math.exp(-tNote * 14.0)
                        val wave = 0.72 * Math.sin(2.0 * Math.PI * f2 * tNote) +
                                   0.22 * Math.sin(2.0 * Math.PI * (f2 * 2.0) * tNote) +
                                   0.06 * Math.sin(2.0 * Math.PI * (f2 * 3.0) * tNote)
                        sampleVal = wave * attack * decay
                    }

                    // 10ms smooth end fade out to absolute zero to prevent speaker click/pop
                    if (t > durationSec - 0.01) {
                        val fade = (durationSec - t) / 0.01
                        sampleVal *= fade.coerceIn(0.0, 1.0)
                    }

                    buffer[i] = (sampleVal * 25000.0).toInt().coerceIn(-32767, 32767).toShort()
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                coinAudioTrack = track
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create coin audio track", e)
            }
        }
        return coinAudioTrack
    }

    /**
     * Professional, studio-quality 1.0-second countdown chime loop:
     * - Exactly 1.000 second duration (44,100 samples) locked 1:1 to the on-screen countdown seconds.
     * - Pure, resonant dual chime: Beat 1 (0.0s) C6 (1046.5Hz bell) -> Beat 2 (0.45s) G5 (784Hz marimba).
     * - Pure phase continuity (starts at sin(0)=0 with 5ms smooth attack envelope).
     * - Smooth zero-crossing fadeout by 0.90s ensuring 100% pop-free, click-free seamless loop transitions.
     * - Zero muddy bass drones or harsh noise clicks.
     */
    private fun generateWaitingMusicBuffer(): ShortArray {
        val sampleRate = SAMPLE_RATE
        val loopDurationSec = 1.0 // Exactly 1.0s locked to the 1-second countdown clock
        val totalSamples = (loopDurationSec * sampleRate).toInt()
        val buffer = ShortArray(totalSamples)

        val f1 = 1046.50 // C6 primary chime (beat 1, on the exact second tick)
        val f2 = 783.99  // G5 warm harmony chime (offbeat response at 0.42s)
        val note2StartSec = 0.42

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // Note 1: Clear crystal chime at t = 0.0s
            val tNote1 = t
            val attack1 = if (tNote1 < 0.005) Math.sin((tNote1 / 0.005) * (Math.PI / 2.0)) else 1.0
            val decay1 = Math.exp(-tNote1 * 9.0)
            val wave1 = (0.70 * Math.sin(2.0 * Math.PI * f1 * tNote1) +
                         0.22 * Math.sin(2.0 * Math.PI * (f1 * 2.0) * tNote1) +
                         0.08 * Math.sin(2.0 * Math.PI * (f1 * 3.0) * tNote1)) * attack1 * decay1

            // Note 2: Warm harmony chime at t = 0.42s
            var wave2 = 0.0
            if (t >= note2StartSec) {
                val tNote2 = t - note2StartSec
                val attack2 = if (tNote2 < 0.005) Math.sin((tNote2 / 0.005) * (Math.PI / 2.0)) else 1.0
                val decay2 = Math.exp(-tNote2 * 11.0)
                wave2 = (0.65 * Math.sin(2.0 * Math.PI * f2 * tNote2) +
                         0.25 * Math.sin(2.0 * Math.PI * (f2 * 2.0) * tNote2) +
                         0.10 * Math.sin(2.0 * Math.PI * (f2 * 3.0) * tNote2)) * 0.65 * attack2 * decay2
            }

            var combined = wave1 + wave2

            // Seamless boundary window: fade to absolute zero in final 80ms
            if (t > 0.90) {
                val fade = (1.0 - t) / 0.10
                combined *= fade.coerceIn(0.0, 1.0)
            }

            // Lower volume slightly (15000.0 vs 22000.0) so it mixes beautifully as background tick under voice
            buffer[i] = (combined * 15000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return buffer
    }

    fun playCoinSound() {
        scope.launch(Dispatchers.IO) {
            HardwareFeedback.triggerShortHaptic(context, 120L)
            try {
                val track = getOrCreateCoinAudioTrack()
                track?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.stop()
                        it.reloadStaticData()
                        it.play()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play coin sound", e)
            }
        }
    }

    /**
     * Synthesizes a beautiful, rich 5-harmonic acoustic chime instead of a plain beep.
     */
    fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = SAMPLE_RATE
                val safeDurationMs = durationMs.coerceIn(50, 1000)
                val numSamples = (sampleRate * (safeDurationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                val durationSec = safeDurationMs / 1000.0

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // 5ms smooth attack and exponential decay
                    val attack = if (t < 0.005) Math.sin((t / 0.005) * (Math.PI / 2.0)) else 1.0
                    val decay = Math.exp(-t * (4.0 / durationSec))
                    
                    // 5-Harmonic rich acoustic bell tone synthesis
                    var wave = 0.60 * Math.sin(2.0 * Math.PI * freqHz * t) +
                               0.22 * Math.sin(2.0 * Math.PI * (freqHz * 2.0) * t) +
                               0.10 * Math.sin(2.0 * Math.PI * (freqHz * 3.0) * t) +
                               0.05 * Math.sin(2.0 * Math.PI * (freqHz * 4.0) * t) +
                               0.03 * Math.sin(2.0 * Math.PI * (freqHz * 0.5) * t)

                    // 10ms smooth end fade out
                    if (t > durationSec - 0.01) {
                        val fade = (durationSec - t) / 0.01
                        wave *= fade.coerceIn(0.0, 1.0)
                    }

                    buffer[i] = (wave * attack * decay * 22000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                playPcmBuffer(buffer, sampleRate, safeDurationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play synthesized tone: ${e.message}")
            }
        }
    }

    fun playPcmBuffer(buffer: ShortArray, sampleRate: Int, durationMs: Int? = null) {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSizeBytes = maxOf(buffer.size * 2, minBuf)

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSizeBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(buffer, 0, buffer.size)
            track.play()
            val timeout = durationMs?.toLong() ?: (((buffer.size.toDouble() / sampleRate) * 1000 + 100).toLong())
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    track.stop()
                    track.release()
                } catch (e: Exception) {}
            }, timeout + 80)
        } catch (e: Exception) {
            Log.w(TAG, "playPcmBuffer error: ${e.message}")
        }
    }

    fun startWaitingMusic(isTtsActive: () -> Boolean) {
        isWaitingMusicDesired = true
        waitingMusicJob?.cancel()
        waitingMusicJob = scope.launch(Dispatchers.IO) {
            synchronized(audioLock) {
                if (!isWaitingMusicDesired) return@launch
                stopWaitingMusicInternalLocked()

                val buffer = precomputedWaitingBuffer ?: generateWaitingMusicBuffer().also { precomputedWaitingBuffer = it }
                if (!isWaitingMusicDesired) return@launch

                try {
                    val sampleRate = SAMPLE_RATE
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()

                    val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val bufSizeBytes = maxOf(buffer.size * 2, minBuf)

                    val track = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufSizeBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()

                    track.write(buffer, 0, buffer.size)
                    track.setLoopPoints(0, buffer.size, -1)

                    if (isWaitingMusicDesired) {
                        track.play()
                        waitingMusicTrack = track
                        Log.d(TAG, "Countdown chime started successfully on STREAM_ALARM")
                    } else {
                        track.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start countdown chime: ${e.message}")
                }
            }
        }
    }

    fun pauseWaitingMusicForTts() {
        synchronized(audioLock) {
            try {
                waitingMusicTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                        Log.d(TAG, "Paused countdown chime for active TTS")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause chime for TTS: ${e.message}")
            }
        }
    }

    fun resumeWaitingMusicAfterTts() {
        synchronized(audioLock) {
            if (isWaitingMusicDesired) {
                try {
                    waitingMusicTrack?.let { track ->
                        if (track.state == AudioTrack.STATE_INITIALIZED && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                            Log.d(TAG, "Resumed countdown chime after TTS")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resume chime after TTS: ${e.message}")
                }
            }
        }
    }

    private fun stopWaitingMusicInternalLocked() {
        try {
            waitingMusicTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
        } catch (e: Exception) {}
        waitingMusicTrack = null
    }

    fun stopWaitingMusic() {
        isWaitingMusicDesired = false
        waitingMusicJob?.cancel()
        scope.launch(Dispatchers.IO) {
            synchronized(audioLock) {
                stopWaitingMusicInternalLocked()
                Log.d(TAG, "Countdown chime stopped successfully")
            }
        }
    }

    fun playAnnoyingLowBatteryTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = SAMPLE_RATE
                val durationSec = 0.40
                val numSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if ((t * 8).toInt() % 2 == 0) 880.0 else 1174.66
                    val attack = if (t < 0.005) Math.sin((t / 0.005) * (Math.PI / 2.0)) else 1.0
                    val decay = 1.0 - (t / durationSec) * 0.3
                    val sample = Math.sin(2.0 * Math.PI * freq * t) * attack * decay * 22000.0
                    buffer[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
                }
                playPcmBuffer(buffer, sampleRate, (durationSec * 1000).toInt())
            } catch (e: Exception) {}
        }
    }

    fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = SAMPLE_RATE
                val durationSec = 0.35
                val numSamples = (sampleRate * durationSec).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val freq = if (t < 0.17) 1318.51 else 1567.98
                    val tSub = if (t < 0.17) t else t - 0.17
                    val attack = if (tSub < 0.004) Math.sin((tSub / 0.004) * (Math.PI / 2.0)) else 1.0
                    val env = Math.exp(-tSub * 8.0)
                    val sample = Math.sin(2.0 * Math.PI * freq * tSub) * attack * env * 22000.0
                    buffer[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
                }
                playPcmBuffer(buffer, sampleRate, (durationSec * 1000).toInt())
            } catch (e: Exception) {}
        }
    }

    fun release() {
        stopWaitingMusic()
        try {
            coinAudioTrack?.release()
        } catch (e: Exception) {}
        coinAudioTrack = null
    }
}
