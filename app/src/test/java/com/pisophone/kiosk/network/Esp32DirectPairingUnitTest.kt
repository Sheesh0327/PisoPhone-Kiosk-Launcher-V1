package com.pisophone.kiosk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.security.KioskSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class Esp32DirectPairingUnitTest {

    private lateinit var context: Context
    private val testMac = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
    }

    @Test
    fun testDefaultStaticEsp32IpConstant() {
        assertEquals("192.168.1.10", Esp32ConnectionManager.DEFAULT_STATIC_ESP32_IP)
    }

    @Test
    fun testGetEsp32HostAndPort_defaultAndCustom() {
        val (host1, port1) = Esp32ConnectionManager.getEsp32HostAndPort("192.168.1.10")
        assertEquals("192.168.1.10", host1)
        assertEquals(80, port1)

        val (host2, port2) = Esp32ConnectionManager.getEsp32HostAndPort("192.168.1.10:8080")
        assertEquals("192.168.1.10", host2)
        assertEquals(8080, port2)

        val (host3, port3) = Esp32ConnectionManager.getEsp32HostAndPort("")
        assertEquals("192.168.1.10", host3)
        assertEquals(80, port3)
    }

    @Test
    fun testIsEsp32MacMatching_matchingMac_returnsTrue() {
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AABBCCDDEEFF", "aa:bb:cc:dd:ee:ff"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun testIsEsp32MacMatching_mismatchedMac_returnsFalse() {
        assertFalse(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"))
        assertFalse(Esp32ConnectionManager.isEsp32MacMatching("AA:BB:CC:DD:EE:FF", ""))
    }

    @Test
    fun testIsEsp32MacMatching_blankExpected_allowsAnyMac() {
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching("", "AA:BB:CC:DD:EE:FF"))
        assertTrue(Esp32ConnectionManager.isEsp32MacMatching(null, "AA:BB:CC:DD:EE:FF"))
    }

    private val fakeDelegate = object : Esp32ConnectionDelegate {
        override fun getDeviceId(): String = "TEST_DEVICE"
        override fun getSecretKey(): String = "secret"
        override fun getAppState(): Int = 0
        override fun getSessionTimeRemaining(): Int = 0
        override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = Pair(100, false)
        override fun onEsp32Paired(ip: String) {}
        override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {}
        override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
        override fun onCoinMessageReceived(
            seconds: Int,
            amount: Double,
            txId: String?,
            operationKind: String,
            coinAmount: Int,
            pricePerCoin: Double,
            boxInstallationEpoch: Long,
            phonePairingEpoch: Long
        ): com.pisophone.kiosk.repository.PaymentResult = com.pisophone.kiosk.repository.PaymentResult.APPLIED
        override fun onSlotBusy() {}
        override fun onArmSuccess() {}
        override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {}
        override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {}
        override fun onSlotRestored(slotNum: Int) {}
        override fun onArenaModeSynced(active: Boolean, role: Int, stake: Int) {}
    }

    private fun createMockClient(statusCode: Int, responseBodyString: String, onRequest: (String) -> Unit = {}): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestUrl = chain.request().url.toString()
                onRequest(requestUrl)
                if (statusCode < 0) {
                    throw IOException("Simulated network timeout/unreachable")
                }
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = responseBodyString.toResponseBody(mediaType)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message(if (statusCode == 200) "OK" else if (statusCode == 409) "Conflict" else "Unauthorized")
                    .body(body)
                    .build()
            }
            .build()
    }

    @Test
    fun testUnpairSuccessClearsLocalState() = runBlocking {
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
        KioskSecurity.setAssignedBoxSlot(context, 1)

        val capturedUrls = mutableListOf<String>()
        val mockClient = createMockClient(200, """{"success":true,"slot":1}""") { url ->
            capturedUrls.add(url)
        }

        val pairing = Esp32DirectPairing(context, CoroutineScope(Dispatchers.IO), mockClient, fakeDelegate)

        var localResetCalled = false
        var resultSuccess: Boolean? = null
        var resultError: String? = null

        val job = pairing.unpair(
            currentEsp32Ip = "192.168.1.10",
            onLocalStateReset = { localResetCalled = true },
            onResult = { s, err ->
                resultSuccess = s
                resultError = err
            }
        )
        job.join()

        assertTrue(localResetCalled)
        assertEquals(true, resultSuccess)
        assertEquals(null, resultError)
        assertEquals("", KioskSecurity.getConfiguredEsp32Mac(context))
        // Verify only /api/slots/unpair was requested (no /api/slots/action and no force=true)
        assertEquals(1, capturedUrls.size)
        assertTrue(capturedUrls[0].endsWith("/api/slots/unpair?slot=1"))
    }

    @Test
    fun testUnpair401UnauthorizedRetainsLocalStateAndProvidesActionableInstruction() = runBlocking {
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
        KioskSecurity.setAssignedBoxSlot(context, 2)

        val mockClient = createMockClient(401, """{"error":"Unauthorized"}""")

        val pairing = Esp32DirectPairing(context, CoroutineScope(Dispatchers.IO), mockClient, fakeDelegate)

        var localResetCalled = false
        var resultSuccess: Boolean? = null
        var resultError: String? = null

        val job = pairing.unpair(
            currentEsp32Ip = "192.168.1.10",
            onLocalStateReset = { localResetCalled = true },
            onResult = { s, err ->
                resultSuccess = s
                resultError = err
            }
        )
        job.join()

        assertFalse(localResetCalled)
        assertEquals(false, resultSuccess)
        assertTrue(resultError != null && resultError!!.contains("admin authentication"))
        assertTrue(resultError!!.contains("http://192.168.1.10:80"))
        // Local pairing state must be retained
        assertEquals(testMac, KioskSecurity.getConfiguredEsp32Mac(context))
    }

    @Test
    fun testUnpair409ConflictRetainsLocalState() = runBlocking {
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
        KioskSecurity.setAssignedBoxSlot(context, 1)

        val mockClient = createMockClient(409, """{"success":false,"error":"BUSY: Device owns active session or unresolved payments"}""")

        val pairing = Esp32DirectPairing(context, CoroutineScope(Dispatchers.IO), mockClient, fakeDelegate)

        var localResetCalled = false
        var resultSuccess: Boolean? = null
        var resultError: String? = null

        val job = pairing.unpair(
            currentEsp32Ip = "192.168.1.10",
            onLocalStateReset = { localResetCalled = true },
            onResult = { s, err ->
                resultSuccess = s
                resultError = err
            }
        )
        job.join()

        assertFalse(localResetCalled)
        assertEquals(false, resultSuccess)
        assertTrue(resultError != null && resultError!!.contains("unresolved payments"))
        // Local pairing state must be retained
        assertEquals(testMac, KioskSecurity.getConfiguredEsp32Mac(context))
    }

    @Test
    fun testUnpairNetworkFailureRetainsLocalState() = runBlocking {
        KioskSecurity.setConfiguredEsp32Mac(context, testMac)
        KioskSecurity.setAssignedBoxSlot(context, 1)

        val mockClient = createMockClient(-1, "")

        val pairing = Esp32DirectPairing(context, CoroutineScope(Dispatchers.IO), mockClient, fakeDelegate)

        var localResetCalled = false
        var resultSuccess: Boolean? = null
        var resultError: String? = null

        val job = pairing.unpair(
            currentEsp32Ip = "192.168.1.10",
            onLocalStateReset = { localResetCalled = true },
            onResult = { s, err ->
                resultSuccess = s
                resultError = err
            }
        )
        job.join()

        assertFalse(localResetCalled)
        assertEquals(false, resultSuccess)
        assertTrue(resultError != null && resultError!!.contains("Failed to connect"))
        // Local pairing state must be retained
        assertEquals(testMac, KioskSecurity.getConfiguredEsp32Mac(context))
    }
}
