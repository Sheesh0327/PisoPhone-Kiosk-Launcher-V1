package com.pisophone.kiosk.overlay.ui

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.network.KioskWifi
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.service.AccountController
import com.pisophone.kiosk.system.AndroidKioskSystemController
import com.pisophone.kiosk.system.KioskSystemController
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.AppCloser
import com.pisophone.kiosk.util.AppLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ADMIN_HOLD_MS = 5_000L
private const val PILL_FADE_AFTER_MS = 5_000L
private const val PILL_FADED_ALPHA = 0.45f

/** Holding the finger on it for five seconds opens the owner sign-in (a short tap does [onTap]). */
private fun Modifier.adminLongPress(scope: CoroutineScope, onTap: () -> Unit = {}, onHold: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onPress = {
                val startTime = System.currentTimeMillis()
                val job = scope.launch {
                    while (isActive) {
                        if (System.currentTimeMillis() - startTime >= ADMIN_HOLD_MS) {
                            onHold()
                            break
                        }
                        delay(50L)
                    }
                }
                tryAwaitRelease()
                job.cancel()
            },
        )
    }

/**
 * The time pill and its panel (docs/design/GUI_SPEC_PISOPHONE_KIDS.md, screens D and E). Collapsed it is a small pill with the
 * time left, a level icon (a dot, an hourglass, an alert) and a note that slides in under it at 5 minutes and 1 minute. Tapped it
 * opens a panel: the time, the coin button, home, close app, clear RAM, sign out, and the volume and brightness. Holding the time
 * for five seconds still opens the owner sign-in.
 */
