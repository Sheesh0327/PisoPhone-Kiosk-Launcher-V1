package com.pisophone.kiosk.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.ui.theme.TechNet

/**
 * The one banner of the lock screen (battery, device not activated, arena duel; later "5 minutes left"): a card with a 4dp bar in
 * the state's colour on the left, an icon, a title in sentence case and an optional detail line. The colour is never the only
 * signal: the icon and the words say the same.
 */
@Composable
fun KidsBanner(
    accent: Color,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    horizontalInset: Dp = 16.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalInset, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(TechNet.surfaceHi)
            .drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) }
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(16.dp))
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = title, color = TechNet.text, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold)
            if (detail != null) {
                Text(text = detail, color = TechNet.textMuted, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Spacer(Modifier.width(12.dp))
    }
}
