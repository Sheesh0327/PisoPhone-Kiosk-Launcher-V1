package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Esp32WebSocketMessagesTest {
    private val secret = "box-secret-0123456789"
    private val phone = "PHONE-1"

    /** What the box sends: plain fields for logging, and the encrypted payload that carries the proof. */
    private fun message(
        tx: String = "tx-1",
        device: String = phone,
        pesos: Int = 1,
        seconds: Int = 360,
        ts: String = "1760000000000",
        sig: String? = null,
        key: String = secret,
        plainSeconds: Int = seconds,
    ): JSONObject {
        val proof = sig ?: KioskSecurity.calculateHmac("v1:$device:$tx:$pesos:$ts", key)
        val inner = """{"seconds":$seconds,"minutes":${seconds / 60},"amount":$pesos,"tx_id":"$tx","ts":"$ts","device_id":"$device","v_sig":"$proof"}"""
        return JSONObject(
            """{"event":"COIN_DETECTED","device_id":"$device","tx_id":"$tx","amount":$pesos,"seconds":$plainSeconds,"payload":"${KioskSecurity.encrypt(inner, key)}"}""",
        )
    }

    private fun parse(json: JSONObject, id: String = phone) = Esp32WebSocketMessages.parseCoinDetected(json, id) { secret }

    @Test
    fun aCoinTheBoxSignedIsAccepted() {
        assertEquals(WebSocketCoin("tx-1", 360, 1.0), parse(message()))
        assertEquals(WebSocketCoin("tx-2", 1800, 5.0), parse(message(tx = "tx-2", pesos = 5, seconds = 1800)))
    }

    @Test
    fun aMessageWithoutTheEncryptedProofCreditsNothing() {
        // anything on the network can write these plain fields
        assertNull(parse(JSONObject("""{"event":"COIN_DETECTED","tx_id":"tx-1","seconds":36000,"amount":10.0}""")))
        assertNull(parse(JSONObject("""{"event":"COIN_DETECTED","tx_id":"tx-1","seconds":360,"amount":1.0,"payload":""}""")))
    }

    @Test
    fun aPayloadMadeWithAnotherSecretCreditsNothing() {
        assertNull(parse(message(key = "some-other-secret-0123456789")))
    }

    @Test
    fun aWrongOrMissingSignatureCreditsNothing() {
        assertNull(parse(message(sig = "00".repeat(32))))
        assertNull(parse(message(sig = "")))
    }

    @Test
    fun aSignatureForAnotherAmountTransactionOrTimeCreditsNothing() {
        val good = KioskSecurity.calculateHmac("v1:$phone:tx-1:1:1760000000000", secret)
        assertNull(parse(message(pesos = 10, sig = good)))
        assertNull(parse(message(tx = "tx-2", sig = good)))
        assertNull(parse(message(ts = "1760000000001", sig = good)))
    }

    @Test
    fun aCoinForAnotherPhoneCreditsNothing() {
        assertNull(parse(message(device = "PHONE-2"), id = phone))
        assertNull(parse(message(), id = "PHONE-2"))
    }

    @Test
    fun theSecondsMustFitTheSignedAmount() {
        assertNull(parse(message(pesos = 1, seconds = CoinProof.MAX_SECONDS_PER_PESO + 1)))
        assertEquals(WebSocketCoin("tx-1", CoinProof.MAX_SECONDS_PER_PESO, 1.0), parse(message(seconds = CoinProof.MAX_SECONDS_PER_PESO)))
    }

    @Test
    fun thePlainFieldsNextToThePayloadAreNeverUsed() {
        val coin = parse(message(seconds = 360, plainSeconds = 99999))
        assertEquals(360, coin!!.seconds)
    }
}
