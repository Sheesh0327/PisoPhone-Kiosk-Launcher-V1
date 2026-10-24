package com.pisophone.kiosk.overlay.ui

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.BatteryAlertState
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.network.Esp32Responses
import com.pisophone.kiosk.service.AccountController
import com.pisophone.kiosk.ui.theme.BrandPalette
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.ui.video.LockScreenPresence
import com.pisophone.kiosk.util.Branding
import kotlin.math.roundToInt

/** "COINSLOT BUSY" as the box names it, said the way the screen says everything else: "Coinslot busy". */
private const val PANEL_ALPHA = 0.55f

/** The height the shop-name row takes in the header band; the sticker band starts below it. */
private val MARQUEE_ROW_HEIGHT = 24.dp

private fun sentenceCase(text: String): String = text.lowercase().replaceFirstChar { it.uppercase() }

/**
 * The lock screen (docs/design/GUI_SPEC_PISOPHONE_KIDS.md, screens A and B): one opaque panel with the mascot on his halo, what the
 * coins are worth, and one big button. While coins are going in, the rate card becomes a running total with the seconds left.
 * What it shows and when comes from the same state as before; only the presentation changed.
 */
@Composable
fun BlockScreen(
    onInsertCoin: () -> Unit,
    isWaiting: Boolean = false,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    armFailure: Esp32Responses.ArmFailure? = null,
    isArmingInProgress: Boolean = false,
    pricePerCoin: Double = 1.0,
    minutesPerCoin: Int = 6,
    deviceIp: String = "127.0.0.1",
    slotNumber: Int = 1,
    themeIndex: Int = 0,
    batteryStatus: BatteryStatus = BatteryStatus(),
    onThemeChange: () -> Unit = {},
    buttonText: String? = null,
    isSlotExpired: Boolean = false,
    slotExpiryReason: String = "",
    isArenaMode: Boolean = false,
    arenaRole: Int = 0,
    arenaStakeMinutes: Int = 15,
    /** The shop's look from the coin box (feature 013); nothing set is today's TechNet screen. */
    branding: Branding = Branding(),
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val context = LocalContext.current
    // a shop palette wins; with none set the child's palette button still cycles the three tints it always did
    val palette = if (branding.palette != null) BrandPalette.of(branding.palette) else BrandPalette.forTheme(themeIndex)
    val effects = rememberEffectsLevel()
    val budget = AmbientBudget.of(AnimationDensity.of(branding.density), effects)
    val marqueeScrolls = rememberMarqueeScrolls(AnimationDensity.of(branding.density))
    val shopName = branding.shopName
    val slot = rememberAssignedSlot(deviceIp, slotNumber)
    val haptic = LocalHapticFeedback.current

    var showPinDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var showEmergencyRecoveryDialog by remember { mutableStateOf(false) }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The launcher sits under this screen while the phone is locked: tell it, so its video does not decode out of sight.
    DisposableEffect(Unit) {
        LockScreenPresence.enter()
        onDispose { LockScreenPresence.leave() }
    }

    // the three states of the main button, in the order the old screen decided them
    val counting = isWaiting && paymentTimeout > 0
    val arming = !counting && (isArmingInProgress || (isWaiting && paymentTimeout <= 0))

    // the longest the countdown has been since the last coin: the bar and the ring show seconds left against it
    var windowMax by remember(coinsInserted, isWaiting) { mutableIntStateOf(maxOf(paymentTimeout, 1)) }
    LaunchedEffect(paymentTimeout) { if (paymentTimeout > windowMax) windowMax = paymentTimeout }
    val countdownFraction by animateFloatAsState(
        targetValue = if (counting) paymentTimeout.toFloat() / windowMax else 0f,
        animationSpec = tween(150),
        label = "countdown",
    )
    val secondsWaited = windowMax - paymentTimeout

    // each coin: a small tick under the finger (the mascot hop and the coin burst are keyed on the same count)
    LaunchedEffect(coinsInserted) {
        if (coinsInserted > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val pesosTarget = coinsInserted * pricePerCoin
    val animatedPesos by animateIntAsState(pesosTarget.roundToInt(), tween(300), label = "pesos")
    val animatedMinutes by animateIntAsState(coinsInserted * minutesPerCoin, tween(300), label = "minutes")
    val pesosText = if (pricePerCoin % 1.0 == 0.0) "₱$animatedPesos" else formatPesos(pesosTarget)

    val mascot = when {
        !isEsp32Online || isSlotExpired -> MascotState.Sleepy
        counting || armFailure != null -> MascotState.Listening
        else -> MascotState.Idle
    }

    Box(modifier = modifier.background(palette.bottom)) {
        if (effects.art) {
            LockScreenArt(
                modifier = Modifier.fillMaxSize(),
                palette = palette,
                landscape = isLandscape,
                effects = effects,
                budget = budget,
                bandTopExtra = if (shopName != null) MARQUEE_ROW_HEIGHT else 0.dp,
            )
        }
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            BlockScreenTimeHeader(batteryStatus = batteryStatus)

            if (batteryStatus.alertState != BatteryAlertState.NONE) {
                BatteryAlertBanner(batteryStatus = batteryStatus)
            }
            if (isSlotExpired) {
                SlotExpiredBanner(reason = slotExpiryReason)
            }
            if (isArenaMode) {
                val role = when (arenaRole) {
                    1 -> stringResource(R.string.arena_player_1)
                    2 -> stringResource(R.string.arena_player_2)
                    else -> stringResource(R.string.arena_participant)
                }
                KidsBanner(
                    accent = TechNet.brandFg,
                    icon = Icons.Filled.SportsEsports,
                    title = stringResource(R.string.banner_arena_title),
                    detail = stringResource(R.string.banner_arena_detail, role, arenaStakeMinutes),
                )
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val compact = maxHeight < 640.dp
                val stage: Dp = if (compact) 120.dp else 168.dp
                val rateRowCount = when {
                    maxHeight >= 700.dp -> 3
                    compact -> 1
                    else -> 2
                }

                val halo: @Composable () -> Unit = {
                    Halo(
                        discSize = stage,
                        effects = effects,
                        mascot = mascot,
                        ringFraction = if (counting) countdownFraction else null,
                        ringSeconds = paymentTimeout,
                        cheerKey = coinsInserted,
                        mascotDescription = stringResource(R.string.lock_cd_mascot),
                    )
                }

                val body: @Composable () -> Unit = {
                    LockBody(
                        counting = counting,
                        arming = arming,
                        coinsInserted = coinsInserted,
                        secondsLeft = paymentTimeout,
                        secondsWaited = secondsWaited,
                        countdownFraction = countdownFraction,
                        pesosText = pesosText,
                        minutesText = formatMinutes(animatedMinutes),
                        pricePerCoin = pricePerCoin,
                        minutesPerCoin = minutesPerCoin,
                        rateRows = rateRowCount,
                        isEsp32Online = isEsp32Online,
                        armFailure = armFailure,
                        isSlotExpired = isSlotExpired,
                        buttonText = buttonText,
                        onInsertCoin = onInsertCoin,
                        onDoneClick = onDoneClick,
                        onScanCard = { AccountController.startScan(context) },
                    )
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(0.4f).fillMaxHeight(), contentAlignment = Alignment.Center) { halo() }
                        Column(modifier = Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            LockPanel {
                                LockUpHeader(slot, title = branding.displayTitle, titleColor = palette.accent, showMakerWord = branding.title == null)
                                if (shopName != null) BrandMarquee(shopName, palette.accent, marqueeScrolls)
                                Spacer(Modifier.height(8.dp))
                                body()
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LockUpHeader(slot, modifier = Modifier.widthIn(max = 420.dp), title = branding.displayTitle, titleColor = palette.accent, showMakerWord = branding.title == null)
                        // the shop name runs under the title, in the header band the stickers keep out of
                        if (shopName != null) {
                            BrandMarquee(shopName, palette.accent, marqueeScrolls, modifier = Modifier.widthIn(max = 420.dp).height(MARQUEE_ROW_HEIGHT))
                        }
                        // the mascot hangs over the top edge of the panel: the panel itself starts halfway down his halo
                        Box(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                            LockPanel(modifier = Modifier.padding(top = stage / 2)) {
                                Spacer(Modifier.height(stage / 2 + if (counting) 20.dp else 8.dp))
                                body()
                            }
                            Box(modifier = Modifier.align(Alignment.TopCenter)) { halo() }
                        }
                    }
                }
            }

            StatusFooter(
                isOnline = isEsp32Online,
                onBackdropClick = onThemeChange,
                onAdminClick = { showPinDialog = true },
                showBackdropButton = branding.palette == null,
            )
            // the maker's credit sits under the footer and takes nothing from the branding: it cannot be set, hidden or still
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MakerCredit(color = palette.credit, effects = effects)
            }

            if (showPinDialog) {
                AdminAuthenticationDialog(
                    context = context,
                    onDismiss = { showPinDialog = false },
                    onUnlockSuccess = {
                        showPinDialog = false
                        showSecurityDialog = true
                    },
                    onOpenEmergencyRecovery = {
                        showPinDialog = false
                        showEmergencyRecoveryDialog = true
                    },
                )
            }

            if (showSecurityDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.92f)
                            .padding(12.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(containerColor = TechNet.surface),
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            SecurityVaultView(
                                context = context,
                                onClose = { showSecurityDialog = false },
                                onOpenRecoveryHub = {
                                    showSecurityDialog = false
                                    showEmergencyRecoveryDialog = true
                                },
                            )
                        }
                    }
                }
            }

            if (showEmergencyRecoveryDialog) {
                EmergencyRecoveryDialog(
                    context = context,
                    onClose = { showEmergencyRecoveryDialog = false },
                )
            }
        }
        // coins fall and sparkles open for a second when a coin goes in; nothing is drawn at any other time
        CelebrationLayer(trigger = coinsInserted, enabled = effects.events, burstAt = Offset(0.5f, if (isLandscape) 0.4f else 0.2f))
    }
}

