package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pisophone.kiosk.overlay.VoiceMessageBanner
import com.pisophone.kiosk.util.KioskSettings
import com.pisophone.kiosk.voice.VoicePlayback
import java.util.concurrent.Executors

/**
 * The sound and the banner of a voice message from the shop (feature 056), behind [VoicePlayback]. The samples are played as they are
 * (8 kHz, mono) on the alarm stream at the owner's speech volume, like the countdown speech, and the game's own sound is ducked while it
 * plays. One message at a time: a new one waits in the worker until the old one has been stopped and its volume put back.
 */
class VoiceMessageOutput(
    private val context: Context,
    private val isSpeaking: () -> Boolean,
    private val banner: VoiceMessageBanner,
) : VoicePlayback.Output {
    /** Called on the main thread when the latest message has finished by itself. */
    var onFinished: () -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "VoiceMessage").apply { isDaemon = true } }

    @Volatile private var generation = 0

    @Volatile private var track: AudioTrack? = null

    override fun isSpeechActive(): Boolean = isSpeaking()

    override fun showBanner(visible: Boolean) {
        if (visible) banner.show() else banner.remove()
    }

    override fun start(samples: ShortArray) {
        val mine = ++generation
        worker.execute { play(samples, mine) }
    }

    override fun stop() {
        generation++
        try {
            track?.stop()
        } catch (_: Exception) {
        }
    }

    private fun play(samples: ShortArray, mine: Int) {
        if (mine != generation) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        var savedVolume: Int? = null
        var focus: AudioFocusRequest? = null
        var local: AudioTrack? = null
        try {
            if (am != null) {
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                val wanted = (maxVolume * KioskSettings.getSpeechVolumePercent(context) / 100).coerceIn(1, maxVolume)
                am.setStreamVolume(AudioManager.STREAM_ALARM, wanted, 0)
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .build()
                am.requestAudioFocus(focus)
            }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            local = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(samples.size * 2, minBytes))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            local.write(samples, 0, samples.size)
            track = local
            if (mine != generation) return
            local.play()
            val deadline = SystemClock.elapsedRealtime() + samples.size * 1000L / SAMPLE_RATE + END_MARGIN_MS
            while (mine == generation && local.playbackHeadPosition < samples.size && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(POLL_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the voice message: ${e.message}")
        } finally {
            try {
                local?.release()
            } catch (_: Exception) {
            }
            track = null
            try {
                if (am != null && savedVolume != null) am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
                if (am != null && focus != null) am.abandonAudioFocusRequest(focus)
            } catch (e: Exception) {
                Log.w(TAG, "Could not put the volume back: ${e.message}")
            }
            // Only the latest message reports that it ended; a stopped or replaced one stays quiet.
            main.post { if (mine == generation) onFinished() }
        }
    }

    private companion object {
        const val TAG = "VoiceMessageOutput"
        const val SAMPLE_RATE = 8000
        const val END_MARGIN_MS = 1_500L
        const val POLL_MS = 50L
    }
}
