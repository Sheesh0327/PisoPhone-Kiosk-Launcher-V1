package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
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

    private fun fixture(): JSONObject {
        val file = listOf("../protocol/fixtures/box_phone_v1.json", "protocol/fixtures/box_phone_v1.json")
            .map { File(it) }.firstOrNull { it.isFile } ?: error("protocol fixture not found")
        return JSONObject(file.readText())
    }

    @Test
    fun messagesAndSignaturesMatchTheSharedVectors() {
        val vectors = fixture().getJSONArray("accounts")
        assertTrue(vectors.length() >= 5)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val args = arrayOf(v.getString("op"), v.getString("device"), v.getString("ts"), v.getString("bound"))
            assertEquals(v.getString("name"), v.getString("message"), Esp32AccountRequests.message(args[0], args[1], args[2], args[3]))
            assertEquals(v.getString("name"), v.getString("hmac"), Esp32AccountRequests.signature(v.getString("secret"), args[0], args[1], args[2], args[3]))
        }
    }

    @Test
    fun thePhoneOpensTheBoxsPinEnvelopeAndSealsItsOwn() {
        val pin = fixture().getJSONObject("account_pin")
        assertEquals(pin.getString("plaintext"), KioskSecurity.decrypt(pin.getString("ciphertext"), pin.getString("secret")))
        val mine = Esp32AccountRequests.encryptPin("4821", secret)
        assertEquals("PIN:4821", KioskSecurity.decrypt(mine, secret))
        assertFalse("the PIN must never appear in clear", mine.contains("4821"))
    }

    @Test
    fun signinUrlCarriesTheEncryptedPinAndABindingSignature() {
        val url = Esp32AccountRequests.signedUrl("10.0.0.2", 80, Esp32AccountRequests.OP_SIGNIN, "dev-1234", secret, "Alice", pin = "4821", nowMs = 1760000000000L)
        assertTrue(url.startsWith("http://10.0.0.2:80/api/account/signin?device_id=dev-1234&user=alice&pin_enc="))
        assertFalse(url.contains("4821"))
        val pinEnc = Regex("pin_enc=([0-9a-f]+)").find(url)!!.groupValues[1]
        val sig = Regex("sig=([0-9a-f]{64})$").find(url)!!.groupValues[1]
        assertEquals(Esp32AccountRequests.signature(secret, "signin", "dev-1234", "1760000000000", "alice:$pinEnc"), sig)
        assertTrue(url.contains("&ts=1760000000000&"))
    }

    @Test
    fun signoutBindsTheSecondsLeftIntoTheSignature() {
        val url = Esp32AccountRequests.signedUrl("10.0.0.2", 80, Esp32AccountRequests.OP_SIGNOUT, "dev-1234", secret, "alice", secondsLeft = 540, nowMs = 1760000000000L)
        assertTrue(url.contains("&time=540&"))
        assertTrue(url.endsWith("sig=" + Esp32AccountRequests.signature(secret, "signout", "dev-1234", "1760000000000", "alice:540")))
        // a negative figure is never sent
        val neg = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_SIGNOUT, "d", secret, "alice", secondsLeft = -5, nowMs = 1L)
        assertTrue(neg.contains("&time=0&"))
    }

    @Test
    fun infoSignsJustTheUsername() {
        val url = Esp32AccountRequests.signedUrl("h", 80, Esp32AccountRequests.OP_INFO, "d", secret, "alice", nowMs = 5L)
        assertTrue(url.endsWith("sig=" + Esp32AccountRequests.signature(secret, "info", "d", "5", "alice")))
        assertFalse(url.contains("pin_enc"))
    }

    @Test
    fun heartbeatReportIsSignedSeparately() {
        val extra = Esp32AccountRequests.heartbeatReport("dev-1234", secret, "Alice", 539, "1760000000000")
        val expected = Esp32AccountRequests.signature(secret, "report", "dev-1234", "1760000000000", "alice:539")
        assertEquals("&acct=alice&atime=539&asig=$expected", extra)
    }

    @Test
    fun validation() {
        assertTrue(Esp32AccountRequests.isValidUsername("bob_99"))
        assertFalse(Esp32AccountRequests.isValidUsername("Bob"))
        assertFalse(Esp32AccountRequests.isValidUsername("ab"))
        assertFalse(Esp32AccountRequests.isValidUsername("a".repeat(17)))
        assertFalse(Esp32AccountRequests.isValidUsername("a b c"))
        assertTrue(Esp32AccountRequests.isValidPin("1234"))
        assertTrue(Esp32AccountRequests.isValidPin("123456"))
        assertFalse(Esp32AccountRequests.isValidPin("123"))
        assertFalse(Esp32AccountRequests.isValidPin("1234567"))
        assertFalse(Esp32AccountRequests.isValidPin("12a4"))
        assertEquals("alice", Esp32AccountRequests.normalizeUsername("  ALICE "))
    }

    @Test
    fun repliesParseAndBadOnesAreFlagged() {
        val ok = Esp32AccountRequests.parseReply("""{"success":true,"username":"alice","balance_sec":540,"server_time_ms":1760000000000}""")
        assertTrue(ok.success)
        assertEquals("alice", ok.username)
        assertEquals(540, ok.balanceSec)
        val bad = Esp32AccountRequests.parseReply("""{"success":false,"error":"BAD_PIN"}""")
        assertFalse(bad.success)
        assertEquals("BAD_PIN", bad.error)
        assertEquals("BAD_REPLY", Esp32AccountRequests.parseReply("<html>").error)
        // a balance beyond Int is clamped, never wrapped negative
        assertEquals(Int.MAX_VALUE, Esp32AccountRequests.parseReply("""{"success":true,"balance_sec":4294967295}""").balanceSec)
        assertTrue(Esp32AccountRequests.describe("BAD_PIN").isNotBlank())
        assertTrue(Esp32AccountRequests.describe("SOMETHING_NEW").contains("SOMETHING_NEW"))
    }
}
