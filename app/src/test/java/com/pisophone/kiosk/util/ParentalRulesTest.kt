package com.pisophone.kiosk.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentalRulesTest {
    @Test
    fun overnightCurfewActiveBetweenStartAndEnd() {
        // Curfew 21:00 (9:00 PM = 1260 min) to 06:00 (6:00 AM = 360 min)
        val schedule = CurfewSchedule(
            enabled = true,
            startMinuteOfDay = 21 * 60,
            endMinuteOfDay = 6 * 60,
        )

        assertTrue("21:00 is active", schedule.isCurfewActive(21 * 60))
        assertTrue("23:30 is active", schedule.isCurfewActive(23 * 60 + 30))
        assertTrue("00:00 midnight is active", schedule.isCurfewActive(0))
        assertTrue("05:59 is active", schedule.isCurfewActive(5 * 60 + 59))

        assertFalse("06:00 is curfew ended", schedule.isCurfewActive(6 * 60))
        assertFalse("12:00 noon is inactive", schedule.isCurfewActive(12 * 60))
        assertFalse("20:59 is before curfew", schedule.isCurfewActive(20 * 60 + 59))
    }

    @Test
    fun sameDayCurfewActiveBetweenStartAndEnd() {
        // Study curfew 14:00 (840 min) to 16:00 (960 min)
        val schedule = CurfewSchedule(
            enabled = true,
            startMinuteOfDay = 14 * 60,
            endMinuteOfDay = 16 * 60,
        )

        assertFalse("13:59 is inactive", schedule.isCurfewActive(13 * 60 + 59))
        assertTrue("14:00 is active", schedule.isCurfewActive(14 * 60))
        assertTrue("15:30 is active", schedule.isCurfewActive(15 * 60 + 30))
        assertFalse("16:00 is inactive", schedule.isCurfewActive(16 * 60))
    }

    @Test
    fun disabledCurfewIsNeverActive() {
        val schedule = CurfewSchedule(
            enabled = false,
            startMinuteOfDay = 20 * 60,
            endMinuteOfDay = 7 * 60,
        )
        assertFalse(schedule.isCurfewActive(22 * 60))
        assertFalse(schedule.isCurfewActive(3 * 60))
    }

    @Test
    fun choreItemSerializationRoundtrip() {
        val chores = listOf(
            ChoreItem("c1", "Wash Dishes", 20, ChoreStatus.TODO, repeatDaily = true),
            ChoreItem("c2", "Finish Math Homework", 30, ChoreStatus.PENDING_APPROVAL, repeatDaily = false),
            ChoreItem("c3", "Clean Bedroom", 15, ChoreStatus.APPROVED, repeatDaily = true),
        )

        val json = ChoreBoardCodec.toJson(chores)
        val decoded = ChoreBoardCodec.fromJson(json)

        assertEquals(3, decoded.size)
        assertEquals("c1", decoded[0].id)
        assertEquals("Wash Dishes", decoded[0].title)
        assertEquals(20, decoded[0].rewardMinutes)
        assertEquals(ChoreStatus.TODO, decoded[0].status)
        assertTrue(decoded[0].repeatDaily)

        assertEquals(ChoreStatus.PENDING_APPROVAL, decoded[1].status)
        assertFalse(decoded[1].repeatDaily)

        assertEquals(ChoreStatus.APPROVED, decoded[2].status)
    }

    @Test
    fun resetDailyChoresResetsDailyOnly() {
        val chores = listOf(
            ChoreItem("c1", "Dishes", 20, ChoreStatus.APPROVED, repeatDaily = true),
            ChoreItem("c2", "One-time quest", 30, ChoreStatus.APPROVED, repeatDaily = false),
            ChoreItem("c3", "Homework", 15, ChoreStatus.TODO, repeatDaily = true),
        )

        val reset = ChoreBoardCodec.resetDailyChores(chores)
        assertEquals(ChoreStatus.TODO, reset[0].status)
        assertEquals(ChoreStatus.APPROVED, reset[1].status) // one-time stays approved/archived
        assertEquals(ChoreStatus.TODO, reset[2].status)
    }

    @Test
    fun piggyBankGoalCalculatesProgressCorrectly() {
        val goal = PiggyBankGoal(targetPesos = 500.0, label = "Weekend Pizza")
        assertEquals(0.0f, goal.progress(0.0), 0.001f)
        assertEquals(0.3f, goal.progress(150.0), 0.001f)
        assertEquals(1.0f, goal.progress(500.0), 0.001f)
        assertEquals(1.0f, goal.progress(700.0), 0.001f) // clamped to 1.0
    }

    @Test
    fun allowedAppsPolicyChecksDefaultAndCustomPackages() {
        val custom = setOf("com.google.android.apps.classroom", "com.duolingo")
        assertTrue(AllowedAppsPolicy.isPackageAllowedWhileLocked("com.android.calculator2", custom))
        assertTrue(AllowedAppsPolicy.isPackageAllowedWhileLocked("com.google.android.apps.classroom", custom))
        assertFalse(AllowedAppsPolicy.isPackageAllowedWhileLocked("com.google.android.youtube", custom))
        assertFalse(AllowedAppsPolicy.isPackageAllowedWhileLocked("com.kiloo.subwaysurf", custom))
    }

    @Test
    fun pauseRulesCalculatesRemainingSecondsFromDeadline() {
        val now = 100_000L
        val deadline = now + 45_000L
        assertEquals(45, PauseRules.calculateRemainingSeconds(now, deadline))

        // Past deadline
        assertEquals(0, PauseRules.calculateRemainingSeconds(now, now - 1000L))
        assertEquals(0, PauseRules.calculateRemainingSeconds(now, 0L))
    }
}
