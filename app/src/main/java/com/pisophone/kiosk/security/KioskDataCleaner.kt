package com.pisophone.kiosk.security

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Handles all application cache sanitization, browser data wiping,
 * and memory clearing operations.
 */
object KioskDataCleaner {
    private const val TAG = "KioskDataCleaner"

    /**
     * Clears application internal and external cache, as well as WebView cookies/storage.
     */
    fun clearAppCacheAndData(context: Context): Boolean {
        return try {
            // 1. Clear internal cache directory
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.isDirectory) {
                cacheDir.deleteRecursively()
            }
            // 2. Clear external cache directory
            val externalCacheDir = context.externalCacheDir
            if (externalCacheDir != null && externalCacheDir.isDirectory) {
                externalCacheDir.deleteRecursively()
            }
            // 3. Clear WebView cookies and WebStorage
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                Log.w(TAG, "WebView cache clear warning: ${e.message}")
            }
            Log.d(TAG, "Successfully cleared app cache and web session data")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing app cache: ${e.message}")
            false
        }
    }
}
