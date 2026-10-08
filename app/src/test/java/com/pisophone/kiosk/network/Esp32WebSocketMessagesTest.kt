package com.pisophone.kiosk.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Esp32WebSocketMessagesTest {
    private fun parse(json: String) = Esp32WebSocketMessages.parseCoinDetected(JSONObject(json)) { "secret" }

    @Test
    fun aCompletePlainMessageIsAccepted() {
        assertEquals(
            WebSocketCoin("tx-1", 360, 1.0),
            parse("""{"event":"COIN_DETECTED","tx_id":" tx-1 ","seconds":360,"amount":1.0}"""),
        )
    }

    @Test
    fun aMessageMissingAnyFieldIsRejected() {
        assertNull(parse("""{"tx_id":"tx-1","seconds":360}"""))
        assertNull(parse("""{"tx_id":"tx-1","amount":1.0}"""))
        assertNull(parse("""{"seconds":360,"amount":1.0}"""))
        assertNull(parse("""{"tx_id":"tx-1","seconds":0,"amount":1.0}"""))
    }

    @Test
    fun theSecretIsOnlyRequestedWhenThereIsAnEncryptedPayload() {
        var asked = false
        val coin = Esp32WebSocketMessages.parseCoinDetected(
            JSONObject("""{"tx_id":"tx-1","seconds":60,"amount":2.0}"""),
        ) { asked = true; "secret" }
        assertEquals(WebSocketCoin("tx-1", 60, 2.0), coin)
        assertEquals(false, asked)
    }
}
