package com.pisophone.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * What the phone's /voice route decides once the signed request has been accepted: the size the request announced, the frames, the
 * clip. Everything that can go wrong ends in a named refusal and plays nothing. (The signature, the time and the replay checks are
 * the server's, tested with the other routes in KioskHttpServerVoiceUnitTest.)
 */
@RunWith(RobolectricTestRunner::class)
class VoiceMessageReceiverTest {
    private fun fixture() = VoiceTestSupport.fixture()

    private fun hex(s: String) = VoiceTestSupport.hex(s)

    private fun clipNamed(name: String) = VoiceTestSupport.clipNamed(name)

    private val secret get() = VoiceTestSupport.secret
    private val envelope get() = VoiceTestSupport.envelope

    private fun frames(clip: ByteArray): ByteArray = VoiceTestSupport.wire(VoiceTestSupport.frames(clip))

    private fun params(clipBytes: Int, action: String = "voice_message") =
        mapOf("action" to action, "ts" to "1790000000000", "nonce" to "vm-1", "bytes" to clipBytes.toString())

    private fun receive(
        clip: ByteArray,
        body: ByteArray = frames(clip),
        announced: Map<String, String> = params(clip.size),
        contentLength: Long = body.size.toLong(),
        stream: InputStream = ByteArrayInputStream(body),
    ) = VoiceMessageReceiver.receive(secret, envelope, announced, contentLength, stream)

    private fun refused(r: VoiceMessageReceiver.Outcome, word: String, what: String) {
        assertTrue("$what: $r", r is VoiceMessageReceiver.Outcome.Refused)
        assertEquals(what, word, (r as VoiceMessageReceiver.Outcome.Refused).word)
    }

    @Test
    fun aGoodMessageGivesTheSamplesOfTheClip() {
        for (name in listOf("one-sample", "sawtooth-0.5s", "noise-1s", "max-15s")) {
            val clip = clipNamed(name)
            val r = receive(clip)
            assertTrue("$name: $r", r is VoiceMessageReceiver.Outcome.Accepted)
            val expected = (VoiceClip.decode(clip) as VoiceClip.Decoded.Ok).samples
            assertTrue(name, expected.contentEquals((r as VoiceMessageReceiver.Outcome.Accepted).samples))
        }
    }

    @Test
    fun theRequestMustAnnounceAVoiceMessageOfASaneSize() {
        val clip = clipNamed("sawtooth-0.5s")
        refused(receive(clip, announced = params(clip.size, action = "locate")), "VOICE_BAD_ACTION", "another action")
        refused(receive(clip, announced = params(clip.size) - "bytes"), "VOICE_BAD_SIZE", "no size")
        refused(receive(clip, announced = params(clip.size) + ("bytes" to "lots")), "VOICE_BAD_SIZE", "a size that is not a number")
        refused(receive(clip, announced = params(0)), "VOICE_BAD_SIZE", "an empty clip")
        refused(receive(clip, announced = params(VoiceClip.MAX_CLIP_BYTES + 1)), "VOICE_BAD_SIZE", "a clip over 15 s")
        refused(receive(clip, announced = params(-5)), "VOICE_BAD_SIZE", "a negative size")
    }

    @Test
    fun theContentLengthMustBeTheOneAnnounced() {
        val clip = clipNamed("sawtooth-0.5s")
        val body = frames(clip)
        refused(receive(clip, body = body, contentLength = body.size + 1L), "VOICE_BAD_LENGTH", "one byte more than the frames need")
        refused(receive(clip, body = body, contentLength = body.size - 1L), "VOICE_BAD_LENGTH", "one byte less")
        refused(receive(clip, body = body, contentLength = -1L), "VOICE_BAD_LENGTH", "no length (a chunked request)")
        refused(receive(clip, body = body, contentLength = Long.MAX_VALUE), "VOICE_BAD_LENGTH", "a huge length")
    }

    @Test
    fun aMessageThatEndsEarlyOrStallsPlaysNothing() {
        val clip = clipNamed("sawtooth-0.5s")
        val body = frames(clip)
        val closedEarly = ByteArrayInputStream(body.copyOf(body.size - 1))
        refused(receive(clip, body = body, stream = closedEarly), "VOICE_FRAMES_short", "the sender closed")
        val stalls = object : InputStream() {
            var sent = 0
            override fun read(): Int = throw SocketTimeoutException("Read timed out")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= 1500) throw SocketTimeoutException("Read timed out")
                val n = minOf(len, 1000, body.size - sent)
                System.arraycopy(body, sent, b, off, n)
                sent += n
                return n
            }
        }
        refused(receive(clip, body = body, stream = stalls), "VOICE_CUT_OFF", "the sender went silent")
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")
        }
        refused(receive(clip, body = body, stream = broken), "VOICE_CUT_OFF", "the connection broke")
    }

    @Test
    fun damagedFramesPlayNothingAndNameTheFault() {
        val clip = clipNamed("sawtooth-0.5s")
        val body = frames(clip)
        val flipped = body.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        refused(receive(clip, body = flipped), "VOICE_FRAMES_mac", "an altered frame")
        val announcedOneLess = params(clip.size).plus("bytes" to (clip.size - 1).toString())
        refused(receive(clip, body = body, announced = announcedOneLess), "VOICE_FRAMES_length", "another size than the frames carry")
    }

    @Test
    fun aClipWithABadBlockIsRefusedEvenWhenTheFramesAreGood() {
        val rows = fixture().getJSONArray("refused_clips")
        var tried = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val clip = hex(r.getString("hex"))
            if (clip.size !in 12..VoiceClip.MAX_CLIP_BYTES) continue
            val out = receive(clip)
            assertTrue("${r.getString("name")}: $out", out is VoiceMessageReceiver.Outcome.Refused)
            assertEquals(r.getString("name"), "VOICE_CLIP_${r.getString("reason")}", (out as VoiceMessageReceiver.Outcome.Refused).word)
            tried++
        }
        assertTrue("tried $tried clips", tried >= 8)
        assertFalse(tried == 0)
    }
}
