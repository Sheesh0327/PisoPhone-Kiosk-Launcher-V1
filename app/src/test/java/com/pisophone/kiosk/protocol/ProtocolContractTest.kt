package com.pisophone.kiosk.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProtocolContractTest {

    private val sharedSecret = "PISOPHONE_SHARED_SECRET_KEY_32B!"

    private fun loadVectorJson(): JSONObject {
        val vectorFile = listOf(
            File("test_vectors/protocol_test_vectors.json"),
            File("../test_vectors/protocol_test_vectors.json"),
            File("../../test_vectors/protocol_test_vectors.json")
        ).firstOrNull { it.exists() } ?: error("protocol_test_vectors.json not found in search paths")
        return JSONObject(vectorFile.readText())
    }

    @Test
    fun testUtf8LengthFraming() {
        // ASCII
        assertEquals("5:hello", KioskProtocol.utf8Frame("hello"))
        // Multi-byte UTF-8 currency sign (3 bytes: 0xE2 0x82 0xB1)
        assertEquals("3:₱", KioskProtocol.utf8Frame("₱"))
        // Multi-byte accented Latin (5 bytes: C a f é=0xC3 0xA9)
        assertEquals("5:Café", KioskProtocol.utf8Frame("Café"))
        // Emoji (3 bytes: 0xE2 0x9A 0xA1)
        assertEquals("3:⚡", KioskProtocol.utf8Frame("⚡"))
        // Empty string
        assertEquals("0:", KioskProtocol.utf8Frame(""))
        // Numbers
        assertEquals("4:1800", KioskProtocol.utf8Frame("1800"))
        assertEquals("4:-600", KioskProtocol.utf8Frame("-600"))
    }

    @Test
    fun testHttpReqVectorsAgainstCommonFixture() {
        val json = loadVectorJson()
        val httpVectors = json.getJSONObject("http_req_vectors")

        // 1. Valid Standard
        val validStd = httpVectors.getJSONObject("valid_standard")
        val stdMethod = validStd.getString("method")
        val stdEndpoint = validStd.getString("endpoint")
        val stdRecipient = validStd.getString("recipient")
        val stdTxId = validStd.getString("tx_id")
        val stdTs = validStd.getString("ts")
        val stdPayload = validStd.getString("payload")
        val expectedStdRaw = validStd.getString("raw_framed")
        val expectedStdHmac = validStd.getString("hmac")

        val actualStdRaw = KioskProtocol.formatHttpReqData(stdMethod, stdEndpoint, stdRecipient, stdTxId, stdTs, stdPayload)
        assertEquals(expectedStdRaw, actualStdRaw)

        val actualStdHmac = KioskProtocol.calculateHttpReqSignature(stdMethod, stdEndpoint, stdRecipient, stdTxId, stdTs, stdPayload, sharedSecret)
        assertEquals(expectedStdHmac, actualStdHmac)

        assertTrue(KioskProtocol.verifyHttpReqSignature(stdMethod, stdEndpoint, stdRecipient, stdTxId, stdTs, stdPayload, actualStdHmac, sharedSecret))

        // 2. Valid Non-ASCII
        val validNonAscii = httpVectors.getJSONObject("valid_non_ascii")
        val naMethod = validNonAscii.getString("method")
        val naEndpoint = validNonAscii.getString("endpoint")
        val naRecipient = validNonAscii.getString("recipient")
        val naTxId = validNonAscii.getString("tx_id")
        val naTs = validNonAscii.getString("ts")
        val naPayload = validNonAscii.getString("payload")
        val expectedNaHmac = validNonAscii.getString("hmac")

        val actualNaHmac = KioskProtocol.calculateHttpReqSignature(naMethod, naEndpoint, naRecipient, naTxId, naTs, naPayload, sharedSecret)
        assertEquals(expectedNaHmac, actualNaHmac)
        assertTrue(KioskProtocol.verifyHttpReqSignature(naMethod, naEndpoint, naRecipient, naTxId, naTs, naPayload, actualNaHmac, sharedSecret))

        // 3. Tampered Endpoint rejection
        val tamperedEndpoint = httpVectors.getJSONObject("tampered_endpoint").getString("tampered_endpoint")
        assertFalse("Tampered endpoint must fail signature verification",
            KioskProtocol.verifyHttpReqSignature(stdMethod, tamperedEndpoint, stdRecipient, stdTxId, stdTs, stdPayload, actualStdHmac, sharedSecret)
        )

        // 4. Tampered Recipient rejection
        val tamperedRecipient = httpVectors.getJSONObject("tampered_recipient").getString("tampered_recipient")
        assertFalse("Tampered recipient must fail signature verification",
            KioskProtocol.verifyHttpReqSignature(stdMethod, stdEndpoint, tamperedRecipient, stdTxId, stdTs, stdPayload, actualStdHmac, sharedSecret)
        )

        // 5. Tampered Payload (e.g. modified seconds) rejection
        val tamperedPayload = httpVectors.getJSONObject("tampered_seconds_in_payload").getString("tampered_payload")
        assertFalse("Tampered payload must fail signature verification",
            KioskProtocol.verifyHttpReqSignature(stdMethod, stdEndpoint, stdRecipient, stdTxId, stdTs, tamperedPayload, actualStdHmac, sharedSecret)
        )
    }

    @Test
    fun testWsPayVectorsAgainstCommonFixture() {
        val json = loadVectorJson()
        val wsVectors = json.getJSONObject("ws_pay_vectors")

        // 1. Valid Coin
        val validCoin = wsVectors.getJSONObject("valid_coin")
        val event = validCoin.getString("event")
        val recipient = validCoin.getString("recipient")
        val txId = validCoin.getString("tx_id")
        val ts = validCoin.getString("ts")
        val payload = validCoin.getString("payload")
        val expectedRaw = validCoin.getString("raw_framed")
        val expectedHmac = validCoin.getString("hmac")

        val actualRaw = KioskProtocol.formatWsPayData(event, recipient, txId, ts, payload)
        assertEquals(expectedRaw, actualRaw)

        val actualHmac = KioskProtocol.calculateWsPaySignature(event, recipient, txId, ts, payload, sharedSecret)
        assertEquals(expectedHmac, actualHmac)
        assertTrue(KioskProtocol.verifyWsPaySignature(event, recipient, txId, ts, payload, actualHmac, sharedSecret))

        // 2. Valid Non-ASCII
        val validNa = wsVectors.getJSONObject("valid_non_ascii")
        val actualNaHmac = KioskProtocol.calculateWsPaySignature(
            validNa.getString("event"), validNa.getString("recipient"),
            validNa.getString("tx_id"), validNa.getString("ts"),
            validNa.getString("payload"), sharedSecret
        )
        assertEquals(validNa.getString("hmac"), actualNaHmac)

        // 3. Tampered Event rejection
        val tamperedEvent = wsVectors.getJSONObject("tampered_event").getString("tampered_event")
        assertFalse("Tampered event must fail signature verification",
            KioskProtocol.verifyWsPaySignature(tamperedEvent, recipient, txId, ts, payload, actualHmac, sharedSecret)
        )

        // 4. Tampered Recipient rejection
        val tamperedRecipient = wsVectors.getJSONObject("tampered_recipient").getString("tampered_recipient")
        assertFalse("Tampered recipient must fail signature verification",
            KioskProtocol.verifyWsPaySignature(event, tamperedRecipient, txId, ts, payload, actualHmac, sharedSecret)
        )
    }

    @Test
    fun testAckVectorsAgainstCommonFixture() {
        val json = loadVectorJson()
        val ackVectors = json.getJSONObject("ack_vectors")

        // 1. Valid OK
        val validOk = ackVectors.getJSONObject("valid_ok")
        val devId = validOk.getString("device_id")
        val txId = validOk.getString("tx_id")
        val amount = validOk.getInt("amount")
        val seconds = validOk.getInt("seconds")
        val ts = validOk.getString("ts")
        val status = validOk.getString("status")
        val expectedRaw = validOk.getString("raw_framed")
        val expectedHmac = validOk.getString("hmac")

        val actualRaw = KioskProtocol.formatAckData(devId, txId, amount, seconds, ts, status)
        assertEquals(expectedRaw, actualRaw)

        val actualHmac = KioskProtocol.calculateAckSignature(devId, txId, amount, seconds, ts, status, sharedSecret)
        assertEquals(expectedHmac, actualHmac)
        assertTrue(KioskProtocol.verifyAckSignature(devId, txId, amount, seconds, ts, status, actualHmac, sharedSecret))

        // 2. Valid ALREADY_PROCESSED
        val validAlready = ackVectors.getJSONObject("valid_already_processed")
        val actualAlreadyHmac = KioskProtocol.calculateAckSignature(
            validAlready.getString("device_id"), validAlready.getString("tx_id"),
            validAlready.getInt("amount"), validAlready.getInt("seconds"),
            validAlready.getString("ts"), validAlready.getString("status"), sharedSecret
        )
        assertEquals(validAlready.getString("hmac"), actualAlreadyHmac)

        // 3. Valid Negative Adjustment (e.g. -300s, amount=0)
        val validNeg = ackVectors.getJSONObject("valid_negative_adjustment")
        val actualNegHmac = KioskProtocol.calculateAckSignature(
            validNeg.getString("device_id"), validNeg.getString("tx_id"),
            validNeg.getInt("amount"), validNeg.getInt("seconds"),
            validNeg.getString("ts"), validNeg.getString("status"), sharedSecret
        )
        assertEquals(validNeg.getString("hmac"), actualNegHmac)

        // 4. Valid Non-ASCII Device ID
        val validNaDev = ackVectors.getJSONObject("valid_non_ascii_device")
        val actualNaHmac = KioskProtocol.calculateAckSignature(
            validNaDev.getString("device_id"), validNaDev.getString("tx_id"),
            validNaDev.getInt("amount"), validNaDev.getInt("seconds"),
            validNaDev.getString("ts"), validNaDev.getString("status"), sharedSecret
        )
        assertEquals(validNaDev.getString("hmac"), actualNaHmac)

        // 5. Tampered Seconds rejection
        val tamperedSec = ackVectors.getJSONObject("tampered_seconds").getInt("tampered_seconds")
        assertFalse("Tampered seconds must fail ACK verification",
            KioskProtocol.verifyAckSignature(devId, txId, amount, tamperedSec, ts, status, actualHmac, sharedSecret)
        )

        // 6. Tampered Status rejection
        val tamperedStatus = ackVectors.getJSONObject("tampered_status").getString("tampered_status")
        assertFalse("Tampered status must fail ACK verification",
            KioskProtocol.verifyAckSignature(devId, txId, amount, seconds, ts, tamperedStatus, actualHmac, sharedSecret)
        )

        // 7. Tampered Amount rejection
        val tamperedAmount = ackVectors.getJSONObject("tampered_amount").getInt("tampered_amount")
        assertFalse("Tampered amount must fail ACK verification",
            KioskProtocol.verifyAckSignature(devId, txId, tamperedAmount, seconds, ts, status, actualHmac, sharedSecret)
        )
    }

    @Test
    fun testContextReconciliation() {
        val envelope = KioskProtocol.VerifiedEnvelope(
            event = "COIN_DETECTED",
            recipient = "dev-01",
            txId = "tx-100",
            ts = 1700000000000L,
            payload = "somepayload",
            seconds = 1800,
            amount = 5.0
        )

        // Exact match -> Valid
        val matchingContext = KioskProtocol.DecryptedPaymentContext(
            deviceId = "dev-01",
            txId = "tx-100",
            ts = 1700000000000L,
            seconds = 1800,
            amount = 5.0
        )
        val validResult = KioskProtocol.reconcilePaymentContext(envelope, matchingContext)
        assertTrue(validResult is KioskProtocol.ProtocolValidationResult.Valid)

        // Mutated Device ID -> Invalid
        val mutatedDev = matchingContext.copy(deviceId = "dev-02")
        val devResult = KioskProtocol.reconcilePaymentContext(envelope, mutatedDev)
        assertTrue(devResult is KioskProtocol.ProtocolValidationResult.Invalid)
        assertEquals("MUTATED_DEVICE_ID", (devResult as KioskProtocol.ProtocolValidationResult.Invalid).code)

        // Mutated Tx ID -> Invalid
        val mutatedTx = matchingContext.copy(txId = "tx-999")
        val txResult = KioskProtocol.reconcilePaymentContext(envelope, mutatedTx)
        assertTrue(txResult is KioskProtocol.ProtocolValidationResult.Invalid)
        assertEquals("MUTATED_TRANSACTION_ID", (txResult as KioskProtocol.ProtocolValidationResult.Invalid).code)

        // Mutated Timestamp -> Invalid
        val mutatedTs = matchingContext.copy(ts = 1700000001000L)
        val tsResult = KioskProtocol.reconcilePaymentContext(envelope, mutatedTs)
        assertTrue(tsResult is KioskProtocol.ProtocolValidationResult.Invalid)
        assertEquals("MUTATED_TIMESTAMP", (tsResult as KioskProtocol.ProtocolValidationResult.Invalid).code)

        // Mutated Seconds -> Invalid
        val mutatedSec = matchingContext.copy(seconds = 3600)
        val secResult = KioskProtocol.reconcilePaymentContext(envelope, mutatedSec)
        assertTrue(secResult is KioskProtocol.ProtocolValidationResult.Invalid)
        assertEquals("MUTATED_SECONDS", (secResult as KioskProtocol.ProtocolValidationResult.Invalid).code)

        // Mutated Amount -> Invalid
        val mutatedAmt = matchingContext.copy(amount = 10.0)
        val amtResult = KioskProtocol.reconcilePaymentContext(envelope, mutatedAmt)
        assertTrue(amtResult is KioskProtocol.ProtocolValidationResult.Invalid)
        assertEquals("MUTATED_AMOUNT", (amtResult as KioskProtocol.ProtocolValidationResult.Invalid).code)
    }
}
