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
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val isOwner = dpm?.isDeviceOwnerApp(packageName) == true
        val hasOverlay = Settings.canDrawOverlays(this)
        return HardwareLockManager.isTutorialCompleted(this) &&
               HardwareLockManager.isAppAllowedToRun(this) &&
               isOwner &&
               hasOverlay
    }

    private fun checkDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        val fullySetup = isFullySetup()
        if (isDeviceOwner && fullySetup) {
            if (!strictPoliciesApplied) {
                KioskSecurity.applyStrictKioskPolicies(this)
                strictPoliciesApplied = true
            }
            tryEnableLockTaskMode()
            checkOverlayPermission()
        }
    }

    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val overlayPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            checkOverlayPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)

        applyKioskWindowFlags()
        hideSystemBars()
        checkOverlayPermission()
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)
        checkDeviceOwner()
        
        lifecycleScope.launch {
            HardwareLockManager.syncWithBackend(this@MainActivity)
        }
        
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            PisoPhoneLauncherTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isAppAllowed by remember { mutableStateOf(HardwareLockManager.isAppAllowedToRun(this@MainActivity)) }
                    var isTutorialCompleted by remember { mutableStateOf(HardwareLockManager.isTutorialCompleted(this@MainActivity)) }
                    var showCelebration by remember { mutableStateOf(false) }
                    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(this@MainActivity)) }

                    val licenseUpdateVer by HardwareLockManager.licenseUpdateVersion.collectAsState()
                    val celebrationTrigger by HardwareLockManager.activationCelebrationEvent.collectAsState()

                    LaunchedEffect(licenseUpdateVer) {
                        isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                        isTutorialCompleted = HardwareLockManager.isTutorialCompleted(this@MainActivity)
                        licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                        checkDeviceOwner()
                    }

                    LaunchedEffect(celebrationTrigger) {
                        if (celebrationTrigger) {
                            isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                            licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                            showCelebration = true
                            checkDeviceOwner()
                        }
                    }

                    if (!isTutorialCompleted) {
                        TutorialScreen(
                            onCompleteTutorial = {
                                HardwareLockManager.setTutorialCompleted(this@MainActivity, true)
                                isTutorialCompleted = true
                                checkDeviceOwner()
                            }
                        )
                    } else if (!isAppAllowed) {
                        HardwareLockScreen(
                            onRebindSuccess = {
                                isAppAllowed = true
                                checkDeviceOwner()
                            },
                        )
                    } else if (!isDeviceOwner) {
                        DeviceOwnerScreen(
                            onCheckAgain = { checkDeviceOwner() },
                        )
                    } else if (!hasOverlayPermission) {
                        PermissionScreen(onRequest = {
                            val intent =
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            overlayPermissionLauncher.launch(intent)
                        })
                    } else {
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
                            }
                        )
                    }

                    if (showCelebration) {
                        ActivationCelebrationDialog(
                            licenseInfo = licenseInfo,
                            onDismiss = {
                                showCelebration = false
                                HardwareLockManager.activationCelebrationEvent.value = false
                            }
                        )
                    }
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
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskWindowFlags()
        hideSystemBars()
        dismissKeyguard()
        checkOverlayPermission()
        loadApps()
        KioskWatchdogReceiver.scheduleWatchdog(this)
        checkDeviceOwner()
        lifecycleScope.launch {
            HardwareLockManager.syncWithBackend(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyKioskWindowFlags()
        hideSystemBars()
        dismissKeyguard()
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
