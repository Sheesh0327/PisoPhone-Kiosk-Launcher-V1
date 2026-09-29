package com.pisophone.kiosk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.util.PinnedSlotsManager

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.bg)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top Header
        LauncherHeader(currentTheme = currentTheme)

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // Pinned Apps / Quick Launch Section (4 Slots)
        PinnedAppsSection(
            pinnedSlots = pinnedSlots,
            apps = apps,
            currentTheme = currentTheme,
            onAppClick = onAppClick,
            onSlotClick = { slotToPinIndex = it },
            onSlotLongClick = { idx, app -> selectedPinnedSlotForOptions = Pair(idx, app) }
        )

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
