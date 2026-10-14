package com.pisophone.kiosk.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The numbers of the final countdown ("5", "4", "3", "2", "1"), spoken by the phone's own speech engine once, kept as small sound
 * files and played from memory. Playing a ready sound answers within a few milliseconds, so each number lands on its own second
 * even on a slow phone, where asking the speech engine every second can lag half a second behind. Until a clip exists (the first
 * minutes after the app starts, or an engine that cannot write files) the caller falls back to the engine itself.
 */
class SpeechClips(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "speech-clips-v1").apply { mkdirs() }
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    /** key -> sample id in the pool, and the keys whose sample finished loading. */
    private val sampleIds = ConcurrentHashMap<String, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId) else Log.w(TAG, "A speech clip did not load (status $status)")
        }
    }

    private fun file(key: String) = File(directory, "$key.wav")

    /** Loads the clips that already exist and asks [tts] to write the missing ones. Call once the engine is ready. */
    fun prepare(tts: TextToSpeech) {
        for ((key, text) in CLIPS) {
            val f = file(key)
            if (f.exists() && f.length() > MIN_VALID_BYTES) {
                load(key, f)
            } else {
                try {
                    f.delete()
                    val result = tts.synthesizeToFile(text, Bundle(), f, UTTERANCE_PREFIX + key)
                    if (result != TextToSpeech.SUCCESS) Log.w(TAG, "The speech engine refused to write the clip \"$text\"")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not write the clip \"$text\": ${e.message}")
                }
            }
        }
    }

    /** The speech engine finished writing the clip for [utteranceId] ("clip_<key>"). */
    fun onSynthesized(utteranceId: String) {
        val key = utteranceId.removePrefix(UTTERANCE_PREFIX)
        val f = file(key)
        if (f.exists() && f.length() > MIN_VALID_BYTES) load(key, f) else f.delete()
    }

    private fun load(key: String, f: File) {
        if (sampleIds.containsKey(key)) return
        try {
            sampleIds[key] = soundPool.load(f.absolutePath, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load the clip $key: ${e.message}")
        }
    }

    /** Plays the clip of [number] at [volume] (0 to 1). False when it is not ready: the caller then speaks it itself. */
    fun playDigit(number: Int, volume: Float): Boolean {
        val id = sampleIds["d$number"] ?: return false
        if (id !in loaded) return false
        return soundPool.play(id, volume, volume, 1, 0, 1f) != 0
    }

    fun stop() {
        try {
            soundPool.autoPause()
        } catch (_: Exception) {
        }
    }

    fun release() {
        try {
            soundPool.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SpeechClips"
        const val UTTERANCE_PREFIX = "clip_"
        private const val MIN_VALID_BYTES = 1_000L
        private val CLIPS = (1..5).map { "d$it" to it.toString() }

        fun isClipUtterance(utteranceId: String?): Boolean = utteranceId?.startsWith(UTTERANCE_PREFIX) == true
    }
}
