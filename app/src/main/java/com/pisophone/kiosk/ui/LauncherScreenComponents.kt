package com.pisophone.kiosk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LauncherHeader(
    currentTheme: LauncherThemeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
        Spacer(modifier = Modifier.height(8.dp))
        TimeDateDisplay(currentTheme = currentTheme)
    }
}

@Composable
fun TimeDateDisplay(currentTheme: LauncherThemeColors) {
    var time by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        while (isActive) {
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PinnedAppsSection(
    pinnedSlots: List<String>,
    apps: List<AppInfo>,
    currentTheme: LauncherThemeColors,
    onAppClick: (AppInfo) -> Unit,
    onSlotClick: (Int) -> Unit,
    onSlotLongClick: (Int, AppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
                                    onSlotClick(slotIndex)
                                }
                            },
                            onLongClick = {
                                if (pinnedApp != null) {
                                    onSlotLongClick(slotIndex, pinnedApp)
                                } else {
                                    onSlotClick(slotIndex)
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
