package com.pisophone.kiosk.util

import android.content.Context
import android.content.SharedPreferences
import com.pisophone.kiosk.security.KioskSecurity

/**
 * The owner's switches for how the phone behaves between customers: how loud and when it speaks, when an idle lock screen
 * turns the screen off, when the social apps are wiped, and whether picking the phone up wakes it. Stored in the
 * device-protected preferences, so they are the same before and after the phone is unlocked.
 */
object KioskSettings {
    private const val PREFS = "kiosk_settings"

    private const val KEY_SPEECH_ENABLED = "speech_enabled"
    private const val KEY_SPEECH_VOLUME = "speech_volume_pct"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start_hour"
    private const val KEY_QUIET_END = "quiet_end_hour"
    private const val KEY_SCREEN_OFF_ENABLED = "screen_off_enabled"
    private const val KEY_SCREEN_OFF_MINUTES = "screen_off_minutes"
    private const val KEY_WAKE_ON_PICKUP = "wake_on_pickup"
    private const val KEY_CLEAR_ENABLED = "guest_clear_enabled"
    private const val KEY_CLEAR_MINUTES = "guest_clear_minutes"
    private const val KEY_SOCIAL_EXTRA = "social_extra"
    private const val KEY_SOCIAL_EXCLUDED = "social_excluded"
    private const val KEY_IS_HOME_MODE = "is_home_mode"
    private const val KEY_CURFEW_ENABLED = "curfew_enabled"
    private const val KEY_CURFEW_START = "curfew_start_min"
    private const val KEY_CURFEW_END = "curfew_end_min"
    private const val KEY_PIGGY_TARGET = "piggy_target_pesos"
    private const val KEY_PIGGY_LABEL = "piggy_label"
    private const val KEY_CHORES_JSON = "chores_json"
    private const val KEY_ALLOWED_APPS = "allowed_apps_while_locked"

    const val DEFAULT_IDLE_MINUTES = 5
    const val MIN_IDLE_MINUTES = 1
    const val MAX_IDLE_MINUTES = 60

    private fun prefs(context: Context): SharedPreferences = KioskSecurity.getDirectBootPrefs(context.applicationContext, PREFS)

