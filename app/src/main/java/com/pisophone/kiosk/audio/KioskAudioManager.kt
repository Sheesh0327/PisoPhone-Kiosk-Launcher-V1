package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class KioskAudioManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "KioskAudioManager"
        private const val SAFETY_UNMUTE_TIMEOUT_MS = 12000L
        private const val TTS_REINIT_BACKOFF_MS = 30_000L
        private const val SAMPLE_RATE = 44100
        const val LOCATE_MAX_DURATION_MS = 60_000L

        /** The waiting loop's chord notes in Hz, one per second: C5 E5 G5 C6 | A5 F5 G5 E5 | D5 E5 G5 A5 | B5 C6 D6. */
        private const val WAITING_NOTES_HZ = "523.25 659.25 783.99 1046.50 880.00 698.46 783.99 659.25 587.33 659.25 783.99 880.00 987.77 1046.50 1174.66"
    }

    private var tts: TextToSpeech? = null

    @Volatile private var isTtsReady = false

    @Volatile private var isTtsInitializing = false

    @Volatile private var lastTtsInitAttemptMs = 0L
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

    @Synchronized
    private fun initTts() {
        if (isTtsInitializing) return
        // Never leak the previous engine: each TextToSpeech instance holds a service binding.
        releaseTtsLocked()
        isTtsInitializing = true
        lastTtsInitAttemptMs = android.os.SystemClock.elapsedRealtime()
        try {
            var created: TextToSpeech? = null
            var syncStatus: Int? = null
            created = TextToSpeech(context.applicationContext) { status ->
                val engine = created
                if (engine == null) {
                    // TextToSpeech reports some failures synchronously from its constructor.
                    syncStatus = status
                } else {
                    onTtsInitResult(engine, status)
                }
            }
            tts = created
            syncStatus?.let { onTtsInitResult(created, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS: ${e.message}")
            isTtsInitializing = false
            releaseTtsLocked()
        }
    }

    @Synchronized
    private fun onTtsInitResult(engine: TextToSpeech?, status: Int) {
        if (engine == null || engine !== tts) {
            // Callback from an engine that has already been replaced/shut down.
            try { engine?.shutdown() } catch (_: Exception) {}
            return
        }
        isTtsInitializing = false
        if (status == TextToSpeech.SUCCESS) {
            val result = engine.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            engine.setAudioAttributes(audioAttributes)
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.0f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
                mainHandler.post { speakWarning(pending) }
            }
        } else {
            Log.w(TAG, "TextToSpeech init failed with status $status")
            pendingSpeechText = null
            releaseTtsLocked()
            // Defensive: make sure media is never left muted by a failed engine.
            onTtsFinished()
        }
    }

    private fun releaseTtsLocked() {
        val old = tts
        tts = null
        isTtsReady = false
        if (old != null) {
            try {
                old.stop()
                old.shutdown()
            } catch (_: Exception) {}
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
            Log.d(TAG, "Requested exclusive transient Audio Focus on STREAM_ALARM for TTS")
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonTtsAudioFocus() {
        try {
            audioFocusRequest?.let { req ->
                systemAudioManager?.abandonAudioFocusRequest(req)
            }
            audioFocusRequest = null
            Log.d(TAG, "Released Audio Focus after TTS playback")
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    private fun initCoinAudioTrack() {
        try {
            val buffer = generateCoinBuffer()
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
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

    /**
     * Mixes one decaying note into [mix] starting at [startSec]. [partials] are (frequency multiplier,
     * relative level) pairs, so bells can use inharmonic overtones and chimes plain harmonics.
     */
    private fun addNote(
        mix: DoubleArray,
        startSec: Double,
        freq: Double,
        durSec: Double,
        amp: Double,
        decayPerSec: Double,
        partials: List<Pair<Double, Double>> = listOf(1.0 to 1.0),
    ) {
        val first = (startSec * SAMPLE_RATE).toInt()
        val count = (durSec * SAMPLE_RATE).toInt()
        for (n in 0 until count) {
            val idx = first + n
            if (idx >= mix.size) break
            val t = n.toDouble() / SAMPLE_RATE
            val attack = minOf(1.0, t / 0.004)
            val release = minOf(1.0, (durSec - t) / 0.02)
            val env = attack * release * Math.exp(-t * decayPerSec)
            var v = 0.0
            for ((mult, level) in partials) v += level * Math.sin(2.0 * Math.PI * freq * mult * t)
            mix[idx] += v * env * amp
        }
    }

    private fun toPcm(mix: DoubleArray, gain: Double = 1.0): ShortArray =
        ShortArray(mix.size) { (mix[it] * gain * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }

    private val bellPartials = listOf(1.0 to 1.0, 2.76 to 0.55, 5.4 to 0.25)

    /** A metal coin dropping into the slot: two quick bright "tink" strikes. */
    private fun generateCoinBuffer(): ShortArray {
        val mix = DoubleArray((0.6 * SAMPLE_RATE).toInt())
        addNote(mix, 0.0, 2093.0, 0.5, 0.42, 9.0, bellPartials)
        addNote(mix, 0.075, 2794.0, 0.5, 0.38, 7.5, bellPartials)
        return toPcm(mix, 0.9)
    }

    /**
     * The cue while the phone waits for coins: a calm 15-second loop that climbs towards the end. One chord note per
     * second, played as a four-note arpeggio (1, 5/4, 3/2, 5/4 of the note) over a warm bass pulse and a soft tick.
     *
     * Polished from the original sine-only version: every note starts with a short fade-in and rings out like a soft
     * bell instead of a bare beep, the high notes are turned down so the climb never turns shrill, the tick is a quiet
     * wooden tap (a little firmer on the first beat of each four), a faint echo gives the arpeggio some space, and
     * nothing is faded at the loop point: the echo wraps around and every sound has died away by then, so the loop
     * repeats without a gap or a dip.
     */
    private fun generateWaitingMusicBuffer(): ShortArray {
        val sr = SAMPLE_RATE.toDouble()
        val total = 15 * SAMPLE_RATE
        val tau = 2.0 * Math.PI
        val chord = WAITING_NOTES_HZ.split(" ").map { it.toDouble() }
        val arp = doubleArrayOf(1.0, 1.25, 1.5, 1.25)

        val melody = DoubleArray(total)
        val rhythm = DoubleArray(total)
        for (i in 0 until total) {
            val t = i / sr
            val beat = minOf(14, t.toInt())
            val beatT = t - beat

            // Bass: a fundamental with a touch of second harmonic, eased in so it never clicks.
            val bassHz = when {
                beat < 4 -> 130.81
                beat < 8 -> 174.61
                beat < 12 -> 196.00
                else -> 130.81
            }
            val bassEnv = minOf(1.0, beatT / 0.015) * Math.exp(-beatT * 3.2)
            val bass = 0.30 * (Math.sin(tau * bassHz * t) + 0.25 * Math.sin(tau * 2.0 * bassHz * t)) * bassEnv

            // Tick: a quiet wooden tap; the first beat of every four is lower and a little firmer.
            val accent = beat % 4 == 0
            val tickHz = if (accent) 1100.0 else 1600.0
            val tickAmp = if (accent) 0.15 else 0.09
            val tick = tickAmp * Math.sin(tau * tickHz * beatT) * Math.exp(-beatT * 55.0)

            // Arpeggio: fundamental plus two soft overtones, faded in over 6 ms, ringing out; high notes turned down.
            val quarter = beatT * 4.0
            val step = minOf(3, quarter.toInt())
            val noteT = (quarter - step) / 4.0
            val hz = chord[beat] * arp[step]
            val brightness = (700.0 / hz).coerceIn(0.55, 1.0)
            val noteEnv = minOf(1.0, noteT / 0.006) * Math.exp(-noteT * 14.0)
            val tone = Math.sin(tau * hz * t) + 0.30 * Math.sin(tau * 2.0 * hz * t) + 0.10 * Math.sin(tau * 3.0 * hz * t)
            melody[i] = 0.20 * brightness * tone * noteEnv
            rhythm[i] = bass + tick
        }

        // Echo of the arpeggio: three repeats, 3/8 s apart, each quieter. Indexed modulo the loop so it wraps seamlessly.
        val delay = (0.375 * sr).toInt()
        val echoGain = doubleArrayOf(0.32, 0.10, 0.03)
        val out = ShortArray(total)
        for (i in 0 until total) {
            var v = rhythm[i] + melody[i]
            for (k in echoGain.indices) {
                val from = ((i - (k + 1) * delay) % total + total) % total
                v += echoGain[k] * melody[from]
            }
            // Gentle limiter so coincident peaks round off instead of clipping.
            out[i] = (Math.tanh(v * 1.1) * 0.85 * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Bright rising arpeggio confirming the customer tapped Done and the connection is being set up. */
    private fun generateDoneBuffer(): ShortArray {
        val mix = DoubleArray((0.9 * SAMPLE_RATE).toInt())
        val chime = listOf(1.0 to 1.0, 2.0 to 0.3, 3.0 to 0.1)
        addNote(mix, 0.00, 784.0, 0.4, 0.35, 5.0, chime) // G5
        addNote(mix, 0.12, 1046.5, 0.4, 0.35, 5.0, chime) // C6
        addNote(mix, 0.24, 1318.5, 0.6, 0.38, 4.0, chime) // E6
        return toPcm(mix)
    }

    /** Falling three-note alert for when the countdown reaches zero. */
    private fun generateTimeUpBuffer(): ShortArray {
        val mix = DoubleArray((1.3 * SAMPLE_RATE).toInt())
        val tone = listOf(1.0 to 1.0, 3.0 to 0.25, 5.0 to 0.1)
        addNote(mix, 0.00, 987.77, 0.22, 0.38, 1.0, tone) // B5
        addNote(mix, 0.26, 783.99, 0.22, 0.38, 1.0, tone) // G5
        addNote(mix, 0.52, 587.33, 0.75, 0.42, 2.2, tone) // D5
        return toPcm(mix)
    }

    fun playDoneSound() {
        scope.launch(Dispatchers.IO) { playPcmBuffer(generateDoneBuffer(), SAMPLE_RATE) }
    }

    fun playTimeUpSound() {
        scope.launch(Dispatchers.IO) {
            HardwareFeedback.triggerVibration(context, longArrayOf(0, 250, 120, 250, 120, 500))
            playPcmBuffer(generateTimeUpBuffer(), SAMPLE_RATE)
        }
    }

    /** Speaks [text] after [delayMs], leaving time for the sound effect before it to finish. */
    fun speakAfterSound(text: String, delayMs: Long, alert: Boolean = false) {
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            speakWarning(text, alert)
        }
    }

    // ------------------------------------------------------------------------
    // Locate alarm: a loud wailing siren on the alarm stream, started from the admin dashboard.
    // ------------------------------------------------------------------------

    private var sirenJob: Job? = null
    private var preLocateAlarmVolume: Int? = null

    @Volatile private var locateActive = false

    fun startLocateAlarm(durationMs: Long = LOCATE_MAX_DURATION_MS) {
        stopLocateAlarm()
        locateActive = true
        synchronized(volumeLock) {
            systemAudioManager?.let { am ->
                try {
                    if (!isAlarmMaxedForTts) {
                        preLocateAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    }
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not raise alarm volume for locate: ${e.message}")
                }
            }
        }
        HardwareFeedback.startLocateFeedback(context)
        sirenJob = scope.launch(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val chunk = ShortArray(2048)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, chunk.size * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                var phase = 0.0
                var sample = 0L
                val endAt = android.os.SystemClock.elapsedRealtime() + durationMs
                while (isActive && locateActive && android.os.SystemClock.elapsedRealtime() < endAt) {
                    for (i in chunk.indices) {
                        // Wail between 700 Hz and 1600 Hz every 1.4 s; phase is accumulated so there are no clicks.
                        val sweep = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * (sample.toDouble() / SAMPLE_RATE) / 1.4)
                        val f = 700.0 + 900.0 * sweep
                        phase += 2.0 * Math.PI * f / SAMPLE_RATE
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
                        val v = Math.sin(phase) + 0.45 * Math.sin(3.0 * phase) + 0.2 * Math.sin(5.0 * phase)
                        chunk[i] = (v / 1.65 * 32767.0 * 0.95).toInt().coerceIn(-32768, 32767).toShort()
                        sample++
                    }
                    track.write(chunk, 0, chunk.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Locate siren failed: ${e.message}")
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {}
                try {
                    track?.release()
                } catch (_: Exception) {}
                // Timed out (or failed) rather than stopped by hand: tidy up so vibration and strobe end too.
                if (locateActive) stopLocateAlarm()
            }
        }
    }

    fun stopLocateAlarm() {
        locateActive = false
        sirenJob?.cancel()
        sirenJob = null
        HardwareFeedback.stopLocateFeedback(context)
        synchronized(volumeLock) {
            val restore = preLocateAlarmVolume ?: return
            preLocateAlarmVolume = null
            try {
                systemAudioManager?.setStreamVolume(AudioManager.STREAM_ALARM, restore, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore alarm volume after locate: ${e.message}")
            }
        }
    }

    /**
     * Speaks [text] loudly. [alert] adds the flash, vibration and fallback beep that warnings need;
     * a plain confirmation passes false and is simply skipped while the speech engine is not ready.
     */
    fun speakWarning(text: String, alert: Boolean = true) {
        // Cancel any pending delayed speech
        delayedTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        delayedTtsRunnable = null

        if (!alert && (!isTtsReady || tts == null)) return

        if (!isTtsReady || tts == null) {
            // Do NOT mute media here: if the engine never comes up, nothing would ever restore
            // the volume. Give an audible cue now and speak once (if) the engine is ready.
            pendingSpeechText = text
            HardwareFeedback.triggerAlertFeedback(context)
            playSynthesizedTone(880, 160)
            val now = android.os.SystemClock.elapsedRealtime()
            if (!isTtsInitializing) {
                val waitMs = if (lastTtsInitAttemptMs == 0L) 0L else TTS_REINIT_BACKOFF_MS - (now - lastTtsInitAttemptMs)
                if (waitMs <= 0L) {
                    initTts()
                } else {
                    // A failed engine never recovers on its own, so retry once the backoff ends.
                    val retry = Runnable {
                        delayedTtsRunnable = null
                        if (!isTtsReady && !isTtsInitializing && pendingSpeechText != null) initTts()
                    }
                    delayedTtsRunnable = retry
                    mainHandler.postDelayed(retry, waitMs)
                }
            }
            return
        }

        if (alert) HardwareFeedback.triggerAlertFeedback(context)
        // Mute media / maximize alarm only once we are actually about to speak; the safety
        // watchdog scheduled in onTtsStartedImmediate guarantees restoration.
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

    /**
     * Stops playback in the customer's app when the session locks: take permanent media audio
     * focus (other players receive AUDIOFOCUS_LOSS and pause/stop), send a MEDIA_PAUSE key for
     * players that ignore focus, then release focus so nothing resumes automatically.
     */
    fun pauseExternalMedia() {
        val am = systemAudioManager ?: return
        try {
            val dispatchPause = {
                try {
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                    am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
                } catch (_: Exception) {}
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            dispatchPause()
            am.abandonAudioFocusRequest(req)
            Log.i(TAG, "Paused customer media playback on session lock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause customer media: ${e.message}")
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
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
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
        stopLocateAlarm()
        restoreMediaStreamAfterTts()
        abandonTtsAudioFocus()
        synchronized(this) {
            pendingSpeechText = null
            isTtsInitializing = false
            releaseTtsLocked()
        }
        try {
            coinAudioTrack?.release()
        } catch (_: Exception) {}
    }
}
