package com.pisophone.kiosk.util

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCloserUnitTest {
    private val own = "com.pisophone.kiosk"

    @Test
    fun `customer apps can be closed`() {
        assertTrue(AppCloser.isClosable("com.game", own, 0))
    }

    @Test
    fun `the kiosk itself is never closed`() {
        assertFalse(AppCloser.isClosable(own, own, 0))
    }

    @Test
    fun `plain system apps are left alone`() {
        assertFalse(AppCloser.isClosable("com.android.settings", own, ApplicationInfo.FLAG_SYSTEM))
    }

    @Test
    fun `updated system apps such as the browser can be closed`() {
        val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        assertTrue(AppCloser.isClosable("com.android.chrome", own, flags))
    }

    @Test
    fun `force close restarts the app in front`() {
        assertEquals("com.game", AppCloser.restartTarget("com.game", "com.other", listOf("com.game", "com.other")))
    }

    @Test
    fun `force close falls back to the app opened last when the one in front is not seen`() {
        assertEquals("com.other", AppCloser.restartTarget(null, "com.other", listOf("com.game", "com.other")))
    }

    @Test
    fun `force close never targets the kiosk or a system app`() {
        assertNull(AppCloser.restartTarget("com.android.settings", null, listOf("com.game")))
        assertNull(AppCloser.restartTarget(null, own, listOf("com.game")))
    }

    @Test
    fun `force close has nothing to close when no app is known`() {
        assertNull(AppCloser.restartTarget(null, null, listOf("com.game")))
    }
}
