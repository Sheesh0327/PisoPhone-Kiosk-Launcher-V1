package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Player-account calls to the box (`/api/account/scan|name|signout|info`) and the signed time report that rides on the
 * heartbeat. Mirrors esp32_firmware/include/AccountProtocol.h; both are tested against the vectors in
 * protocol/fixtures/box_phone_v1.json.
 *
 * A player's account is a QR card ("PISO1.<box>.<serial>.<seconds>.<signature>", see scripts/card_format.py). The phone only
 * reads the text; the box checks the card's signature and that it is meant for this box. The card's serial is the account id.
 *
 * Every call is signed: `sig = HMAC(secret, "v1:acct_<op>:<deviceId>:<ts>:<bound>")`. `<bound>` carries every parameter that
 * matters, so a captured request cannot be edited:
 *  - scan:            the card text
 *  - name:            `<id>:<name>`
 *  - signout, report: `<id>:<seconds left>`
 *  - info:            `<id>`
 */
object Esp32AccountRequests {
    const val OP_SCAN = "scan"
    const val OP_NAME = "name"
    const val OP_SIGNOUT = "signout"
    const val OP_INFO = "info"
    const val OP_REPORT = "report"

    private const val CARD_PREFIX = "PISO1."
    private const val MAX_CARD_TEXT = 200
    private val NAME = Regex("^[A-Za-z0-9_-]([A-Za-z0-9 _-]{0,14}[A-Za-z0-9_-])?$")

    /** A display name: 1 to 16 of letters, digits, space, `_` and `-`, not starting or ending with a space. */
    fun isValidName(name: String): Boolean = NAME.matches(name)

    /**
     * A quick look at scanned text, so an unrelated QR code (a website, a Wi-Fi code) is ignored without asking the box.
     * Only the box can tell a real card from a forged one.
     */
    fun looksLikeCard(text: String): Boolean =
        text.length in 40..MAX_CARD_TEXT && text.startsWith(CARD_PREFIX) && text.count { it == '.' } == 4

    fun message(op: String, deviceId: String, ts: String, bound: String): String = "v1:acct_$op:$deviceId:$ts:$bound"

    fun signature(secret: String, op: String, deviceId: String, ts: String, bound: String): String =
        KioskSecurity.calculateHmac(message(op, deviceId, ts, bound), secret)

    /**
     * The URL for one account call.
     * @param id the account (card serial); for [OP_NAME], [OP_SIGNOUT] and [OP_INFO]
     * @param card the scanned card text; for [OP_SCAN]
     * @param name the display name; for [OP_NAME]
     * @param secondsLeft the time the phone still has; for [OP_SIGNOUT], banked into the account
     */
    fun signedUrl(
        host: String,
        port: Int,
        op: String,
        deviceId: String,
        secret: String,
        id: Int = 0,
        card: String = "",
        name: String = "",
        secondsLeft: Int = 0,
        nowMs: Long = BoxClock.nowMs(),
    ): String {
        val ts = nowMs.toString()
        val query = StringBuilder("device_id=${enc(deviceId)}")
        val bound = when (op) {
            OP_SCAN -> {
                query.append("&card=${enc(card)}")
                card
            }
            OP_NAME -> {
                query.append("&id=$id&name=${enc(name)}")
                "$id:$name"
            }
            OP_SIGNOUT -> {
                val left = secondsLeft.coerceAtLeast(0)
                query.append("&id=$id&time=$left")
                "$id:$left"
            }
            else -> {
                query.append("&id=$id")
                "$id"
            }
        }
        val sig = signature(secret, op, deviceId, ts, bound)
        return "http://$host:$port/api/account/$op?$query&ts=$ts&sig=$sig"
    }

    /**
     * The extra heartbeat parameters that report how much time the signed-in player still has. The heartbeat's own
     * signature does not cover them, so they carry their own (`asig`); the box ignores them without it.
     */
    fun heartbeatReport(deviceId: String, secret: String, id: Int, secondsLeft: Int, ts: String): String {
        val left = secondsLeft.coerceAtLeast(0)
        val asig = signature(secret, OP_REPORT, deviceId, ts, "$id:$left")
        return "&acct=$id&atime=$left&asig=$asig"
    }

    /**
     * The box's answer to an account call. [error] is the box's code (`WRONG_BOX`, `ALREADY_SIGNED_IN`, ...) or
     * `NETWORK` / `BAD_REPLY`. [bonusSec] is the starter time this call just gave (only on the first scan of a card).
     */
    data class Reply(
        val success: Boolean,
        val error: String = "",
        val id: Int = 0,
        val name: String = "",
        val balanceSec: Int = 0,
        val bonusSec: Int = 0,
    )

    fun parseReply(body: String): Reply = try {
        val json = JSONObject(body)
        Reply(
            success = json.optBoolean("success", false),
            error = json.optString("error", ""),
            id = json.optInt("id", 0),
            name = json.optString("name", ""),
            balanceSec = json.optLong("balance_sec", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            bonusSec = json.optLong("bonus_sec", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    } catch (_: Exception) {
        Reply(false, "BAD_REPLY")
    }

    /** Short text for the player for each error code. */
    fun describe(error: String): String = when (error) {
        "BAD_CARD" -> "That is not a valid PisoPhone card."
        "WRONG_BOX" -> "This card is for a different PisoPhone."
        "CARDS_OFF" -> "Cards are not set up on this PisoPhone yet. Ask the attendant."
        "BAD_NAME" -> "Use 1 to 16 letters, numbers, spaces, _ or -."
        "ACCOUNTS_FULL" -> "No room for more accounts. Ask the attendant."
        "NO_SUCH_ACCOUNT" -> "No account for that card."
        "ALREADY_SIGNED_IN" -> "This card is already in use on another phone."
        "NOT_SIGNED_IN" -> "Not signed in."
        "SESSION_ACTIVE" -> "Finish your current time first."
        "TOO_FAST" -> "Please wait a moment and try again."
        "SLOT_NOT_PAIRED", "SLOT_EXPIRED" -> "This phone is not active. Ask the attendant."
        "SETUP_REQUIRED" -> "The box is not set up yet."
        "ACCOUNTS_OFF" -> "Accounts are not available on this box."
        "CLOCK_UNKNOWN" -> "The box is starting up. Try again in a few seconds."
        "NETWORK" -> "Cannot reach the box."
        else -> "Something went wrong ($error)."
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
