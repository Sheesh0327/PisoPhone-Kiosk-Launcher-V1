package com.pisophone.kiosk.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.protocol.KioskProtocol
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.service.KioskEngine
import com.pisophone.kiosk.service.KioskStateManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KioskHttpServerUnitTest {

    private lateinit var context: Context
    private lateinit var stateManager: KioskStateManager
    private lateinit var engine: KioskEngine
    private lateinit var db: AppDatabase
    private lateinit var paymentHandler: KioskHttpPaymentHandler
    private val sharedSecret = "PISOPHONE_SHARED_SECRET_KEY_32B!"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.setSharedSecret(context, sharedSecret)
        stateManager = KioskStateManager(context)
        stateManager.deviceId.value = "PHONE_A"
        engine = KioskEngine(context, stateManager)
        db = AppDatabase.getDatabase(context)

        engine.paymentRepo.migrateAndInitialize(context)
        engine.setInitializedForTesting(true)

        val delegate = object : KioskServerDelegate {
            override fun isInitialized(): Boolean = engine.isEngineReady || engine.paymentRepo != null
            override fun getDeviceId(): String = "PHONE_A"
            override fun getSecretKey(): String = sharedSecret
            override fun onCreditPayment(
                txId: String,
                seconds: Int,
                amount: Double,
                operationKind: String,
                coinAmount: Int,
                pricePerCoin: Double,
                boxInstallationEpoch: Long,
                phonePairingEpoch: Long
            ): PaymentResult {
                return engine.creditPayment(
                    txId = txId,
                    seconds = seconds,
                    amount = amount,
                    operationKind = operationKind,
                    coinAmount = coinAmount,
                    pricePerCoin = pricePerCoin,
                    boxInstallationEpoch = boxInstallationEpoch,
                    phonePairingEpoch = phonePairingEpoch,
                    remainingMs = 0L
                )
            }

            override fun onDeductPayment(
                seconds: Int,
                txId: String?,
                operationKind: String,
                boxInstallationEpoch: Long,
                phonePairingEpoch: Long
            ): PaymentResult {
                return engine.deductPayment(
                    seconds = seconds,
                    txId = txId,
                    operationKind = operationKind,
                    boxInstallationEpoch = boxInstallationEpoch,
                    phonePairingEpoch = phonePairingEpoch
                )
            }

            override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
            override fun onTriggerAction(action: String, params: Map<String, String>): Boolean = true
        }

        paymentHandler = KioskHttpPaymentHandler(delegate)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }
        ShadowLooper.idleMainLooper()
    }

    private fun createMockSession(
        uri: String,
        method: NanoHTTPD.Method,
        params: Map<String, String>
    ): NanoHTTPD.IHTTPSession {
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() {}
            override fun getCookies() = null
            override fun getHeaders() = HashMap<String, String>()
            override fun getInputStream() = "".byteInputStream()
            override fun getMethod() = method
            override fun getParms() = HashMap(params)
            override fun getQueryParameterString() = ""
            override fun getUri() = uri
            override fun parseBody(files: MutableMap<String, String>?) {}
            override fun getRemoteIpAddress() = "192.168.1.10"
            override fun getRemoteHostName() = "192.168.1.10"
            override fun getParameters() = HashMap<String, MutableList<String>>()
        }
    }

    @Test
    fun testQuickAddWhilePhoneIsLockedUnlocksDirectlyWithoutCountdown() {
        stateManager.appState.value = 0 // Locked
        stateManager.sessionTimeRemaining.value = 0
        stateManager.paymentTimeout.value = 0

        val txId = "tx-quick-lock-01"
        val seconds = 300 // 5 minutes
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_A&ts=$now&op_kind=2"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)
        val hmac = KioskProtocol.calculateHttpReqSignature("GET", "/add_time", "PHONE_A", txId, now, encryptedPayload, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "ts" to now
        )

        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = paymentHandler.handlePaymentRequest(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)

        // Read response body
        val reader = response.data.bufferedReader()
        val body = reader.readText()

        assertTrue("Response body must start with OK", body.startsWith("OK"))
        assertTrue("Response must contain tx_id", body.contains("tx_id=$txId"))
        assertTrue("Response must contain seconds=300", body.contains("seconds=300"))
        assertTrue("Response must contain v_sig", body.contains("v_sig="))

        // Verify phone state: transitioned to active session (appState=2) with 300 seconds, and NO payment timeout
        assertEquals(2, stateManager.appState.value)
        assertEquals(300, stateManager.sessionTimeRemaining.value)
        assertEquals(0, stateManager.paymentTimeout.value)
    }

    @Test
    fun testQuickAddWhilePhoneIsPlayingExtendsActiveTime() {
        // Initialize an active base session of 600s
        engine.creditPayment("tx-base-00", 600, 5.0, "COIN")
        stateManager.appState.value = 2 // Active / Playing
        stateManager.paymentTimeout.value = 0

        val txId = "tx-quick-play-02"
        val seconds = 300 // 5 minutes
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_A&ts=$now&op_kind=2"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)
        val hmac = KioskProtocol.calculateHttpReqSignature("GET", "/add_time", "PHONE_A", txId, now, encryptedPayload, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "ts" to now
        )

        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = paymentHandler.handlePaymentRequest(session)

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(2, stateManager.appState.value)
        assertEquals(900, stateManager.sessionTimeRemaining.value) // 600 + 300
        assertEquals(0, stateManager.paymentTimeout.value)
    }

    @Test
    fun testRetrySameTransactionReturnsAlreadyProcessedAndCreditsOnce() {
        stateManager.appState.value = 0
        stateManager.sessionTimeRemaining.value = 0

        val txId = "tx-dedup-03"
        val seconds = 300
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_A&ts=$now&op_kind=2"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)
        val hmac = KioskProtocol.calculateHttpReqSignature("GET", "/add_time", "PHONE_A", txId, now, encryptedPayload, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "ts" to now
        )

        // 1. First execution
        val session1 = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response1 = paymentHandler.handlePaymentRequest(session1)
        assertEquals(NanoHTTPD.Response.Status.OK, response1.status)
        val body1 = response1.data.bufferedReader().readText()
        assertTrue(body1.startsWith("OK"))
        assertEquals(300, stateManager.sessionTimeRemaining.value)

        // 2. Retry with same transaction ID
        val session2 = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response2 = paymentHandler.handlePaymentRequest(session2)
        assertEquals(NanoHTTPD.Response.Status.OK, response2.status)
        val body2 = response2.data.bufferedReader().readText()
        assertTrue("Second delivery must return ALREADY_PROCESSED status", body2.startsWith("ALREADY_PROCESSED"))
        assertTrue("Second delivery must contain same tx_id", body2.contains("tx_id=$txId"))

        // Time must NOT be added twice!
        assertEquals(300, stateManager.sessionTimeRemaining.value)
    }

    @Test
    fun testUpdatePhoneBWhilePhoneAIsTargetedRejectsMismatch() {
        val txId = "tx-wrong-dev-04"
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_B&ts=$now"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)
        val hmac = KioskProtocol.calculateHttpReqSignature("GET", "/add_time", "PHONE_B", txId, now, encryptedPayload, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to "PHONE_B",
            "tx_id" to txId,
            "ts" to now
        )

        // Request intended for PHONE_B received by PHONE_A
        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = paymentHandler.handlePaymentRequest(session)

        // Must reject with Bad Request and produce NO success ACK
        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        val body = response.data.bufferedReader().readText()
        assertFalse("Must not produce success ACK for wrong device", body.startsWith("OK") || body.startsWith("ALREADY_PROCESSED"))
    }

    @Test
    fun testTamperedSignatureRejection() {
        val txId = "tx-tamper-05"
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_A&ts=$now"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to "bad_tampered_hmac_signature",
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "ts" to now
        )

        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = paymentHandler.handlePaymentRequest(session)

        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
        val body = response.data.bufferedReader().readText()
        assertFalse("Tampered signature must not produce success ACK", body.startsWith("OK"))
    }

    @Test
    fun testUninitializedRejection() {
        val uninitDelegate = object : KioskServerDelegate {
            override fun isInitialized(): Boolean = false
            override fun getDeviceId(): String = "PHONE_A"
            override fun getSecretKey(): String = sharedSecret
            override fun onCreditPayment(txId: String, seconds: Int, amount: Double, operationKind: String, coinAmount: Int, pricePerCoin: Double, boxInstallationEpoch: Long, phonePairingEpoch: Long) = PaymentResult.FAILED
            override fun onDeductPayment(seconds: Int, txId: String?, operationKind: String, boxInstallationEpoch: Long, phonePairingEpoch: Long) = PaymentResult.FAILED
            override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
            override fun onTriggerAction(action: String, params: Map<String, String>) = false
        }
        val uninitHandler = KioskHttpPaymentHandler(uninitDelegate)

        val txId = "tx-uninit-06"
        val now = System.currentTimeMillis().toString()
        val plainParams = "minutes=5&seconds=300&amount=0&tx_id=$txId&device_id=PHONE_A&ts=$now&op_kind=2"
        val encryptedPayload = KioskSecurity.encrypt(plainParams, sharedSecret)
        val hmac = KioskProtocol.calculateHttpReqSignature("GET", "/add_time", "PHONE_A", txId, now, encryptedPayload, sharedSecret)

        val parms = mapOf(
            "payload" to encryptedPayload,
            "hmac" to hmac,
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "ts" to now
        )
        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = uninitHandler.handlePaymentRequest(session)

        assertEquals(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, response.status)
        val body = response.data.bufferedReader().readText()
        assertEquals("INITIALIZATION_IN_PROGRESS", body)
    }

    @Test
    fun testUnsignedRequestRejection() {
        stateManager.appState.value = 0
        stateManager.sessionTimeRemaining.value = 0

        val txId = "tx-unsigned-07"
        val parms = mapOf(
            "device_id" to "PHONE_A",
            "tx_id" to txId,
            "seconds" to "300"
        )
        val session = createMockSession("/add_time", NanoHTTPD.Method.GET, parms)
        val response = paymentHandler.handlePaymentRequest(session)

        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, response.status)
        val body = response.data.bufferedReader().readText()
        assertEquals("UNAUTHORIZED", body)

        // Balance must NOT have changed!
        assertEquals(0, stateManager.sessionTimeRemaining.value)
    }
}
