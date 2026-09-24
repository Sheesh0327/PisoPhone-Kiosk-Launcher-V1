package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.pisophone.kiosk.util.HardwareFeedback
import java.util.Locale

/**
 * Manages Text-To-Speech lifecycle, hardware volume ducking/restoration,
 * alarm-stream routing, and safety timeout watchdogs.
 */
class KioskTtsEngine(
    private val context: Context,
    private val onTtsStart: () -> Unit,
    private val onTtsFinish: () -> Unit,
    private val onFallbackTone: (freqHz: Int, durationMs: Int) -> Unit
) {
    companion object {
        private const val TAG = "KioskTtsEngine"
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

    fun isSpeaking(): Boolean = isTtsActive

    fun initTts() {
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
        onTtsStart()

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
        onTtsFinish()
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
                    .setOnAudioFocusChangeListener { }
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
                    onFallbackTone(880, 160)
                    onTtsFinished()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                onFallbackTone(880, 160)
                onTtsFinished()
            }
        } else {
            onFallbackTone(880, 160)
            onTtsFinished()
        }
    }

    fun shutdown() {
        delayedTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedTtsRunnable = null
        restoreMediaStreamAfterTts()
        abandonTtsAudioFocus()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
    }
}
