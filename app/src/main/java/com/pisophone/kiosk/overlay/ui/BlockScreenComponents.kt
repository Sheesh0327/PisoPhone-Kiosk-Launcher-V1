package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Token
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.model.BatteryStatus
import com.pisophone.kiosk.security.KioskSecurity
import com.pisophone.kiosk.ui.theme.TechNet
import com.pisophone.kiosk.util.ShopBranding
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Numbers line up in columns and do not jump while they count: tabular figures. */
private fun numberStyle(sizeSp: Int, lineSp: Int) =
    TextStyle(fontSize = sizeSp.sp, lineHeight = lineSp.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum")

/** The clock and battery at the top of the lock screen (the phone's own status bar is covered), 12sp. */
@Composable
fun BlockScreenTimeHeader(batteryStatus: BatteryStatus) {
    var currentTimeStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            currentTimeStr = timeFormat.format(Date())
            // the clock only changes once a minute: nothing to draw in between
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .height(24.dp)
            .alpha(0.85f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(currentTimeStr, color = TechNet.text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = TechNet.text, modifier = Modifier.size(16.dp))
            Text("${batteryStatus.level}%", color = TechNet.text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The number this phone shows ("Phone 3"): the slot the owner gave it, else the one the coin box's address implies. It also
 * remembers that number as the phone's slot and alias, as the old title badge did.
 */
@Composable
fun rememberAssignedSlot(deviceIp: String, slotNumber: Int): Int {
    val ctx = LocalContext.current
    val assignedSlot = if (slotNumber > 0) slotNumber else KioskSecurity.getAssignedBoxSlot(ctx)
    val effectiveSlotNumber = if (assignedSlot > 0) {
        assignedSlot
    } else {
        val lastOctet = deviceIp.substringAfterLast(".").toIntOrNull()
        when {
            lastOctet != null && lastOctet in 100..120 -> lastOctet - 99
            lastOctet != null && lastOctet in 1..254 -> lastOctet
            else -> 1
        }
    }
    LaunchedEffect(effectiveSlotNumber) {
        KioskSecurity.setAssignedBoxSlot(ctx, effectiveSlotNumber)
        KioskSecurity.setDeviceAlias(ctx, "PisoPhone $effectiveSlotNumber")
    }
    return effectiveSlotNumber
}

/** The logo line of the panel: the brand name, and which phone this is. */
@Composable
fun LockUpHeader(
    slot: Int,
    modifier: Modifier = Modifier,
    title: String = ShopBranding.DEFAULT_TITLE,
    titleColor: Color = TechNet.teal,
) {
    Row(modifier = modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(R.drawable.technet_sticker_pisophone), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text("TechNet", color = TechNet.text, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(5.dp))
        // the shop's header title (feature 012, at most 20 characters); a long one on a narrow phone is cut with dots, never pushes the chip off
        Text(
            title,
            color = titleColor,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.lock_phone_chip, slot),
            color = TechNet.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(TechNet.surface.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** What the coins are worth: "₱5 = 30 min", "₱10 = 1 hr" (the box's own rate), and a line to say more coins give more time. */
@Composable
fun RateCard(pricePerCoin: Double, minutesPerCoin: Int, rows: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TechNet.surfaceHi.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in rateRows(pricePerCoin, minutesPerCoin, rows)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
                Icon(Icons.Filled.Token, contentDescription = null, tint = TechNet.gold, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(row, color = TechNet.gold, style = numberStyle(24, 30))
            }
        }
        Text(
            stringResource(R.string.lock_rate_more),
            color = TechNet.textMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** While coins are going in: the running total in pesos (big, gold), what it buys, and the seconds left as a bar. */
@Composable
fun LiveTotalCard(
    pesosText: String,
    minutesText: String,
    secondsLeft: Int,
    barFraction: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(TechNet.surfaceHi.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(pesosText, color = TechNet.gold, style = numberStyle(56, 60), textAlign = TextAlign.Center)
        Text("= $minutesText", color = TechNet.text, style = numberStyle(20, 24), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).height(6.dp).background(TechNet.line, RoundedCornerShape(3.dp))) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barFraction.coerceIn(0f, 1f))
                        .background(TechNet.teal, RoundedCornerShape(3.dp)),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.lock_seconds_left, secondsLeft),
                color = TechNet.text,
                style = numberStyle(12, 16),
            )
        }
    }
}

/** Under the panel: whether the coin box answers (a dot and words), the backdrop colour button and the owner's door. */
@Composable
fun StatusFooter(isOnline: Boolean, onBackdropClick: () -> Unit, onAdminClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackdropClick, modifier = Modifier.alpha(0.8f)) {
            Icon(Icons.Filled.Palette, contentDescription = stringResource(R.string.lock_cd_backdrop), tint = TechNet.text)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(if (isOnline) TechNet.ok else TechNet.warn, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(if (isOnline) R.string.lock_footer_online else R.string.lock_footer_offline),
                color = TechNet.text,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = onAdminClick, modifier = Modifier.alpha(0.8f)) {
            Icon(Icons.Filled.AdminPanelSettings, contentDescription = stringResource(R.string.lock_cd_admin), tint = TechNet.text)
        }
    }
}

/** A small coloured note on the panel (for example "Coin not detected. Try again."): a word and a colour. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .border(1.dp, color, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun SlotExpiredBanner(
    reason: String = "",
    modifier: Modifier = Modifier,
) {
    KidsBanner(
        accent = TechNet.danger,
        icon = Icons.Filled.Block,
        title = stringResource(R.string.banner_not_activated),
        detail = reason.ifBlank { stringResource(R.string.banner_not_activated_default) },
        modifier = modifier,
    )
}
