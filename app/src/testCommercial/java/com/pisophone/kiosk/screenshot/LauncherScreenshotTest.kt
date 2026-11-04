package com.pisophone.kiosk.screenshot

import androidx.test.core.app.ApplicationProvider
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.LauncherScreen
import com.pisophone.kiosk.util.Branding
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The home screen (feature 013): unbranded, branded, and at density off, where the credit line must still be there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-hdpi")
class LauncherScreenshotTest : ScreenshotTest() {
    private val apps = listOf(
        AppInfo("Calculator", "com.example.calc"),
        AppInfo("Camera", "com.example.camera"),
        AppInfo("Games", "com.example.games"),
        AppInfo("Music", "com.example.music"),
    )

    /** A fixed moment for the header clock: Monday, 15 November 2027, 1:23 PM UTC. */
    private val fixedNow = { Date(1_826_292_180_000) }

    @Before
    fun noVideo() {
        // the poster picture is shown instead of the video, so the picture is the same every time
        KioskSecurity.setVideoBackgroundEnabled(ApplicationProvider.getApplicationContext(), false)
        // the header shows the wall clock, which would otherwise make every run differ by the minute: pin it, and the
        // zone and language with it, so the recorded picture and the checked picture are drawn from the same moment
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @Test
    fun unbranded() = shoot("launcher_default") {
        LauncherScreen(apps = apps, onAppClick = {}, branding = Branding(), now = fixedNow)
    }

    @Test
    fun branded() = shoot("launcher_branded") {
        LauncherScreen(apps = apps, onAppClick = {}, branding = Branding(palette = "rosas", shopName = "Mang Juan's Tindahan", density = "normal"), now = fixedNow)
    }

    @Test
    fun densityOffStillShowsTheCredit() = shoot("launcher_density_off") {
        LauncherScreen(apps = apps, onAppClick = {}, branding = Branding(palette = "berde", shopName = "Mang Juan's Tindahan", density = "off"), now = fixedNow)
    }
}
