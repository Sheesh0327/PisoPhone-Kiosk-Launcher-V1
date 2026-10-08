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
        override fun onSlotBusy() { events += "busy" }
        override fun onArmSuccess() { events += "armed" }
        override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) { events += "warning=$daysLeft" }
        override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) { events += "lockdown=$slotNum" }
        override fun onSlotRestored(slotNum: Int) { events += "restored=$slotNum" }
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
    fun aSlotNearingExpiryWarnsOnlyWithinSevenDays() {
        handler.handle("""{"slot_num":1,"slot_warning":true,"days_left":3}""", "10.0.0.2")
        assertTrue("warning=3" in delegate.events)
        delegate.events.clear()
        handler.handle("""{"slot_num":1,"slot_warning":true,"days_left":30}""", "10.0.0.2")
        assertTrue(delegate.events.none { it.startsWith("warning") })
    }

    @Test
    fun malformedJsonIsIgnoredWithoutThrowing() {
        handler.handle("not json", "10.0.0.2")
        assertTrue(delegate.events.isEmpty())
    }
}
