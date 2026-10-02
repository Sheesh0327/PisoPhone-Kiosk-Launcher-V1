package com.pisophone.kiosk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Esp32CoinslotRequestsTest {
    // Signatures below were computed independently with Python hmac/sha256, see KioskSecurityUnitTest.
    @Test
    fun armUrlCarriesDeviceExtrasTimestampAndSignature() {
        val url = Esp32CoinslotRequests.signedUrl(
            host = "192.168.1.10",
            action = Esp32CoinslotRequests.ACTION_ARM,
            deviceId = "dev1",
            secret = "secret123",
            extraQuery = "ip=192.168.1.20&duration=45",
            nowMs = 1700000000000L,
        )
        assertEquals(
            "http://192.168.1.10:80/api/coinslot/arm?device_id=dev1&ip=192.168.1.20&duration=45" +
                "&ts=1700000000000&sig=e08e6c51e0d791208efe56de6838e81125fe3bbd52783e278949c35019716006",
            url,
        )
    }

    @Test
    fun ackUrlSignsTheTransactionId() {
        val url = Esp32CoinslotRequests.signedUrl(
            host = "192.168.1.10",
            action = Esp32CoinslotRequests.ACTION_ACK,
            deviceId = "dev1",
            secret = "secret123",
            txId = "tx-9",
            nowMs = 1700000000000L,
        )
        assertTrue(url.contains("&tx_id=tx-9&ts=1700000000000"))
        assertTrue(url.endsWith("&sig=5134caa51edc4292a5b7be46ff376dbc7acb2426168cb8e5c772827ff2f8f38c"))
    }

    @Test
    fun unarmUrlHasNoOptionalParts() {
        val url = Esp32CoinslotRequests.signedUrl("h", Esp32CoinslotRequests.ACTION_UNARM, "d", "s", nowMs = 5L)
        assertTrue(url.startsWith("http://h:80/api/coinslot/unarm?device_id=d&ts=5&sig="))
    }
}
