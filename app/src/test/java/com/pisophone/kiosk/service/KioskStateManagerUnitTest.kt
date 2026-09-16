package com.pisophone.kiosk.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KioskStateManagerUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("kiosk_persistent_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testSaveAndRestoreState_persistsAndRestoresEsp32IpAndMac() {
        val stateManager = KioskStateManager(context)
        stateManager.esp32Ip = "192.168.4.1"
        stateManager.esp32MacAddress.value = "11:22:33:44:55:66"

        stateManager.saveState()

        val restoredManager = KioskStateManager(context)
        restoredManager.restoreState()

        assertEquals("192.168.4.1", restoredManager.esp32Ip)
        assertEquals("11:22:33:44:55:66", restoredManager.esp32MacAddress.value)
    }

    @Test
    fun testSaveAndRestoreState_emptyEsp32IpRestoresAsNull() {
        val stateManager = KioskStateManager(context)
        stateManager.esp32Ip = null
        stateManager.esp32MacAddress.value = ""

        stateManager.saveState()

        val restoredManager = KioskStateManager(context)
        restoredManager.restoreState()

        assertNull(restoredManager.esp32Ip)
    }
}
