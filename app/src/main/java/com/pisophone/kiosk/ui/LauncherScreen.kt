

package com.pisophone.kiosk.ui
import kotlinx.coroutines.isActive
import com.pisophone.kiosk.ui.PinAppPickerModal
import com.pisophone.kiosk.ui.PinnedSlotOptionsModal
import com.pisophone.kiosk.ui.PinAppToSlotModal
import com.pisophone.kiosk.ui.LauncherThemeColors

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.util.PinnedSlotsManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    apps: List<AppInfo>,
    onAppClick: (AppInfo) -> Unit,
) {
    val context = LocalContext.current
    val currentTheme = remember { LauncherThemeColors() }

    var pinnedSlots by remember { mutableStateOf(PinnedSlotsManager.getPinnedSlots(context)) }
    var slotToPinIndex by remember { mutableStateOf<Int?>(null) }
    var selectedPinnedSlotForOptions by remember { mutableStateOf<Pair<Int, AppInfo>?>(null) }
    var appToPinFromDrawer by remember { mutableStateOf<AppInfo?>(null) }

    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val query = searchQuery.trim().lowercase()
            apps.filter { it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
        }
    }

    @Composable
    fun TimeDateDisplay() {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.bg)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Header Row
        Column(
            modifier = Modifier
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
            TimeDateDisplay()
        }

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // Pinned Apps / Quick Launch Section (4 Slots)
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
                                        slotToPinIndex = slotIndex
                                    }
                                },
                                onLongClick = {
                                    if (pinnedApp != null) {
                                        selectedPinnedSlotForOptions = Pair(slotIndex, pinnedApp)
                                    } else {
                                        slotToPinIndex = slotIndex
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

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.4f), thickness = 1.dp)

        // Search Bar & Drawer Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Search games & apps...",
                        color = currentTheme.textMuted,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = currentTheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = currentTheme.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = currentTheme.surface,
                    unfocusedContainerColor = currentTheme.surface.copy(alpha = 0.7f),
                    focusedBorderColor = currentTheme.primary,
                    unfocusedBorderColor = currentTheme.border,
                    focusedTextColor = currentTheme.textPrimary,
                    unfocusedTextColor = currentTheme.textPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = currentTheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ALL APPS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = currentTheme.textPrimary
                    )
                }
                Text(
                    text = "${filteredApps.size} apps available",
                    fontSize = 11.sp,
                    color = currentTheme.textMuted
                )
            }
        }

        // Standard App Drawer Grid
        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = currentTheme.textMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No apps match \"$searchQuery\"",
                        color = currentTheme.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { onAppClick(app) },
                                onLongClick = {
                                    appToPinFromDrawer = app
                                }
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (app.bitmap != null) {
                            Image(
                                bitmap = app.bitmap,
                                contentDescription = app.name,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .background(currentTheme.surface, RoundedCornerShape(14.dp))
                                    .border(1.dp, currentTheme.border, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Apps,
                                    contentDescription = null,
                                    tint = currentTheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = app.name,
                            color = currentTheme.textPrimary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (slotToPinIndex != null) {
        PinAppPickerModal(
            context = context,
            targetSlot = slotToPinIndex!!,
            apps = apps,
            colors = currentTheme,
            onDismiss = { slotToPinIndex = null },
            onSlotPinned = { pinnedSlots = PinnedSlotsManager.getPinnedSlots(context) }
        )
    }

    if (selectedPinnedSlotForOptions != null) {
        val (slotIdx, app) = selectedPinnedSlotForOptions!!
        PinnedSlotOptionsModal(
            context = context,
            slotIdx = slotIdx,
            app = app,
            colors = currentTheme,
            onDismiss = { selectedPinnedSlotForOptions = null },
            onChangeApp = {
                selectedPinnedSlotForOptions = null
                slotToPinIndex = slotIdx
            },
            onUnpin = {
                pinnedSlots = PinnedSlotsManager.getPinnedSlots(context)
                selectedPinnedSlotForOptions = null
            },
            onLaunch = {
                selectedPinnedSlotForOptions = null
                onAppClick(app)
            }
        )
    }

    if (appToPinFromDrawer != null) {
        val app = appToPinFromDrawer!!
        PinAppToSlotModal(
            context = context,
            app = app,
            colors = currentTheme,
            onDismiss = { appToPinFromDrawer = null },
            onSlotSelected = {
                pinnedSlots = PinnedSlotsManager.getPinnedSlots(context)
                appToPinFromDrawer = null
            }
        )
    }
}
