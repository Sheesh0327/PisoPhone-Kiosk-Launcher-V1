package com.pisophone.kiosk.util

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small log of the events that matter when a rental box misbehaves (ESP32 link changes,
 * coin credits, arm failures, lockdowns, admin actions). The admin vault shows it, so a problem can
 * be diagnosed on site without a computer. Bounded, thread-safe, and holds no personal data.
 *
 * Once [persistTo] is called the lines are also kept in a small file, so the log survives an app restart or a
 * reboot (which is often exactly when it is needed). The file is rewritten from memory before it grows past
 * twice [MAX_LINES].
 */
object DiagnosticsLog {
    const val MAX_LINES = 200

    /** Replaced in tests so output does not depend on the real clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var file: File? = null
    private var linesInFile = 0

    /** Loads the lines kept by an earlier run from [target] and keeps writing to it. Later calls are ignored. */
    @Synchronized
    fun persistTo(target: File) {
        if (file != null) return
        val earlier = try {
            if (target.exists()) target.readLines().filter { it.isNotBlank() } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val current = lines.toList()
        lines.clear()
        (earlier + current).takeLast(MAX_LINES).forEach { lines.addLast(it) }
        file = target
        rewriteFile()
    }

    @Synchronized
    fun add(tag: String, message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        val line = "${format.format(Date(clock()))} [$tag] ${message.replace('\n', ' ').replace('\r', ' ')}"
        lines.addLast(line)
        val target = file ?: return
        if (linesInFile + 1 > MAX_LINES * 2) {
            rewriteFile()
            return
        }
        try {
            target.appendText(line + "\n")
            linesInFile++
        } catch (_: Exception) {
            // A full or read-only disk must never break the kiosk; the in-memory log still works.
        }
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        rewriteFile()
    }

    /** Test hook: forget the file without touching it. */
    @Synchronized
    internal fun detach() {
        file = null
        linesInFile = 0
    }

    private fun rewriteFile() {
        val target = file ?: return
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
            linesInFile = lines.size
        } catch (_: Exception) {
        }
    }
}
