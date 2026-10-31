package com.pisophone.kiosk.voice

/**
 * When a voice message plays on the phone (feature 056). It plays at once, with a banner, unless the countdown or the closing speech is
 * being spoken: that keeps its priority and the message waits for it to end (but never longer than [MAX_WAIT_MS]). A newer message
 * replaces the one playing or waiting; they are never played over each other. Pure logic: the sound and the banner window are
 * Android's, behind [Output]. Call everything from one thread (the main thread).
 */
class VoicePlayback(private val output: Output, private val nowMs: () -> Long) {
    interface Output {
        /** True while the countdown digits or the closing remark are being spoken. */
        fun isSpeechActive(): Boolean

        /** Starts playing [samples] (8 kHz, mono). The caller of [onFinished] is the output, when the sound has ended. */
        fun start(samples: ShortArray)

        fun stop()

        fun showBanner(visible: Boolean)
    }

    companion object {
        const val MAX_WAIT_MS = 20_000L
    }

    var playing = false
        private set

    private var pending: ShortArray? = null
    private var waitingSince = 0L

    val waiting: Boolean get() = pending != null

    fun onMessage(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (playing) {
            output.stop()
            playing = false
        }
        if (output.isSpeechActive()) {
            pending = samples
            waitingSince = nowMs()
            output.showBanner(false)
        } else {
            start(samples)
        }
    }

    /** The output's sound has ended (a stopped one does not report). */
    fun onFinished() {
        if (!playing) return
        playing = false
        output.showBanner(false)
    }

    /** Call every few hundred milliseconds while [waiting]. */
    fun tick() {
        val message = pending ?: return
        if (!output.isSpeechActive() || nowMs() - waitingSince >= MAX_WAIT_MS) start(message)
    }

    fun stopAll() {
        pending = null
        if (playing) output.stop()
        playing = false
        output.showBanner(false)
    }

    private fun start(samples: ShortArray) {
        pending = null
        playing = true
        output.showBanner(true)
        output.start(samples)
    }
}
