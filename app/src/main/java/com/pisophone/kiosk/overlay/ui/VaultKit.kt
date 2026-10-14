package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.R
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The building blocks of the owner's screens (the vault, the PIN card, the recovery hub), in the same TechNet look as the
 * customer's screens but plainer: solid cards in two tones of navy, sentence-case titles, 12sp captions at the smallest, one
 * accent colour per meaning (blue = action, teal = on / done, gold = money and time, red = danger).
 */

/** Text field colours: light text, a strong outline (it is the only boundary of the control), blue when focused. */
@Composable
fun vaultTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TechNet.text,
    unfocusedTextColor = TechNet.text,
    focusedBorderColor = TechNet.brandFg,
    unfocusedBorderColor = TechNet.lineStrong,
    errorBorderColor = TechNet.dangerText,
    focusedContainerColor = TechNet.surfaceHi,
    unfocusedContainerColor = TechNet.surfaceHi,
    errorContainerColor = TechNet.surfaceHi,
    focusedPlaceholderColor = TechNet.textMuted,
    unfocusedPlaceholderColor = TechNet.textMuted,
    cursorColor = TechNet.brandFg,
)

@Composable
fun vaultSliderColors(accent: Color = TechNet.brandFg) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = TechNet.line,
)

@Composable
fun vaultSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = TechNet.teal,
    checkedBorderColor = TechNet.teal,
    uncheckedThumbColor = TechNet.textMuted,
    uncheckedTrackColor = TechNet.surface,
    uncheckedBorderColor = TechNet.lineStrong,
)

/** A solid card of the owner's screens. */
@Composable
fun VaultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TechNet.surfaceHi, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

/** The title line of a card, with the small "?" that explains it. */
@Composable
fun VaultCardTitle(title: String, onShowHelp: (String, String) -> Unit, helpText: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TechNet.text, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold)
        if (helpText != null) {
            Spacer(Modifier.width(4.dp))
            HelpInfoButton(title = title, description = helpText, onShowHelp = onShowHelp)
        }
    }
}

/** A line with a switch: what it does, and its state in words under it. */
@Composable
fun VaultToggleRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TechNet.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            if (detail != null) {
                Text(detail, color = TechNet.textMuted, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = vaultSwitchColors())
    }
}

@Composable
fun VaultDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(vertical = 8.dp), color = TechNet.line)
}

/** A small label under a card title or above a group of buttons. */
@Composable
fun VaultCaption(text: String, modifier: Modifier = Modifier, color: Color = TechNet.textMuted) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** The "?" that opens an explanation (the 40dp touch area is bigger than the 18dp mark). */
@Composable
fun HelpInfoButton(
    title: String,
    description: String,
    onShowHelp: (String, String) -> Unit,
) {
    IconButton(onClick = { onShowHelp(title, description) }, modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(TechNet.surface)
                .border(1.dp, TechNet.lineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = TechNet.text, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

/** What a confirmation asks: it names the thing that will happen. */
class VaultConfirm(val title: String, val message: String, val confirmLabel: String, val action: () -> Unit)

/**
 * A modal note drawn inside the view (not a Dialog window: the overlay windows here are not safe for those). A tap outside
 * dismisses it. With [confirm] it asks first and names what the button does.
 */
@Composable
fun VaultNote(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirm: VaultConfirm? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clickable(enabled = false) {}
                .background(TechNet.surfaceHi, RoundedCornerShape(28.dp))
                .border(1.dp, TechNet.line, RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            Text(title, color = TechNet.text, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = TechNet.textMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Start)
            Spacer(Modifier.height(16.dp))
            if (confirm != null) {
                PisoButton(
                    text = confirm.confirmLabel,
                    onClick = {
                        onDismiss()
                        confirm.action()
                    },
                    style = PisoButtonStyle.Danger,
                    size = PisoButtonSize.Small,
                )
                Spacer(Modifier.height(6.dp))
                PisoButton(text = stringResource(R.string.ui_cancel), onClick = onDismiss, style = PisoButtonStyle.Outline, size = PisoButtonSize.Small)
            } else {
                PisoButton(text = stringResource(R.string.ui_got_it), onClick = onDismiss, size = PisoButtonSize.Small)
            }
        }
    }
}
