package com.pisophone.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

/**
 * The phone's side of the voice clip. protocol/fixtures/box_phone_voice_v1.json holds known-answer vectors made by
 * protocol/gen_voice_vectors.py, independent of both implementations; the box's host tests (voice_clip_test.cpp) and the admin
 * page's browser test check the same file.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceClipTest {
    private fun fixture() = VoiceTestSupport.fixture()

    private fun hex(s: String) = VoiceTestSupport.hex(s)

    private fun sha256(samples: ShortArray): String {
        val bytes = ByteArray(samples.size * 2)
        for ((i, v) in samples.withIndex()) {
            bytes[i * 2] = (v.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (v.toInt() shr 8).toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun theNumbersMatchTheVectorFile() {
        val c = fixture().getJSONObject("constants")
        assertEquals(c.getInt("header_bytes"), VoiceClip.HEADER_BYTES)
        assertEquals(c.getInt("block_bytes"), VoiceClip.BLOCK_BYTES)
        assertEquals(c.getInt("block_samples"), VoiceClip.BLOCK_SAMPLES)
        assertEquals(c.getInt("max_samples"), VoiceClip.MAX_SAMPLES)
        assertEquals(c.getInt("max_clip_bytes"), VoiceClip.MAX_CLIP_BYTES)
        assertEquals(c.getInt("rate"), VoiceClip.SAMPLE_RATE)
        assertEquals(60841, VoiceClip.clipBytesFor(VoiceClip.MAX_SAMPLES))
    }

    @Test
    fun theLengthFormulaAtTheEdges() {
        assertEquals(12, VoiceClip.clipBytesFor(1))
        assertEquals(8 + 256, VoiceClip.clipBytesFor(505))
        assertEquals(8 + 256 + 4, VoiceClip.clipBytesFor(506))
        assertEquals(0, VoiceClip.clipBytesFor(0))
        assertEquals(0, VoiceClip.clipBytesFor(VoiceClip.MAX_SAMPLES + 1))
    }

    @Test
    fun everyGoodClipPassesAndDecodesToTheRecordedSamples() {
        val clips = fixture().getJSONArray("clips")
        assertTrue(clips.length() >= 7)
        for (i in 0 until clips.length()) {
            val v = clips.getJSONObject(i)
            val clip = hex(v.getString("hex"))
            val name = v.getString("name")
            assertEquals(name, v.getInt("bytes"), clip.size)
            assertEquals(name, v.getInt("bytes"), VoiceClip.clipBytesFor(v.getInt("samples")))
            assertNull(name, VoiceClip.checkHeader(clip, clip.size))
            val decoded = VoiceClip.decode(clip)
            assertTrue("$name: $decoded", decoded is VoiceClip.Decoded.Ok)
            val samples = (decoded as VoiceClip.Decoded.Ok).samples
            assertEquals(name, v.getInt("samples"), samples.size)
            assertEquals(name, v.getString("decoded_sha256"), sha256(samples))
        }
    }

    @Test
    fun everyRefusedClipIsRefusedForTheRecordedReason() {
        val rows = fixture().getJSONArray("refused_clips")
        assertTrue(rows.length() >= 10)
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val clip = hex(r.getString("hex"))
            val name = r.getString("name")
            val reason = r.getString("reason")
            // The faults the box would also find are found by the same header check.
            if (r.getBoolean("box_refuses")) {
                assertEquals(name, reason, VoiceClip.checkHeader(clip, clip.size)?.reason)
            } else {
                assertNull(name, VoiceClip.checkHeader(clip, clip.size))
            }
            val decoded = VoiceClip.decode(clip)
            assertTrue("$name: $decoded", decoded is VoiceClip.Decoded.Refused)
            assertEquals(name, reason, (decoded as VoiceClip.Decoded.Refused).fault.reason)
        }
    }

    @Test
    fun theHeaderIsCheckedFromTheFirstEightBytesAndTheAnnouncedLength() {
        val clip = hex(fixture().getJSONArray("clips").getJSONObject(2).getString("hex"))
        assertNull(VoiceClip.checkHeader(clip.copyOf(VoiceClip.HEADER_BYTES), clip.size))
        assertEquals("length", VoiceClip.checkHeader(clip.copyOf(VoiceClip.HEADER_BYTES), clip.size + 1)?.reason)
        assertEquals("short", VoiceClip.checkHeader(clip.copyOf(7), clip.size)?.reason)
        assertEquals("short", VoiceClip.checkHeader(ByteArray(0), 0)?.reason)
    }
}
