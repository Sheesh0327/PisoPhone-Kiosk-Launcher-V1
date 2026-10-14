package com.pisophone.kiosk.overlay.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.ui.theme.TechNet

/** The look of a [PisoButton]: Primary (blue), Success (teal), Gold (gold outline), Tonal, Outline, Danger. */
enum class PisoButtonStyle { Primary, Success, Gold, Tonal, Outline, Danger }

/** The heights from the spec: Large 64dp (the one main action), Medium 56dp, Small 48dp (also the smallest touch target). */
enum class PisoButtonSize(val height: Dp) {
    Large(64.dp),
    Medium(56.dp),
    Small(48.dp),
}

private val EDGE = 4.dp
private val PRESS_TRAVEL = 3.dp
private val BUTTON_SHAPE = RoundedCornerShape(20.dp)

private class ButtonLook(val face: Color, val edge: Color?, val content: Color, val border: Color?)

private fun lookOf(style: PisoButtonStyle, enabled: Boolean): ButtonLook {
    if (!enabled) return ButtonLook(TechNet.surfaceHi, null, TechNet.textMuted, null)
    return when (style) {
        PisoButtonStyle.Primary -> ButtonLook(TechNet.brand, TechNet.brandEdge, Color.White, null)
        PisoButtonStyle.Success -> ButtonLook(TechNet.teal, TechNet.tealEdge, TechNet.onTeal, null)
        PisoButtonStyle.Gold -> ButtonLook(Color.Transparent, null, TechNet.gold, TechNet.gold)
        PisoButtonStyle.Tonal -> ButtonLook(TechNet.surfaceHi, TechNet.line, TechNet.text, null)
        PisoButtonStyle.Outline -> ButtonLook(Color.Transparent, null, TechNet.text, TechNet.lineStrong)
        PisoButtonStyle.Danger -> ButtonLook(TechNet.danger, TechNet.dangerEdge, Color(0xFF2A0709), null)
    }
}

/**
 * A solid button with a 4dp edge under it; on a press the face moves down 3dp over 80 ms (docs/design/GUI_SPEC_PISOPHONE_KIDS.md
 * section 3). Every button has a text label; a disabled one is flat, grey and says why in its own text or next to it.
 */
@Composable
fun PisoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PisoButtonStyle = PisoButtonStyle.Primary,
    size: PisoButtonSize = PisoButtonSize.Large,
    enabled: Boolean = true,
    subline: String? = null,
    icon: ImageVector? = null,
) {
    val look = lookOf(style, enabled)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val travel by animateDpAsState(if (pressed && enabled) PRESS_TRAVEL else 0.dp, tween(80), label = "buttonPress")
    val hasEdge = look.edge != null

    Box(modifier = modifier.fillMaxWidth().height(size.height + if (hasEdge) EDGE else 0.dp)) {
        if (look.edge != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size.height)
                    .offset(y = EDGE)
                    .background(look.edge, BUTTON_SHAPE),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(size.height)
                .offset { IntOffset(0, travel.roundToPx()) }
                .clip(BUTTON_SHAPE)
                .background(look.face)
                .then(if (look.border != null) Modifier.border(BorderStroke(2.dp, look.border), BUTTON_SHAPE) else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = look.content, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = text,
                        color = look.content,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    if (subline != null) {
                        Text(
                            text = subline,
                            color = look.content.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
