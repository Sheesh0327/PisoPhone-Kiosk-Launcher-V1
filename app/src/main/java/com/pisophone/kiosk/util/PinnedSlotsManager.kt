package com.pisophone.kiosk.util

import android.content.Context

object PinnedSlotsManager {
    private const val PREFS_NAME = "kiosk_pinned_slots_prefs"
    private const val KEY_PREFIX = "pinned_slot_"

    fun getPinnedSlots(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (0 until 4).map { index ->
            sp.getString("$KEY_PREFIX$index", "") ?: ""
        }
    }

    fun setPinnedSlot(context: Context, slotIndex: Int, packageName: String) {
        if (slotIndex in 0..3) {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString("$KEY_PREFIX$slotIndex", packageName).apply()
        }
    }

    fun clearPinnedSlot(context: Context, slotIndex: Int) {
        if (slotIndex in 0..3) {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().remove("$KEY_PREFIX$slotIndex").apply()
        }
    }
}
