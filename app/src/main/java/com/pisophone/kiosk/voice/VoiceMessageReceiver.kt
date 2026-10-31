package com.pisophone.kiosk.voice

import java.io.IOException
import java.io.InputStream

/**
 * What the phone's /voice route decides once the server has accepted the signed request (feature 056): the request must announce a voice
 * message of a sane size, the body must be the length that size needs, the frames must check out, and the clip inside must be a good
 * one. Any fault ends in a named refusal and nothing is played. Pure Kotlin, so it is tested on a computer.
 */
object VoiceMessageReceiver {
    sealed class Outcome {
        class Accepted(val samples: ShortArray) : Outcome()

        /** [word] is what the server answers with and writes to its log. */
        class Refused(val word: String) : Outcome()
    }

    /**
     * [params] are the decrypted parameters of the signed request, [envelopeHmacHex] its hmac (it starts the chain of frames),
     * [contentLength] the request's Content-Length (-1 when it has none), [body] the frames.
     */
    fun receive(secret: String, envelopeHmacHex: String, params: Map<String, String>, contentLength: Long, body: InputStream): Outcome {
        if (params["action"] != "voice_message") return Outcome.Refused("VOICE_BAD_ACTION")
        val clipBytes = params["bytes"]?.trim()?.toIntOrNull()
        if (clipBytes == null || clipBytes !in 1..VoiceClip.MAX_CLIP_BYTES) return Outcome.Refused("VOICE_BAD_SIZE")
        if (!VoiceFrameAssembler.lengthMatches(contentLength, clipBytes)) return Outcome.Refused("VOICE_BAD_LENGTH")

        val assembled = try {
            VoiceFrameAssembler(secret, envelopeHmacHex, clipBytes).readFrom(body)
        } catch (e: IOException) {
            // The sender stopped (a Wi-Fi drop: the server's own read timeout) or the connection broke: nothing is played.
            return Outcome.Refused("VOICE_CUT_OFF")
        }
        val clip = when (assembled) {
            is VoiceFrameAssembler.Result.Refused -> return Outcome.Refused("VOICE_FRAMES_${assembled.reason}")
            is VoiceFrameAssembler.Result.Ok -> assembled.clip
        }
        return when (val decoded = VoiceClip.decode(clip)) {
            is VoiceClip.Decoded.Refused -> Outcome.Refused("VOICE_CLIP_${decoded.fault.reason}")
            is VoiceClip.Decoded.Ok -> Outcome.Accepted(decoded.samples)
        }
    }
}
