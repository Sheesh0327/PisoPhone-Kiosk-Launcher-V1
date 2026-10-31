package com.pisophone.kiosk.voice

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How the phone puts a streamed voice message together (VoiceFrameAssembler). The vectors are
 * protocol/fixtures/box_phone_voice_v1.json (protocol/gen_voice_vectors.py); the box makes the same frames (voice_frames_test.cpp).
 * The test-only framer below is the box's algorithm written again here: it must reproduce the recorded frames byte for byte before
 * it is allowed to make the damaged ones.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceFrameAssemblerTest {
    private fun fixture(): JSONObject {
        val candidates = listOf("../protocol/fixtures/box_phone_voice_v1.json", "protocol/fixtures/box_phone_voice_v1.json")
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("protocol/fixtures/box_phone_voice_v1.json not found from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun clipNamed(name: String): ByteArray {
        val clips = fixture().getJSONArray("clips")
        for (i in 0 until clips.length()) {
            if (clips.getJSONObject(i).getString("name") == name) return hex(clips.getJSONObject(i).getString("hex"))
        }
        error("no clip $name")
    }

    private val secret get() = fixture().getJSONObject("frames").getString("secret")
    private val envelope get() = fixture().getJSONObject("frames").getString("envelope_hmac")

    /** The box's framing, written again for the tests (iv rule of the vectors: iv[j] = (seq * 31 + j * 7 + 1) % 256). */
    private fun frames(
        clip: ByteArray,
        secret: String = this.secret,
        envelopeHex: String = envelope,
        rawFirstBody: ByteArray? = null,
    ): List<ByteArray> {
        val key = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
        var prev = hex(envelopeHex)
        val count = (clip.size + 1023) / 1024
        val out = ArrayList<ByteArray>()
        for (seq in 0 until count) {
            val plain = clip.copyOfRange(seq * 1024, minOf(clip.size, (seq + 1) * 1024))
            val iv = ByteArray(16) { j -> ((seq * 31 + j * 7 + 1) % 256).toByte() }
            // (rawFirstBody: already-padded bytes encrypted as they are, to make a frame whose padding is wrong but whose mac is right)
            val cipher = Cipher.getInstance(if (seq == 0 && rawFirstBody != null) "AES/CBC/NoPadding" else "AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val body = cipher.doFinal(if (seq == 0 && rawFirstBody != null) rawFirstBody else plain)
            val head = byteArrayOf(
                if (seq == count - 1) 1 else 0,
                (seq shr 8).toByte(), seq.toByte(), (plain.size shr 8).toByte(), plain.size.toByte(),
            )
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            mac.update("pisovoice1".toByteArray())
            mac.update(prev)
            mac.update(head)
            mac.update(iv)
            mac.update(body)
            prev = mac.doFinal()
            out.add(head + iv + body + prev)
        }
        return out
    }

    private fun wire(f: List<ByteArray>) = f.fold(ByteArray(0)) { a, b -> a + b }

    private fun assemble(bytes: ByteArray, clipBytes: Int, secret: String = this.secret, envelopeHex: String = envelope) =
        VoiceFrameAssembler(secret, envelopeHex, clipBytes).readFrom(ByteArrayInputStream(bytes))

    private fun refused(r: VoiceFrameAssembler.Result, reason: String, what: String) {
        assertTrue("$what: $r", r is VoiceFrameAssembler.Result.Refused)
        assertEquals(what, reason, (r as VoiceFrameAssembler.Result.Refused).reason)
    }

    private fun flip(data: ByteArray, at: Int) = data.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun theTestFramerReproducesTheRecordedFramesAndTheSizesAreAnnounced() {
        val cases = fixture().getJSONObject("frames").getJSONArray("cases")
        assertEquals(3, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val clip = clipNamed(c.getString("clip"))
            val f = frames(clip)
            assertEquals(c.getInt("frame_count"), f.size)
            assertEquals(c.getInt("frame_count"), VoiceFrameAssembler.frameCountFor(clip.size))
            assertEquals(c.getInt("wire_bytes"), wire(f).size)
            assertEquals(c.getInt("wire_bytes"), VoiceFrameAssembler.wireBytesFor(clip.size))
            assertEquals(c.getString("wire_sha256"), sha(wire(f)))
            if (c.has("frames_hex")) {
                val recorded = c.getJSONArray("frames_hex")
                for (k in 0 until recorded.length()) assertArrayEquals("frame $k", hex(recorded.getString(k)), f[k])
            }
        }
        assertEquals(64972, VoiceFrameAssembler.wireBytesFor(60841))
        assertEquals(60, VoiceFrameAssembler.frameCountFor(60841))
    }

    @Test
    fun goodFramesGiveBackTheClip() {
        for (name in listOf("one-sample", "sawtooth-0.5s", "one-full-block", "noise-1s", "max-15s")) {
            val clip = clipNamed(name)
            val r = assemble(wire(frames(clip)), clip.size)
            assertTrue("$name: $r", r is VoiceFrameAssembler.Result.Ok)
            assertArrayEquals(name, clip, (r as VoiceFrameAssembler.Result.Ok).clip)
        }
    }

    @Test
    fun aStreamThatGivesOneByteAtATimeStillWorks() {
        val clip = clipNamed("sawtooth-0.5s")
        val bytes = wire(frames(clip))
        val slow = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val v = read()
                if (v < 0) return -1
                b[off] = v.toByte()
                return 1
            }
        }
        val r = VoiceFrameAssembler(secret, envelope, clip.size).readFrom(slow)
        assertTrue("$r", r is VoiceFrameAssembler.Result.Ok)
        assertArrayEquals(clip, (r as VoiceFrameAssembler.Result.Ok).clip)
    }

    @Test
    fun theWholeLongestClipGoesThroughFramesAndDecoderToTheRecordedSamples() {
        val clip = clipNamed("max-15s")
        val r = assemble(wire(frames(clip)), clip.size)
        assertTrue("$r", r is VoiceFrameAssembler.Result.Ok)
        val decoded = VoiceClip.decode((r as VoiceFrameAssembler.Result.Ok).clip)
        assertTrue("$decoded", decoded is VoiceClip.Decoded.Ok)
        assertEquals(VoiceClip.MAX_SAMPLES, (decoded as VoiceClip.Decoded.Ok).samples.size)
    }

    @Test
    fun aStreamThatEndsEarlyIsRefusedAsShort() {
        val clip = clipNamed("sawtooth-0.5s")
        val bytes = wire(frames(clip))
        refused(assemble(bytes.copyOf(bytes.size - 1), clip.size), "short", "one byte short")
        refused(assemble(bytes.copyOf(frames(clip)[0].size), clip.size), "short", "the last frame is missing")
        refused(assemble(bytes.copyOf(3), clip.size), "short", "ends inside a header")
        refused(assemble(ByteArray(0), clip.size), "short", "empty")
    }

    @Test
    fun theSizeTheSignedRequestAnnouncedMustBeTheSizeThatArrives() {
        val clip = clipNamed("sawtooth-0.5s")
        val n = wire(frames(clip)).size
        assertTrue(VoiceFrameAssembler.lengthMatches(n.toLong(), clip.size))
        assertFalse(VoiceFrameAssembler.lengthMatches(n + 1L, clip.size))
        assertFalse(VoiceFrameAssembler.lengthMatches(n - 1L, clip.size))
        assertFalse(VoiceFrameAssembler.lengthMatches(-1L, clip.size))
        val overLong = VoiceFrameAssembler.wireBytesFor(60842).toLong()
        assertFalse("a clip over 15 s is never read", VoiceFrameAssembler.lengthMatches(overLong, 60842))
        assertFalse("an empty clip is never read", VoiceFrameAssembler.lengthMatches(0L, 0))
        refused(assemble(wire(frames(clip)), clip.size + 1), "length", "announced one clip byte more than was sent")
        refused(assemble(wire(frames(clip)), 60842), "length", "announced a clip over 15 s")
        refused(assemble(wire(frames(clip)), 0), "length", "announced an empty clip")
    }

    @Test
    fun aDamagedSetOfFramesIsRefused() {
        val clip = clipNamed("sawtooth-0.5s")
        val f = frames(clip)
        val bytes = wire(f)
        val first = f[0].size
        refused(assemble(f[0] + f[0] + f[1], clip.size), "seq", "a repeated frame")
        refused(assemble(f[1] + f[0], clip.size), "seq", "reordered")
        refused(assemble(flip(bytes, 0), clip.size), "last", "the first frame says it is the last")
        refused(assemble(flip(bytes, 1), clip.size), "seq", "sequence number")
        refused(assemble(flip(bytes, 4), clip.size), "length", "the plain length")
        refused(assemble(flip(bytes, 5), clip.size), "mac", "the iv")
        refused(assemble(flip(bytes, 25), clip.size), "mac", "the ciphertext")
        refused(assemble(flip(bytes, first - 1), clip.size), "mac", "the mac")
        refused(assemble(flip(bytes, first), clip.size), "last", "the second frame loses its last mark")
        val last = bytes.size - f[1].size
        refused(assemble(flip(bytes, last), clip.size), "last", "the last frame says it is not the last")
        refused(assemble(bytes, clip.size, secret = "another-secret-1234"), "mac", "another key")
        refused(assemble(bytes, clip.size, envelopeHex = sha("x".toByteArray())), "mac", "frames of another signed request")
        val other = frames(clip, envelopeHex = sha("y".toByteArray()))
        refused(assemble(f[0] + other[1], clip.size), "mac", "a frame from another chain")
        refused(assemble(bytes, clip.size, secret = ""), "key", "an empty key")
    }

    @Test
    fun aFrameWithTheRightMacButWrongPaddingIsRefused() {
        val clip = clipNamed("one-sample")
        val ok = frames(clip, rawFirstBody = clip + byteArrayOf(4, 4, 4, 4)) // 12 bytes + the padding a real framer writes
        assertTrue(assemble(wire(ok), clip.size) is VoiceFrameAssembler.Result.Ok)
        val bad = frames(clip, rawFirstBody = clip + byteArrayOf(0, 0, 0, 0)) // padding bytes of 0 are not PKCS#7
        refused(assemble(wire(bad), clip.size), "padding", "bad padding under a good mac")
        val wrongCount = frames(clip, rawFirstBody = clip + byteArrayOf(5, 5, 5, 5)) // says 5 padding bytes, there are 4
        refused(assemble(wire(wrongCount), clip.size), "padding", "padding that disagrees with the length")
    }
}
