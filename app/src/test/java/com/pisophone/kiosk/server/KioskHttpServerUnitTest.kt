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
        override fun isReady(): Boolean = true
        override fun getDeviceId(): String = "TEST_DEVICE"
        override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult {
            creditPaymentCallCount++
            lastCreditedTxId = txId
            lastCreditedSeconds = seconds
            lastCreditedAmount = amount
            return simulatedPaymentResult
        }
        override fun onDeductTime(seconds: Int, txId: String?): PaymentResult = simulatedPaymentResult
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

    private fun parseQueryString(query: String): Map<String, String> {
        return query.split("&").associate {
            val parts = it.split("=", limit = 2)
            val key = parts[0]
            val value = if (parts.size > 1) parts[1] else ""
            key to value
        }
    }

    private fun createEncryptedParams(uri: String, query: String): Map<String, String> {
        val decryptedParams = parseQueryString(query)
        val txId = decryptedParams["tx_id"] ?: decryptedParams["nonce"] ?: ""
        val deviceId = decryptedParams["device_id"] ?: ""
        val ts = decryptedParams["ts"] ?: ""

        val encryptedPayload = KioskSecurity.encrypt(query, testSecret)
        val hmac = KioskSecurity.calculateHttpReqSignature(
            method = "POST",
            endpoint = uri,
            recipient = deviceId,
            txId = txId,
            ts = ts,
            payload = encryptedPayload,
            secret = testSecret
        )
        return mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to deviceId,
            "tx_id" to txId,
            "ts" to ts
        )
    }

    private fun readResponseBody(response: NanoHTTPD.Response): String {
        return response.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
    }

    private fun makePaymentQuery(
        txId: String,
        seconds: Int = 300,
        amount: Double = 5.0,
        ts: Long = System.currentTimeMillis(),
        deviceId: String? = null
    ): String {
        val targetDev = deviceId ?: "TEST_DEVICE"
        val devParam = if (deviceId != null) "&device_id=$deviceId" else "&device_id=TEST_DEVICE"
        return "tx_id=$txId$devParam&seconds=$seconds&amount=$amount&ts=$ts"
    }

    @Test
    fun testAppliedReturns200Ok() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val query = makePaymentQuery("tx-100", 300, 5.0)
        val params = createEncryptedParams("/coin", query)
        val session = createSession("/coin", params)

        val response = server.serve(session)

        assertEquals("Status must be 200", 200, response.status.requestStatus)
        assertTrue("Body must start with OK", readResponseBody(response).startsWith("OK"))
        assertEquals("creditPayment called once", 1, creditPaymentCallCount)
        assertEquals("tx-100", lastCreditedTxId)
        assertEquals(300, lastCreditedSeconds)
        assertEquals(5.0, lastCreditedAmount ?: 0.0, 0.001)
    }

    @Test
    fun testAlreadyAppliedReturns200AlreadyProcessed() {
        simulatedPaymentResult = PaymentResult.ALREADY_APPLIED
        val query = makePaymentQuery("tx-200", 300, 5.0)
        val params = createEncryptedParams("/coin", query)
        val session = createSession("/coin", params)

        val response = server.serve(session)

        assertEquals("Status must be 200", 200, response.status.requestStatus)
        assertTrue("Body must start with ALREADY_PROCESSED", readResponseBody(response).startsWith("ALREADY_PROCESSED"))
        assertEquals("creditPayment called once", 1, creditPaymentCallCount)
    }

    @Test
    fun testValidationLayerAndTimestampChecks() {
        val now = System.currentTimeMillis()

        // Missing tx_id
        val qMissingTx = "seconds=300&amount=5.0&ts=$now"
        val resMissingTx = server.serve(createSession("/coin", createEncryptedParams("/coin", qMissingTx)))
        assertEquals("Missing tx_id returns 400", 400, resMissingTx.status.requestStatus)

        // Negative amount
        val qNegAmount = makePaymentQuery("tx-301", 300, -5.0, now)
        val resNegAmount = server.serve(createSession("/coin", createEncryptedParams("/coin", qNegAmount)))
        assertEquals("Negative amount returns 400", 400, resNegAmount.status.requestStatus)

        // Invalid seconds parameter
        val qInvalidSec = "tx_id=tx-302&device_id=TEST_DEVICE&seconds=invalid&amount=5.0&ts=$now"
        val resInvalidSec = server.serve(createSession("/coin", createEncryptedParams("/coin", qInvalidSec)))
        assertEquals("Invalid seconds returns 400", 400, resInvalidSec.status.requestStatus)

        // Missing all time parameters
        val qNoTime = "tx_id=tx-303&device_id=TEST_DEVICE&amount=5.0&ts=$now"
        val resNoTime = server.serve(createSession("/coin", createEncryptedParams("/coin", qNoTime)))
        assertEquals("Missing time returns 400", 400, resNoTime.status.requestStatus)

        // Stale timestamp (skew > 60s)
        val staleTs = now - 120_000L
        val qStale = "tx_id=tx-304&device_id=TEST_DEVICE&seconds=300&amount=5.0&ts=$staleTs"
        val resStale = server.serve(createSession("/coin", createEncryptedParams("/coin", qStale)))
        assertEquals("Stale timestamp returns 400", 400, resStale.status.requestStatus)
    }

    @Test
    fun testNotEligibleReturns403() {
        simulatedPaymentResult = PaymentResult.NOT_ELIGIBLE
        val query = makePaymentQuery("tx-400", 300, 5.0)
        val session = createSession("/coin", createEncryptedParams("/coin", query))

        val response = server.serve(session)

        assertEquals("Status must be 403", 403, response.status.requestStatus)
        assertEquals("Body must be NOT_ELIGIBLE", "NOT_ELIGIBLE", readResponseBody(response))
    }

    @Test
    fun testConflictReturns409() {
        simulatedPaymentResult = PaymentResult.CONFLICT
        val query = makePaymentQuery("tx-500", 300, 5.0)
        val session = createSession("/coin", createEncryptedParams("/coin", query))

        val response = server.serve(session)

        assertEquals("Status must be 409", 409, response.status.requestStatus)
        assertEquals("Body must be CONFLICT", "CONFLICT", readResponseBody(response))
    }

    @Test
    fun testFailedReturns503() {
        simulatedPaymentResult = PaymentResult.FAILED
        val query = makePaymentQuery("tx-600", 300, 5.0)
        val session = createSession("/coin", createEncryptedParams("/coin", query))

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
            override fun getDeviceId(): String = "TEST_DEVICE"
            override fun creditPayment(txId: String, seconds: Int, amount: Double): PaymentResult = PaymentResult.APPLIED
            override fun onDeductTime(seconds: Int, txId: String?): PaymentResult = PaymentResult.APPLIED
            override fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int?) {}
            override fun onTriggerAction(action: String, slotNum: Int?, extra: Map<String, String>?) {}
            override fun getCrashLog(): String? = null
        }
        val unreadyServer = KioskHttpServer(context = context, port = 8080, delegate = unreadyDelegate)

        val query = makePaymentQuery("tx-init-test", 300, 5.0)
        val params = createEncryptedParams("/coin", query)

        val response = unreadyServer.serve(createSession("/coin", params))
        assertEquals("Status must be 503 while initializing", 503, response.status.requestStatus)
        assertEquals("Body must be INITIALIZING", "INITIALIZING", readResponseBody(response))

        // Once ready, it succeeds
        isServerReady = true
        val readyResponse = unreadyServer.serve(createSession("/coin", params))
        assertEquals("Status must be 200 once initialized", 200, readyResponse.status.requestStatus)
        assertTrue("Body must start with OK", readResponseBody(readyResponse).startsWith("OK"))
    }

    @Test
    fun testNoInMemoryCacheBypassOnDuplicateAttempts() {
        simulatedPaymentResult = PaymentResult.FAILED
        val query = makePaymentQuery("tx-700", 300, 5.0)
        val params = createEncryptedParams("/coin", query)

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
        val query = makePaymentQuery("tx-mismatch", 300, 5.0, now, deviceId = "OTHER_DEVICE_ID")
        val params = createEncryptedParams("/coin", query)

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 403 Forbidden on recipient mismatch", 403, response.status.requestStatus)
        assertEquals("Body must be MISMATCHED_RECIPIENT", "MISMATCHED_RECIPIENT", readResponseBody(response))
        assertEquals("Delegate must not be called", 0, creditPaymentCallCount)
    }

    @Test
    fun testInvalidHttpReqSignatureRejectedWith401() {
        val now = System.currentTimeMillis()
        val query = "tx_id=tx-badsig&device_id=TEST_DEVICE&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams("/coin", query).toMutableMap().apply {
            put("hmac", "bad_signature_value")
        }

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 401 Unauthorized on invalid signature", 401, response.status.requestStatus)
        assertEquals("Body must be HMAC verification failed", "HMAC verification failed", readResponseBody(response))
        assertEquals("Delegate must not be called", 0, creditPaymentCallCount)
    }

    @Test
    fun testValidHttpReqSignatureAndSignedAcknowledgment() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val now = System.currentTimeMillis()
        val myDeviceId = "TEST_DEVICE"
        val query = "tx_id=tx-valid&device_id=$myDeviceId&seconds=300&amount=5.0&ts=$now"
        val params = createEncryptedParams("/coin", query)

        val response = server.serve(createSession("/coin", params))
        assertEquals("Status must be 200 OK", 200, response.status.requestStatus)
        val body = readResponseBody(response)
        assertTrue("Body must start with OK", body.startsWith("OK:"))
        assertTrue("Body must contain tx_id=tx-valid", body.contains("tx_id=tx-valid"))
        assertTrue("Body must contain device_id=$myDeviceId", body.contains("device_id=$myDeviceId"))
        assertTrue("Body must contain v_sig=", body.contains("v_sig="))
        assertEquals("Delegate called once", 1, creditPaymentCallCount)
    }

    @Test
    fun testQuickAdjustAddTimePositiveMinutes() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val now = System.currentTimeMillis()
        val myDeviceId = "TEST_DEVICE"
        val txId = "adj-TEST_DEVICE-12345"
        val query = "device_id=$myDeviceId&tx_id=$txId&seconds=60&amount=0&ts=$now"
        val params = createEncryptedParams("/add_time", query)

        val response = server.serve(createSession("/add_time", params))
        assertEquals("Status must be 200 OK", 200, response.status.requestStatus)
        val body = readResponseBody(response)
        assertTrue("Body must start with OK", body.startsWith("OK:"))
        assertTrue("Body must contain tx_id=$txId", body.contains("tx_id=$txId"))
        assertTrue("Body must contain device_id=$myDeviceId", body.contains("device_id=$myDeviceId"))
        assertTrue("Body must contain seconds=60", body.contains("seconds=60"))
        assertTrue("Body must contain amount=0", body.contains("amount=0"))
        assertTrue("Body must contain v_sig=", body.contains("v_sig="))
        assertEquals("Delegate called once", 1, creditPaymentCallCount)
        assertEquals(txId, lastCreditedTxId)
        assertEquals(60, lastCreditedSeconds)
        assertEquals(0.0, lastCreditedAmount ?: 1.0, 0.001)
    }

    @Test
    fun testQuickAdjustDeductTimeNegativeMinutes() {
        simulatedPaymentResult = PaymentResult.APPLIED
        val now = System.currentTimeMillis()
        val myDeviceId = "TEST_DEVICE"
        val txId = "adj-TEST_DEVICE-deduct-123"
        val query = "device_id=$myDeviceId&tx_id=$txId&seconds=-60&amount=0&ts=$now"
        val params = createEncryptedParams("/add_time", query)

        val response = server.serve(createSession("/add_time", params))
        assertEquals("Status must be 200 OK", 200, response.status.requestStatus)
        val body = readResponseBody(response)
        assertTrue("Body must start with OK", body.startsWith("OK:"))
        assertTrue("Body must contain tx_id=$txId", body.contains("tx_id=$txId"))
        assertTrue("Body must contain seconds=-60", body.contains("seconds=-60"))
        assertTrue("Body must contain amount=0", body.contains("amount=0"))
        assertTrue("Body must contain v_sig=", body.contains("v_sig="))
    }

    @Test
    fun testQuickAdjustDeductTimeAlreadyProcessed() {
        simulatedPaymentResult = PaymentResult.ALREADY_APPLIED
        val now = System.currentTimeMillis()
        val myDeviceId = "TEST_DEVICE"
        val txId = "adj-TEST_DEVICE-repeat-1"
        val query = "device_id=$myDeviceId&tx_id=$txId&seconds=-60&amount=0&ts=$now"
        val params = createEncryptedParams("/add_time", query)

        val response = server.serve(createSession("/add_time", params))
        assertEquals("Status must be 200 OK", 200, response.status.requestStatus)
        val body = readResponseBody(response)
        assertTrue("Body must start with ALREADY_PROCESSED", body.startsWith("ALREADY_PROCESSED:"))
        assertTrue("Body must contain tx_id=$txId", body.contains("tx_id=$txId"))
        assertTrue("Body must contain v_sig=", body.contains("v_sig="))
    }

    @Test
    fun testQuickAdjustDeductTimeConflictAndFailureResponses() {
        val now = System.currentTimeMillis()
        val myDeviceId = "TEST_DEVICE"

        // Conflict
        simulatedPaymentResult = PaymentResult.CONFLICT
        val queryConflict = "device_id=$myDeviceId&tx_id=adj-conflict&seconds=-60&amount=0&ts=$now"
        val respConflict = server.serve(createSession("/add_time", createEncryptedParams("/add_time", queryConflict)))
        assertEquals("Conflict returns 409", 409, respConflict.status.requestStatus)

        // Not eligible
        simulatedPaymentResult = PaymentResult.NOT_ELIGIBLE
        val queryIneligible = "device_id=$myDeviceId&tx_id=adj-ineligible&seconds=-60&amount=0&ts=$now"
        val respIneligible = server.serve(createSession("/add_time", createEncryptedParams("/add_time", queryIneligible)))
        assertEquals("Ineligible returns 403", 403, respIneligible.status.requestStatus)

        // Database failure
        simulatedPaymentResult = PaymentResult.FAILED
        val queryFail = "device_id=$myDeviceId&tx_id=adj-fail&seconds=-60&amount=0&ts=$now"
        val respFail = server.serve(createSession("/add_time", createEncryptedParams("/add_time", queryFail)))
        assertEquals("Failure returns 503", 503, respFail.status.requestStatus)
    }
}
