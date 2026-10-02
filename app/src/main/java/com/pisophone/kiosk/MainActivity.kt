package com.pisophone.kiosk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.KioskActivationManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.*
import com.pisophone.kiosk.ui.theme.PisoPhoneLauncherTheme
import com.pisophone.kiosk.util.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"

        /** Ask for the Doze exemption at most once per process to avoid nagging. */
        @Volatile
        private var batteryExemptionRequested = false
    }

    private var appsList by mutableStateOf<List<AppInfo>>(emptyList())
    private var hasOverlayPermission by mutableStateOf(false)
    private var isLockTaskActive by mutableStateOf(false)
    private var isDeviceOwner by mutableStateOf(false)
    private var strictPoliciesApplied = false

    private fun checkDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val isOwner = dpm?.isDeviceOwnerApp(packageName) == true
        isDeviceOwner = isOwner

        if (isOwner) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!strictPoliciesApplied) {
                    strictPoliciesApplied = true
                    try {
                        KioskSecurity.applyStrictKioskPolicies(applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error applying kiosk policies in background: ${e.message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    tryEnableLockTaskMode()
                    if (!batteryExemptionRequested) {
                        batteryExemptionRequested = true
                        com.pisophone.kiosk.security.KioskPolicyManager.ensureBatteryOptimizationExemption(this@MainActivity)
                    }
                }
            }
        }
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)

        handleSetupIntent(intent)
        applyKioskWindowFlags()
        hideSystemBars()
        checkOverlayPermission()
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)
        checkDeviceOwner()

        setContent {
            PisoPhoneLauncherTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchedEffect(Unit) {
                        checkDeviceOwner()
                        checkOverlayPermission()
                        applyKioskWindowFlags()
                        hideSystemBars()
                        dismissKeyguard()
                    }
                    LauncherScreen(
                        apps = appsList,
                        onAppClick = { appInfo ->
                            AppLauncher.launchApp(this@MainActivity, appInfo.packageName)
                        },
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyKioskWindowFlags()
            hideSystemBars()
            dismissKeyguard()
        } else {
            KioskSecurity.collapseStatusBar(this)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        hideSystemBars()
        dismissKeyguard()
        KioskSecurity.collapseStatusBar(this)
        checkOverlayPermission()
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)
        checkDeviceOwner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupIntent(intent)
        applyKioskWindowFlags()
        hideSystemBars()
        dismissKeyguard()
    }

    private fun handleSetupIntent(intent: Intent?) {
        if (intent == null) return
        val secret = intent.getStringExtra("setup_secret")
            ?: intent.getStringExtra("secret")
            ?: intent.getStringExtra("shared_secret")
        val mac = intent.getStringExtra("setup_mac")
            ?: intent.getStringExtra("esp32_mac")
            ?: intent.getStringExtra("mac")
            ?: intent.getStringExtra("box_mac")
        val slot = intent.getIntExtra("setup_slot", intent.getIntExtra("slot", -1))
        val name = intent.getStringExtra("setup_name")
            ?: intent.getStringExtra("name")
            ?: intent.getStringExtra("alias")
        val activate = intent.getBooleanExtra("activate", intent.hasExtra("setup_secret") || intent.hasExtra("secret") || intent.hasExtra("setup_mac"))
        val hasProvisioningData = !secret.isNullOrBlank() || !mac.isNullOrBlank() || slot > 0
        if (!hasProvisioningData && !activate) return

        // MainActivity is exported, so any app could send these extras. Same rule as the
        // CONFIGURE_ESP32 / ACTIVATE broadcasts: free only during the first-setup window,
        // otherwise an admin PIN or the shared secret is required.
        if (!isSetupIntentAuthorized(intent, secret)) {
            Log.w(TAG, "Rejected unauthorized setup intent (MAC/slot/secret change).")
            return
        }

        if (hasProvisioningData) {
            android.util.Log.i("MainActivity", "Direct Provisioning setup parameters received: MAC=$mac, Slot=$slot, SecretConfigured=${!secret.isNullOrBlank()}")
            KioskService.configureMasterBox(
                context = this,
                mac = mac ?: "",
                slot = slot,
                secret = secret,
                name = name,
            )
        }

        if (activate) {
            KioskActivationManager.recordDeviceIdentity(this)
            KioskActivationManager.setPairingCompleted(this, true)
            try {
                val serviceIntent = Intent(this, KioskService::class.java)
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to start KioskService on setup: ${e.message}")
            }
        }
    }

    private fun isSetupIntentAuthorized(intent: Intent, secret: String?): Boolean {
        val paired = KioskActivationManager.isPairingCompleted(this) || KioskSecurity.isProvisioned(this)
        if (!paired) {
            // The WebADB installer launches this activity right after install, before the
            // service has opened the setup window.
            KioskActivationManager.startSetupWindow(this)
            if (KioskActivationManager.isSetupModeActive(this)) return true
        }
        val pin = intent.getStringExtra("pin") ?: intent.getStringExtra("admin_pin")
        if (!pin.isNullOrBlank() && KioskSecurity.verifyAdminPin(this, pin.trim())) return true
        if (!secret.isNullOrBlank() &&
            KioskSecurity.constantTimeEquals(secret.trim(), KioskSecurity.getSharedSecret(this).trim())
        ) {
            return true
        }
        return false
    }

    private fun dismissKeyguard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                km?.requestDismissKeyguard(
                    this,
                    object : android.app.KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            super.onDismissSucceeded()
                            hideSystemBars()
                        }
                    },
                )
            }
            KioskSecurity.dismissKeyguard(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyKioskWindowFlags() {
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideSystemBars() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun tryEnableLockTaskMode() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (dpm != null && dpm.isDeviceOwnerApp(packageName) && dpm.isLockTaskPermitted(packageName)) {
                if (am != null && am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask()
                    isLockTaskActive = true
                }
            } else {
                isLockTaskActive = false
            }
        } catch (e: Exception) {
            isLockTaskActive = false
        }
    }

    private fun checkOverlayPermission() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        if (hasOverlayPermission) {
            try {
                val intent = Intent(this, KioskService::class.java)
                startForegroundService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to start KioskService from checkOverlayPermission: ${e.message}")
            }
        }
    }

    private var lastKnownAppCount = -1

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val intent =
                Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            val resolveInfoList = pm.queryIntentActivities(intent, 0)

            // Optimization: Skip heavy bitmap rendering if app list hasn't changed
            if (appsList.isNotEmpty() && resolveInfoList.size == lastKnownAppCount) {
                return@launch
            }
            lastKnownAppCount = resolveInfoList.size

            val hiddenApps = KioskSecurity.getHiddenApps(this@MainActivity)

            val apps =
                resolveInfoList.mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo.packageName
                    if (pkgName == packageName) return@mapNotNull null

                    if (hiddenApps.contains(pkgName) ||
                        pkgName.startsWith("com.android.settings.") ||
                        (pkgName.contains(".settings") && !pkgName.contains("game"))
                    ) {
                        return@mapNotNull null
                    }

                    val appName = try {
                        resolveInfo.loadLabel(pm).toString()
                    } catch (_: Throwable) {
                        pkgName
                    }

                    val imgBitmap = try {
                        val iconDrawable = resolveInfo.activityInfo.loadIcon(pm)
                        val w = 96
                        val h = 96
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        iconDrawable.setBounds(0, 0, w, h)
                        iconDrawable.draw(canvas)
                        bmp.asImageBitmap()
                    } catch (_: Throwable) {
                        null
                    }

                    AppInfo(
                        name = appName,
                        packageName = pkgName,
                        bitmap = imgBitmap,
                    )
                }
                    .distinctBy { it.packageName }
                    .sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                appsList = apps
            }
        }
    }
}
