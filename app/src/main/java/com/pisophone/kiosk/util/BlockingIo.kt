package com.pisophone.kiosk.util

import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The one place the app blocks a thread on a coroutine. It exists for callers that are themselves synchronous
 * (the HTTP/WebSocket handlers, the delegates they call) and must wait for a database result. Everything that
 * already runs in a coroutine calls the suspend functions directly instead.
 *
 * Blocking the main thread freezes the screen and can trigger an "app not responding" report, so a call from
 * the main thread is logged loudly (and kept in the diagnostics) rather than hidden.
 */
internal fun <T> blockingIo(block: suspend () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Log.e("BlockingIo", "Blocking database call on the main thread", Throwable("main-thread blocking call"))
        DiagnosticsLog.add("BLOCKING", "database call blocked the main thread")
    }
    return runBlocking(Dispatchers.IO) { block() }
}
