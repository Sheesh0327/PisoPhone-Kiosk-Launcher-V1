package com.pisophone.kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a voice message plays on the phone (VoicePlayback): at once, unless the countdown or closing speech is being spoken, which keeps
 * its priority; a newer message replaces the one playing or waiting; the banner is up exactly while a message plays. The sound itself
 * and the window are Android's, behind VoicePlayback.Output.
 */
class VoicePlaybackTest {
    private class FakeOutput : VoicePlayback.Output {
        var speechActive = false
        val started = mutableListOf<ShortArray>()
        var stops = 0
        var banner = false
        var bannerShown = 0
        override fun isSpeechActive() = speechActive
        override fun start(samples: ShortArray) {
            started += samples
        }
        override fun stop() {
            stops++
        }
        override fun showBanner(visible: Boolean) {
            banner = visible
            if (visible) bannerShown++
        }
    }

    private var now = 1_000L
    private val out = FakeOutput()
    private val playback = VoicePlayback(out) { now }
    private val a = shortArrayOf(1, 2, 3)
    private val b = shortArrayOf(4, 5, 6)
    private val c = shortArrayOf(7, 8, 9)

    @Test
    fun aMessagePlaysAtOnceWithTheBanner() {
        playback.onMessage(a)
        assertEquals(1, out.started.size)
        assertSame(a, out.started[0])
        assertTrue(out.banner)
        assertTrue(playback.playing)
        assertFalse(playback.waiting)
    }

    @Test
    fun theBannerGoesWhenThePlayingEnds() {
        playback.onMessage(a)
        playback.onFinished()
        assertFalse(out.banner)
        assertFalse(playback.playing)
    }

    @Test
    fun aNewerMessageReplacesTheOnePlaying() {
        playback.onMessage(a)
        playback.onMessage(b)
        assertEquals(2, out.started.size)
        assertSame(b, out.started[1])
        assertEquals("the first one is stopped, not left to overlap", 1, out.stops)
        assertTrue("the banner stays", out.banner)
        playback.onFinished()
        assertFalse(out.banner)
    }

    @Test
    fun theCountdownSpeechKeepsItsPriority() {
        out.speechActive = true
        playback.onMessage(a)
        assertEquals("nothing plays over the countdown", 0, out.started.size)
        assertFalse("no banner for a message that has not started", out.banner)
        assertTrue(playback.waiting)
        now += 2_000
        playback.tick()
        assertEquals(0, out.started.size)
        out.speechActive = false
        now += 100
        playback.tick()
        assertEquals(1, out.started.size)
        assertSame(a, out.started[0])
        assertTrue(out.banner)
        assertFalse(playback.waiting)
    }

    @Test
    fun aWaitingMessageIsReplacedByANewerOne() {
        out.speechActive = true
        playback.onMessage(a)
        playback.onMessage(b)
        out.speechActive = false
        playback.tick()
        assertEquals(1, out.started.size)
        assertSame(b, out.started[0])
    }

    @Test
    fun aMessageDoesNotWaitForeverForSpeechThatNeverEnds() {
        out.speechActive = true
        playback.onMessage(a)
        now += VoicePlayback.MAX_WAIT_MS - 1
        playback.tick()
        assertEquals(0, out.started.size)
        now += 1
        playback.tick()
        assertEquals("after the longest wait it plays anyway", 1, out.started.size)
    }

    @Test
    fun tickDoesNothingWhenNothingWaits() {
        playback.tick()
        playback.onMessage(a)
        playback.tick()
        assertEquals(1, out.started.size)
    }

    @Test
    fun stopAllStopsTheSoundHidesTheBannerAndForgetsTheWaitingOne() {
        playback.onMessage(a)
        out.speechActive = true
        playback.onMessage(c)
        playback.stopAll()
        assertFalse(out.banner)
        assertFalse(playback.playing)
        assertFalse(playback.waiting)
        out.speechActive = false
        playback.tick()
        assertEquals("nothing comes back after stopAll", 1, out.started.size)
    }

    @Test
    fun anEmptyMessageIsIgnored() {
        playback.onMessage(ShortArray(0))
        assertEquals(0, out.started.size)
        assertFalse(out.banner)
    }
}
