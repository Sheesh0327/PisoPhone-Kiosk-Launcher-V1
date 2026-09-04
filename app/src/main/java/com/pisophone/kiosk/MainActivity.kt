package com.pisophone.kiosk
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pisophone.kiosk.receiver.KioskWatchdogReceiver
import com.pisophone.kiosk.security.HardwareLockManager
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.util.AppLauncher
import com.pisophone.kiosk.ui.DeviceOwnerScreen
import com.pisophone.kiosk.ui.HardwareLockScreen
import com.pisophone.kiosk.ui.ProvisioningScreen
import com.pisophone.kiosk.ui.TutorialScreen
import com.pisophone.kiosk.ui.ActivationCelebrationDialog
import com.pisophone.kiosk.ui.theme.PisoPhoneLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable? = null,
    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
)

class MainActivity : ComponentActivity() {
    private var appsList by mutableStateOf<List<AppInfo>>(emptyList())
    private var hasOverlayPermission by mutableStateOf(false)
    private var isLockTaskActive by mutableStateOf(false)
    private var isDeviceOwner by mutableStateOf(false)
    private var strictPoliciesApplied = false

    private fun checkDeviceOwner() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        if (isDeviceOwner) {
            if (!strictPoliciesApplied) {
                com.pisophone.kiosk.security.KioskSecurity.applyStrictKioskPolicies(this)
                strictPoliciesApplied = true
            }
            tryEnableLockTaskMode()
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
                    var isProvisioned by remember { mutableStateOf(KioskSecurity.getAdminPin(this@MainActivity) != "1234") }
                    var showCelebration by remember { mutableStateOf(false) }
                    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(this@MainActivity)) }

                    val licenseUpdateVer by HardwareLockManager.licenseUpdateVersion.collectAsState()
                    val celebrationTrigger by HardwareLockManager.activationCelebrationEvent.collectAsState()

                    LaunchedEffect(licenseUpdateVer) {
                        isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                        isTutorialCompleted = HardwareLockManager.isTutorialCompleted(this@MainActivity)
                        licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                    }

                    LaunchedEffect(celebrationTrigger) {
                        if (celebrationTrigger) {
                            isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                            licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                            showCelebration = true
                        }
                    }

                    if (!isTutorialCompleted) {
                        TutorialScreen(
                            onCompleteTutorial = {
                                HardwareLockManager.setTutorialCompleted(this@MainActivity, true)
                                isTutorialCompleted = true
                            }
                        )
                    } else if (!isAppAllowed) {
                        HardwareLockScreen(
                            onRebindSuccess = {
                                isAppAllowed = true
                            },
                        )
                    } else if (!isDeviceOwner) {
                        DeviceOwnerScreen(
                            onCheckAgain = { checkDeviceOwner() },
                        )
                    } else if (!isProvisioned) {
                        ProvisioningScreen(onComplete = {
                            isProvisioned = true
                        })
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
        if (hasOverlayPermission) {
            val intent = Intent(this, KioskService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val intent =
                Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            val resolveInfoList = pm.queryIntentActivities(intent, 0)
            val hiddenApps = KioskSecurity.getHiddenApps(this@MainActivity)

            val apps =
                resolveInfoList.mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo.packageName
                    if (pkgName == packageName) return@mapNotNull null

                    // Hide if in configured hidden apps or system settings
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

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF060B14),
                            Color(0xFF0B1120),
                            Color(0xFF0F172A)
                        )
                    )
                )
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Tagline Pill
        Surface(
            color = Color(0x1A10B981),
            shape = RoundedCornerShape(100.dp),
            border = BorderStroke(1.dp, Color(0x4D10B981)),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF10B981), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "SYSTEM AUTHORIZATION",
                    color = Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .size(88.dp)
                    .background(Color(0x1A10B981), shape = CircleShape)
                    .border(1.dp, Color(0x4D10B981), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = "Security Permission",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Overlay Permission Required",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFF8FAFC)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "PisoPhone Kiosk requires permission to display over other apps to lock the terminal securely and manage gaming sessions reliably.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF94A3B8), lineHeight = 22.sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color(0xFF020617)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
            modifier = Modifier.testTag("grant_permission_button"),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Overlay Permission", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

