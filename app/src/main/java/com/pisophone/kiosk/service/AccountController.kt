package com.pisophone.kiosk.service

import com.pisophone.kiosk.network.Esp32AccountRequests
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bridge between the lock screen / floating pill (which only draw and call this) and [KioskEngine] (which talks to
 * the box). Same pattern as `AdminMaintenanceMode.suspendOverlays`: a small object the UI can reach without every
 * overlay class passing the engine around.
 */
object AccountController {
    interface Handler {
        /**
         * Signs in (or creates the account and then signs in). The answer arrives on the main thread. On success the
         * account's time is already running on this phone.
         */
        fun submit(op: String, username: String, pin: String, onDone: (Esp32AccountRequests.Reply) -> Unit)

        /** Banks the time left into the account and locks the phone. */
        fun signOut()
    }

    /** The player signed in on this phone, or empty. */
    val signedIn = MutableStateFlow("")

    @Volatile
    var handler: Handler? = null

    fun submit(op: String, username: String, pin: String, onDone: (Esp32AccountRequests.Reply) -> Unit) {
        val h = handler
        if (h == null) onDone(Esp32AccountRequests.Reply(false, "NETWORK")) else h.submit(op, username, pin, onDone)
    }

    fun signOut() {
        handler?.signOut()
    }
}
