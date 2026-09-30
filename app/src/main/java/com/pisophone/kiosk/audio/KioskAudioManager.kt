package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.pisophone.kiosk.util.HardwareFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class KioskAudioManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "KioskAudioManager"
        private const val SAFETY_UNMUTE_TIMEOUT_MS = 12000L
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeechText: String? = null
    @Volatile private var isTtsActive = false

    private val systemAudioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val volumeLock = Any()
    @Volatile private var preMuteMediaVolume: Int? = null
    @Volatile private var preMuteAlarmVolume: Int? = null
    @Volatile private var isMediaMutedForTts = false
    @Volatile private var isAlarmMaxedForTts = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var safetyUnmuteRunnable: Runnable? = null
    private var delayedTtsRunnable: Runnable? = null

    private var coinAudioTrack: AudioTrack? = null
    private var waitingMusicTrack: AudioTrack? = null
    @Volatile private var isWaitingMusicDesired = false
    private var waitingMusicJob: Job? = null
    private val audioLock = Any()
    private var precomputedWaitingBuffer: ShortArray? = null

    fun initAudioEngine() {
        initTts()
        scope.launch(Dispatchers.Default) {
            precomputedWaitingBuffer = generateWaitingMusicBuffer()
            initCoinAudioTrack()
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts?.setAudioAttributes(audioAttributes)
                    }
                    tts?.setSpeechRate(1.02f)
                    tts?.setPitch(1.0f)

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isTtsActive = true
                        }

                        override fun onDone(utteranceId: String?) {
                            onTtsFinished()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            onTtsFinished()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            onTtsFinished()
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            onTtsFinished()
                        }
                    })

                    isTtsReady = true
                    Log.i(TAG, "TextToSpeech initialized with USAGE_ALARM stream routing and hardware ducking.")

                    pendingSpeechText?.let { pending ->
                        pendingSpeechText = null
                        speakWarning(pending)
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}")
        }
    }

    private fun muteMediaStreamForTts() {
        synchronized(volumeLock) {
            try {
                systemAudioManager?.let { am ->
                    if (!isMediaMutedForTts) {
                        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        preMuteMediaVolume = currentVol
                        isMediaMutedForTts = true
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                        Log.i(TAG, "Hardware STREAM_MUSIC muted for TTS (saved pre-mute volume: $currentVol)")
                    }
                    if (!isAlarmMaxedForTts) {
                        val currentAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                        preMuteAlarmVolume = currentAlarmVol
                        isAlarmMaxedForTts = true
                        val maxAlarmVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
                        Log.i(TAG, "Hardware STREAM_ALARM maximized to max ($maxAlarmVol) for TTS (saved pre-max volume: $currentAlarmVol)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mute STREAM_MUSIC and maximize STREAM_ALARM for TTS: ${e.message}")
            }
        }
    }

    private fun restoreMediaStreamAfterTts() {
        synchronized(volumeLock) {
            try {
                safetyUnmuteRunnable?.let { mainHandler.removeCallbacks(it) }
                safetyUnmuteRunnable = null

                if (isMediaMutedForTts) {
                    val restoreVol = preMuteMediaVolume ?: 0
                    systemAudioManager?.let { am ->
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
                        Log.i(TAG, "Hardware STREAM_MUSIC restored to $restoreVol after TTS")
                    }
                    isMediaMutedForTts = false
                    preMuteMediaVolume = null
                }

                if (isAlarmMaxedForTts) {
                    val restoreAlarmVol = preMuteAlarmVolume ?: 0
                    systemAudioManager?.let { am ->
                        am.setStreamVolume(AudioManager.STREAM_ALARM, restoreAlarmVol, 0)
                        Log.i(TAG, "Hardware STREAM_ALARM restored to $restoreAlarmVol after TTS")
                    }
                    isAlarmMaxedForTts = false
                    preMuteAlarmVolume = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore audio streams after TTS: ${e.message}")
            }
        }
    }

    private fun onTtsStartedImmediate() {
        isTtsActive = true
        requestTtsAudioFocus()
        muteMediaStreamForTts()

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

        // Schedule safety watchdog unmute in case TTS callback is dropped or interrupted
        safetyUnmuteRunnable?.let { mainHandler.removeCallbacks(it) }
        val watchdog = Runnable {
            Log.w(TAG, "Safety watchdog triggered — restoring media volume after TTS timeout")
            onTtsFinished()
        }
        safetyUnmuteRunnable = watchdog
        mainHandler.postDelayed(watchdog, SAFETY_UNMUTE_TIMEOUT_MS)
    }

    private fun onTtsFinished() {
        isTtsActive = false
        restoreMediaStreamAfterTts()
        abandonTtsAudioFocus()

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

    private fun requestTtsAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Managed synchronously */ }
                    .build()

                audioFocusRequest = focusReq
                systemAudioManager?.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                systemAudioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            Log.d(TAG, "Requested exclusive transient Audio Focus on STREAM_ALARM for TTS")
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonTtsAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { req ->
                    systemAudioManager?.abandonAudioFocusRequest(req)
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                systemAudioManager?.abandonAudioFocus(null)
            }
            Log.d(TAG, "Released Audio Focus after TTS playback")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    private fun initCoinAudioTrack() {
        try {
            val sampleRate = 44100
            val durationSec = 0.38
            val numSamples = (durationSec * sampleRate).toInt()
            val buffer = ShortArray(numSamples)
            val splitSample = (0.085 * sampleRate).toInt()
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate.toDouble()
                val valSample: Double
                val env: Double
                if (i < splitSample) {
                    val f = 987.77 // B5
                    env = 1.0 - (t / 0.085) * 0.15
                    valSample = 0.7 * Math.sin(2.0 * Math.PI * f * t) + 0.25 * Math.sin(4.0 * Math.PI * f * t)
                } else {
                    val f = 1318.51 // E6
                    val t2 = t - 0.085
                    env = Math.exp(-t2 * 8.5)
                    valSample = 0.75 * Math.sin(2.0 * Math.PI * f * t) + 0.2 * Math.sin(4.0 * Math.PI * f * t) + 0.1 * Math.sin(6.0 * Math.PI * f * t)
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
            coinAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            coinAudioTrack?.write(buffer, 0, buffer.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-initialize coin audio track", e)
        }
    }

    private fun generateWaitingMusicBuffer(): ShortArray {
        val sampleRate = 44100
        val loopDurationSec = 15.0
        val totalSamples = (loopDurationSec * sampleRate).toInt()
        val buffer = ShortArray(totalSamples)

        val notes = doubleArrayOf(
            523.25, 659.25, 783.99, 1046.50, // C5, E5, G5, C6 (s 1-4)
            880.00, 698.46, 783.99, 659.25,  // A5, F5, G5, E5 (s 5-8)
            587.33, 659.25, 783.99, 880.00,  // D5, E5, G5, A5 (s 9-12)
            987.77, 1046.50, 1174.66         // B5, C6, D6 (s 13-15 urgency)
        )

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val beatIdx = Math.min(14, t.toInt())
            val beatT = t - beatIdx

            // 1. Rhythmic clock tick on every second
            val tickEnv = Math.exp(-beatT * 35.0)
            val tickVal = 0.28 * Math.sin(2.0 * Math.PI * 2200.0 * beatT) * tickEnv

            // 2. Warm bass pulse
            val bassF = when {
                beatIdx < 4 -> 130.81
                beatIdx < 8 -> 174.61
                beatIdx < 12 -> 196.00
                else -> 130.81
            }
            val bassEnv = Math.exp(-beatT * 3.5)
            val bassVal = 0.32 * Math.sin(2.0 * Math.PI * bassF * t) * bassEnv

            // 3. Arpeggiated melody note
            val noteF = notes[beatIdx]
            val subBeat = ((beatT * 4) % 4).toInt()
            val arpMult = when (subBeat) {
                0 -> 1.0
                1 -> 1.25
                2 -> 1.5
                else -> 1.25
            }
            val curF = noteF * arpMult
            val subT = (beatT * 4) - (beatT * 4).toInt()
            val melEnv = Math.exp(-subT * 6.0)
            val melVal = 0.22 * Math.sin(2.0 * Math.PI * curF * t) * melEnv

            var total = (tickVal + bassVal + melVal) * 0.75
            if (t < 0.1) {
                total *= (t / 0.1)
            } else if (t > 14.8) {
                total *= ((15.0 - t) / 0.2)
            }

            val sample = (total * 32767.0).toInt().coerceIn(-32768, 32767)
            buffer[i] = sample.toShort()
        }
        return buffer
    }

    fun speakWarning(text: String) {
        // Cancel any pending delayed speech
        delayedTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedTtsRunnable = null

        // Mute media volume and maximize alarm volume immediately when warning is requested
        muteMediaStreamForTts()

        if (!isTtsReady || tts == null) {
            pendingSpeechText = text
            initTts()
            return
        }

        HardwareFeedback.triggerAlertFeedback(context)
        onTtsStartedImmediate()

        if (isTtsReady && tts != null) {
            try {
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                }
                val utteranceId = "kiosk_warning_${System.currentTimeMillis()}"
                val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS speak returned non-success code $result, triggering fallback")
                    playSynthesizedTone(880, 160)
                    onTtsFinished()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                playSynthesizedTone(880, 160)
                onTtsFinished()
            }
        } else {
            playSynthesizedTone(880, 160)
            onTtsFinished()
        }
    }

    fun playCoinSound() {
        scope.launch(Dispatchers.IO) {
            HardwareFeedback.triggerShortHaptic(context, 120L)
            try {
                coinAudioTrack?.let {
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
                val sampleRate = 44100
                val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i / (sampleRate.toDouble() / freqHz)
                    val decay = 1.0 - (i.toDouble() / numSamples.toDouble())
                    buffer[i] = (Math.sin(angle) * 32767 * decay * 0.7).toInt().toShort()
                }
                playPcmBuffer(buffer, sampleRate, durationMs)
            } catch (_: Exception) {}
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
                } catch (_: Exception) {}
            }, timeout + 60)
        } catch (_: Exception) {}
    }

    fun startWaitingMusic() {
        isWaitingMusicDesired = true
        waitingMusicJob?.cancel()
        waitingMusicJob = scope.launch(Dispatchers.IO) {
            synchronized(audioLock) {
                if (!isWaitingMusicDesired) return@launch
                stopWaitingMusicInternalLocked()

                val buffer = precomputedWaitingBuffer ?: generateWaitingMusicBuffer().also { precomputedWaitingBuffer = it }
                if (!isWaitingMusicDesired) return@launch

                try {
                    val sampleRate = 44100
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
                    track.setLoopPoints(0, buffer.size, -1) // Infinite looping until stopped
                    
                    if (isWaitingMusicDesired && !isTtsActive) {
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
        } catch (_: Exception) {}
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
                val sampleRate = 44100
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
            } catch (_: Exception) {}
        }
    }

    fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
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
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        delayedTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedTtsRunnable = null
        stopWaitingMusic()
        restoreMediaStreamAfterTts()
        abandonTtsAudioFocus()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        try {
            coinAudioTrack?.release()
        } catch (_: Exception) {}
    }
}
