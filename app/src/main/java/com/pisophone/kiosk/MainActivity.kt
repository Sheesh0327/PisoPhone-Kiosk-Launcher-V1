package com.pisophone.kiosk
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.drawable.toBitmap
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
import com.pisophone.kiosk.ui.ActivationCelebrationDialog
import com.pisophone.kiosk.ui.theme.PisoPhoneLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
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
                    var showCelebration by remember { mutableStateOf(false) }
                    var licenseInfo by remember { mutableStateOf(HardwareLockManager.getLicenseInfo(this@MainActivity)) }

                    val licenseUpdateVer by HardwareLockManager.licenseUpdateVersion.collectAsState()
                    val celebrationTrigger by HardwareLockManager.activationCelebrationEvent.collectAsState()

                    LaunchedEffect(licenseUpdateVer) {
                        isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                        licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                    }

                    LaunchedEffect(celebrationTrigger) {
                        if (celebrationTrigger) {
                            isAppAllowed = HardwareLockManager.isAppAllowedToRun(this@MainActivity)
                            licenseInfo = HardwareLockManager.getLicenseInfo(this@MainActivity)
                            showCelebration = true
                        }
                    }

                    if (!isAppAllowed) {
                        HardwareLockScreen(
                            onRebindSuccess = {
                                isAppAllowed = true
                            },
                        )
                    } else if (!isDeviceOwner) {
                        DeviceOwnerScreen(
                            onCheckAgain = { checkDeviceOwner() },
                        )
                    } else {
                        val hasPerm = hasOverlayPermission
                        if (!hasPerm) {
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

                    val iconDrawable = resolveInfo.activityInfo.loadIcon(pm)
                    val imgBitmap =
                        try {
                            iconDrawable.toBitmap().asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    AppInfo(
                        name = resolveInfo.loadLabel(pm).toString(),
                        packageName = pkgName,
                        icon = iconDrawable,
                        bitmap = imgBitmap,
                    )
                }.sortedBy { it.name }

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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
) {
    data class CleanTheme(
        val name: String,
        val bg: Color,
        val surface: Color,
        val primary: Color,
        val onPrimary: Color,
        val textPrimary: Color,
        val textSecondary: Color,
        val border: Color,
        val isDark: Boolean
    )

    val currentTheme = CleanTheme(
        name = "Obsidian Emerald",
        bg = Color(0xFF060B14),
        surface = Color(0xFF0F172A),
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF020617),
        textPrimary = Color(0xFFF8FAFC),
        textSecondary = Color(0xFF94A3B8),
        border = Color(0xFF1E293B),
        isDark = true
    )

    @Composable
    fun TimeDateDisplay(currentTheme: CleanTheme) {
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
                .padding(horizontal = 20.dp, vertical = 14.dp)
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
            Spacer(modifier = Modifier.height(10.dp))
            TimeDateDisplay(currentTheme)
        }
        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        val categories = listOf("Social Media", "Gaming", "Entertainment", "Browsing", "Shopping", "Utilities", "Other Apps")
        
        val appsByCategory = remember(apps) {
            val map = mutableMapOf<String, MutableList<AppInfo>>()
            categories.forEach { map[it] = mutableListOf() }
            
            apps.forEach { app ->
                val name = app.name.lowercase()
                val pkg = app.packageName.lowercase()
                
                val category = when {
                    pkg.contains("facebook") || pkg.contains("twitter") || pkg.contains("instagram") || pkg.contains("tiktok") || pkg.contains("snapchat") || pkg.contains("social") || pkg.contains("discord") || pkg.contains("reddit") || pkg.contains("telegram") || pkg.contains("whatsapp") || pkg.contains("messenger") || pkg.contains("viber") || name.contains("facebook") || name.contains("instagram") || name.contains("tiktok") || name.contains("messenger") -> "Social Media"
                    pkg.contains("game") || pkg.contains("unity") || pkg.contains("epic") || pkg.contains("roblox") || pkg.contains("minecraft") || pkg.contains("mobilelegends") || pkg.contains("pubg") || pkg.contains("tencent") || pkg.contains("codm") || pkg.contains("supercell") || name.contains("game") || name.contains("roblox") -> "Gaming"
                    pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("hulu") || pkg.contains("spotify") || pkg.contains("video") || pkg.contains("music") || pkg.contains("tv") || pkg.contains("media") || pkg.contains("player") || name.contains("youtube") || name.contains("netflix") || name.contains("tv") || name.contains("player") || name.contains("music") -> "Entertainment"
                    pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("opera") || pkg.contains("edge") || pkg.contains("brave") || pkg.contains("duckduckgo") || name.contains("browser") || name.contains("chrome") -> "Browsing"
                    pkg.contains("shop") || pkg.contains("amazon") || pkg.contains("ebay") || pkg.contains("lazada") || pkg.contains("shopee") || pkg.contains("zalora") || pkg.contains("shein") || pkg.contains("alibaba") || pkg.contains("aliexpress") || name.contains("shop") || name.contains("lazada") || name.contains("shopee") || name.contains("amazon") -> "Shopping"
                    pkg.contains("calc") || pkg.contains("clock") || pkg.contains("calendar") || pkg.contains("camera") || pkg.contains("gallery") || pkg.contains("settings") || pkg.contains("util") || pkg.contains("file") || pkg.contains("tools") || pkg.contains("notes") || pkg.contains("maps") || pkg.contains("weather") -> "Utilities"
                    else -> "Other Apps"
                }
                map[category]?.add(app)
            }
            map
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            categories.forEach { category ->
                val categoryApps = appsByCategory[category]
                if (!categoryApps.isNullOrEmpty()) {
                    item {
                        Text(
                            text = category,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = currentTheme.textPrimary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(categoryApps) { app ->
                                Column(
                                    modifier = Modifier
                                        .width(76.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onAppClick(app) }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (app.bitmap != null) {
                                        Image(
                                            bitmap = app.bitmap,
                                            contentDescription = app.name,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .background(currentTheme.surface, RoundedCornerShape(14.dp))
                                                .border(1.dp, currentTheme.border, RoundedCornerShape(14.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Apps,
                                                contentDescription = null,
                                                tint = currentTheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
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
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
