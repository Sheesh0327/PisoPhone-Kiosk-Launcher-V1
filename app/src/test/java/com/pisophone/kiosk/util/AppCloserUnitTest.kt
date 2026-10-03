package com.pisophone.kiosk.util

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
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
}
