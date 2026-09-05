package com.pisophone.kiosk.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pisophone.kiosk.model.AppInfo
import com.pisophone.kiosk.util.PinnedSlotsManager

class LauncherThemeColors(
    val bg: Color = Color(0xFF060B14),
    val surface: Color = Color(0xFF0F172A),
    val cardBg: Color = Color(0xFF0D1527),
    val primary: Color = Color(0xFF10B981),
    val primaryLight: Color = Color(0xFF34D399),
    val onPrimary: Color = Color(0xFF020617),
    val textPrimary: Color = Color(0xFFF8FAFC),
    val textSecondary: Color = Color(0xFF94A3B8),
    val textMuted: Color = Color(0xFF64748B),
    val border: Color = Color(0xFF1E293B),
    val borderEmerald: Color = Color(0x4010B981)
)

@Composable
fun PinAppPickerModal(
    context: Context,
    targetSlot: Int,
    apps: List<AppInfo>,
    colors: LauncherThemeColors,
    onDismiss: () -> Unit,
    onSlotPinned: () -> Unit
) {
    var pickerSearchQuery by remember { mutableStateOf("") }
    val pickerApps = remember(apps, pickerSearchQuery) {
        if (pickerSearchQuery.isBlank()) {
            apps
        } else {
            val q = pickerSearchQuery.trim().lowercase()
            apps.filter { it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xDD020617))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, colors.borderEmerald, RoundedCornerShape(24.dp)),
                color = colors.surface,
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Pin App to Slot ${targetSlot + 1}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "Select an app or game for quick access",
                                fontSize = 12.sp,
                                color = colors.textSecondary
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = colors.textSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = pickerSearchQuery,
                        onValueChange = { pickerSearchQuery = it },
                        placeholder = { Text("Filter apps...", color = colors.textMuted, fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.bg,
                            unfocusedContainerColor = colors.bg,
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pickerApps, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        PinnedSlotsManager.setPinnedSlot(context, targetSlot, app.packageName)
                                        Toast.makeText(context, "${app.name} pinned to Slot ${targetSlot + 1}", Toast.LENGTH_SHORT).show()
                                        onSlotPinned()
                                        onDismiss()
                                    },
                                color = colors.cardBg,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (app.bitmap != null) {
                                        Image(
                                            bitmap = app.bitmap,
                                            contentDescription = app.name,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Apps,
                                            contentDescription = null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(46.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = app.name,
                                        color = colors.textPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PinnedSlotOptionsModal(
    context: Context,
    slotIdx: Int,
    app: AppInfo,
    colors: LauncherThemeColors,
    onDismiss: () -> Unit,
    onChangeApp: () -> Unit,
    onUnpin: () -> Unit,
    onLaunch: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (app.bitmap != null) {
                    Image(
                        bitmap = app.bitmap,
                        contentDescription = app.name,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
                Text(
                    text = "Slot ${slotIdx + 1}: ${app.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        },
        text = {
            Text(
                text = "Manage this pinned slot:",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onChangeApp,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.cardBg, contentColor = colors.primaryLight),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Text("Change App", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        PinnedSlotsManager.clearPinnedSlot(context, slotIdx)
                        Toast.makeText(context, "Unpinned Slot ${slotIdx + 1}", Toast.LENGTH_SHORT).show()
                        onUnpin()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.2f), contentColor = Color(0xFFF87171)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Unpin", fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onLaunch) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Launch", color = colors.primary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun PinAppToSlotModal(
    context: Context,
    app: AppInfo,
    colors: LauncherThemeColors,
    onDismiss: () -> Unit,
    onSlotSelected: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.bitmap != null) {
                    Image(
                        bitmap = app.bitmap,
                        contentDescription = app.name,
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    )
                }
                Text(
                    text = "Pin ${app.name}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Choose which slot to pin this app to:",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0 until 4) {
                        Button(
                            onClick = {
                                PinnedSlotsManager.setPinnedSlot(context, i, app.packageName)
                                Toast.makeText(context, "${app.name} pinned to Slot ${i + 1}", Toast.LENGTH_SHORT).show()
                                onSlotSelected(i)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.cardBg,
                                contentColor = colors.primary
                            ),
                            border = BorderStroke(1.dp, colors.borderEmerald),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("Slot ${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary)
            }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(20.dp)
    )
}
