package com.pisophone.kiosk.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The phone side of the box<->phone contract. protocol/fixtures/box_phone_v1.json holds known-answer vectors
 * made independently of both implementations; the firmware's host test (protocol_contract_test.cpp) checks the
 * same file, so a change on one side that the other cannot read fails a test.
 */
@RunWith(RobolectricTestRunner::class)
class ProtocolContractUnitTest {
    private fun fixture(): JSONObject {
        val candidates = listOf("../protocol/fixtures/box_phone_v1.json", "protocol/fixtures/box_phone_v1.json")
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("protocol/fixtures/box_phone_v1.json not found from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    @Test
    fun phoneDecryptsEveryMessageTheBoxProduces() {
        val vectors = fixture().getJSONArray("encrypt")
        assertTrue(vectors.length() >= 5)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val decrypted = KioskSecurity.decrypt(v.getString("ciphertext"), v.getString("secret"))
            assertEquals("vector ${v.getString("name")}", v.getString("plaintext"), decrypted)
        }
    }

    @Test
    fun phoneEncryptionRoundTripsAndHasTheSameShape() {
        val vectors = fixture().getJSONArray("encrypt")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val mine = KioskSecurity.encrypt(v.getString("plaintext"), v.getString("secret"))
            // iv (32 hex) + whole 16-byte blocks, same length as the box's output for the same plaintext
            assertEquals("vector ${v.getString("name")}", v.getString("ciphertext").length, mine.length)
            assertEquals(v.getString("plaintext"), KioskSecurity.decrypt(mine, v.getString("secret")))
        }
    }

    @Test
    fun signaturesMatchTheSharedVectors() {
        val vectors = fixture().getJSONArray("hmac")
        assertTrue(vectors.length() >= 4)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            assertEquals(
                "vector ${v.getString("name")}",
                v.getString("hmac"),
                KioskSecurity.calculateHmac(v.getString("message"), v.getString("secret")),
            )
        }
    }

    @Test
    fun aWrongSecretNeverYieldsTheOriginalText() {
        val v = fixture().getJSONArray("encrypt").getJSONObject(0)
        val wrong = KioskSecurity.decrypt(v.getString("ciphertext"), v.getString("secret") + "x")
        assertTrue(wrong != v.getString("plaintext"))
    }
}