/** The panel (surface colour at about half strength, 28dp corners, a thin border) that holds everything a child has to read. */
@Composable
private fun LockPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // semi-translucent, so the backdrop animation shows through the panel; the buttons inside stay solid
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .background(TechNet.surface.copy(alpha = PANEL_ALPHA), RoundedCornerShape(28.dp))
            .border(1.dp, TechNet.lineStrong.copy(alpha = 0.45f), RoundedCornerShape(28.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** Title, rate card or running total, hints and the main button: everything under the mascot. */
@Composable
private fun LockBody(
    counting: Boolean,
    arming: Boolean,
    coinsInserted: Int,
    secondsLeft: Int,
    secondsWaited: Int,
    countdownFraction: Float,
    pesosText: String,
    minutesText: String,
    pricePerCoin: Double,
    minutesPerCoin: Int,
    rateRows: Int,
    isEsp32Online: Boolean,
    armFailure: Esp32Responses.ArmFailure?,
    isSlotExpired: Boolean,
    buttonText: String?,
    onInsertCoin: () -> Unit,
    onDoneClick: () -> Unit,
    onScanCard: () -> Unit,
) {
    val title = when {
        counting && coinsInserted > 0 -> stringResource(R.string.lock_title_coin_in)
        counting -> stringResource(R.string.lock_title_waiting)
        arming -> stringResource(R.string.lock_title_arming)
        else -> stringResource(R.string.lock_title_idle)
    }
    Text(title, color = TechNet.text, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
    if (!counting && !arming) {
        Text(
            stringResource(R.string.lock_body_idle),
            color = TechNet.textMuted,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
    } else {
        Spacer(Modifier.height(12.dp))
    }

    when {
        counting -> LiveTotalCard(pesosText, minutesText, secondsLeft, countdownFraction)
        !arming -> RateCard(pricePerCoin, minutesPerCoin, rateRows)
    }

    if (counting && coinsInserted == 0 && secondsWaited >= 8) {
        Text(
            stringResource(R.string.lock_stuck_hint),
            color = TechNet.textMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (!counting && !arming) {
        Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = TechNet.textMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.lock_caption_signout),
                color = TechNet.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    if (armFailure != null && !counting) {
        StatusChip(sentenceCase(armFailure.label), TechNet.dangerText, Modifier.padding(top = 12.dp))
    }

    Spacer(Modifier.height(16.dp))

    when {
        counting && coinsInserted > 0 -> PisoButton(
            text = stringResource(R.string.lock_button_done, pesosText),
            onClick = onDoneClick,
            style = PisoButtonStyle.Success,
            icon = Icons.Filled.Check,
        )
        counting -> PisoButton(
            text = stringResource(R.string.lock_button_waiting),
            onClick = {},
            enabled = false,
            size = PisoButtonSize.Medium,
        )
        arming -> PisoButton(
            text = stringResource(R.string.lock_button_arming),
            onClick = {},
            enabled = false,
            size = PisoButtonSize.Medium,
        )
        else -> {
            val canArm = isEsp32Online && armFailure == null && !isSlotExpired
            val ready = buttonText ?: stringResource(R.string.lock_button_ready)
            PisoButton(
                text = when {
                    isSlotExpired -> stringResource(R.string.lock_button_not_activated)
                    !isEsp32Online -> stringResource(R.string.lock_button_offline)
                    else -> ready
                },
                onClick = onInsertCoin,
                enabled = canArm,
                subline = if (canArm && buttonText == null) stringResource(R.string.lock_button_ready_subline) else null,
                icon = if (canArm) Icons.Filled.AddCircle else Icons.Filled.Lock,
            )
            Spacer(Modifier.height(8.dp))
            PisoButton(
                text = stringResource(R.string.lock_scan_card),
                onClick = onScanCard,
                style = PisoButtonStyle.Outline,
                size = PisoButtonSize.Small,
            )
        }
    }
}