    // --- speech ---
    fun isSpeechEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SPEECH_ENABLED, true)

    fun setSpeechEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_SPEECH_ENABLED, enabled).apply()

    /** How loud spoken messages are, 20 to 100 percent of the alarm volume. */
    fun getSpeechVolumePercent(context: Context): Int = prefs(context).getInt(KEY_SPEECH_VOLUME, 100).coerceIn(20, 100)

    fun setSpeechVolumePercent(context: Context, percent: Int) = prefs(context).edit().putInt(KEY_SPEECH_VOLUME, percent.coerceIn(20, 100)).apply()

    fun isQuietHoursEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_QUIET_ENABLED, false)

    fun setQuietHoursEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_QUIET_ENABLED, enabled).apply()

    fun getQuietStartHour(context: Context): Int = prefs(context).getInt(KEY_QUIET_START, 22).coerceIn(0, 23)

    fun getQuietEndHour(context: Context): Int = prefs(context).getInt(KEY_QUIET_END, 7).coerceIn(0, 23)

    fun setQuietHours(context: Context, startHour: Int, endHour: Int) =
        prefs(context).edit().putInt(KEY_QUIET_START, startHour.coerceIn(0, 23)).putInt(KEY_QUIET_END, endHour.coerceIn(0, 23)).apply()

    /** True when spoken messages must stay silent right now (quiet hours on and the clock inside them). */
    fun isQuietNow(context: Context, hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Boolean =
        isQuietHoursEnabled(context) && QuietHours.contains(hour, getQuietStartHour(context), getQuietEndHour(context))

    // --- idle lock screen ---
    fun isScreenOffEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SCREEN_OFF_ENABLED, true)

    fun setScreenOffEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_SCREEN_OFF_ENABLED, enabled).apply()

    fun getScreenOffMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SCREEN_OFF_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)

    fun setScreenOffMinutes(context: Context, minutes: Int) =
        prefs(context).edit().putInt(KEY_SCREEN_OFF_MINUTES, minutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)).apply()

    fun isWakeOnPickupEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_ON_PICKUP, true)

    fun setWakeOnPickupEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_WAKE_ON_PICKUP, enabled).apply()

    // --- guest reset (social apps) ---
    /** Disabled unconditionally in Home mode to protect child app data and game saves. */
    fun isGuestClearEnabled(context: Context): Boolean =
        !isHomeMode(context) && prefs(context).getBoolean(KEY_CLEAR_ENABLED, true)

    fun setGuestClearEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_CLEAR_ENABLED, enabled).apply()

    fun getGuestClearMinutes(context: Context): Int =
        prefs(context).getInt(KEY_CLEAR_MINUTES, DEFAULT_IDLE_MINUTES).coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)

    fun setGuestClearMinutes(context: Context, minutes: Int) =
        prefs(context).edit().putInt(KEY_CLEAR_MINUTES, minutes.coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)).apply()

    /** Apps the owner added to the wipe list (package names). */
    fun getSocialExtra(context: Context): Set<String> = prefs(context).getStringSet(KEY_SOCIAL_EXTRA, emptySet())?.toSet() ?: emptySet()

    /** Apps of the built-in list the owner took off it. */
    fun getSocialExcluded(context: Context): Set<String> = prefs(context).getStringSet(KEY_SOCIAL_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun setSocialLists(context: Context, extra: Set<String>, excluded: Set<String>) =
        prefs(context).edit().putStringSet(KEY_SOCIAL_EXTRA, extra).putStringSet(KEY_SOCIAL_EXCLUDED, excluded).apply()

    // --- PisoHome / PisoKids settings ---
    fun isHomeMode(context: Context): Boolean {
        val buildFlag = runCatching {
            Class.forName("com.pisophone.kiosk.BuildConfig")
                .getField("IS_HOME_PRODUCT")
                .getBoolean(null)
        }.getOrDefault(false)
        return buildFlag || prefs(context).getBoolean(KEY_IS_HOME_MODE, false)
    }

    fun setHomeMode(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_IS_HOME_MODE, enabled).apply()

    fun getCurfewSchedule(context: Context): CurfewSchedule {
        val p = prefs(context)
        return CurfewSchedule(
            enabled = p.getBoolean(KEY_CURFEW_ENABLED, false),
            startMinuteOfDay = p.getInt(KEY_CURFEW_START, 21 * 60),
            endMinuteOfDay = p.getInt(KEY_CURFEW_END, 6 * 60),
        )
    }

    fun setCurfewSchedule(context: Context, schedule: CurfewSchedule) {
        prefs(context).edit()
            .putBoolean(KEY_CURFEW_ENABLED, schedule.enabled)
            .putInt(KEY_CURFEW_START, schedule.startMinuteOfDay)
            .putInt(KEY_CURFEW_END, schedule.endMinuteOfDay)
            .apply()
    }

    fun getPiggyBankGoal(context: Context): PiggyBankGoal {
        val p = prefs(context)
        val target = p.getString(KEY_PIGGY_TARGET, "500.0")?.toDoubleOrNull() ?: 500.0
        val label = p.getString(KEY_PIGGY_LABEL, "Weekend Pizza") ?: "Weekend Pizza"
        return PiggyBankGoal(targetPesos = target, label = label)
    }

    fun setPiggyBankGoal(context: Context, goal: PiggyBankGoal) {
        prefs(context).edit()
            .putString(KEY_PIGGY_TARGET, goal.targetPesos.toString())
            .putString(KEY_PIGGY_LABEL, goal.label)
            .apply()
    }

    fun getChores(context: Context): List<ChoreItem> {
        val json = prefs(context).getString(KEY_CHORES_JSON, "")
        if (json.isNullOrBlank()) {
            return listOf(
                ChoreItem("c1", "Wash the Dishes", 20, ChoreStatus.TODO, repeatDaily = true),
                ChoreItem("c2", "Clean Bedroom", 15, ChoreStatus.TODO, repeatDaily = true),
                ChoreItem("c3", "Finish Math Homework", 30, ChoreStatus.TODO, repeatDaily = false),
            )
        }
        return ChoreBoardCodec.fromJson(json)
    }

    fun setChores(context: Context, chores: List<ChoreItem>) {
        prefs(context).edit().putString(KEY_CHORES_JSON, ChoreBoardCodec.toJson(chores)).apply()
    }

    fun getAllowedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED_APPS, emptySet())?.toSet() ?: emptySet()

    fun setAllowedApps(context: Context, apps: Set<String>) =
        prefs(context).edit().putStringSet(KEY_ALLOWED_APPS, apps).apply()
}

/** Quiet hours as a pure rule: [startHour] to [endHour], and the window may cross midnight (22 to 7). */
object QuietHours {
    fun contains(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }
}
