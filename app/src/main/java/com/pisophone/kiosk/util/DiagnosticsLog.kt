package com.pisophone.kiosk.util

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory log of the events that matter when a rental box misbehaves (ESP32 link changes,
 * coin credits, arm failures, lockdowns, admin actions). The admin vault shows it, so a problem can
 * be diagnosed on site without a computer. Bounded, thread-safe, and holds no personal data.
 */
object DiagnosticsLog {
    const val MAX_LINES = 200

    /** Replaced in tests so output does not depend on the real clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(tag: String, message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${format.format(Date(clock()))} [$tag] ${message.replace('\n', ' ')}")
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}
