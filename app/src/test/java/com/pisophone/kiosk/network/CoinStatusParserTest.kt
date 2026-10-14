package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoinStatusParserTest {
    private val secret = "box-secret-0123456789"

    private fun status(vararg tx: String) = JSONObject("""{"success":true,"transactions":[${tx.joinToString(",")}]}""")

    private fun tx(owner: String?, id: String, ack: Boolean = false, sig: String? = null, seconds: Int = 360): String {
        val proof = sig ?: KioskSecurity.calculateHmac("v1:$owner:$id:1:1760000000000", secret)
        return "{" + (if (owner != null) "\"device_id\":\"$owner\"," else "") +
            "\"tx_id\":\"$id\",\"pulses\":1,\"amount\":1.00,\"seconds\":$seconds,\"minutes\":6,\"acknowledged\":$ack," +
            "\"ts\":1760000000000,\"v_sig\":\"$proof\"}"
    }

    private fun coins(json: JSONObject, id: String) = CoinStatusParser.ownedCoins(json, id, secret)

    @Test
    fun aPhoneCreditsItsOwnCoins() {
        assertEquals(listOf(CoinStatusParser.Coin("tx-1", 360, 1.0)), coins(status(tx("PHONE-1", "tx-1")), "PHONE-1"))
    }

    @Test
    fun theNextCustomersPhoneNeverCreditsSomeoneElsesCoins() {
        // The bug: phone 2 pressed Insert Coin and its status poll still listed phone 1's coin.
        assertTrue(coins(status(tx("PHONE-1", "tx-1")), "PHONE-2").isEmpty())
    }

    @Test
    fun onlyTheMatchingCoinsOfAMixedListAreTaken() {
        val list = coins(status(tx("PHONE-1", "tx-1"), tx("PHONE-2", "tx-2"), tx("PHONE-2", "tx-3", ack = true)), "PHONE-2")
        assertEquals(listOf("tx-2"), list.map { it.txId })
    }

    @Test
    fun aCoinWithoutAnOwnerIsIgnored() {
        assertTrue(coins(status(tx(null, "tx-9")), "PHONE-1").isEmpty())
    }

    @Test
    fun noTransactionsMeansNoCoins() {
        assertTrue(coins(JSONObject("""{"success":true}"""), "PHONE-1").isEmpty())
    }

    @Test
    fun aCoinWithoutTheBoxsProofIsNotCredited() {
        // an answer from something that is not the box can list any coin it likes
        assertTrue(coins(status(tx("PHONE-1", "tx-1", sig = "")), "PHONE-1").isEmpty())
        assertTrue(coins(status(tx("PHONE-1", "tx-1", sig = "00".repeat(32))), "PHONE-1").isEmpty())
        val unsigned = "{\"device_id\":\"PHONE-1\",\"tx_id\":\"tx-1\",\"pulses\":1,\"amount\":1.00,\"seconds\":360,\"acknowledged\":false,\"ts\":1}"
        assertTrue(coins(status(unsigned), "PHONE-1").isEmpty())
    }

    @Test
    fun aCoinSignedWithAnotherSecretIsNotCredited() {
        val forged = KioskSecurity.calculateHmac("v1:PHONE-1:tx-1:1:1760000000000", "another-secret-0123456789")
        assertTrue(coins(status(tx("PHONE-1", "tx-1", sig = forged)), "PHONE-1").isEmpty())
    }

    @Test
    fun aListedAmountCannotBeInflatedBeyondTheSignedOne() {
        assertTrue(coins(status(tx("PHONE-1", "tx-1", seconds = CoinProof.MAX_SECONDS_PER_PESO + 1)), "PHONE-1").isEmpty())
    }
}
