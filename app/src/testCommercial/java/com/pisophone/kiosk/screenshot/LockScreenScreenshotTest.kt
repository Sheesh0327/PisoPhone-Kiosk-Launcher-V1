package com.pisophone.kiosk.screenshot

import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.overlay.ui.BlockScreen
import com.pisophone.kiosk.util.Branding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lock screen in the states a customer meets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-hdpi")
class LockScreenScreenshotTest : ScreenshotTest() {
    private companion object {
        val BATTERY = BatteryStatus(level = 80)
    }

    @Test
    fun waitingForACoin() = shoot("lock_idle") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, batteryStatus = BATTERY)
    }

    // feature 013: the shop's look. The art stands still in these pictures (the base turns the animation scale off), so the
    // density shows in how many stickers are drawn; the credit line is in every one of them.
    private fun branded(density: String) = Branding(palette = "rosas", shopName = "Mang Juan's Tindahan", title = "Piso Net", density = density)

    @Test
    fun aBrandedPhone() = shoot("lock_branded") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, batteryStatus = BATTERY, branding = branded("normal"))
    }

    @Test
    fun densityOffStillShowsTheCredit() = shoot("lock_density_off") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, batteryStatus = BATTERY, branding = branded("off"))
    }

    @Test
    fun densityLowDrawsFourStickers() = shoot("lock_density_low") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, batteryStatus = BATTERY, branding = branded("low"))
    }

    @Test
    fun densityHigh() = shoot("lock_density_high") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, batteryStatus = BATTERY, branding = branded("high"))
    }

    @Test
    fun coinsGoingIn() = shoot("lock_counting") {
        BlockScreen(
            onInsertCoin = {},
            isWaiting = true,
            coinsInserted = 3,
            paymentTimeout = 14,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BATTERY,
        )
    }

    @Test
    fun gettingReady() = shoot("lock_arming") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = true, isArmingInProgress = true, batteryStatus = BATTERY)
    }

    @Test
    fun theBoxIsOffline() = shoot("lock_box_offline") {
        BlockScreen(onInsertCoin = {}, coinsInserted = 0, paymentTimeout = 0, onDoneClick = {}, isEsp32Online = false, batteryStatus = BATTERY)
    }

    @Test
    fun theBoxIsBusyWithAnotherPhone() = shoot("lock_coinslot_busy") {
        BlockScreen(
            onInsertCoin = {},
            coinsInserted = 0,
            paymentTimeout = 0,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BATTERY,
            armFailure = Esp32Responses.ArmFailure.BUSY,
        )
    }

    @Test
    fun theTropicColourTheme() = shoot("lock_idle_theme1") {
        BlockScreen(
            onInsertCoin = {},
            coinsInserted = 0,
            paymentTimeout = 0,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BATTERY,
            themeIndex = 1,
        )
    }

    @Test
    fun theGoldColourTheme() = shoot("lock_idle_theme2") {
        BlockScreen(
            onInsertCoin = {},
            coinsInserted = 0,
            paymentTimeout = 0,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BATTERY,
            themeIndex = 2,
        )
    }

    @Test
    fun theRentalHasEnded() = shoot("lock_slot_expired") {
        BlockScreen(
            onInsertCoin = {},
            coinsInserted = 0,
            paymentTimeout = 0,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BATTERY,
            isSlotExpired = true,
            slotExpiryReason = "Rental ended",
        )
    }
}

/** The same screen on a wide (landscape) phone: fewer stickers, the two-column layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w740dp-h360dp-land-hdpi")
class LockScreenLandscapeScreenshotTest : ScreenshotTest() {
    @Test
    fun waitingForACoin() = shoot("lock_idle_landscape") {
        BlockScreen(
            onInsertCoin = {},
            coinsInserted = 0,
            paymentTimeout = 0,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BatteryStatus(level = 80),
        )
    }

    @Test
    fun coinsGoingIn() = shoot("lock_counting_landscape") {
        BlockScreen(
            onInsertCoin = {},
            isWaiting = true,
            coinsInserted = 3,
            paymentTimeout = 14,
            onDoneClick = {},
            isEsp32Online = true,
            batteryStatus = BatteryStatus(level = 80),
        )
    }
}
