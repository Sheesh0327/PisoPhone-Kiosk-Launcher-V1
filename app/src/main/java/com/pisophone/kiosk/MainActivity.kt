package com.pisophone.kiosk

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
                            val deviceContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                            val prefs = deviceContext.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
                            var isCardMode by remember { mutableStateOf(prefs.getBoolean("card_mode", false)) }
                            var savedGames by remember { mutableStateOf(prefs.getStringSet("saved_games", emptySet()) ?: emptySet()) }
                            var themeIndex by remember { mutableStateOf(prefs.getInt("theme_index", 0)) }

                            LauncherScreen(
                                apps = appsList,
                                onAppClick = { appInfo ->
                                    AppLauncher.launchApp(this@MainActivity, appInfo.packageName)
                                },
                                isCardMode = isCardMode,
                                onToggleMode = {
                                    val newVal = !isCardMode
                                    isCardMode = newVal
                                    prefs.edit().putBoolean("card_mode", newVal).apply()
                                },
                                savedGames = savedGames,
                                onToggleGame = { packageName ->
                                    val newGames = savedGames.toMutableSet()
                                    if (newGames.contains(packageName)) {
                                        newGames.remove(packageName)
                                    } else {
                                        newGames.add(packageName)
                                    }
                                    savedGames = newGames
                                    prefs.edit().putStringSet("saved_games", newGames).apply()
                                },
                                themeIndex = themeIndex,
                                onCycleTheme = {
                                    val nextTheme = (themeIndex + 1) % 5
                                    themeIndex = nextTheme
                                    prefs.edit().putInt("theme_index", nextTheme).apply()
                                },
                            )
                        }
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
    isCardMode: Boolean,
    onToggleMode: () -> Unit,
    savedGames: Set<String>,
    onToggleGame: (String) -> Unit,
    themeIndex: Int = 0,
    onCycleTheme: () -> Unit = {},
) {
    var showGameDropdown by remember { mutableStateOf(false) }

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

    val cleanThemes = listOf(
        // 0: Obsidian Emerald (Signature Website Look)
        CleanTheme(
            name = "Obsidian Emerald",
            bg = Color(0xFF060B14),
            surface = Color(0xFF0F172A),
            primary = Color(0xFF10B981),
            onPrimary = Color(0xFF020617),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            border = Color(0xFF1E293B),
            isDark = true
        ),
        // 1: Emerald Matrix
        CleanTheme(
            name = "Emerald Matrix",
            bg = Color(0xFF04120B),
            surface = Color(0xFF0C2B1D),
            primary = Color(0xFF34D399),
            onPrimary = Color(0xFF020617),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF6EE7B7),
            border = Color(0xFF164E35),
            isDark = true
        ),
        // 2: Cyber Slate
        CleanTheme(
            name = "Cyber Slate",
            bg = Color(0xFF090D16),
            surface = Color(0xFF131E30),
            primary = Color(0xFF38BDF8),
            onPrimary = Color(0xFF020617),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            border = Color(0xFF1E293B),
            isDark = true
        ),
        // 3: Stealth Noir
        CleanTheme(
            name = "Stealth Noir",
            bg = Color(0xFF000000),
            surface = Color(0xFF111827),
            primary = Color(0xFF10B981),
            onPrimary = Color(0xFF020617),
            textPrimary = Color(0xFFF9FAFB),
            textSecondary = Color(0xFF6B7280),
            border = Color(0xFF1F2937),
            isDark = true
        )
    )

    val currentTheme = cleanThemes[themeIndex % cleanThemes.size]

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    var time by remember { mutableStateOf(timeFormat.format(Date())) }
    var date by remember { mutableStateOf(dateFormat.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            time = timeFormat.format(now)
            date = dateFormat.format(now)
            delay(1000)
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

                // Header Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onCycleTheme,
                        modifier = Modifier
                            .size(38.dp)
                            .background(currentTheme.surface, CircleShape)
                            .border(1.dp, currentTheme.border, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Palette,
                            contentDescription = "Change Theme",
                            tint = currentTheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = onToggleMode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentTheme.surface,
                            contentColor = currentTheme.textPrimary
                        ),
                        border = BorderStroke(1.dp, currentTheme.border),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            if (isCardMode) Icons.Filled.Apps else Icons.Filled.SportsEsports,
                            contentDescription = null,
                            tint = currentTheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isCardMode) "All Apps" else "Featured",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Time & Date Display
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

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // Main Content Area
        if (isCardMode) {
            // Featured Apps (Cards)
            val featuredApps = apps.filter { savedGames.contains(it.packageName) }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Featured Applications",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = currentTheme.textPrimary
                    )
                    Surface(
                        color = currentTheme.surface,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, currentTheme.border)
                    ) {
                        Text(
                            text = "${featuredApps.size}",
                            color = currentTheme.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Box {
                    OutlinedButton(
                        onClick = { showGameDropdown = !showGameDropdown },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = currentTheme.primary
                        ),
                        border = BorderStroke(1.dp, currentTheme.primary.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    
                    DropdownMenu(
                        expanded = showGameDropdown,
                        onDismissRequest = { showGameDropdown = false },
                        modifier = Modifier
                            .background(currentTheme.surface)
                            .border(1.dp, currentTheme.border, RoundedCornerShape(12.dp))
                            .heightIn(max = 400.dp)
                            .width(280.dp)
                    ) {
                        apps.forEach { app ->
                            val isPinned = savedGames.contains(app.packageName)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (app.bitmap != null) {
                                            Image(
                                                bitmap = app.bitmap,
                                                contentDescription = app.name,
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }
                                        Text(
                                            text = app.name,
                                            color = currentTheme.textPrimary,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isPinned) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = currentTheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = { onToggleGame(app.packageName) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            
            if (featuredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = currentTheme.surface),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, currentTheme.border)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(currentTheme.primary.copy(alpha = 0.12f), CircleShape)
                                    .border(1.dp, currentTheme.primary.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = currentTheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No Featured Apps Selected",
                                color = currentTheme.textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Tap 'Manage' above to select games and apps for quick carousel access.",
                                color = currentTheme.textSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                val pagerState = rememberPagerState(pageCount = { featuredApps.size })
                
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    pageSpacing = 20.dp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val app = featuredApps[page]
                    val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                    val scale = lerp(start = 0.90f, stop = 1.0f, fraction = 1f - pageOffset.coerceIn(0f, 1f))
                    val alpha = lerp(start = 0.7f, stop = 1.0f, fraction = 1f - pageOffset.coerceIn(0f, 1f))
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.9f)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { onAppClick(app) },
                        colors = CardDefaults.cardColors(containerColor = currentTheme.surface),
                        border = BorderStroke(1.dp, if (pageOffset < 0.5f) currentTheme.primary.copy(alpha = 0.4f) else currentTheme.border),
                        elevation = CardDefaults.cardElevation(if (pageOffset < 0.5f) 8.dp else 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (app.bitmap != null) {
                                Image(
                                    bitmap = app.bitmap,
                                    contentDescription = app.name,
                                    modifier = Modifier
                                        .size(108.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .border(1.dp, currentTheme.border, RoundedCornerShape(22.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = app.name,
                                color = currentTheme.textPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ready to Play",
                                color = currentTheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                // Launch Button (Styled like website primary CTA)
                val focusedApp = featuredApps.getOrNull(pagerState.currentPage)
                if (focusedApp != null) {
                    Button(
                        onClick = { onAppClick(focusedApp) },
                        modifier = Modifier
                            .padding(horizontal = 32.dp, vertical = 20.dp)
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = currentTheme.primary,
                            contentColor = currentTheme.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Launch ${focusedApp.name}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // All Apps Mode (Grid)
            val allApps = apps.filter { !savedGames.contains(it.packageName) }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "All Applications",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = currentTheme.textPrimary
                )
                Surface(
                    color = currentTheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, currentTheme.border)
                ) {
                    Text(
                        text = "${allApps.size}",
                        color = currentTheme.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(allApps) { app ->
                    Card(
                        modifier = Modifier
                            .padding(6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onAppClick(app) },
                        colors = CardDefaults.cardColors(containerColor = currentTheme.surface.copy(alpha = 0.7f)),
                        border = BorderStroke(1.dp, currentTheme.border),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (app.bitmap != null) {
                                Image(
                                    bitmap = app.bitmap,
                                    contentDescription = app.name,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(currentTheme.bg, RoundedCornerShape(14.dp)),
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
                                fontSize = 12.sp,
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
