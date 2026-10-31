package com.pisophone.kiosk.voice

import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Puts a streamed voice message together on the phone (feature 056). The box does not hold or store the clip: it encrypts and signs it
 * one frame at a time as the admin page's upload arrives. This reads those frames, and returns the clip only when the last frame
 * has checked out; any fault throws the whole message away. Pure Kotlin; the vectors are protocol/fixtures/box_phone_voice_v1.json.
 *
 *   frame = flags (bit 0 = last) | seq u16 | plain length u16 | iv 16 | AES-256-CBC (PKCS#7) | mac 32       (big-endian)
 *   mac   = HMAC-SHA256(secret, "pisovoice1" | previous mac | the 5 header bytes | iv | ciphertext)
 *   the first "previous mac" is the 32 raw bytes of the signed request's hmac, which the caller has already checked, so these
 *   frames belong to that one request. key = SHA-256(secret), as for every box message.
 *
 * The signed request says how many clip bytes follow, and so exactly how many frames and bytes: every size read from a frame is
 * compared with what the request promised, never trusted.
 */
class VoiceFrameAssembler(
    private val secret: String,
    private val envelopeHmacHex: String,
    private val clipBytes: Int,
) {
    sealed class Result {
        class Ok(val clip: ByteArray) : Result()

        class Refused(val reason: String) : Result()
    }

    companion object {
        const val PLAIN_MAX = 1024
        const val HEAD_BYTES = 5
        const val IV_BYTES = 16
        const val MAC_BYTES = 32
        private const val MAC_TAG = "pisovoice1"

        fun frameCountFor(clipBytes: Int): Int = (clipBytes + PLAIN_MAX - 1) / PLAIN_MAX

        fun frameWireBytes(plainLen: Int): Int = HEAD_BYTES + IV_BYTES + 16 * (plainLen / 16 + 1) + MAC_BYTES

        /** The length of the whole request body for a clip of [clipBytes] bytes. */
        fun wireBytesFor(clipBytes: Int): Int {
            val full = clipBytes / PLAIN_MAX
            val rest = clipBytes % PLAIN_MAX
            return full * frameWireBytes(PLAIN_MAX) + if (rest > 0) frameWireBytes(rest) else 0
        }

        /** True when the request's Content-Length is the one the signed request announced; a clip over 15 s or an empty one never is. */
        fun lengthMatches(contentLength: Long, clipBytes: Int): Boolean =
            clipBytes in 1..VoiceClip.MAX_CLIP_BYTES && contentLength == wireBytesFor(clipBytes).toLong()
    }

    /** Reads exactly [wireBytesFor] bytes. A stream that ends early is "short"; the caller's read timeout ends a stalled one. */
    fun readFrom(input: InputStream): Result {
        if (clipBytes !in 1..VoiceClip.MAX_CLIP_BYTES) return Result.Refused("length")
        if (secret.isEmpty()) return Result.Refused("key")
        var prev = hexToBytes(envelopeHmacHex) ?: return Result.Refused("key")
        if (prev.size != MAC_BYTES) return Result.Refused("key")

        val aesKey = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)), "AES")
        val macKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val count = frameCountFor(clipBytes)
        val clip = ByteArray(clipBytes)

        for (seq in 0 until count) {
            val head = readFully(input, HEAD_BYTES) ?: return Result.Refused("short")
            val flags = head[0].toInt() and 0xFF
            if (flags and 0xFE != 0) return Result.Refused("flags")
            if (u16(head, 1) != seq) return Result.Refused("seq")
            if ((flags == 1) != (seq == count - 1)) return Result.Refused("last")
            val plainLen = u16(head, 3)
            if (plainLen != minOf(PLAIN_MAX, clipBytes - PLAIN_MAX * seq)) return Result.Refused("length")

            val rest = readFully(input, frameWireBytes(plainLen) - HEAD_BYTES) ?: return Result.Refused("short")
            val iv = rest.copyOfRange(0, IV_BYTES)
            val cipher = rest.copyOfRange(IV_BYTES, rest.size - MAC_BYTES)
            val mac = rest.copyOfRange(rest.size - MAC_BYTES, rest.size)

            val expected = Mac.getInstance("HmacSHA256").run {
                init(macKey)
                update(MAC_TAG.toByteArray(Charsets.UTF_8))
                update(prev)
                update(head)
                update(iv)
                update(cipher)
                doFinal()
            }
            if (!MessageDigest.isEqual(mac, expected)) return Result.Refused("mac")

            val plain = try {
                Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(iv))
                    doFinal(cipher)
                }
            } catch (e: GeneralSecurityException) {
                return Result.Refused("padding")
            }
            if (plain.size != plainLen) return Result.Refused("padding")
            plain.copyInto(clip, seq * PLAIN_MAX)
            prev = mac
        }
        return Result.Ok(clip)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) return null
            got += r
        }
        return buf
    }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun hexToBytes(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
