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
import com.pisophone.kiosk.overlay.ui.TimeAddedPopOverContent
import com.pisophone.kiosk.overlay.ui.rememberEffectsLevel

/**
 * A small window above the game, shown for 1.5 seconds when a customer adds time during a session. It takes no touches and no
 * focus, so the game under it is not disturbed. Call from the main thread.
 */
class TimeAddedPopOver(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: ComposeOverlayView? = null
    private val removeRunnable = Runnable { remove() }

    fun show(addedSeconds: Int) {
        if (!Settings.canDrawOverlays(context)) return
        remove()
        val view = ComposeOverlayView(context)
        view.setContent {
            TimeAddedPopOverContent(addedSeconds = addedSeconds, effects = rememberEffectsLevel())
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
            y = (96 * context.resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(view.view, params)
            view.start()
            overlay = view
            handler.postDelayed(removeRunnable, SHOW_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the time-added pop-over: ${e.message}")
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
        const val TAG = "TimeAddedPopOver"
        const val SHOW_MS = 1_700L
    }
}
