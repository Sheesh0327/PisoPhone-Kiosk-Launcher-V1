package com.pisophone.kiosk.network

/**
 * How the phone reads the coin box's refusals, and when it gives the box up as offline.
 *
 * The box answers 423 when the slot is not paired, expired or locked, and 403 when it does not accept the request itself:
 * a missing or wrong signature (a different box secret), a timestamp outside its clock window, or a box whose admin
 * password has not been changed yet. Only 423 means "this device is not activated". Treating a 403 the same way told
 * people their device was not activated while the box's dashboard showed it paired.
 */
object Esp32Responses {
    enum class Refusal {
        /** 423: the slot is expired, locked or not paired to this phone. */
        SLOT_LOCKED,

        /** 403 with SETUP_REQUIRED: the owner has not changed the box's admin password. */
        SETUP_REQUIRED,

        /** Any other 403: the box does not accept this phone's signature or clock. Provision the phone again. */
        AUTH_REFUSED,

        /** 409: another device holds the coin slot. */
        BUSY,

        /** Anything else (a timeout, a 5xx): not a statement about this phone. */
        OTHER,
    }

    fun classify(code: Int, body: String = ""): Refusal = when {
        code == 423 -> Refusal.SLOT_LOCKED
        code == 403 && body.contains("SETUP_REQUIRED") -> Refusal.SETUP_REQUIRED
        code == 403 -> Refusal.AUTH_REFUSED
        code == 409 -> Refusal.BUSY
        else -> Refusal.OTHER
    }

    /** What to tell the person for a refusal that is not about the slot. */
    fun authRefusedMessage(): String =
        "The coin box does not accept this phone (HTTP 403). Check the phone's date and time, or provision the phone again."

    private const val OFFLINE_AFTER_FAILURES = 3
    private const val OFFLINE_AFTER_MS = 20_000L
    private const val FORGET_ADDRESS_AFTER_FAILURES = 6
    private const val FORGET_ADDRESS_AFTER_MS = 45_000L

    /**
     * One slow or lost answer is normal on Wi-Fi (the box is a small single-threaded server): show "offline" only after
     * several failures in a row or a long silence.
     */
    fun shouldMarkOffline(consecutiveFailures: Int, msSinceLastGood: Long): Boolean =
        consecutiveFailures >= OFFLINE_AFTER_FAILURES || msSinceLastGood > OFFLINE_AFTER_MS

    /** The box's address is dropped, and found again by discovery, only after a sustained outage. */
    fun shouldForgetAddress(consecutiveFailures: Int, msSinceLastGood: Long): Boolean =
        consecutiveFailures >= FORGET_ADDRESS_AFTER_FAILURES || msSinceLastGood > FORGET_ADDRESS_AFTER_MS
}
