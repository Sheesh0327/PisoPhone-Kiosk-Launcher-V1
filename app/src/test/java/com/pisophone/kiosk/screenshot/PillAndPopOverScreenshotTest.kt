package com.pisophone.kiosk.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.overlay.ui.EffectsLevel
import com.pisophone.kiosk.overlay.ui.FloatingPill
import com.pisophone.kiosk.overlay.ui.PisoButton
import com.pisophone.kiosk.overlay.ui.PisoButtonSize
import com.pisophone.kiosk.overlay.ui.PisoButtonStyle
import com.pisophone.kiosk.overlay.ui.TimeAddedPopOverBody
import com.pisophone.kiosk.system.KioskSystemController
import com.pisophone.kiosk.ui.theme.TechNet
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A controller that touches no hardware: the pill's panel shows its volume, brightness and memory. */
private object FakeSystemController : KioskSystemController {
    override fun getStreamVolume() = 7

    override fun getStreamMaxVolume() = 15

    override fun setStreamVolume(volume: Int) {}

    override fun getScreenBrightness() = 150f

    override fun setScreenBrightness(brightness: Float) {}

    override fun getMemoryStats() = 1800L to 4096L

    override fun optimizeMemory() = 0L

    override fun triggerHapticFeedback() {}
}

/** The time pill, the "time added" pop-over and the buttons every screen is made of. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-hdpi")
class PillAndPopOverScreenshotTest : ScreenshotTest() {
    private fun overAGame(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF1B2A41)), contentAlignment = Alignment.TopCenter) {
            content()
        }
    }

    @Test
    fun thePillWithTimeLeft() = shoot(
        "pill_collapsed",
        content = overAGame {
            FloatingPill(
                timeRemaining = 25 * 60,
                onInsertCoinClick = {},
                coinsInserted = 0,
                paymentTimeout = 0,
                onDoneClick = {},
                isEsp32Online = true,
                isWaiting = false,
                batteryStatus = BatteryStatus(level = 80),
                systemController = FakeSystemController,
                onDrag = { _, _ -> },
            )
        },
    )

    @Test
    fun thePillWithAlmostNoTime() = shoot(
        "pill_almost_out",
        content = overAGame {
            FloatingPill(
                timeRemaining = 45,
                onInsertCoinClick = {},
                coinsInserted = 0,
                paymentTimeout = 0,
                onDoneClick = {},
                isEsp32Online = true,
                isWaiting = false,
                batteryStatus = BatteryStatus(level = 80),
                systemController = FakeSystemController,
                onDrag = { _, _ -> },
            )
        },
    )

    @Test
    fun thePillPanelWhileCoinsGoIn() = shoot(
        "pill_panel_waiting",
        content = overAGame {
            FloatingPill(
                timeRemaining = 12 * 60,
                onInsertCoinClick = {},
                coinsInserted = 2,
                paymentTimeout = 12,
                onDoneClick = {},
                isEsp32Online = true,
                isWaiting = true,
                batteryStatus = BatteryStatus(level = 80),
                systemController = FakeSystemController,
                onDrag = { _, _ -> },
            )
        },
    )

    @Test
    fun theTimeAddedPopOver() = shoot(
        "popover_time_added",
        content = overAGame { TimeAddedPopOverBody(addedSeconds = 30 * 60, effects = EffectsLevel.Still, appear = 1f) },
    )

    @Test
    fun theButtons() = shoot("buttons") {
        Column(
            modifier = Modifier.fillMaxSize().background(TechNet.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PisoButton(text = "Primary", onClick = {}, style = PisoButtonStyle.Primary)
            PisoButton(text = "Success", onClick = {}, style = PisoButtonStyle.Success, size = PisoButtonSize.Medium)
            PisoButton(text = "Gold", onClick = {}, style = PisoButtonStyle.Gold, size = PisoButtonSize.Medium)
            PisoButton(text = "Tonal", onClick = {}, style = PisoButtonStyle.Tonal, size = PisoButtonSize.Small)
            PisoButton(text = "Outline", onClick = {}, style = PisoButtonStyle.Outline, size = PisoButtonSize.Small)
            PisoButton(text = "Danger", onClick = {}, style = PisoButtonStyle.Danger, size = PisoButtonSize.Small)
            PisoButton(text = "Disabled", onClick = {}, enabled = false, size = PisoButtonSize.Small, subline = "Says why")
        }
    }
}
