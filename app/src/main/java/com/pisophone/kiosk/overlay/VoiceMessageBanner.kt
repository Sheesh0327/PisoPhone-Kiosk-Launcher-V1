package com.pisophone.kiosk.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.pisophone.kiosk.ComposeOverlayView
import com.pisophone.kiosk.overlay.ui.VoiceMessageBannerContent
import com.pisophone.kiosk.overlay.ui.rememberEffectsLevel

/**
 * A small window above the lock screen or the game while a voice message from the shop plays (feature 056). It takes no touches and no
 * focus, so the game under it is not disturbed. Removed when the sound ends; the timer is only a safety net. Call from the main thread.
 */
class VoiceMessageBanner(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: ComposeOverlayView? = null
    private val removeRunnable = Runnable { remove() }

    fun show() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(context)) return
        val view = ComposeOverlayView(context)
        view.setContent {
            VoiceMessageBannerContent(effects = rememberEffectsLevel())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * context.resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(view.view, params)
            view.start()
            overlay = view
            handler.postDelayed(removeRunnable, SAFETY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the voice message banner: ${e.message}")
            try {
                view.stop()
                view.destroy()
            } catch (_: Exception) {
            }
            overlay = null
        }
    }

    fun remove() {
        handler.removeCallbacks(removeRunnable)
        val current = overlay ?: return
        overlay = null
        try {
            current.stop()
            current.destroy()
        } catch (_: Exception) {
        }
        try {
            windowManager.removeView(current.view)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val TAG = "VoiceMessageBanner"
        const val SAFETY_MS = 25_000L
    }
}
