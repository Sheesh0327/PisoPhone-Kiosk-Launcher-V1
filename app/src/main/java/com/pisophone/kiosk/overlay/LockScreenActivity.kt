package com.pisophone.kiosk.overlay

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pisophone.kiosk.service.SessionRules
import kotlinx.coroutines.delay

/**
 * The lock screen as a full-screen activity, for a phone where the app may not draw over other apps.
 *
 * Normally the lock screen is an overlay window ([LockScreenOverlay]), which needs "display over other apps". The USB setup
 * grants it over ADB; a phone set up by QR code never gets it (no app can grant it to itself, not even the device owner,
 * and Android Go phones do not offer it in Settings). On such a phone the device owner shows the same lock screen as this
 * activity instead: [KioskOverlayCoordinator] starts it whenever the phone is to be locked (a device owner may start
 * activities from the background), it closes itself when a session starts, Back does nothing, and the home screen
 * ([com.pisophone.kiosk.MainActivity]) brings it back if a customer leaves it while the phone is locked.
 */
class LockScreenActivity : ComponentActivity() {
    companion object {
        private const val TAG = "LockScreenActivity"

        /** The overlay whose content and state this activity shows (set by [KioskOverlayCoordinator]). */
        @Volatile
        var host: KioskOverlay? = null

        /** True from creation until the activity is destroyed (the health monitor's "is the lock screen there"). */
        @Volatile
        var isAlive = false
            private set

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isDeviceOwnerApp(context.packageName) == true
        }

        /** The lock screen has to be this activity: no overlay permission, but the app is the device owner. */
        fun shouldUse(context: Context): Boolean = !Settings.canDrawOverlays(context) && isDeviceOwner(context)

        /** The phone is to be locked now (by the session state of the current overlay). */
        fun shouldShowNow(): Boolean {
            val overlay = host ?: return false
            return SessionRules.isLockScreenShown(overlay.appState.value)
        }

        fun launch(context: Context) {
            try {
                context.startActivity(
                    Intent(context, LockScreenActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not show the lock screen: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val overlay = host
        if (overlay == null) {
            finish()
            return
        }
        isAlive = true
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        hideSystemBars()
        // a customer cannot leave the lock screen with Back
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {}
            },
        )
        setContent {
            val state by overlay.appState.collectAsState()
            overlay.LockContent()
            LaunchedEffect(state) {
                if (!SessionRules.isLockScreenShown(state)) {
                    delay(400) // (the unlock animation)
                    if (!SessionRules.isLockScreenShown(overlay.appState.value)) finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        isAlive = false
        super.onDestroy()
    }

    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            Log.w(TAG, "Could not hide the system bars: ${e.message}")
        }
    }
}
