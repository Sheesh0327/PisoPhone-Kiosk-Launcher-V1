package com.pisophone.kiosk.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** APP-27: a hidden lock screen must not block touches to a game on Android 12+; a shown one must still block everything. */
class LockWindowSpecUnitTest {
    @Test
    fun hiddenLockScreenIsNotTouchableNotFocusableAndHasNoOpacityOfItsOwn() {
        val spec = lockWindowSpec(visible = false)
        assertFalse(spec.touchable)
        assertFalse(spec.focusable)
        // the system counts the window's own alpha, not the view's: anything above 0.8 would block touches to the game
        assertEquals(0f, spec.windowAlpha, 0f)
    }

    @Test
    fun shownLockScreenStillTakesEveryTouchAndIsOpaque() {
        val spec = lockWindowSpec(visible = true)
        assertTrue(spec.touchable)
        assertTrue(spec.focusable)
        assertEquals(1f, spec.windowAlpha, 0f)
    }
}
