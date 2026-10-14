package com.pisophone.kiosk.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class Esp32AccountRequestsTest {
    private val secret = "x7Yq-9_zK.m+3=Rt8UvW4nBp"
    private val card = "PISO1.aabbccddeeff.42.10800.MEUCIQDm9R0kYwq3cG0gq2K1mQ8k5VwXnJ7p2sT4yZb6dUe1xwIgY2n0vLhPq8aRtB3cS5uVwXy7Zk1aD4fG6hJ9lMnO2pQ="

    private fun fixture(): JSONObject {
        val file = listOf("../protocol/fixtures/box_phone_v1.json", "protocol/fixtures/box_phone_v1.json")
            .map { File(it) }.firstOrNull { it.isFile } ?: error("protocol fixture not found")
        return JSONObject(file.readText())
    }

    @Test
    fun messagesAndSignaturesMatchTheSharedVectors() {
        val vectors = fixture().getJSONArray("accounts")
        assertEquals(setOf("scan", "name", "signout", "report", "info"), (0 until vectors.length()).map { vectors.getJSONObject(it).getString("op") }.toSet())
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val args = arrayOf(v.getString("op"), v.getString("device"), v.getString("ts"), v.getString("bound"))
            assertEquals(v.getString("name"), v.getString("message"), Esp32AccountRequests.message(args[0], args[1], args[2], args[3]))
            assertEquals(v.getString("name"), v.getString("hmac"), Esp32AccountRequests.signature(v.getString("secret"), args[0], args[1], args[2], args[3]))
        }
    }

    @Test
    fun scanUrlCarriesTheCardAndABindingSignature() {
        val url = Esp32AccountRequests.signedUrl("10.0.0.2", 80, Esp32AccountRequests.OP_SCAN, "dev-1234", secret, card = card, nowMs = 1760000000000L)
        assertTrue(url.startsWith("http://10.0.0.2:80/api/account/scan?device_id=dev-1234&card=PISO1."))
        assertFalse("base64 characters must be URL-encoded", url.substringAfter("card=").substringBefore("&").contains("="))
        val sig = Regex("sig=([0-9a-f]{64})$").find(url)!!.groupValues[1]
        assertEquals(Esp32AccountRequests.signature(secret, "scan", "dev-1234", "1760000000000", card), sig)
    }

    @Test
    fun nameBindsTheIdAndTheNameIntoTheSignature() {
        val url = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_NAME, "dev-1234", secret, id = 42, name = "Juan Dela Cruz", nowMs = 1760000000000L)
        assertTrue(url.contains("&id=42&name=Juan+Dela+Cruz&"))
        assertTrue(url.endsWith("sig=" + Esp32AccountRequests.signature(secret, "name", "dev-1234", "1760000000000", "42:Juan Dela Cruz")))
    }

    @Test
    fun signoutBindsTheSecondsLeftIntoTheSignature() {
        val url = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_SIGNOUT, "dev-1234", secret, id = 42, secondsLeft = 540, nowMs = 1760000000000L)
        assertTrue(url.contains("&id=42&time=540&"))
        assertTrue(url.endsWith("sig=" + Esp32AccountRequests.signature(secret, "signout", "dev-1234", "1760000000000", "42:540")))
        // a negative figure is never sent
        val neg = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_SIGNOUT, "d", secret, id = 42, secondsLeft = -5, nowMs = 1L)
        assertTrue(neg.contains("&time=0&"))
    }

    @Test
    fun infoSignsJustTheId() {
        val url = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_INFO, "d", secret, id = 42, nowMs = 5L)
        assertTrue(url.endsWith("sig=" + Esp32AccountRequests.signature(secret, "info", "d", "5", "42")))
    }

    @Test
    fun heartbeatReportIsSignedSeparately() {
        val extra = Esp32AccountRequests.heartbeatReport("dev-1234", secret, 42, 539, "1760000000000")
        val expected = Esp32AccountRequests.signature(secret, "report", "dev-1234", "1760000000000", "42:539")
        assertEquals("&acct=42&atime=539&asig=$expected", extra)
    }

    @Test
    fun namesAreChecked() {
        assertTrue(Esp32AccountRequests.isValidName("Juan"))
        assertTrue(Esp32AccountRequests.isValidName("Ana Maria_2-b"))
        assertTrue(Esp32AccountRequests.isValidName("A"))
        assertTrue(Esp32AccountRequests.isValidName("a".repeat(16)))
        assertFalse(Esp32AccountRequests.isValidName(""))
        assertFalse(Esp32AccountRequests.isValidName("a".repeat(17)))
        assertFalse(Esp32AccountRequests.isValidName(" Juan"))
        assertFalse(Esp32AccountRequests.isValidName("Juan "))
        assertFalse(Esp32AccountRequests.isValidName("a\"b"))
        assertFalse(Esp32AccountRequests.isValidName("<b>"))
        assertFalse(Esp32AccountRequests.isValidName("a:b"))
        assertFalse(Esp32AccountRequests.isValidName("café"))
    }

    @Test
    fun onlyCardLookingTextIsSentToTheBox() {
        assertTrue(Esp32AccountRequests.looksLikeCard(card))
        assertFalse(Esp32AccountRequests.looksLikeCard(""))
        assertFalse(Esp32AccountRequests.looksLikeCard("https://example.com/?x=" + "a".repeat(50)))
        assertFalse(Esp32AccountRequests.looksLikeCard("WIFI:T:WPA;S:Shop;P:secret;;"))
        assertFalse(Esp32AccountRequests.looksLikeCard(card + ".extra"))
        assertFalse(Esp32AccountRequests.looksLikeCard("PISO2" + card.substring(5)))
        assertFalse(Esp32AccountRequests.looksLikeCard(card + "x".repeat(200)))
    }

    @Test
    fun repliesParseAndBadOnesAreFlagged() {
        val ok = Esp32AccountRequests.parseReply("""{"success":true,"id":42,"name":"Juan","balance_sec":10800,"bonus_sec":10800,"server_time_ms":1760000000000}""")
        assertTrue(ok.success)
        assertEquals(42, ok.id)
        assertEquals("Juan", ok.name)
        assertEquals(10800, ok.balanceSec)
        assertEquals(10800, ok.bonusSec)
        val back = Esp32AccountRequests.parseReply("""{"success":true,"id":42,"name":"","balance_sec":540,"bonus_sec":0}""")
        assertEquals(0, back.bonusSec)
        assertEquals("", back.name)
        val bad = Esp32AccountRequests.parseReply("""{"success":false,"error":"WRONG_BOX"}""")
        assertFalse(bad.success)
        assertEquals("WRONG_BOX", bad.error)
        assertEquals("BAD_REPLY", Esp32AccountRequests.parseReply("<html>").error)
        // a balance beyond Int is clamped, never wrapped negative
        assertEquals(Int.MAX_VALUE, Esp32AccountRequests.parseReply("""{"success":true,"balance_sec":4294967295}""").balanceSec)
        for (code in listOf("BAD_CARD", "WRONG_BOX", "CARDS_OFF", "ALREADY_SIGNED_IN", "CLOCK_UNKNOWN", "NETWORK", "TOO_FAST")) {
            assertTrue(code, Esp32AccountRequests.describe(code).isNotBlank())
        }
        assertTrue(Esp32AccountRequests.describe("SOMETHING_NEW").contains("SOMETHING_NEW"))
    }
}
