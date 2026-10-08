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

        /** 403 because the phone's clock is outside the box's window: the refusal carries the box's time, the phone adjusts. */
        CLOCK_SKEW,

        /** 409: another device holds the coin slot. */
        BUSY,

        /** Anything else (a timeout, a 5xx): not a statement about this phone. */
        OTHER,
    }

    fun classify(code: Int, body: String = ""): Refusal = when {
        code == 423 -> Refusal.SLOT_LOCKED
        code == 403 && body.contains("SETUP_REQUIRED") -> Refusal.SETUP_REQUIRED
        code == 403 && body.contains("STALE_TIMESTAMP") -> Refusal.CLOCK_SKEW
        code == 403 -> Refusal.AUTH_REFUSED
        code == 409 -> Refusal.BUSY
        else -> Refusal.OTHER
    }

    /** The box does not recognise this phone's key (a different secret than the phone has). */
    fun badSecretMessage(): String =
        "The coin box does not recognise this phone's key. On the box's dashboard press Save (it sends the key to every phone), or set the phone up again."

    /** What to tell the person for a refusal that is not about the slot. */
    fun authRefusedMessage(): String =
        "The coin box does not accept this phone (HTTP 403). Check the phone's date and time, or provision the phone again."

    /**
     * Why an arm attempt failed, as the phone shows it. Only [BUSY] means another device holds the coin slot; the others
     * are the box refusing this phone or the phone not reaching the box, and tapping again will not fix them.
     */
    enum class ArmFailure(val label: String, val advice: String) {
        BUSY("COINSLOT BUSY", "Another device is using the coin slot. Try again in a moment."),
        NOT_PAIRED("NOT PAIRED YET", "The box has not paired this phone to a slot. Pair it on the box's dashboard."),
        BOX_REFUSED("BOX REFUSED PHONE", "The box does not accept this phone. Save the box settings to push the secret, or provision the phone again."),
        SETUP_REQUIRED("BOX SETUP NEEDED", "The box's admin password has not been changed yet."),
        BOX_NOT_FOUND("BOX NOT FOUND", "The phone cannot find the coin box on this Wi-Fi."),
        BOX_UNREACHABLE("CAN'T REACH BOX", "The coin box did not answer. Check that it is on and on the same Wi-Fi."),
        BOX_ERROR("BOX ERROR", "The coin box could not start the coin slot. Check its diagnostics."),
    }

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
