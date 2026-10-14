package com.pisophone.kiosk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * The real service on a real (emulated) Android, driven the way the coin box drives a phone: signed, encrypted requests to the
 * app's own server on port 8080. What is checked is what a customer sees: the phone is locked, a payment arrives, the phone
 * unlocks, the time counts down, and the phone locks again by itself. The answers come back over the same protocol the box
 * uses (`/state`, `/get_time`), so no test hook is needed in the app.
 *
 * Not covered here (needs the phone to be device owner, which an emulator image cannot always become): the lock itself,
 * app wiping, Wi-Fi switching.
 */
@RunWith(AndroidJUnit4::class)
class KioskWireFlowTest {
    companion object {
        private const val TAG = "KioskWireFlowTest"
        private const val SECRET = "emulator-test-secret-0123456789"
        private const val LOCKED = 0
        private const val ARMED_LOCKED = 1
        private const val UNLOCKED = 2

        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        private fun shell(command: String) {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } // read to the end: that is how we wait for it
        }

        @BeforeClass
        @JvmStatic
        fun startTheKiosk() {
            val pkg = context.packageName
            // the lock screen is an overlay window; notifications are needed by the foreground service
            shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            KioskSecurity.applyDirectProvisioning(context, secret = SECRET, mac = "02:00:00:00:00:01", slot = 1, name = "Emulator")
            KioskActivationManager.setPairingCompleted(context, true)
            KioskService.requestHealthCheck(context)

            val deadline = System.currentTimeMillis() + 90_000
            var up = false
            while (!up && System.currentTimeMillis() < deadline) {
                up = try {
                    get("/ping").first == 200
                } catch (_: Exception) {
                    false
                }
                if (!up) Thread.sleep(1_000)
            }
            assertTrue("the phone's server did not come up on port 8080 within 90 s", up)
            // the payment endpoints answer 503 INITIALIZING until the engine has restored its state
            val ready = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < ready && signed("/state").first != 200) Thread.sleep(1_000)
        }

        private fun get(path: String): Pair<Int, String> {
            val connection = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                connection.disconnect()
            }
        }

        /** A request the way the box makes it: the parameters encrypted into `payload`, signed in `hmac`. */
        private fun signed(
            path: String,
            params: Map<String, String> = emptyMap(),
            secret: String = SECRET,
            tamper: Boolean = false,
        ): Pair<Int, String> {
            val all = linkedMapOf("ts" to System.currentTimeMillis().toString(), "nonce" to UUID.randomUUID().toString()) + params
            val payload = KioskSecurity.encrypt(all.entries.joinToString("&") { "${it.key}=${it.value}" }, secret)
            var hmac = KioskSecurity.calculateHmac(payload, secret)
            if (tamper) hmac = hmac.reversed()
            return get("$path?payload=${URLEncoder.encode(payload, "UTF-8")}&hmac=$hmac")
        }

        private fun state(): Int = signed("/state").second.trim().toIntOrNull() ?: -1

        private fun secondsLeft(): Int = signed("/get_time").second.trim().toIntOrNull() ?: -1

        private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(1_000)
            }
            throw AssertionError("timed out after ${timeoutMs / 1000} s waiting for: $what (state=${state()}, seconds left=${secondsLeft()})")
        }
    }

    @Before
    fun startLocked() {
        KioskService.triggerLockSession(context)
        waitFor("the phone to be locked", 30_000) { state() == LOCKED }
    }

    @After
    fun lockAgain() {
        KioskService.triggerLockSession(context)
    }

    /** A payment the way the box sends it. While the phone is still starting up it answers 503 INITIALIZING: like the box, try again. */
    private fun pay(tx: String, seconds: Int, amount: String): Pair<Int, String> {
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            val answer = signed("/add_time", mapOf("tx_id" to tx, "seconds" to "$seconds", "amount" to amount))
            if (answer.first != 503 || System.currentTimeMillis() > deadline) return answer
            Thread.sleep(1_000)
        }
    }

    @Test
    fun aRequestWithoutOrWithABadSignatureIsRefused() {
        assertEquals("no payload", 401, get("/state").first)
        assertEquals("a broken signature", 401, signed("/state", tamper = true).first)
        assertEquals("the wrong box secret", 401, signed("/state", secret = "another-secret-0123456789abcdef").first)
        assertEquals("the phone is still locked", LOCKED, state())
    }

    @Test
    fun complimentaryTimeUnlocksTheCountdownRunsAndThePhoneLocksItself() {
        val (code, body) = pay("emu-comp-${UUID.randomUUID()}", seconds = 8, amount = "0")
        assertEquals("the payment is accepted: $body", 200, code)
        waitFor("the phone to unlock", 10_000) { state() == UNLOCKED }

        val first = secondsLeft()
        assertTrue("time is running: $first", first in 1..8)
        Thread.sleep(2_500)
        val later = secondsLeft()
        assertTrue("the countdown goes down ($first -> $later)", later < first)

        waitFor("the phone to lock when the time is up", 20_000) { state() == LOCKED }
        assertEquals("no time is left", 0, secondsLeft())
    }

    @Test
    fun aCoinArmsThePhoneThenItUnlocksWithThePaidTime() {
        val (code, body) = pay("emu-coin-${UUID.randomUUID()}", seconds = 90, amount = "1")
        assertEquals("the coin is accepted: $body", 200, code)
        val now = state()
        assertTrue("a coin on a locked phone arms it (state $now)", now == ARMED_LOCKED || now == UNLOCKED)

        waitFor("the arming window to end and the phone to unlock", 40_000) { state() == UNLOCKED }
        val left = secondsLeft()
        assertTrue("the paid time is there ($left s of 90)", left in 60..90)
    }

    @Test
    fun theSamePaymentIsCreditedOnlyOnce() {
        val tx = "emu-dup-${UUID.randomUUID()}"
        assertEquals(200, pay(tx, seconds = 60, amount = "0").first)
        waitFor("the phone to unlock", 10_000) { state() == UNLOCKED }
        val afterFirst = secondsLeft()

        val (code, body) = pay(tx, seconds = 60, amount = "0")
        assertEquals("the box repeating a payment is acknowledged: $body", 200, code)
        assertTrue("and said to be processed already: $body", body.startsWith("ALREADY_PROCESSED"))
        val afterSecond = secondsLeft()
        assertTrue("no second credit ($afterFirst -> $afterSecond)", afterSecond <= afterFirst)
        Log.i(TAG, "duplicate payment ignored: $afterFirst -> $afterSecond")
    }

    @Test
    fun aDifferentAmountForTheSamePaymentIdIsAConflict() {
        val tx = "emu-conflict-${UUID.randomUUID()}"
        assertEquals(200, pay(tx, seconds = 30, amount = "0").first)
        assertEquals("the same id with other seconds", 409, pay(tx, seconds = 31, amount = "0").first)
    }
}
