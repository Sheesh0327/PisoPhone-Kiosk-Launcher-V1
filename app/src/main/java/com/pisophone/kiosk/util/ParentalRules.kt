package com.pisophone.kiosk.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Curfew schedule rules.
 * Times are stored as minutes from midnight (0..1439).
 */
data class CurfewSchedule(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 21 * 60, // 21:00 (9:00 PM) default
    val endMinuteOfDay: Int = 6 * 60,    // 06:00 (6:00 AM) default
) {
    /**
     * Evaluates if curfew is currently active at [currentMinuteOfDay].
     * Handles both overnight curfew windows (e.g., 21:00 -> 06:00) and same-day windows (e.g., 14:00 -> 16:00).
     */
    fun isCurfewActive(currentMinuteOfDay: Int): Boolean {
        if (!enabled) return false
        val minute = ((currentMinuteOfDay % 1440) + 1440) % 1440
        return if (startMinuteOfDay > endMinuteOfDay) {
            // Overnight window: active if >= start OR < end
            minute >= startMinuteOfDay || minute < endMinuteOfDay
        } else if (startMinuteOfDay < endMinuteOfDay) {
            // Same-day window: active if start <= minute < end
            minute in startMinuteOfDay until endMinuteOfDay
        } else {
            false
        }
    }
}

/**
 * Lifecycle status of a quest/chore item.
 */
enum class ChoreStatus {
    TODO,
    PENDING_APPROVAL,
    APPROVED,
}

/**
 * A chore/quest rewarding screen time minutes upon parental approval.
 */
data class ChoreItem(
    val id: String,
    val title: String,
    val rewardMinutes: Int,
    val status: ChoreStatus = ChoreStatus.TODO,
    val repeatDaily: Boolean = true,
)

/**
 * Pure JSON serializer and deserializer for the chore board.
 */
object ChoreBoardCodec {
    fun toJson(chores: List<ChoreItem>): String {
        val arr = JSONArray()
        for (item in chores) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("rewardMinutes", item.rewardMinutes)
            obj.put("status", item.status.name)
            obj.put("repeatDaily", item.repeatDaily)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun fromJson(json: String?): List<ChoreItem> {
        if (json.isNullOrBlank()) return emptyList()
        val list = mutableListOf<ChoreItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").ifEmpty { "c_$i" }
                val title = obj.optString("title", "Chore")
                val reward = obj.optInt("rewardMinutes", 15)
                val statusStr = obj.optString("status", ChoreStatus.TODO.name)
                val status = try {
                    ChoreStatus.valueOf(statusStr)
                } catch (_: Exception) {
                    ChoreStatus.TODO
                }
                val repeatDaily = obj.optBoolean("repeatDaily", true)
                list.add(ChoreItem(id, title, reward, status, repeatDaily))
            }
        } catch (_: Exception) {
            // Malformed JSON returns empty list
        }
        return list
    }

    /**
     * Resets daily repeating chores back to [ChoreStatus.TODO] (called at midnight),
     * leaving one-time quests in their approved/archived state.
     */
    fun resetDailyChores(chores: List<ChoreItem>): List<ChoreItem> {
        return chores.map { chore ->
            if (chore.repeatDaily) chore.copy(status = ChoreStatus.TODO) else chore
        }
    }
}

/**
 * Family savings goal tied to the physical Piggy Bank (alkansya).
 */
data class PiggyBankGoal(
    val targetPesos: Double = 500.0,
    val label: String = "Family Treat",
) {
    fun progress(currentPesos: Double): Float {
        if (targetPesos <= 0.0) return 1.0f
        return (currentPesos / targetPesos).toFloat().coerceIn(0.0f, 1.0f)
    }
}

/**
 * Policy defining applications always accessible even while screen time is locked.
 */
object AllowedAppsPolicy {
    val DEFAULT_ALLOWED_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.google.android.calculator",
        "com.android.calculator2",
        "com.android.emergency",
    )

    fun isPackageAllowedWhileLocked(pkg: String, customAllowed: Set<String> = emptySet()): Boolean {
        if (pkg.isBlank()) return false
        return pkg in DEFAULT_ALLOWED_PACKAGES || pkg in customAllowed
    }
}

/**
 * Session pausing calculations.
 */
object PauseRules {
    fun calculateRemainingSeconds(nowMonotonic: Long, deadlineMs: Long): Int {
        if (deadlineMs <= nowMonotonic) return 0
        return ((deadlineMs - nowMonotonic) / 1000L).toInt()
    }
}
