package com.pisophone.kiosk.security

import com.pisophone.kiosk.security.AppVisibilityPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityPolicyUnitTest {
    private val own = "com.pisophone.kiosk"

    private fun decide(pkg: String, system: Boolean, critical: Boolean = false) = AppVisibilityPolicy.decide(pkg, system, critical, own)

    @Test
    fun appsTheUserInstalledAreNeverTouched() {
        assertEquals(Action.KEEP_VISIBLE, decide("com.mojang.minecraftpe", system = false))
        assertEquals(Action.KEEP_VISIBLE, decide("com.android.chrome", system = false)) // installed by the user, even with a system-like name
        assertEquals(Action.KEEP_VISIBLE, decide(own, system = true))
    }

    @Test
    fun unneededPreinstalledAppsAreDisabled() {
        assertEquals(Action.DISABLE, decide("com.google.android.apps.photos", system = true))
        assertEquals(Action.DISABLE, decide("com.google.android.youtube", system = true))
        assertEquals(Action.DISABLE, decide("com.android.calendar", system = true))
        assertEquals(Action.DISABLE, decide("com.android.deskclock", system = true))
    }

    @Test
    fun appsThePhoneNeedsAreOnlyHidden() {
        // things the system itself depends on, whether or not the phone says so
        for (pkg in listOf(
            "com.android.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.android.systemui",
            "com.android.providers.contacts",
            "com.google.android.tts",
            "com.google.android.inputmethod.latin",
            "com.android.permissioncontroller",
            "com.android.documentsui",
        )) {
            assertEquals(pkg, Action.HIDE_IN_LAUNCHER, decide(pkg, system = true))
        }
        // a phone vendor's own apps are mixed up with its framework
        assertEquals(Action.HIDE_IN_LAUNCHER, decide("com.samsung.android.dialer", system = true))
        assertEquals(Action.HIDE_IN_LAUNCHER, decide("com.miui.gallery", system = true))
        // whatever the phone reports as in use (home screen, dialer, keyboard, web view, ...) is only hidden
        assertEquals(Action.HIDE_IN_LAUNCHER, decide("com.example.stockhome", system = true, critical = true))
    }

    @Test
    fun prefixRulesDoNotMatchLookAlikes() {
        assertTrue(AppVisibilityPolicy.isNeverDisabled("com.android.providers.media"))
        assertFalse(AppVisibilityPolicy.isNeverDisabled("com.android.providersfake"))
        assertTrue(AppVisibilityPolicy.isNeverDisabled("android"))
        assertFalse(AppVisibilityPolicy.isNeverDisabled("android.fake"))
    }
}
