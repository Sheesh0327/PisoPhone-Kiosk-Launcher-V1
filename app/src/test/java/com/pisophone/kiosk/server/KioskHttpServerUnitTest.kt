package com.pisophone.kiosk.server

import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class KioskHttpServerUnitTest {

    private val testSecret = "0123456789abcdef0123456789abcdef"
    private var simulatedPaymentResult: PaymentResult = PaymentResult.APPLIED
    private var lastCreditedTxId: String? = null
    private var lastCreditedSeconds: Int? = null
    private var lastCreditedAmount: Double? = null
    private var creditPaymentCallCount: Int = 0

    private val fakeDelegate = object : KioskServerDelegate {
        override fun getSecretKey(): String = testSecret
        override fun onHeartbeat(clientIp: String?) {}
        override fun getStatusJson(): JSONObject = JSONObject()
        override fun getAuditEventsJson(): String = "[]"
        override fun getSessionTimeRemaining(): Int = 300
        override fun getAppState(): Int = 2
        override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
            creditPaymentCallCount++
            lastCreditedTxId = txId
            lastCreditedSeconds = seconds
            lastCreditedAmount = amount
            return simulatedPaymentResult
        }
        override fun onDeductTime(seconds: Int, txId: String?) {}
        override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int?) {}
        override fun onTriggerAction(action: String, slotNum: Int?, extra: Map<String, String>?) {}
        override fun getCrashLog(): String? = null
    }

    private lateinit var server: KioskHttpServer
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        server = KioskHttpServer(context = context, port = 8080, delegate = fakeDelegate)
        creditPaymentCallCount = 0
        lastCreditedTxId = null
        lastCreditedSeconds = null
        lastCreditedAmount = null
    }

    private fun createSession(
        uri: String,
        params: Map<String, String>,
        headers: Map<String, String> = mapOf("remote-addr" to "192.168.4.2")
    ): NanoHTTPD.IHTTPSession {
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = headers
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST
            override fun getParms(): Map<String, String> = params
            override fun getParameters(): Map<String, List<String>> = params.mapValues { listOf(it.value) }
            override fun getQueryParameterString(): String = ""
            override fun getUri(): String = uri
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress(): String = headers["remote-addr"] ?: "127.0.0.1"
            override fun getRemoteHostName(): String = "test-host"
        }
    }

    private fun createEncryptedParams(query: String): Map<String, String> {
        val encryptedPayload = KioskSecurity.encrypt(query, testSecret)
        val hmac = KioskSecurity.calculateHmac(encryptedPayload, testSecret)
        return mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac
        )
    }

    private fun readResponseBody(response: NanoHTTPD.Response): String {
        return response.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
    }

    @Test
    fun testAppliedReturns200Ok() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-100&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams(query)
        val session = createSession("/coin", params)

        val response = server.serve(session)

        assertEquals("Status must be 200", 200, response.status.requestStatus)
        assertEquals("Body must be OK", "OK", readResponseBody(response))
        assertEquals("creditPayment called once", 1, creditPaymentCallCount)
        assertEquals("tx-100", lastCreditedTxId)
        assertEquals(300, lastCreditedSeconds)
        assertEquals(5.0, lastCreditedAmount ?: 0.0, 0.001)
    }

    @Test
    fun testAlreadyAppliedReturns200AlreadyProcessed() {
        simulatedPaymentResult = PaymentResult.ALREADY_APPLIED
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-200&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams(query)
        val session = createSession("/coin", params)

        val response = server.serve(session)

        assertEquals("Status must be 200", 200, response.status.requestStatus)
        assertEquals("Body must be ALREADY_PROCESSED", "ALREADY_PROCESSED", readResponseBody(response))
        assertEquals("creditPayment called", 1, creditPaymentCallCount)
    }

    @Test
    fun testInvalidPaymentReturns400() {
        val now = System.currentTimeMillis()

        // Missing tx_id
        val qMissingTx = "seconds=300&amount=5.0&ts=$now"
        val resMissingTx = server.serve(createSession("/coin", createEncryptedParams(qMissingTx)))
        assertEquals("Missing tx_id returns 400", 400, resMissingTx.status.requestStatus)

        // Negative amount
        val qNegAmount = "tx_id=tx-301&seconds=300&amount=-5.0&ts=$now"
        val resNegAmount = server.serve(createSession("/coin", createEncryptedParams(qNegAmount)))
        assertEquals("Negative amount returns 400", 400, resNegAmount.status.requestStatus)

        // Invalid seconds parameter
        val qInvalidSec = "tx_id=tx-302&seconds=invalid&amount=5.0&ts=$now"
        val resInvalidSec = server.serve(createSession("/coin", createEncryptedParams(qInvalidSec)))
        assertEquals("Invalid seconds returns 400", 400, resInvalidSec.status.requestStatus)

        // Missing all time parameters
        val qNoTime = "tx_id=tx-303&amount=5.0&ts=$now"
        val resNoTime = server.serve(createSession("/coin", createEncryptedParams(qNoTime)))
        assertEquals("Missing time returns 400", 400, resNoTime.status.requestStatus)

        // Stale timestamp (skew > 60s)
        val qStale = "tx_id=tx-304&seconds=300&amount=5.0&ts=${now - 120_000L}"
        val resStale = server.serve(createSession("/coin", createEncryptedParams(qStale)))
        assertEquals("Stale timestamp returns 400", 400, resStale.status.requestStatus)
    }

    @Test
    fun testNotEligibleReturns403() {
        simulatedPaymentResult = PaymentResult.NOT_ELIGIBLE
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-400&seconds=300&amount=5.0&ts=$now"
        val session = createSession("/coin", createEncryptedParams(query))

        val response = server.serve(session)

        assertEquals("Status must be 403", 403, response.status.requestStatus)
        assertEquals("Body must be NOT_ELIGIBLE", "NOT_ELIGIBLE", readResponseBody(response))
    }

    @Test
    fun testConflictReturns409() {
        simulatedPaymentResult = PaymentResult.CONFLICT
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-500&seconds=300&amount=5.0&ts=$now"
        val session = createSession("/coin", createEncryptedParams(query))

        val response = server.serve(session)

        assertEquals("Status must be 409", 409, response.status.requestStatus)
        assertEquals("Body must be CONFLICT", "CONFLICT", readResponseBody(response))
    }

    @Test
    fun testFailedReturns503() {
        simulatedPaymentResult = PaymentResult.FAILED
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-600&seconds=300&amount=5.0&ts=$now"
        val session = createSession("/coin", createEncryptedParams(query))

        val response = server.serve(session)

        assertEquals("Status must be 503", 503, response.status.requestStatus)
        assertEquals("Body must be SERVICE_UNAVAILABLE", "SERVICE_UNAVAILABLE", readResponseBody(response))
    }

    @Test
    fun testPaymentEndpointsReturn503WhenServerNotReady() {
        var isServerReady = false
        val unreadyDelegate = object : KioskServerDelegate {
            override fun isReady(): Boolean = isServerReady
            override fun getSecretKey(): String = testSecret
            override fun onHeartbeat(clientIp: String?) {}
            override fun getStatusJson(): JSONObject = JSONObject()
            override fun getAuditEventsJson(): String = "[]"
            override fun getSessionTimeRemaining(): Int = 300
            override fun getAppState(): Int = 2
            override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult = PaymentResult.APPLIED
            override fun onDeductTime(seconds: Int, txId: String?) {}
            override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int?) {}
            override fun onTriggerAction(action: String, slotNum: Int?, extra: Map<String, String>?) {}
            override fun getCrashLog(): String? = null
        }
        val unreadyServer = KioskHttpServer(context = context, port = 8080, delegate = unreadyDelegate)

        val now = System.currentTimeMillis()
        val query = "tx_id=tx-init-test&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams(query)

        val response = unreadyServer.serve(createSession("/coin", params))
        assertEquals("Status must be 503 while initializing", 503, response.status.requestStatus)
        assertEquals("Body must be INITIALIZING", "INITIALIZING", readResponseBody(response))

        // Once ready, it succeeds
        isServerReady = true
        val readyResponse = unreadyServer.serve(createSession("/coin", params))
        assertEquals("Status must be 200 once initialized", 200, readyResponse.status.requestStatus)
        assertEquals("Body must be OK", "OK", readResponseBody(readyResponse))
    }

    @Test
    fun testNoInMemoryCacheBypassOnDuplicateAttempts() {
        // When delegate reports FAILED, retrying with the same tx_id must STILL call delegate
        // and must NEVER return 200 from an in-memory cache!
        simulatedPaymentResult = PaymentResult.FAILED
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-700&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams(query)

        val res1 = server.serve(createSession("/coin", params))
        assertEquals("First attempt fails with 503", 503, res1.status.requestStatus)
        assertEquals("Delegate called once", 1, creditPaymentCallCount)

        val res2 = server.serve(createSession("/coin", params))
        assertEquals("Second attempt still calls delegate and returns 503", 503, res2.status.requestStatus)
        assertEquals("Delegate called twice (no in-memory cache hijack)", 2, creditPaymentCallCount)
    }

    @Test
    fun testMismatchedRecipientRejectedWith403() {
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-mismatch&device_id=OTHER_DEVICE_ID&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams(query)

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 403 Forbidden on recipient mismatch", 403, response.status.requestStatus)
        assertEquals("Body must be MISMATCHED_RECIPIENT", "MISMATCHED_RECIPIENT", readResponseBody(response))
        assertEquals("Delegate must not be called", 0, creditPaymentCallCount)
    }

    @Test
    fun testInvalidVersionedSignatureRejectedWith401() {
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-badsig&seconds=300&amount=5.0&ts=$now&v_sig=bad_signature_value"
        val params = createEncryptedParams(query)

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 401 Unauthorized on invalid signature", 401, response.status.requestStatus)
        assertEquals("Body must be INVALID_SIGNATURE", "INVALID_SIGNATURE", readResponseBody(response))
        assertEquals("Delegate must not be called", 0, creditPaymentCallCount)
    }

    @Test
    fun testValidVersionedSignatureAndSignedAcknowledgment() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val now = System.currentTimeMillis()
        val myDeviceId = KioskSecurity.getHardwareId(context)
        val validSig = KioskSecurity.calculateHmac("v1:$myDeviceId:tx-valid:5:$now", testSecret)
        val query = "tx_id=tx-valid&device_id=$myDeviceId&seconds=300&amount=5.0&ts=$now&v_sig=$validSig"
        val params = createEncryptedParams(query)

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 200 OK", 200, response.status.requestStatus)
        val body = readResponseBody(response)
        assertTrue("Body must start with OK", body.startsWith("OK:"))
        assertTrue("Body must contain tx_id=tx-valid", body.contains("tx_id=tx-valid"))
        assertTrue("Body must contain device_id=$myDeviceId", body.contains("device_id=$myDeviceId"))
        assertTrue("Body must contain v_sig=", body.contains("v_sig="))
        assertEquals("Delegate called once", 1, creditPaymentCallCount)
    }
}
