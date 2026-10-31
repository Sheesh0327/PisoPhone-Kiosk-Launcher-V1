package com.pisophone.kiosk.voice

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What the voice tests share: the vector file (protocol/fixtures/box_phone_voice_v1.json, made by protocol/gen_voice_vectors.py) and the
 * box's framing written again for the tests (iv rule of the vectors: iv[j] = (seq * 31 + j * 7 + 1) % 256). The framer must reproduce the
 * recorded frames byte for byte (VoiceFrameAssemblerTest) before it is trusted to make damaged ones.
 */
internal object VoiceTestSupport {
    fun fixture(): JSONObject {
        val candidates = listOf("../protocol/fixtures/box_phone_voice_v1.json", "protocol/fixtures/box_phone_voice_v1.json")
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("protocol/fixtures/box_phone_voice_v1.json not found from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun clipNamed(name: String): ByteArray {
        val clips = fixture().getJSONArray("clips")
        for (i in 0 until clips.length()) {
            if (clips.getJSONObject(i).getString("name") == name) return hex(clips.getJSONObject(i).getString("hex"))
        }
        error("no clip $name")
    }

    val secret: String get() = fixture().getJSONObject("frames").getString("secret")

    val envelope: String get() = fixture().getJSONObject("frames").getString("envelope_hmac")

    /** The frames of [clip] as the box makes them, chained from [envelopeHex] (the hmac of the signed request). */
    fun frames(
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
            val raw = seq == 0 && rawFirstBody != null
            val cipher = Cipher.getInstance(if (raw) "AES/CBC/NoPadding" else "AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val body = cipher.doFinal(if (raw) rawFirstBody else plain)
            val head = byteArrayOf(
                if (seq == count - 1) 1 else 0,
                (seq shr 8).toByte(),
                seq.toByte(),
                (plain.size shr 8).toByte(),
                plain.size.toByte(),
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

    fun wire(frames: List<ByteArray>): ByteArray = frames.fold(ByteArray(0)) { a, b -> a + b }
}