object PinnedSlotsManager {
    private const val PREFS_NAME = "kiosk_pinned_slots_prefs"
    private const val KEY_PREFIX = "pinned_slot_"

    fun getPinnedSlots(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (0 until 4).map { index ->
            sp.getString("$KEY_PREFIX$index", "") ?: ""
        }
    }

    fun setPinnedSlot(context: Context, slotIndex: Int, packageName: String) {
        if (slotIndex in 0..3) {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString("$KEY_PREFIX$slotIndex", packageName).apply()
        }
    }

    fun clearPinnedSlot(context: Context, slotIndex: Int) {
        if (slotIndex in 0..3) {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().remove("$KEY_PREFIX$slotIndex").apply()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
) {
    val context = LocalContext.current
    val currentTheme = remember {
        object {
            val bg = Color(0xFF060B14)
            val surface = Color(0xFF0F172A)
            val cardBg = Color(0xFF0D1527)
            val primary = Color(0xFF10B981)
            val primaryLight = Color(0xFF34D399)
            val onPrimary = Color(0xFF020617)
            val textPrimary = Color(0xFFF8FAFC)
            val textSecondary = Color(0xFF94A3B8)
            val textMuted = Color(0xFF64748B)
            val border = Color(0xFF1E293B)
            val borderEmerald = Color(0x4010B981)
        }
    }

    // Pinned slots state (4 slots)
    var pinnedSlots by remember { mutableStateOf(PinnedSlotsManager.getPinnedSlots(context)) }
    var slotToPinIndex by remember { mutableStateOf<Int?>(null) }
    var selectedPinnedSlotForOptions by remember { mutableStateOf<Pair<Int, AppInfo>?>(null) }
    var appToPinFromDrawer by remember { mutableStateOf<AppInfo?>(null) }

    // Search query state for standard app drawer
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val query = searchQuery.trim().lowercase()
            apps.filter { it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        }
    }

    @Composable
    fun TimeDateDisplay() {
        var time by remember { mutableStateOf("") }
        var date by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
            while (true) {
                val now = Date()
                time = timeFormat.format(now)
                date = dateFormat.format(now)
                delay(1000)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = time,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = currentTheme.textPrimary,
                letterSpacing = (-0.5).sp,
            )
            Text(
                text = date,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = currentTheme.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.bg)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Header Row with Website-style Branding & Clock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // PisoPhone Branding (Matches website navigation bar)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "PisoPhone",
                        tint = currentTheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Row {
                        Text(
                            text = "PISO",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = currentTheme.textPrimary
                        )
                        Text(
                            text = "PHONE",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = currentTheme.primary
                        )
                    }
                    // Status Pill
                    Surface(
                        color = currentTheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, currentTheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(currentTheme.primary, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "KIOSK",
                                color = currentTheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TimeDateDisplay()
        }

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // Pinned Apps / Quick Launch Section (4 Slots)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Pinned Apps",
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "PINNED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = currentTheme.textSecondary
                )
            }

            // 4 Top Slots Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (slotIndex in 0 until 4) {
                    val pkgName = pinnedSlots.getOrElse(slotIndex) { "" }
                    val pinnedApp = apps.find { it.packageName == pkgName }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(88.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (pinnedApp != null) currentTheme.cardBg else currentTheme.surface.copy(alpha = 0.45f)
                            )
                            .border(
                                width = if (pinnedApp != null) 1.2.dp else 1.dp,
                                color = if (pinnedApp != null) currentTheme.borderEmerald else currentTheme.border.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (pinnedApp != null) {
                                        onAppClick(pinnedApp)
                                    } else {
                                        slotToPinIndex = slotIndex
                                    }
                                },
                                onLongClick = {
                                    if (pinnedApp != null) {
                                        selectedPinnedSlotForOptions = Pair(slotIndex, pinnedApp)
                                    } else {
                                        slotToPinIndex = slotIndex
                                    }
                                }
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (pinnedApp != null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (pinnedApp.bitmap != null) {
                                    Image(
                                        bitmap = pinnedApp.bitmap,
                                        contentDescription = pinnedApp.name,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(currentTheme.surface, RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.SportsEsports,
                                            contentDescription = null,
                                            tint = currentTheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pinnedApp.name,
                                    color = currentTheme.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }

                            // Small Pin badge top-right
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(13.dp)
                                    .background(Color(0xFF059669), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.PushPin,
                                    contentDescription = "Pinned",
                                    tint = Color.White,
                                    modifier = Modifier.size(8.dp)
                                )
                            }
                        } else {
                            // Minimal Empty Slot UI without descriptive text
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(0x1510B981), CircleShape)
                                    .border(1.dp, Color(0x3310B981), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Add Pinned App",
                                    tint = currentTheme.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.4f), thickness = 1.dp)

        // Search Bar & Drawer Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Search games & apps...",
                        color = currentTheme.textMuted,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = currentTheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = currentTheme.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = currentTheme.surface,
                    unfocusedContainerColor = currentTheme.surface.copy(alpha = 0.7f),
                    focusedBorderColor = currentTheme.primary,
                    unfocusedBorderColor = currentTheme.border,
                    focusedTextColor = currentTheme.textPrimary,
                    unfocusedTextColor = currentTheme.textPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = currentTheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ALL APPS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = currentTheme.textPrimary
                    )
                }
                Text(
                    text = "${filteredApps.size} apps available",
                    fontSize = 11.sp,
                    color = currentTheme.textMuted
                )
            }
        }

        // Standard App Drawer Grid (4 columns, smooth scrolling, reliable layout)
        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = currentTheme.textMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No apps match \"$searchQuery\"",
                        color = currentTheme.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = {
                                    appToPinFromDrawer = app
                                }
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (app.bitmap != null) {
                            Image(
                                bitmap = app.bitmap,
                                contentDescription = app.name,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(currentTheme.surface, RoundedCornerShape(14.dp))
                                    .border(1.dp, currentTheme.border, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Apps,
                                    contentDescription = null,
                                    tint = currentTheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = app.name,
                            color = currentTheme.textPrimary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // Modal Dialog: Select App to Pin to a specific slot
    if (slotToPinIndex != null) {
        val targetSlot = slotToPinIndex!!
        var pickerSearchQuery by remember { mutableStateOf("") }
        val pickerApps = remember(apps, pickerSearchQuery) {
            if (pickerSearchQuery.isBlank()) {
                apps
            } else {
                val q = pickerSearchQuery.trim().lowercase()
                apps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            }
        }

        Dialog(
            onDismissRequest = { slotToPinIndex = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xDD020617))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, currentTheme.borderEmerald, RoundedCornerShape(24.dp)),
                    color = currentTheme.surface,
                    shadowElevation = 24.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Pin App to Slot ${targetSlot + 1}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = currentTheme.textPrimary
                                )
                                Text(
                                    text = "Select an app or game for quick access",
                                    fontSize = 12.sp,
                                    color = currentTheme.textSecondary
                                )
                            }

                            IconButton(
                                onClick = { slotToPinIndex = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close",
                                    tint = currentTheme.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Picker Search Input
                        OutlinedTextField(
                            value = pickerSearchQuery,
                            onValueChange = { pickerSearchQuery = it },
                            placeholder = { Text("Filter apps...", color = currentTheme.textMuted, fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = currentTheme.primary, modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = currentTheme.bg,
                                unfocusedContainerColor = currentTheme.bg,
                                focusedBorderColor = currentTheme.primary,
                                unfocusedBorderColor = currentTheme.border,
                                focusedTextColor = currentTheme.textPrimary,
                                unfocusedTextColor = currentTheme.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pickerApps, key = { it.packageName }) { app ->
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable {
                                            PinnedSlotsManager.setPinnedSlot(context, targetSlot, app.packageName)
                                            pinnedSlots = PinnedSlotsManager.getPinnedSlots(context)
                                            Toast.makeText(context, "${app.name} pinned to Slot ${targetSlot + 1}", Toast.LENGTH_SHORT).show()
                                            slotToPinIndex = null
                                        },
                                    color = currentTheme.cardBg,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, currentTheme.border)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (app.bitmap != null) {
                                            Image(
                                                bitmap = app.bitmap,
                                                contentDescription = app.name,
                                                modifier = Modifier
                                                    .size(46.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                            )
                                        } else {
                                            Icon(
                                                Icons.Filled.Apps,
                                                contentDescription = null,
                                                tint = currentTheme.primary,
                                                modifier = Modifier.size(46.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = app.name,
                                            color = currentTheme.textPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Dialog: Options for an already Pinned Slot
    if (selectedPinnedSlotForOptions != null) {
        val (slotIdx, app) = selectedPinnedSlotForOptions!!

        AlertDialog(
            onDismissRequest = { selectedPinnedSlotForOptions = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (app.bitmap != null) {
                        Image(
                            bitmap = app.bitmap,
                            contentDescription = app.name,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Text(
                        text = "Slot ${slotIdx + 1}: ${app.name}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textPrimary
                    )
                }
            },
            text = {
                Text(
                    text = "Manage this pinned slot:",
                    fontSize = 13.sp,
                    color = currentTheme.textSecondary
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            selectedPinnedSlotForOptions = null
                            slotToPinIndex = slotIdx
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.cardBg, contentColor = currentTheme.primaryLight),
                        border = BorderStroke(1.dp, currentTheme.border)
                    ) {
                        Text("Change App", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            PinnedSlotsManager.clearPinnedSlot(context, slotIdx)
                            pinnedSlots = PinnedSlotsManager.getPinnedSlots(context)
                            Toast.makeText(context, "Unpinned Slot ${slotIdx + 1}", Toast.LENGTH_SHORT).show()
                            selectedPinnedSlotForOptions = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.2f), contentColor = Color(0xFFF87171)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unpin", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedPinnedSlotForOptions = null
                    onAppClick(app)
                }) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch", color = currentTheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = currentTheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Modal Dialog: Pin an App selected from the Drawer to a specific slot
    if (appToPinFromDrawer != null) {
        val app = appToPinFromDrawer!!

        AlertDialog(
            onDismissRequest = { appToPinFromDrawer = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.bitmap != null) {
                        Image(
                            bitmap = app.bitmap,
                            contentDescription = app.name,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        )
                    }
                    Text(
                        text = "Pin ${app.name}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textPrimary
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Choose which slot to pin this app to:",
                        fontSize = 13.sp,
                        color = currentTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 0 until 4) {
                            Button(
                                onClick = {
                                    PinnedSlotsManager.setPinnedSlot(context, i, app.packageName)
                                    pinnedSlots = PinnedSlotsManager.getPinnedSlots(context)
                                    Toast.makeText(context, "${app.name} pinned to Slot ${i + 1}", Toast.LENGTH_SHORT).show()
                                    appToPinFromDrawer = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = currentTheme.cardBg,
                                    contentColor = currentTheme.primary
                                ),
                                border = BorderStroke(1.dp, currentTheme.borderEmerald),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Slot ${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { appToPinFromDrawer = null }) {
                    Text("Cancel", color = currentTheme.textSecondary)
                }
            },
            containerColor = currentTheme.surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
