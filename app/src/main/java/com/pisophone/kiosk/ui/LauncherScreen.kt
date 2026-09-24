package com.pisophone.kiosk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    apps: List<AppInfo>,
    themeIndex: Int = 0,
    onAppClick: (AppInfo) -> Unit
) {
    val currentTheme = launcherThemes[themeIndex % launcherThemes.size]
    val context = LocalContext.current
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
        // Top Header Row
        LauncherHeader(currentTheme = currentTheme)

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // Pinned Apps / Quick Launch Section (4 Slots)
        PinnedAppsSection(
            apps = apps,
            pinnedSlots = pinnedSlots,
            currentTheme = currentTheme,
            onAppClick = onAppClick,
            onSlotClickToPin = { slotToPinIndex = it },
            onSlotLongClickOptions = { slotIdx, app ->
                selectedPinnedSlotForOptions = Pair(slotIdx, app)
            }
        )

        HorizontalDivider(color = currentTheme.border.copy(alpha = 0.6f), thickness = 1.dp)

        // All Applications Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ALL APPLICATIONS",
                        color = currentTheme.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = currentTheme.surface,
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, currentTheme.border)
                    ) {
                        Text(
                            text = "${filteredApps.size}",
                            color = currentTheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        "Search apps or long-press to pin...",
                        color = currentTheme.textSecondary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = currentTheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear",
                                tint = currentTheme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = currentTheme.surface,
                    unfocusedContainerColor = currentTheme.surface.copy(alpha = 0.6f),
                    focusedBorderColor = currentTheme.primary,
                    unfocusedBorderColor = currentTheme.border,
                    focusedTextColor = currentTheme.textPrimary,
                    unfocusedTextColor = currentTheme.textPrimary,
                    cursorColor = currentTheme.primary
                )
            )

            // Grid of Installed Apps
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
