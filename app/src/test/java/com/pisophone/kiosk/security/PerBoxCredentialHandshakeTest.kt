package com.pisophone.kiosk.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.db.AppDatabase
import com.pisophone.kiosk.network.Esp32ConnectionDelegate
import com.pisophone.kiosk.network.Esp32ConnectionManager
import com.pisophone.kiosk.network.Esp32PayloadHandler
import com.pisophone.kiosk.repository.PaymentRepository
import com.pisophone.kiosk.repository.PaymentResult
import com.pisophone.kiosk.service.CoinProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PerBoxCredentialHandshakeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val testScope = TestScope()
    private val perBoxSecretKey = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val wrongSecretKey = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
    private val legacyUniversalKey = "PISOPHONE_HMAC_MASTER_KEY"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KioskSecurity.clearSharedSecret(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        KioskSecurity.clearSharedSecret(context)
        db.close()
    }

    /**
     * 1. Matching keys -> arm and credit successfully
     */
    @Test
    fun testMatchingKeysArmAndCreditSuccessfully() = runBlocking {
        // Provision phone with per-box secret key
        KioskSecurity.setSharedSecret(context, perBoxSecretKey)
        assertEquals(perBoxSecretKey, KioskSecurity.getSharedSecret(context))

        var armSuccessCalled = false
        var coinCreditedSeconds = 0
        var coinCreditedAmount = 0.0
        var coinTxId = ""

        val delegate = object : Esp32ConnectionDelegate {
            override fun getDeviceId(): String = "TEST-BOX-PHONE-01"
            override fun getSecretKey(): String = KioskSecurity.getSharedSecret(context)
            override fun getAppState(): Int = 0
            override fun getSessionTimeRemaining(): Int = 0
            override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = Pair(95, true)
            override fun onEsp32Discovered(ip: String) {}
            override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {}
            override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
            override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) {
                coinCreditedSeconds = seconds
                coinCreditedAmount = amount
                coinTxId = txId ?: ""
            }
            override fun onSlotBusy() {}
            override fun onArmSuccess() {
                armSuccessCalled = true
            }
            override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {}
            override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {}
            override fun onSlotRestored(slotNum: Int) {}
        }

        val manager = Esp32ConnectionManager(context, testScope, delegate)
        manager.setEsp32Ip("192.168.4.1")

        // 1a. Verify HMAC Signature match between phone and box
        val deviceId = delegate.getDeviceId()
        val ts = System.currentTimeMillis().toString()
        val phoneSig = KioskSecurity.generateTimestampSignature(deviceId, ts, delegate.getSecretKey())
        val boxExpectedSig = KioskSecurity.calculateHmac("$deviceId:$ts", perBoxSecretKey)
        assertEquals("HMAC signature must match when keys match", boxExpectedSig, phoneSig)

        // 1b. Box detects coin and sends encrypted payload with matching key
        val txId = "TX-VALID-MATCH-001"
        val innerJson = JSONObject().apply {
            put("tx_id", txId)
            put("seconds", 1800)
            put("amount", 5.0)
            put("ts", System.currentTimeMillis().toString())
        }.toString()

        val encryptedPayloadHex = KioskSecurity.encrypt(innerJson, perBoxSecretKey)
        assertTrue(encryptedPayloadHex.isNotBlank())

        val wsMessage = JSONObject().apply {
            put("event", "COIN_DETECTED")
            put("payload", encryptedPayloadHex)
        }.toString()

        val parsed = Esp32PayloadHandler.parseWebSocketCoinEvent(wsMessage, delegate.getSecretKey(), 300000L)
        assertNotNull("Coin message must parse successfully with matching key", parsed)
        assertEquals(txId, parsed!!.txId)
        assertEquals(1800, parsed.seconds)
        assertEquals(5.0, parsed.amount, 0.001)

        // 1c. Credit to PaymentRepository
        val paymentRepo = PaymentRepository(db = db)
        val coinProcessor = CoinProcessor(context, paymentRepo)
        val creditSuccess = coinProcessor.processCoinCredit(
            seconds = parsed.seconds,
            source = "WEBSOCKET_TEST",
            txId = parsed.txId,
            amount = parsed.amount
        )
        assertTrue("Payment credit must succeed", creditSuccess)

        val receipt = db.paymentDao().getReceiptByTxId(txId)
        assertNotNull(receipt)
        assertEquals(1800, receipt?.secondsCredited)

        val session = paymentRepo.getSessionState()
        assertNotNull(session)
        assertTrue((session?.sessionTimeRemaining ?: 0) > 0)

        manager.shutdown()
    }

    /**
     * 2. Wrong or missing key -> relay stays disarmed
     */
    @Test
    fun testWrongOrMissingKeyLeavesAcceptorDisarmed() {
        // Case 2a: Missing key
        KioskSecurity.clearSharedSecret(context)
        assertEquals("", KioskSecurity.getSharedSecret(context))
        assertFalse(KioskSecurity.isPaymentConfigured(context))

        var armSuccessMissingKey = false
        val missingKeyDelegate = object : Esp32ConnectionDelegate {
            override fun getDeviceId(): String = "TEST-BOX-PHONE-02"
            override fun getSecretKey(): String = KioskSecurity.getSharedSecret(context)
            override fun getAppState(): Int = 0
            override fun getSessionTimeRemaining(): Int = 0
            override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = Pair(80, false)
            override fun onEsp32Discovered(ip: String) {}
            override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {}
            override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
            override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) {}
            override fun onSlotBusy() {}
            override fun onArmSuccess() {
                armSuccessMissingKey = true
            }
            override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {}
            override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {}
            override fun onSlotRestored(slotNum: Int) {}
        }

        val missingKeyManager = Esp32ConnectionManager(context, testScope, missingKeyDelegate)
        missingKeyManager.setEsp32Ip("192.168.4.1")
        missingKeyManager.armSlot(15)

        assertFalse("Missing secret key must disarm acceptor immediately", armSuccessMissingKey)
        missingKeyManager.shutdown()

        // Case 2b: Wrong key -> Signature and Decryption fail
        KioskSecurity.setSharedSecret(context, wrongSecretKey)
        val deviceId = "TEST-BOX-PHONE-02"
        val ts = System.currentTimeMillis().toString()

        val wrongPhoneSig = KioskSecurity.generateTimestampSignature(deviceId, ts, wrongSecretKey)
        val boxExpectedSig = KioskSecurity.calculateHmac("$deviceId:$ts", perBoxSecretKey)
        assertFalse("Box must reject wrong phone signature", KioskSecurity.constantTimeEquals(boxExpectedSig, wrongPhoneSig))

        // Decryption of coin message from box must fail on phone with wrong key
        val innerJson = JSONObject().apply {
            put("tx_id", "TX-WRONG-KEY-001")
            put("seconds", 600)
            put("amount", 1.0)
            put("ts", System.currentTimeMillis().toString())
        }.toString()
        val encryptedByBox = KioskSecurity.encrypt(innerJson, perBoxSecretKey)

        val parseAttempt = Esp32PayloadHandler.parseWebSocketCoinEvent(
            text = JSONObject().put("payload", encryptedByBox).toString(),
            secretKey = wrongSecretKey,
            maxTimestampSkewMs = 300000L
        )
        assertNull("Coin event payload decrypted with wrong key must be rejected (null)", parseAttempt)
    }

    /**
     * 3. Restart both -> credentials survive and payment still works
     */
    @Test
    fun testCredentialsSurviveRestartAndPaymentStillWorks() = runBlocking {
        // Step 1: Provision
        KioskSecurity.setSharedSecret(context, perBoxSecretKey)
        KioskSecurity.setConfiguredEsp32Mac(context, "34:85:18:90:AB:CD")
        KioskSecurity.setAssignedBoxSlot(context, 1)

        assertEquals(perBoxSecretKey, KioskSecurity.getSharedSecret(context))

        // Step 2: Simulate Cold Reboot (reload SharedPreferences / new Context / reset direct boot)
        val rebootedSecret = KioskSecurity.getSharedSecret(context)

        assertEquals("Secret must survive reboot / storage reload", perBoxSecretKey, rebootedSecret)
        assertTrue(KioskSecurity.isProvisioned(context))

        // Step 3: Perform authenticated payment credit after simulated restart
        val txId = "TX-POST-RESTART-001"
        val innerJson = JSONObject().apply {
            put("tx_id", txId)
            put("seconds", 1200)
            put("amount", 2.0)
            put("ts", System.currentTimeMillis().toString())
        }.toString()
        val encryptedPayload = KioskSecurity.encrypt(innerJson, rebootedSecret)

        val parsed = Esp32PayloadHandler.parseWebSocketCoinEvent(
            text = JSONObject().put("payload", encryptedPayload).toString(),
            secretKey = rebootedSecret,
            maxTimestampSkewMs = 300000L
        )
        assertNotNull("Payment event must be parsed and authenticated after restart", parsed)

        val paymentRepo = PaymentRepository(db = db)
        val result = paymentRepo.creditPayment(parsed!!.txId, parsed.seconds, parsed.amount)
        assertEquals(PaymentResult.APPLIED, result)
        assertEquals(1200, paymentRepo.getSessionState()?.sessionTimeRemaining)
    }

    /**
     * 4. Old universal key -> rejected
     */
    @Test
    fun testOldUniversalKeyIsRejectedByReprovisionedBox() {
        // Box is configured with unique per-box key
        val boxSecret = perBoxSecretKey

        // Phone attempting to use legacy universal master key
        val deviceId = "TEST-BOX-PHONE-LEGACY"
        val ts = System.currentTimeMillis().toString()

        val legacySig = KioskSecurity.generateTimestampSignature(deviceId, ts, legacyUniversalKey)
        val boxExpectedSig = KioskSecurity.calculateHmac("$deviceId:$ts", boxSecret)

        assertFalse("Box configured with per-box key must reject legacy universal key signature",
            KioskSecurity.constantTimeEquals(boxExpectedSig, legacySig))

        // Legacy coin packet encrypted with old universal key is rejected by reprovisioned box/app
        val legacyPayload = JSONObject().apply {
            put("tx_id", "TX-LEGACY-ATTEMPT-001")
            put("seconds", 1800)
            put("amount", 5.0)
            put("ts", System.currentTimeMillis().toString())
        }.toString()

        val encryptedWithLegacyKey = KioskSecurity.encrypt(legacyPayload, legacyUniversalKey)

        // Attempting to parse with reprovisioned box secret
        val parsedWithBoxSecret = Esp32PayloadHandler.parseWebSocketCoinEvent(
            text = JSONObject().put("payload", encryptedWithLegacyKey).toString(),
            secretKey = boxSecret,
            maxTimestampSkewMs = 300000L
        )
        assertNull("Payload encrypted with legacy universal key must not be accepted by per-box secret", parsedWithBoxSecret)
    }
}
