package com.pisophone.kiosk.service

import android.content.Context
import com.pisophone.kiosk.network.Esp32AccountRequests
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The bridge between the card scanner / floating pill (which only draw and call this) and [KioskEngine] (which talks to the
 * box). Same pattern as `AdminMaintenanceMode.suspendOverlays`: a small object the UI can reach without every screen passing
 * the engine around.
 */
object AccountController {
    interface Handler {
        /** Opens the card scanner screen. */
        fun startScan(context: Context)

        /**
         * Signs in with a scanned card. The answer arrives on the main thread. On success the account's time (including the
         * starter time on a card's first scan) is already running on this phone.
         */
        fun scanCard(card: String, onDone: (Esp32AccountRequests.Reply) -> Unit)

        /** Gives the signed-in account its display name. */
        fun setName(name: String, onDone: (Esp32AccountRequests.Reply) -> Unit)

        /** Banks the time left into the account and locks the phone. */
        fun signOut()
    }

    /** The card number of the player signed in on this phone, or empty. */
    val signedIn = MutableStateFlow("")

    /** That player's display name, or empty until they choose one. */
    val signedName = MutableStateFlow("")

    /** True while the card scanner is on screen: the lock screen and pill are taken off so they do not cover the camera. */
    val scanning = MutableStateFlow(false)

    @Volatile
    var handler: Handler? = null

    fun startScan(context: Context) {
        handler?.startScan(context)
    }

    fun scanCard(card: String, onDone: (Esp32AccountRequests.Reply) -> Unit) {
        val h = handler
        if (h == null) onDone(Esp32AccountRequests.Reply(false, "NETWORK")) else h.scanCard(card, onDone)
    }

    fun setName(name: String, onDone: (Esp32AccountRequests.Reply) -> Unit) {
        val h = handler
        if (h == null) onDone(Esp32AccountRequests.Reply(false, "NETWORK")) else h.setName(name, onDone)
    }

    fun signOut() {
        handler?.signOut()
    }

    /** What to call the signed-in player on screen. */
    fun displayName(id: String, name: String): String = name.ifBlank { "Card ${id.padStart(5, '0')}" }
}
