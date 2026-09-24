package com.pisophone.kiosk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.AppInfo

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PinnedAppsSection(
    apps: List<AppInfo>,
    pinnedSlots: List<String>,
    currentTheme: LauncherThemeColors,
    onAppClick: (AppInfo) -> Unit,
    onSlotClickToPin: (Int) -> Unit,
    onSlotLongClickOptions: (Int, AppInfo) -> Unit
) {
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
                                    onSlotClickToPin(slotIndex)
                                }
                            },
                            onLongClick = {
                                if (pinnedApp != null) {
                                    onSlotLongClickOptions(slotIndex, pinnedApp)
                                } else {
                                    onSlotClickToPin(slotIndex)
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
}
