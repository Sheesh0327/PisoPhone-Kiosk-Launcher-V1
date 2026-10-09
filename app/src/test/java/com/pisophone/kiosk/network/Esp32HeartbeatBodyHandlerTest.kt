package com.pisophone.kiosk.network

import com.pisophone.kiosk.repository.PaymentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Esp32HeartbeatBodyHandlerTest {
    private class RecordingDelegate : Esp32ConnectionDelegate {
        val events = mutableListOf<String>()
        override fun getDeviceId() = "PHONE-1"
        override fun getSecretKey() = "secret"
        override fun getAppState() = 0
        override fun getSessionTimeRemaining() = 0
        override fun getRealTimeBatteryInfo() = 100 to false
        override fun onEsp32Discovered(ip: String) { events += "discovered" }
        override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) { events += "online=$isOnline,mac=$mac" }
        override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {
            events += "config=$price,$minutes,$alias,$slotNum"
        }
        override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) = PaymentResult.APPLIED
        override fun onArmFailed(failure: Esp32Responses.ArmFailure) { events += "arm-failed=${failure.name}" }
        override fun onArmSuccess() { events += "armed" }
        override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) { events += "lockdown=$slotNum" }
        override fun onSlotRestored(slotNum: Int) { events += "restored=$slotNum" }
        override fun onAccountSync(account: String, balanceSec: Int) { events += "account=$account,$balanceSec" }
    }

    private val delegate = RecordingDelegate()
    private val pairingRequests = mutableListOf<String>()
    private val handler = Esp32HeartbeatBodyHandler(delegate) { pairingRequests += it }

    @Test
    fun aBlankBodyJustMarksTheBoxOnline() {
        handler.handle("", "10.0.0.2")
        assertEquals(listOf("online=true,mac=null"), delegate.events)
    }

    @Test
    fun anUnassignedPhoneRequestsPairingAndIsLockedDown() {
        handler.handle("""{"status":"unassigned"}""", "10.0.0.2")
        assertEquals(listOf("10.0.0.2"), pairingRequests)
        assertTrue(delegate.events.first().startsWith("lockdown="))
    }

    @Test
    fun aHealthySlotRestoresAndSyncsConfig() {
        handler.handle("""{"slot_num":3,"mac":"AA:BB","price":5.0,"minutes":30,"device_name":" Box "}""", "10.0.0.2")
        assertEquals(
            listOf("restored=3", "online=true,mac=AA:BB", "config=5.0,30,Box,3"),
            delegate.events,
        )
        assertTrue(pairingRequests.isEmpty())
    }

    @Test
    fun theBoxsViewOfTheSignedInAccountIsReported() {
        handler.handle("""{"slot_num":3,"acct":"alice","acct_bal":539}""", "10.0.0.2")
        assertTrue(delegate.events.contains("account=alice,539"))
        delegate.events.clear()
        handler.handle("""{"slot_num":3,"acct":""}""", "10.0.0.2")
        assertTrue(delegate.events.contains("account=,-1"))
        delegate.events.clear()
        handler.handle("""{"slot_num":3}""", "10.0.0.2")
        assertTrue(delegate.events.none { it.startsWith("account=") })
    }

    @Test
    fun malformedJsonIsIgnoredWithoutThrowing() {
        handler.handle("not json", "10.0.0.2")
        assertTrue(delegate.events.isEmpty())
    }

    @Test
    fun anUnpairedPhoneIsToldWhetherTheBoxAcceptsItsKeyAndLearnsTheBoxTime() {
        BoxClock.reset()
        val problems = mutableListOf<String>()
        val h = Esp32HeartbeatBodyHandler(delegate, onAuthProblem = { problems += it }) { pairingRequests += it }
        h.handle("""{"status":"unassigned","auth_ok":false,"auth_reason":"BAD_SIGNATURE","server_time_ms":1790000000000}""", "10.0.0.2")
        assertEquals(listOf("BAD_SIGNATURE"), problems)
        assertTrue("the box's time was adopted", kotlin.math.abs(BoxClock.nowMs() - 1790000000000L) < 60_000L)
        problems.clear()
        h.handle("""{"status":"unassigned","auth_ok":true}""", "10.0.0.2")
        assertTrue("a good key is not a problem", problems.isEmpty())
        BoxClock.reset()
    }
}
