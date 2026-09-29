package com.pisophone.kiosk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Esp32ConnectionManagerUnitTest {

    private lateinit var context: Context
    private val testScope = TestScope()

    private val fakeDelegate = object : Esp32ConnectionDelegate {
        override fun getDeviceId(): String = "TEST_DEV_123"
        override fun getSecretKey(): String = "testsecret123456"
        override fun getAppState(): Int = 0
        override fun getSessionTimeRemaining(): Int = 0
        override fun getRealTimeBatteryInfo(): Pair<Int, Boolean> = Pair(90, false)
        override fun onEsp32Discovered(ip: String) {}
        override fun onOnlineStatusChanged(isOnline: Boolean, mac: String?) {}
        override fun onConfigSynced(price: Double?, minutes: Int?, alias: String?, adminPin: String?, slotNum: Int?) {}
        override fun onCoinMessageReceived(seconds: Int, amount: Double, txId: String?) {}
        override fun onSlotBusy() {}
        override fun onArmSuccess() {}
        override fun onSlotWarning(daysLeft: Int, expiresAt: Long, slotNum: Int, message: String) {}
        override fun onSlotLockdown(reason: String, slotNum: Int, expiresAt: Long) {}
        override fun onSlotRestored(slotNum: Int) {}
    }

    private lateinit var manager: Esp32ConnectionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = Esp32ConnectionManager(context, testScope, fakeDelegate)
    }

    @Test
    fun testCloseSessionGracefulDoesNotThrow() {
        assertNotNull(manager)
        // Invoking graceful closeSession when no socket is open should safely no-op
        manager.closeSession(sendUnarmToEsp = true)
        // Invoking forced closeSession should safely no-op
        manager.closeSession(sendUnarmToEsp = false)
    }

    @Test
    fun testShutdownCleansUpResourcesSafely() {
        manager.shutdown()
    }
}
