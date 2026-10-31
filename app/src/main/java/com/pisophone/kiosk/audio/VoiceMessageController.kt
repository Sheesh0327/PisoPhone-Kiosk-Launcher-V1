package com.pisophone.kiosk.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.pisophone.kiosk.overlay.VoiceMessageBanner
import com.pisophone.kiosk.voice.VoicePlayback

/**
 * Plays the voice messages the box passes on (feature 056). A message arrives on the server's thread; everything else happens on the main
 * thread, in [VoicePlayback] (when it plays, what waits for the countdown speech) with the sound and the banner behind it.
 */
class VoiceMessageController(context: Context, isSpeaking: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val output = VoiceMessageOutput(context.applicationContext, isSpeaking, VoiceMessageBanner(context.applicationContext))
    private val playback = VoicePlayback(output) { SystemClock.elapsedRealtime() }
    private val tick = object : Runnable {
        override fun run() {
            playback.tick()
            if (playback.waiting) main.postDelayed(this, TICK_MS)
        }
    }

    init {
        output.onFinished = { playback.onFinished() }
    }

    /** Any thread. */
    fun onMessage(samples: ShortArray) {
        main.post {
            playback.onMessage(samples)
            main.removeCallbacks(tick)
            if (playback.waiting) main.postDelayed(tick, TICK_MS)
        }
    }

    /** Any thread: silences a message that plays or waits (the service is stopping). */
    fun stop() {
        main.post {
            main.removeCallbacks(tick)
            playback.stopAll()
        }
    }

    private companion object {
        const val TICK_MS = 300L
    }
}
