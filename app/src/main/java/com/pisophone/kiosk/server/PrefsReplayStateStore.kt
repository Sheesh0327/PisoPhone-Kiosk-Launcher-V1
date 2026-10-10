package com.pisophone.kiosk.server

import android.content.Context
import com.pisophone.kiosk.security.KioskSecurity

/** Keeps the replay state in device-protected storage (readable before the phone is unlocked, like the server itself). */
class PrefsReplayStateStore(private val context: Context) : ReplayStateStore {
    private companion object {
        const val PREFS = "kiosk_replay_state"
        const val KEY = "box_message_state"
    }

    private val prefs by lazy { KioskSecurity.getDirectBootPrefs(context.applicationContext ?: context, PREFS) }

    override fun load(): String = try {
        prefs.getString(KEY, "") ?: ""
    } catch (_: Exception) {
        ""
    }

    override fun save(state: String) {
        try {
            prefs.edit().putString(KEY, state).apply()
        } catch (_: Exception) {
            // see RequestFreshness.save: the message in hand was already judged
        }
    }
}