@Composable
fun FloatingPill(
    timeRemaining: Int,
    onInsertCoinClick: () -> Unit,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    armFailure: Esp32Responses.ArmFailure? = null,
    isArmingInProgress: Boolean = false,
    isWaiting: Boolean,
    batteryStatus: BatteryStatus = BatteryStatus(),
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
    isArenaBannerVisible: Boolean = false,
    onDismissArenaBanner: () -> Unit = {},
    systemController: KioskSystemController? = null,
    onRequestFocus: (Boolean) -> Unit = {},
    onRequestFullScreen: (Boolean) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val context = LocalContext.current
    val controller = systemController ?: remember(context) { AndroidKioskSystemController(context) }
    val effects = rememberEffectsLevel()
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(isWaiting) {
        if (isWaiting) {
            expanded = true
        }
    }

    LaunchedEffect(isArenaBannerVisible) {
        if (isArenaBannerVisible) {
            delay(3800L)
            onDismissArenaBanner()
        }
    }

    val coroutineScope = rememberCoroutineScope()

    var showUnlockedPinDialog by remember { mutableStateOf(false) }
    var showUnlockedSecurityDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showUnlockedPinDialog, showUnlockedSecurityDialog) {
        val needActive = showUnlockedPinDialog || showUnlockedSecurityDialog
        onRequestFocus(needActive)
        onRequestFullScreen(needActive)
    }

    val timeStr = formatClock(timeRemaining)
    val timeLevel = TimeLevel.of(timeRemaining)

    // the longest the session has been in this panel's life: the bar shows what is left of it
    var sessionMax by remember { mutableIntStateOf(maxOf(timeRemaining, 1)) }
    LaunchedEffect(timeRemaining) { if (timeRemaining > sessionMax) sessionMax = timeRemaining }

    // "5 minutes left" shows for six seconds; "1 minute left" stays until the time is up or more is added
    var lowNoteDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(timeLevel) {
        if (timeLevel == TimeLevel.LOW) {
            lowNoteDismissed = false
            delay(6_000L)
            lowNoteDismissed = true
        }
    }
    val showTimeNote = timeLevel == TimeLevel.CRITICAL || (timeLevel == TimeLevel.LOW && !lowNoteDismissed)

    var currentVolume by remember { mutableIntStateOf(controller.getStreamVolume()) }
    val maxVolume = remember { controller.getStreamMaxVolume() }

    var ramStats by remember { mutableStateOf(controller.getMemoryStats()) }
    fun refreshRam() {
        ramStats = controller.getMemoryStats()
    }

    val maxBrightness = AndroidKioskSystemController.MAX_BRIGHTNESS
    var currentBrightness by remember { mutableFloatStateOf(controller.getScreenBrightness()) }

    fun applyBrightness(value: Float) {
        currentBrightness = value
        onBrightnessChange(value / maxBrightness)
        controller.setScreenBrightness(value)
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5000)
            refreshRam()
        }
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxOverlayHeight = (config.screenHeightDp.dp - 24.dp).coerceAtLeast(240.dp)

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = { onDragEnd() }, onDragCancel = { onDragEnd() }) { change, dragAmount ->
            change.consume()
            onDrag(dragAmount.x, dragAmount.y)
        }
    }

    // the collapsed pill fades a little five seconds after it was last touched (or after the time level changed)
    var touchCount by remember { mutableIntStateOf(0) }
    var faded by remember { mutableStateOf(false) }
    LaunchedEffect(touchCount, timeLevel, showTimeNote, expanded) {
        faded = false
        delay(PILL_FADE_AFTER_MS)
        faded = true
    }
    val pillAlpha by animateFloatAsState(
        targetValue = if (faded && timeLevel == TimeLevel.OK && !showTimeNote) PILL_FADED_ALPHA else 1f,
        animationSpec = tween(600),
        label = "pillFade",
    )

    val isSecurityActive = showUnlockedPinDialog || showUnlockedSecurityDialog

    if (isSecurityActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) {
            if (showUnlockedPinDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .widthIn(max = 420.dp)
                        .background(TechNet.surfaceHi, RoundedCornerShape(28.dp))
                        .border(1.dp, TechNet.line, RoundedCornerShape(28.dp))
                        .padding(20.dp),
                ) {
                    FloatingPillAdminAuthCard(
                        context = context,
                        onDismiss = { showUnlockedPinDialog = false },
                        onUnlockSuccess = {
                            showUnlockedPinDialog = false
                            showUnlockedSecurityDialog = true
                        },
                    )
                }
            } else if (showUnlockedSecurityDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.92f)
                        .background(TechNet.surface, RoundedCornerShape(28.dp))
                        .border(1.dp, TechNet.line, RoundedCornerShape(28.dp))
                        .padding(12.dp),
                ) {
                    SecurityVaultView(
                        context = context,
                        onClose = { showUnlockedSecurityDialog = false },
                    )
                }
            }
        }
    } else if (isArenaBannerVisible) {
        Box(
            modifier = Modifier
                .width(if (isLandscape) 340.dp else 300.dp)
                .then(dragModifier),
        ) {
            ArenaModeBanner(
                visible = true,
                playerRole = arenaRole,
                stakeMinutes = arenaStakeMinutes,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissArenaBanner() },
            )
        }
    } else if (expanded) {
        val hasBatteryAlert = batteryStatus.alertState != BatteryAlertState.NONE
        Column(
            modifier = Modifier
                .width(if (isLandscape) 320.dp else 300.dp)
                .heightIn(max = maxOverlayHeight)
                .background(TechNet.surfaceHi, RoundedCornerShape(28.dp))
                .border(1.dp, TechNet.line, RoundedCornerShape(28.dp))
                .padding(16.dp),
        ) {
            if (isArenaMode) {
                val role = when (arenaRole) {
                    1 -> stringResource(R.string.arena_player_1)
                    2 -> stringResource(R.string.arena_player_2)
                    else -> stringResource(R.string.arena_participant)
                }
                KidsBanner(
                    accent = TechNet.brandFg,
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.banner_arena_title),
                    detail = stringResource(R.string.banner_arena_detail, role, arenaStakeMinutes),
                    horizontalInset = 0.dp,
                )
            }
            if (hasBatteryAlert) {
                BatteryAlertBanner(batteryStatus = batteryStatus, horizontalInset = 0.dp)
                Spacer(Modifier.height(4.dp))
            }

            // header: the mascot, which phone this is, and close; dragging it moves the panel
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().then(dragModifier),
            ) {
                MascotView(
                    state = if (timeLevel == TimeLevel.CRITICAL) MascotState.Worried else MascotState.Idle,
                    size = 40.dp,
                    effects = effects,
                    cheerKey = coinsInserted,
                )
                Spacer(Modifier.width(12.dp))
                var currentAlias by remember { mutableStateOf(KioskSecurity.getDeviceAlias(context)) }
                LaunchedEffect(Unit) {
                    while (isActive) {
                        currentAlias = KioskSecurity.getDeviceAlias(context)
                        delay(2000)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentAlias.ifBlank { stringResource(R.string.pill_game_space) },
                        color = TechNet.text,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.pill_time_left),
                        color = TechNet.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(
                    onClick = {
                        if (isWaiting) {
                            onDoneClick()
                        }
                        expanded = false
                    },
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pill_cd_close), tint = TechNet.text)
                }
            }

            // the time, in the colour of its level, and the bar of what is left (holding the number opens the owner sign-in)
            Text(
                text = timeStr,
                color = timeLevel.textColor(),
                style = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .adminLongPress(coroutineScope) {
                        showUnlockedPinDialog = true
                        controller.triggerHapticFeedback()
                    },
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(TechNet.line, RoundedCornerShape(3.dp))) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((timeRemaining.toFloat() / sessionMax).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(timeLevel.accentColor(), RoundedCornerShape(3.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                // the coin button, in the same states as the lock screen's
                when {
                    isWaiting && paymentTimeout > 0 && coinsInserted > 0 -> PisoButton(
                        text = context.resources.getQuantityString(R.plurals.pill_done_coins, coinsInserted, coinsInserted),
                        onClick = {
                            expanded = false
                            onDoneClick()
                        },
                        style = PisoButtonStyle.Success,
                        size = PisoButtonSize.Medium,
                    )
                    isWaiting && paymentTimeout > 0 -> PisoButton(
                        text = stringResource(R.string.pill_waiting, paymentTimeout),
                        onClick = {},
                        enabled = false,
                        size = PisoButtonSize.Medium,
                    )
                    isArmingInProgress || isWaiting -> PisoButton(
                        text = stringResource(R.string.pill_arming),
                        onClick = {},
                        enabled = false,
                        size = PisoButtonSize.Medium,
                    )
                    else -> {
                        if (armFailure != null) {
                            StatusChip(armFailure.label.lowercase().replaceFirstChar { it.uppercase() }, TechNet.dangerText, Modifier.align(Alignment.CenterHorizontally))
                        }
                        PisoButton(
                            text = stringResource(if (isEsp32Online) R.string.pill_add_coin else R.string.pill_offline),
                            onClick = { onInsertCoinClick() },
                            style = PisoButtonStyle.Gold,
                            size = PisoButtonSize.Medium,
                            enabled = isEsp32Online && armFailure == null,
                        )
                    }
                }

                // Two taps on purpose for Boost and Sign out: they drop what the customer has not saved.
                var confirmBoost by remember { mutableStateOf(false) }
                var boosting by remember { mutableStateOf(false) }
                LaunchedEffect(confirmBoost) {
                    if (confirmBoost) {
                        delay(4000L)
                        confirmBoost = false
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    PisoButton(
                        text = stringResource(R.string.pill_home),
                        onClick = {
                            expanded = false
                            AppLauncher.launchHome(context)
                        },
                        modifier = Modifier.weight(1f),
                        style = PisoButtonStyle.Tonal,
                        size = PisoButtonSize.Small,
                    )
                    PisoButton(
                        text = stringResource(
                            when {
                                boosting -> R.string.pill_boosting
                                confirmBoost -> R.string.pill_tap_again
                                else -> R.string.pill_boost
                            },
                        ),
                        onClick = {
                            if (!confirmBoost) {
                                confirmBoost = true
                            } else {
                                confirmBoost = false
                                boosting = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) { AppCloser.boost(context) }
                                    boosting = false
                                    refreshRam()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.pill_boost_done, result.closed, result.freedMb),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        style = if (confirmBoost) PisoButtonStyle.Danger else PisoButtonStyle.Tonal,
                        size = PisoButtonSize.Small,
                        enabled = !boosting,
                    )
                }
                if (confirmBoost) {
                    Text(
                        text = stringResource(R.string.pill_boost_hint),
                        color = TechNet.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // A hung app: close it for good and open it again. Two taps on purpose: what the customer has not saved is lost.
                var confirmForceClose by remember { mutableStateOf(false) }
                var forceClosing by remember { mutableStateOf(false) }
                LaunchedEffect(confirmForceClose) {
                    if (confirmForceClose) {
                        delay(4000L)
                        confirmForceClose = false
                    }
                }
                PisoButton(
                    text = stringResource(
                        when {
                            forceClosing -> R.string.pill_force_closing
                            confirmForceClose -> R.string.pill_tap_again
                            else -> R.string.pill_force_close
                        },
                    ),
                    onClick = {
                        if (!confirmForceClose) {
                            confirmForceClose = true
                        } else {
                            confirmForceClose = false
                            forceClosing = true
                            coroutineScope.launch {
                                val target = withContext(Dispatchers.IO) { AppCloser.restartTarget(context) }
                                if (target == null) {
                                    forceClosing = false
                                    Toast.makeText(context, context.getString(R.string.pill_force_close_none), Toast.LENGTH_SHORT).show()
                                } else {
                                    AppLauncher.launchHome(context) // in the background, any app can be stopped
                                    delay(400L)
                                    withContext(Dispatchers.IO) { AppCloser.forceStop(context, target) }
                                    delay(300L)
                                    val label = AppCloser.appLabel(context, target)
                                    val opened = AppLauncher.launchApp(context, target)
                                    forceClosing = false
                                    expanded = false
                                    val message = if (opened) R.string.pill_force_close_done else R.string.pill_force_close_failed
                                    Toast.makeText(context, context.getString(message, label), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    style = if (confirmForceClose) PisoButtonStyle.Danger else PisoButtonStyle.Tonal,
                    size = PisoButtonSize.Small,
                    enabled = !forceClosing,
                )
                if (confirmForceClose) {
                    Text(
                        text = stringResource(R.string.pill_force_close_hint),
                        color = TechNet.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // A quick off and on of the Wi-Fi: the radio is never left off, and the phone returns to the kiosk network.
                var wifiBusy by remember { mutableStateOf(false) }
                var lastWifiToggleMs by remember { mutableLongStateOf(0L) }
                PisoButton(
                    text = stringResource(if (wifiBusy) R.string.pill_wifi_busy else R.string.pill_wifi),
                    onClick = {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!KioskWifi.canToggle(now, lastWifiToggleMs, coinWindowOpen = isWaiting || isArmingInProgress)) {
                            Toast.makeText(context, context.getString(R.string.pill_wifi_wait), Toast.LENGTH_SHORT).show()
                        } else {
                            wifiBusy = true
                            lastWifiToggleMs = now
                            coroutineScope.launch {
                                val back = withContext(Dispatchers.IO) { KioskWifi.quickToggle(context) }
                                wifiBusy = false
                                Toast.makeText(
                                    context,
                                    context.getString(if (back) R.string.pill_wifi_ok else R.string.pill_wifi_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    style = PisoButtonStyle.Tonal,
                    size = PisoButtonSize.Small,
                    enabled = !wifiBusy && !isWaiting && !isArmingInProgress,
                    icon = Icons.Filled.Wifi,
                )

                val signedInUser by AccountController.signedIn.collectAsState()
                val signedInName by AccountController.signedName.collectAsState()
                if (signedInUser.isNotBlank()) {
                    var confirmSignOut by remember { mutableStateOf(false) }
                    LaunchedEffect(confirmSignOut) {
                        if (confirmSignOut) {
                            delay(4000L)
                            confirmSignOut = false
                        }
                    }
                    PisoButton(
                        text = if (confirmSignOut) {
                            stringResource(R.string.pill_sign_out_confirm)
                        } else {
                            stringResource(R.string.pill_sign_out_signed_in, AccountController.displayName(signedInUser, signedInName))
                        },
                        onClick = {
                            if (!confirmSignOut) {
                                confirmSignOut = true
                            } else {
                                confirmSignOut = false
                                expanded = false
                                AccountController.signOut()
                            }
                        },
                        style = if (confirmSignOut) PisoButtonStyle.Danger else PisoButtonStyle.Outline,
                        size = PisoButtonSize.Small,
                    )
                }

                FloatingPillBrightnessControl(
                    currentBrightness = currentBrightness,
                    maxBrightness = maxBrightness,
                    onBrightnessChange = { applyBrightness(it) },
                )
                FloatingPillVolumeControl(
                    currentVolume = currentVolume,
                    maxVolume = maxVolume,
                    onVolumeChange = {
                        currentVolume = it
                        controller.setStreamVolume(it)
                    },
                )
                FloatingPillStatusLine(batteryPct = batteryStatus.level, ramStats = ramStats)
            }
        }
    } else {
        val hasBatteryAlert = batteryStatus.alertState != BatteryAlertState.NONE
        val isLowBattery = batteryStatus.alertState == BatteryAlertState.LOW_BATTERY_UNPLUGGED
        val critical = timeLevel == TimeLevel.CRITICAL

        val pillBorderColor = when {
            isLowBattery -> TechNet.danger
            hasBatteryAlert -> TechNet.warn
            timeLevel != TimeLevel.OK -> timeLevel.accentColor()
            isArenaMode -> TechNet.brandFg
            else -> TechNet.lineStrong
        }

        // once a second, in the last minute, the pill swells a little (driven by the clock tick, not an endless animation)
        val pulse by animateFloatAsState(
            targetValue = if (critical && effects.events && timeRemaining % 2 == 0) 1.06f else 1f,
            animationSpec = tween(200),
            label = "pillPulse",
        )

        Column {
            Box(modifier = Modifier.padding(4.dp)) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = pulse
                            scaleY = pulse
                            alpha = pillAlpha
                        }
                        .height(28.dp)
                        .wrapContentWidth()
                        .pointerInput(Unit) {
                            // any touch brings the pill back to full strength (nothing is consumed here)
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                touchCount++
                            }
                        }
                        .then(dragModifier)
                        .background(TechNet.surface, CircleShape)
                        .border(if (isArenaMode || hasBatteryAlert || timeLevel != TimeLevel.OK) 2.dp else 1.dp, pillBorderColor, CircleShape)
                        .adminLongPress(
                            scope = coroutineScope,
                            onTap = { expanded = true },
                            onHold = {
                                expanded = true
                                showUnlockedPinDialog = true
                                controller.triggerHapticFeedback()
                            },
                        )
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        when {
                            isArenaMode -> Text(
                                text = when (arenaRole) {
                                    1 -> "P1"
                                    2 -> "P2"
                                    else -> "1v1"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.background(TechNet.brand, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp),
                            )
                            hasBatteryAlert -> Icon(
                                if (isLowBattery) Icons.Filled.BatteryAlert else Icons.Filled.Bolt,
                                contentDescription = stringResource(R.string.banner_unplug_detail),
                                tint = if (isLowBattery) TechNet.danger else TechNet.warn,
                                modifier = Modifier.size(14.dp),
                            )
                            timeLevel == TimeLevel.LOW -> Icon(Icons.Filled.HourglassBottom, contentDescription = null, tint = TechNet.gold, modifier = Modifier.size(14.dp))
                            critical -> Icon(Icons.Filled.Warning, contentDescription = null, tint = TechNet.danger, modifier = Modifier.size(14.dp))
                            else -> Box(modifier = Modifier.size(6.dp).background(if (isEsp32Online) TechNet.ok else TechNet.warn, CircleShape))
                        }
                        Text(
                            text = timeStr,
                            color = timeLevel.textColor(),
                            style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }
            if (showTimeNote) {
                Box(modifier = Modifier.width(300.dp)) {
                    KidsBanner(
                        accent = if (critical) TechNet.danger else TechNet.gold,
                        icon = if (critical) Icons.Filled.Warning else Icons.Filled.HourglassBottom,
                        title = stringResource(if (critical) R.string.banner_time_critical else R.string.banner_time_low),
                        horizontalInset = 0.dp,
                        trailing = {
                            PisoButton(
                                text = stringResource(R.string.banner_add_time),
                                onClick = { expanded = true },
                                modifier = Modifier.width(104.dp),
                                style = PisoButtonStyle.Gold,
                                size = PisoButtonSize.Small,
                            )
                        },
                    )
                }
            }
        }
    }
}
