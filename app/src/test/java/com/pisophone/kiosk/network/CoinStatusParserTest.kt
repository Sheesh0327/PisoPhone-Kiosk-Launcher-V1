package com.pisophone.kiosk.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoinStatusParserTest {
    private fun status(vararg tx: String) = JSONObject("""{"success":true,"transactions":[${tx.joinToString(",")}]}""")

    private fun tx(owner: String?, id: String, ack: Boolean = false) =
        "{" + (if (owner != null) "\"device_id\":\"$owner\"," else "") +
            "\"tx_id\":\"$id\",\"pulses\":1,\"amount\":1.00,\"seconds\":360,\"minutes\":6,\"acknowledged\":$ack,\"ts\":1}"

    @Test
    fun aPhoneCreditsItsOwnCoins() {
        val coins = CoinStatusParser.ownedCoins(status(tx("PHONE-1", "tx-1")), "PHONE-1")
        assertEquals(listOf(CoinStatusParser.Coin("tx-1", 360, 1.0)), coins)
    }

    @Test
    fun theNextCustomersPhoneNeverCreditsSomeoneElsesCoins() {
        // The bug: phone 2 pressed Insert Coin and its status poll still listed phone 1's coin.
        assertTrue(CoinStatusParser.ownedCoins(status(tx("PHONE-1", "tx-1")), "PHONE-2").isEmpty())
    }

    @Test
    fun onlyTheMatchingCoinsOfAMixedListAreTaken() {
        val coins = CoinStatusParser.ownedCoins(status(tx("PHONE-1", "tx-1"), tx("PHONE-2", "tx-2"), tx("PHONE-2", "tx-3", ack = true)), "phone-2")
        assertEquals(listOf("tx-2"), coins.map { it.txId })
    }

    @Test
    fun aCoinWithoutAnOwnerIsIgnored() {
        assertTrue(CoinStatusParser.ownedCoins(status(tx(null, "tx-9")), "PHONE-1").isEmpty())
    }

    @Test
    fun noTransactionsMeansNoCoins() {
        assertTrue(CoinStatusParser.ownedCoins(JSONObject("""{"success":true}"""), "PHONE-1").isEmpty())
    }
}
