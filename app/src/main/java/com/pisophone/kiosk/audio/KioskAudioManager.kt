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
            precomputedWaitingBuffer = KioskSoundSynthesizer.generateWaitingMusicBuffer()
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
                    }
                    if (!isAlarmMaxedForTts) {
                        val currentAlarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                        preMuteAlarmVolume = currentAlarmVol
                        isAlarmMaxedForTts = true
                        val maxAlarmVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
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
                    systemAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
                    isMediaMutedForTts = false
                    preMuteMediaVolume = null
                }

                if (isAlarmMaxedForTts) {
                    val restoreAlarmVol = preMuteAlarmVolume ?: 0
                    systemAudioManager?.setStreamVolume(AudioManager.STREAM_ALARM, restoreAlarmVol, 0)
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
                    }
                }
            } catch (_: Exception) {}
        }

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
                        }
                    }
                } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }

    private fun initCoinAudioTrack() {
        try {
            val sampleRate = 44100
            val buffer = KioskSoundSynthesizer.generateCoinSoundBuffer(sampleRate)
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

    fun speakWarning(text: String) {
        delayedTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedTtsRunnable = null
        muteMediaStreamForTts()

        if (!isTtsReady || tts == null) {
            pendingSpeechText = text
            initTts()
            return
        }

        HardwareFeedback.triggerAlertFeedback(context)
        onTtsStartedImmediate()

        try {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            }
            val utteranceId = "kiosk_warning_${System.currentTimeMillis()}"
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                playSynthesizedTone(880, 160)
                onTtsFinished()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak failed: ${e.message}")
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
                val buffer = KioskSoundSynthesizer.generateSynthesizedToneBuffer(freqHz, durationMs, sampleRate)
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

                val buffer = precomputedWaitingBuffer ?: KioskSoundSynthesizer.generateWaitingMusicBuffer().also { precomputedWaitingBuffer = it }
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
                    track.setLoopPoints(0, buffer.size, -1)
                    
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
            }
        }
    }

    fun playAnnoyingLowBatteryTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationSec = 0.45
                val buffer = KioskSoundSynthesizer.generateLowBatteryToneBuffer(sampleRate, durationSec)
                playPcmBuffer(buffer, sampleRate, (durationSec * 1000).toInt())
            } catch (_: Exception) {}
        }
    }

    fun playHighBatteryAttentionTone() {
        scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val durationSec = 0.4
                val buffer = KioskSoundSynthesizer.generateHighBatteryToneBuffer(sampleRate, durationSec)
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
