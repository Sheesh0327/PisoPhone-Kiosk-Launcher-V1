package com.pisophone.kiosk.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBackgroundPolicyUnitTest {
    private fun play(
        enabled: Boolean = true,
        lowRam: Boolean = false,
        powerSave: Boolean = false,
        battery: Int = 80,
        charging: Boolean = false,
        inFront: Boolean = true,
        covered: Boolean = false,
    ) = VideoBackgroundPolicy.shouldPlay(enabled, lowRam, powerSave, battery, charging, inFront, covered)

    @Test
    fun playsOnAnOrdinaryPhoneWhileVisible() {
        assertTrue(play())
    }

    @Test
    fun neverPlaysWhereNobodyCanSeeIt() {
        assertFalse("screen not in front", play(inFront = false))
        assertFalse("covered by the lock screen", play(covered = true))
    }

    @Test
    fun neverPlaysWhenTheAdminSwitchedItOffOrThePhoneCannotAffordIt() {
        assertFalse(play(enabled = false))
        assertFalse(play(lowRam = true))
        assertFalse(play(powerSave = true))
    }

    @Test
    fun stopsOnALowBatteryUnlessCharging() {
        assertFalse(play(battery = 14))
        assertTrue(play(battery = 15))
        assertTrue(play(battery = 5, charging = true))
        assertTrue("unknown battery level does not stop it", play(battery = -1))
    }

    @Test
    fun lockScreenPresenceFollowsEveryUser() {
        LockScreenPresence.leave() // never goes negative
        assertFalse(LockScreenPresence.shown.value)
        LockScreenPresence.enter()
        LockScreenPresence.enter()
        assertTrue(LockScreenPresence.shown.value)
        LockScreenPresence.leave()
        assertTrue("one lock screen branch is still shown", LockScreenPresence.shown.value)
        LockScreenPresence.leave()
        assertFalse(LockScreenPresence.shown.value)
    }

    @Test
    fun cropScaleFillsTheViewWithoutDistortion() {
        // a 9:16 video on a taller phone (9:20): the video is wider than the view, so x grows
        val (sx1, sy1) = VideoBackgroundPolicy.cropScale(900f, 2000f, 720f, 1280f)
        assertEquals(1f, sy1, 0.0001f)
        assertEquals((720f / 1280f) / (900f / 2000f), sx1, 0.0001f)
        // the same video on a squarer phone (9:16 exactly): nothing to crop
        val (sx2, sy2) = VideoBackgroundPolicy.cropScale(720f, 1280f, 720f, 1280f)
        assertEquals(1f, sx2, 0.0001f)
        assertEquals(1f, sy2, 0.0001f)
        // a wider view than the video: y grows
        val (sx3, sy3) = VideoBackgroundPolicy.cropScale(1000f, 1000f, 720f, 1280f)
        assertEquals(1f, sx3, 0.0001f)
        assertTrue(sy3 > 1f)
    }
}
