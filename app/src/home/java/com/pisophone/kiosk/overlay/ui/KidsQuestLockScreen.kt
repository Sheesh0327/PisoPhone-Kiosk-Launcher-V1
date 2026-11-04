package com.pisophone.kiosk.overlay.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.PisoKidsTheme
import com.pisophone.kiosk.util.ChoreItem
import com.pisophone.kiosk.util.ChoreStatus
import com.pisophone.kiosk.util.KioskSettings
import java.util.Calendar

/**
 * Gamified Dual-Hero Lock Screen Hub for PisoKids / PisoHome (Feature 064).
 * Dual-Hero:
 *   - Hero 1: Time & Play Hub (Remaining time / Resume saved time / Drop a coin / Curfew lock).
 *   - Hero 2: Today's Quests (Chores board with instant "I Did It!" request).
 *   - Bottom Dock: Always-allowed apps (Dialer, Calculator, etc.).
 */
@Composable
fun KidsQuestLockScreen(
    onInsertCoin: () -> Unit,
    isWaiting: Boolean = false,
    coinsInserted: Int,
    paymentTimeout: Int,
    onDoneClick: () -> Unit,
    isEsp32Online: Boolean,
    pricePerCoin: Double = 1.0,
    minutesPerCoin: Int = 10,
    batteryStatus: BatteryStatus = BatteryStatus(),
    heldSeconds: Int = 0,
    onOpenParentPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    // Curfew check
    val curfew = remember { KioskSettings.getCurfewSchedule(context) }
    val cal = Calendar.getInstance()
    val minOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val isCurfewActive = curfew.isCurfewActive(minOfDay)

    // Chores state
    var chores by remember { mutableStateOf(KioskSettings.getChores(context)) }
    val piggyGoal = remember { KioskSettings.getPiggyBankGoal(context) }
    val deviceAlias = remember { KioskSecurity.getDeviceAlias(context).ifBlank { "Liam's Phone" } }

    val counting = isWaiting && paymentTimeout > 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PisoKidsTheme.bg)
            .systemBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- Top Bar: Device Name + Piggy Bank Goal Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = deviceAlias,
                    color = PisoKidsTheme.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PisoKidsTheme.surface)
                        .clickable { onOpenParentPin() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Parent PIN",
                            tint = PisoKidsTheme.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Parent", color = PisoKidsTheme.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Piggy Bank Mini Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PisoKidsTheme.surface),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Savings,
                            contentDescription = "Piggy Bank",
                            tint = PisoKidsTheme.gold,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(piggyGoal.label, color = PisoKidsTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Target: ₱${piggyGoal.targetPesos.toInt()}", color = PisoKidsTheme.textMuted, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(PisoKidsTheme.surfaceHi),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(PisoKidsTheme.emerald),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- HERO 1: Time & Play Hub ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PisoKidsTheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, PisoKidsTheme.line),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    isCurfewActive -> {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF312E81)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nightlight,
                                contentDescription = "Curfew",
                                tint = Color(0xFFA5B4FC),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Bedtime Curfew Active",
                            color = PisoKidsTheme.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Screen time is paused until morning. Rest well!",
                            color = PisoKidsTheme.textMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    heldSeconds > 0 -> {
                        val heldMin = heldSeconds / 60
                        val heldSec = heldSeconds % 60
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF064E3B)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Saved Time",
                                tint = PisoKidsTheme.emerald,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "You have ${heldMin}m ${if (heldSec > 0) "${heldSec}s " else ""}saved!",
                            color = PisoKidsTheme.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap to resume your screen time.",
                            color = PisoKidsTheme.textMuted,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(PisoKidsTheme.emerald)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDoneClick()
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Resume Playing",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    counting -> {
                        Text(
                            text = "Deposit Coin",
                            color = PisoKidsTheme.gold,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "₱${(coinsInserted * pricePerCoin).toInt()} · +${coinsInserted * minutesPerCoin}m",
                            color = PisoKidsTheme.text,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(PisoKidsTheme.emerald)
                                .clickable { onDoneClick() }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Done (${paymentTimeout}s)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(PisoKidsTheme.surfaceHi),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = "Play",
                                tint = PisoKidsTheme.emerald,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Drop a Coin to Play",
                            color = PisoKidsTheme.text,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "₱${pricePerCoin.toInt()} = $minutesPerCoin Minutes Screen Time",
                            color = PisoKidsTheme.gold,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isEsp32Online) PisoKidsTheme.emerald else PisoKidsTheme.surfaceHi)
                                .clickable(enabled = isEsp32Online) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onInsertCoin()
                                }
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (isEsp32Online) "Drop Coin in Box" else "Coin Box Offline (Use Chores)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- HERO 2: Today's Quests (Chores Board) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PisoKidsTheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, PisoKidsTheme.line),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⭐ Today's Quests",
                        color = PisoKidsTheme.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Earn Screen Time",
                        color = PisoKidsTheme.gold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (chores.isEmpty()) {
                    Text(
                        text = "No quests today! Ask Mom or Dad to assign one.",
                        color = PisoKidsTheme.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    chores.forEach { chore ->
                        QuestRow(
                            chore = chore,
                            onComplete = {
                                val updated = chores.map {
                                    if (it.id == chore.id) it.copy(status = ChoreStatus.PENDING_APPROVAL) else it
                                }
                                chores = updated
                                KioskSettings.setChores(context, updated)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Bottom Dock: Always-Allowed Apps ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = PisoKidsTheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AllowedAppPill(icon = Icons.Default.Phone, label = "Phone") {
                    val intent = Intent(Intent.ACTION_DIAL)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (_: Exception) {}
                }
                AllowedAppPill(icon = Icons.Default.Calculate, label = "Calculator") {
                    val calcIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.calculator")
                        ?: context.packageManager.getLaunchIntentForPackage("com.android.calculator2")
                    calcIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { if (calcIntent != null) context.startActivity(calcIntent) } catch (_: Exception) {}
                }
            }
        }
    }
}

@Composable
private fun QuestRow(
    chore: ChoreItem,
    onComplete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PisoKidsTheme.surfaceHi)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chore.title,
                color = PisoKidsTheme.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "+${chore.rewardMinutes} min reward",
                color = PisoKidsTheme.gold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        when (chore.status) {
            ChoreStatus.TODO -> {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PisoKidsTheme.emerald)
                        .clickable { onComplete() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "I Did It!",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            ChoreStatus.PENDING_APPROVAL -> {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PisoKidsTheme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = PisoKidsTheme.gold,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pending",
                            color = PisoKidsTheme.gold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            ChoreStatus.APPROVED -> {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF064E3B))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = PisoKidsTheme.emerald,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Done",
                            color = PisoKidsTheme.emerald,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllowedAppPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PisoKidsTheme.surfaceHi)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = PisoKidsTheme.emerald, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = PisoKidsTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
