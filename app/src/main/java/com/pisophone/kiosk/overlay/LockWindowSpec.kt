package com.pisophone.kiosk.overlay

/**
 * How the full-screen lock window must be set up for the lock screen shown or hidden.
 *
 * A hidden lock screen must not take touches or focus, and it must not count as a window that covers other apps: from Android 12 the
 * system blocks touches to another app's window (a game) when an overlay of this app sits over it with a window opacity above 80%.
 * That check reads the window's own alpha ([windowAlpha]), not the alpha of the view inside it, so hiding the view alone is not enough.
 */
internal data class LockWindowSpec(val touchable: Boolean, val focusable: Boolean, val windowAlpha: Float)

internal fun lockWindowSpec(visible: Boolean): LockWindowSpec =
    if (visible) {
        LockWindowSpec(touchable = true, focusable = true, windowAlpha = 1f)
    } else {
        LockWindowSpec(touchable = false, focusable = false, windowAlpha = 0f)
    }
