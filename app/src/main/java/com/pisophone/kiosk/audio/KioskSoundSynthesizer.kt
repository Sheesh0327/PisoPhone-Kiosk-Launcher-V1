package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Handles synthesized tone generation, PCM audio playback, coin sound effects,
 * and loopable kiosk waiting music efficiently with lazy initialization.
 */
class KioskSoundSynthesizer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "KioskSoundSynthesizer"
        private const val SAMPLE_RATE = 44100 // Standard CD-quality 44.1kHz ensures native hardware playback without DSP downsampling / slow-motion distortion
    }

    private var coinAudioTrack: AudioTrack? = null
    private var waitingMusicTrack: AudioTrack? = null
    private var toneGenerator: ToneGenerator? = null
    @Volatile private var isWaitingMusicDesired = false
    private var waitingMusicJob: Job? = null
    private val audioLock = Any()
    private var precomputedWaitingBuffer: ShortArray? = null

    fun initAudioEngine() {
        // Lightweight lazy initialization: allocate ToneGenerator only
        scope.launch(Dispatchers.IO) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator unavailable: ${e.message}")
            }
        }
    }

    private fun getOrCreateCoinAudioTrack(): AudioTrack? {
        if (coinAudioTrack != null) return coinAudioTrack
        synchronized(audioLock) {
            if (coinAudioTrack != null) return coinAudioTrack
            try {
                val sampleRate = SAMPLE_RATE
                val durationSec = 0.28
                val numSamples = (durationSec * sampleRate).toInt()
                val buffer = ShortArray(numSamples)
                val splitSample = (0.07 * sampleRate).toInt()
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate.toDouble()
                    val valSample: Double
                    val env: Double
                    if (i < splitSample) {
                        val f = 987.77 // B5
                        env = 1.0 - (t / 0.07) * 0.15
                        valSample = 0.7 * Math.sin(2.0 * Math.PI * f * t) + 0.25 * Math.sin(4.0 * Math.PI * f * t)
                    } else {
                        val f = 1318.51 // E6
                        val t2 = t - 0.07
                        env = Math.exp(-t2 * 12.0)
                        valSample = 0.75 * Math.sin(2.0 * Math.PI * f * t) + 0.2 * Math.sin(4.0 * Math.PI * f * t)
                    }
                    val sample = (valSample * env * 32767.0 * 0.88).toInt().coerceIn(-32768, 32767)
                    buffer[i] = sample.toShort()
                }
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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

    private fun generateWaitingMusicBuffer(): ShortArray {
        val sampleRate = SAMPLE_RATE
        val loopDurationSec = 1.6 // Snappy, pleasant 1.6s arpeggio loop (150 BPM)
        val totalSamples = (loopDurationSec * sampleRate).toInt()
        val buffer = ShortArray(totalSamples)
        // Upbeat arpeggio: C5 -> E5 -> G5 -> C6
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val beatIdx = (t * 4 / loopDurationSec).toInt().coerceIn(0, 3)
            val beatT = (t * 4 / loopDurationSec) - beatIdx

            val tickEnv = Math.exp(-beatT * 40.0)
            val tickVal = 0.20 * Math.sin(2.0 * Math.PI * 2400.0 * beatT) * tickEnv

            val bassEnv = Math.exp(-beatT * 6.0)
            val bassVal = 0.25 * Math.sin(2.0 * Math.PI * 130.81 * t) * bassEnv

            val melEnv = Math.exp(-beatT * 8.0)
            val melVal = 0.30 * Math.sin(2.0 * Math.PI * notes[beatIdx] * t) * melEnv

            var total = (tickVal + bassVal + melVal) * 0.75
            if (t < 0.03) total *= (t / 0.03)
            else if (t > 1.57) total *= ((1.6 - t) / 0.03)

            val sample = (total * 32767.0).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
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

    fun playSynthesizedTone(freqHz: Int, durationMs: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs) ?: run {
                    val sampleRate = SAMPLE_RATE
                    val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                    val buffer = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val angle = 2.0 * Math.PI * i / (sampleRate.toDouble() / freqHz)
                        val decay = 1.0 - (i.toDouble() / numSamples.toDouble())
                        buffer[i] = (Math.sin(angle) * 32767 * decay * 0.7).toInt().toShort()
                    }
                    playPcmBuffer(buffer, sampleRate, durationMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play synthesized tone: ${e.message}")
            }
        }
    }

    fun playPcmBuffer(buffer: ShortArray, sampleRate: Int, durationMs: Int? = null) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
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
            }, timeout + 60)
        } catch (e: Exception) {}
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
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
                    track.setLoopPoints(0, buffer.size, -1)

                    if (isWaitingMusicDesired && !isTtsActive()) {
                        track.play()
                        waitingMusicTrack = track
                        Log.d(TAG, "Waiting music started successfully")
                    } else if (isWaitingMusicDesired) {
                        waitingMusicTrack = track
                        Log.d(TAG, "Waiting music prepared but kept paused while TTS is active")
                    } else {
                        track.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start waiting music: ${e.message}")
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
                        Log.d(TAG, "Paused kiosk waiting music for active TTS speech")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause waiting music on TTS start: ${e.message}")
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
                            Log.d(TAG, "Resumed kiosk waiting music after TTS completed")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resume waiting music after TTS: ${e.message}")
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
                Log.d(TAG, "Waiting music stopped successfully")
            }
        }
    }

    fun playAnnoyingLowBatteryTone() {
        scope.launch(Dispatchers.IO) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, 400) ?: run {
                    val sampleRate = SAMPLE_RATE
                    val durationSec = 0.45
                    val numSamples = (sampleRate * durationSec).toInt()
                    val buffer = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val freq = if ((t * 9).toInt() % 2 == 0) 880.0 else 1174.66
                        val decay = 1.0 - (t / durationSec) * 0.2
                        val sample = (Math.sin(2.0 * Math.PI * freq * t) * 32767 * decay * 0.85).toInt().coerceIn(-32768, 32767)
                        buffer[i] = sample.toShort()
                    }
                    playPcmBuffer(buffer, sampleRate, (durationSec * 1000).toInt())
                }
            } catch (e: Exception) {}
        }
    }

    fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.IO) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 350) ?: run {
                    val sampleRate = SAMPLE_RATE
                    val durationSec = 0.4
                    val numSamples = (sampleRate * durationSec).toInt()
                    val buffer = ShortArray(numSamples)
                    for (i in 0 until numSamples) {
                        val t = i.toDouble() / sampleRate
                        val freq = if (t < 0.2) 1318.51 else 1567.98
                        val env = 1.0 - (t / durationSec) * 0.15
                        val sample = (Math.sin(2.0 * Math.PI * freq * t) * 32767 * env * 0.75).toInt().coerceIn(-32768, 32767)
                        buffer[i] = sample.toShort()
                    }
                    playPcmBuffer(buffer, sampleRate, (durationSec * 1000).toInt())
                }
            } catch (e: Exception) {}
        }
    }

    fun release() {
        stopWaitingMusic()
        try {
            coinAudioTrack?.release()
            toneGenerator?.release()
        } catch (e: Exception) {}
        coinAudioTrack = null
        toneGenerator = null
    }
}
