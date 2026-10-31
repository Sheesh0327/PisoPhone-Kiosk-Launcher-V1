package com.pisophone.kiosk.voice

/**
 * A voice message clip as the admin page makes it and the phone plays it (feature 056). Pure Kotlin (nothing of Android), so the
 * decoding is tested on a computer against protocol/fixtures/box_phone_voice_v1.json, made independently by protocol/gen_voice_vectors.py.
 *
 *   bytes 0..7   version 1, codec 1 (IMA ADPCM, mono), sample rate 8000 (u16), sample count (u32), all big-endian
 *   then         blocks of 256 bytes (505 samples each, the last one shorter): a 4-byte header (predictor i16 little-endian,
 *                step index 0..88, reserved 0), then 4-bit codes, low nibble first. The first sample of a block is the predictor.
 *
 * At most 120000 samples (15.0 s). The length is fixed by the sample count, so a header that lies about it is found.
 */
object VoiceClip {
    const val HEADER_BYTES = 8
    const val BLOCK_BYTES = 256
    const val BLOCK_SAMPLES = 505
    const val MAX_SAMPLES = 120_000
    const val MAX_CLIP_BYTES = 60_841
    const val SAMPLE_RATE = 8000

    private const val VERSION = 1
    private const val CODEC = 1
    private const val BLOCK_HEADER_BYTES = 4
    private const val MAX_STEP_INDEX = 88

    private val STEP =
        intArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28) +
            intArrayOf(31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118) +
            intArrayOf(130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494) +
            intArrayOf(544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066) +
            intArrayOf(2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630) +
            intArrayOf(9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
    private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

    /** Why a clip was refused. The words are the same as in the vector file and in the box's VoiceClip.h. */
    enum class Fault(val reason: String) {
        SHORT("short"),
        VERSION("version"),
        CODEC("codec"),
        RATE("rate"),
        EMPTY("empty"),
        TOO_LONG("too_long"),
        LENGTH("length"),
        BLOCK_HEADER("block_header"),
    }

    sealed class Decoded {
        class Ok(val samples: ShortArray) : Decoded()

        class Refused(val fault: Fault) : Decoded()
    }

    /** The length of the whole clip (header and blocks) for a sample count; 0 when there are no samples or more than 15 s. */
    fun clipBytesFor(samples: Int): Int {
        if (samples < 1 || samples > MAX_SAMPLES) return 0
        val blocks = (samples + BLOCK_SAMPLES - 1) / BLOCK_SAMPLES
        val last = samples - BLOCK_SAMPLES * (blocks - 1)
        return HEADER_BYTES + BLOCK_BYTES * (blocks - 1) + BLOCK_HEADER_BYTES + last / 2
    }

    /**
     * What the box checks too: the first 8 bytes and that the whole clip is the length they promise. [clip] may be only the
     * header; [totalBytes] is how long the whole clip is (announced, or as received).
     */
    fun checkHeader(clip: ByteArray, totalBytes: Int): Fault? {
        if (clip.size < HEADER_BYTES) return Fault.SHORT
        if ((clip[0].toInt() and 0xFF) != VERSION) return Fault.VERSION
        if ((clip[1].toInt() and 0xFF) != CODEC) return Fault.CODEC
        if (u16(clip, 2) != SAMPLE_RATE) return Fault.RATE
        val samples = sampleCount(clip)
        if (samples == 0L) return Fault.EMPTY
        if (samples > MAX_SAMPLES) return Fault.TOO_LONG
        if (totalBytes != clipBytesFor(samples.toInt())) return Fault.LENGTH
        return null
    }

    /** The phone's whole check, then the samples. Block headers are checked here: the box never looks inside the blocks. */
    fun decode(clip: ByteArray): Decoded {
        checkHeader(clip, clip.size)?.let { return Decoded.Refused(it) }
        val total = sampleCount(clip).toInt()
        val out = ShortArray(total)
        var pos = HEADER_BYTES
        var written = 0
        while (written < total) {
            val take = minOf(BLOCK_SAMPLES, total - written)
            var predictor = ((clip[pos + 1].toInt() shl 8) or (clip[pos].toInt() and 0xFF)).toShort().toInt()
            var index = clip[pos + 2].toInt() and 0xFF
            if (index > MAX_STEP_INDEX || clip[pos + 3].toInt() != 0) return Decoded.Refused(Fault.BLOCK_HEADER)
            out[written++] = predictor.toShort()
            for (i in 0 until take - 1) {
                val byte = clip[pos + BLOCK_HEADER_BYTES + i / 2].toInt() and 0xFF
                val code = if (i % 2 == 1) byte shr 4 else byte and 0x0F
                val step = STEP[index]
                var delta = step shr 3
                if (code and 4 != 0) delta += step
                if (code and 2 != 0) delta += step shr 1
                if (code and 1 != 0) delta += step shr 2
                val moved = if (code and 8 != 0) predictor - delta else predictor + delta
                predictor = moved.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                index = (index + INDEX[code]).coerceIn(0, MAX_STEP_INDEX)
                out[written++] = predictor.toShort()
            }
            pos += BLOCK_HEADER_BYTES + take / 2
        }
        return Decoded.Ok(out)
    }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun sampleCount(b: ByteArray): Long =
        (u16(b, 4).toLong() shl 16) or u16(b, 6).toLong()
}
