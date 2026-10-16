package com.pisophone.kiosk.model

import androidx.compose.ui.graphics.ImageBitmap

data class AppInfo(
    val name: String,
    val packageName: String,
    val bitmap: ImageBitmap? = null,
)
