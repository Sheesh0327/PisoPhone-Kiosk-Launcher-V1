package com.pisophone.kiosk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.pisophone.kiosk.receiver.KioskAdminActionReceiver
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.*
import com.pisophone.kiosk.ui.theme.PisoPhoneLauncherTheme
import com.pisophone.kiosk.util.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var appsList by mutableStateOf<List<AppInfo>>(emptyList())
    private var hasOverlayPermission by mutableStateOf(false)
    private var isLockTaskActive by mutableStateOf(false)
    private var isDeviceOwner by mutableStateOf(false)
    private var strictPoliciesApplied = false

    private fun isFullySetup(): Boolean {
        return HardwareLockManager.isAppAllowedToRun(this)
    }

    private fun checkDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        isDeviceOwner = dpm?.isDeviceOwnerApp(packageName) == true
        val fullySetup = isFullySetup()
        if (isDeviceOwner && fullySetup) {
            if (!strictPoliciesApplied) {
                KioskSecurity.applyStrictKioskPolicies(this)
                strictPoliciesApplied = true
            }
            tryEnableLockTaskMode()
        }
        if (fullySetup) {
            checkOverlayPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)

        handleSetupIntent(intent)
        if (isFullySetup()) {
            applyKioskWindowFlags()
            hideSystemBars()
            checkOverlayPermission()
            checkDeviceOwner()
        }
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)

        setContent {
            PisoPhoneLauncherTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val securityVer by HardwareLockManager.securityUpdateVersion.collectAsState()
                    val fullySetup = remember(securityVer) { isFullySetup() }

                    if (!fullySetup) {
                        HardwareLockScreen(
                            onRebindSuccess = {
                                if (isFullySetup()) {
                                    checkDeviceOwner()
                                    checkOverlayPermission()
                                    applyKioskWindowFlags()
                                    hideSystemBars()
                                    dismissKeyguard()
                                    startKioskService()
                                }
                            }
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            checkDeviceOwner()
                            checkOverlayPermission()
                            applyKioskWindowFlags()
                            hideSystemBars()
                            dismissKeyguard()
                            startKioskService()
                        }
                        LauncherScreen(
                            apps = appsList,
                            onAppClick = { appInfo ->
                                AppLauncher.launchApp(this@MainActivity, appInfo.packageName)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun startKioskService() {
        if (!isFullySetup()) return
        try {
            val serviceIntent = Intent(this, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to start KioskService: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullySetup()) {
            applyKioskWindowFlags()
            hideSystemBars()
            dismissKeyguard()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFullySetup()) {
            applyKioskWindowFlags()
            hideSystemBars()
            dismissKeyguard()
            checkOverlayPermission()
            checkDeviceOwner()
        }
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)
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
            ?: KioskSecurity.getSharedSecret(this)
        val mac = intent.getStringExtra("setup_mac")
            ?: intent.getStringExtra("esp32_mac")
            ?: intent.getStringExtra("mac")
            ?: intent.getStringExtra("box_mac")
            ?: KioskSecurity.getConfiguredEsp32Mac(this)
        val ip = intent.getStringExtra("setup_ip")
            ?: intent.getStringExtra("esp32_ip")
            ?: intent.getStringExtra("ip")
            ?: KioskSecurity.getConfiguredEsp32Ip(this)
        val slot = intent.getIntExtra("setup_slot", intent.getIntExtra("slot", KioskSecurity.getAssignedBoxSlot(this)))
        val name = intent.getStringExtra("setup_name")
            ?: intent.getStringExtra("name")
            ?: intent.getStringExtra("alias")
            ?: KioskSecurity.getDeviceAlias(this)

        if (!secret.isNullOrBlank() && slot in 1..12) {
            android.util.Log.i("MainActivity", "PairingCoordinator: initiating configureAndPair for slot $slot, IP $ip, MAC $mac")
            com.pisophone.kiosk.provisioning.PairingCoordinator.configureAndPair(
                context = this,
                secret = secret,
                mac = mac ?: "",
                ip = if (!ip.isNullOrBlank()) ip else "kioskmanager.local",
                slot = slot,
                name = name
            ) { result ->
                when (result) {
                    is com.pisophone.kiosk.provisioning.PairingResult.Success -> {
                        android.widget.Toast.makeText(this, "Paired to Hardware Box Slot ${result.slot}!", android.widget.Toast.LENGTH_SHORT).show()
                        startKioskService()
                    }
                    is com.pisophone.kiosk.provisioning.PairingResult.OccupiedSlot -> {
                        android.widget.Toast.makeText(this, "Pairing Failed: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                    is com.pisophone.kiosk.provisioning.PairingResult.AuthFailed -> {
                        android.widget.Toast.makeText(this, "Authentication Failed: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                    is com.pisophone.kiosk.provisioning.PairingResult.ConfigurationError -> {
                        android.widget.Toast.makeText(this, "Configuration Error: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                    is com.pisophone.kiosk.provisioning.PairingResult.NetworkError -> {
                        android.widget.Toast.makeText(this, "Network Error: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun dismissKeyguard() {
        if (!isFullySetup()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                km?.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        hideSystemBars()
                    }
                })
            }
            KioskSecurity.dismissKeyguard(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyKioskWindowFlags() {
        if (!isFullySetup()) return
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
        if (!isFullySetup()) return
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
        if (!isFullySetup()) {
            isLockTaskActive = false
            return
        }
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

    fun stopLockTaskMode() {
        try {
            stopLockTask()
            isLockTaskActive = false
        } catch (e: Exception) {
            isLockTaskActive = false
        }
    }

    private fun checkOverlayPermission() {
        hasOverlayPermission = Settings.canDrawOverlays(this)
        if (hasOverlayPermission && isFullySetup()) {
            val intent = Intent(this, KioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
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
                        pkgName == "com.android.settings" ||
                        pkgName.startsWith("com.android.settings.") ||
                        pkgName == "com.google.android.settings" ||
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
