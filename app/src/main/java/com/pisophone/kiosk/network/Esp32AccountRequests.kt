package com.pisophone.kiosk.network

import com.pisophone.kiosk.security.KioskSecurity
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Player-account calls to the box (`/api/account/create|signin|signout|info`) and the signed time report that rides on
 * the heartbeat. Mirrors esp32_firmware/include/AccountProtocol.h; both are tested against the vectors in
 * protocol/fixtures/box_phone_v1.json.
 *
 * Every call is signed: `sig = HMAC(secret, "v1:acct_<op>:<deviceId>:<ts>:<bound>")`. `<bound>` carries every parameter
 * that matters, so a captured request cannot be edited:
 *  - create / signin: `<user>:<hex AES of "PIN:<pin>">`   (the PIN never travels in clear)
 *  - signout, report: `<user>:<seconds left>`
 *  - info:            `<user>`
 */
object Esp32AccountRequests {
    const val OP_CREATE = "create"
    const val OP_SIGNIN = "signin"
    const val OP_SIGNOUT = "signout"
    const val OP_INFO = "info"
    const val OP_REPORT = "report"

    private val USERNAME = Regex("^[a-z0-9_]{3,16}$")
    private val PIN = Regex("^[0-9]{4,6}$")

    /** Usernames are case-insensitive: always sent in lowercase. */
    fun normalizeUsername(name: String): String = name.trim().lowercase()

    fun isValidUsername(name: String): Boolean = USERNAME.matches(name)

    fun isValidPin(pin: String): Boolean = PIN.matches(pin)

    fun message(op: String, deviceId: String, ts: String, bound: String): String = "v1:acct_$op:$deviceId:$ts:$bound"

    fun signature(secret: String, op: String, deviceId: String, ts: String, bound: String): String =
        KioskSecurity.calculateHmac(message(op, deviceId, ts, bound), secret)

    fun encryptPin(pin: String, secret: String): String = KioskSecurity.encrypt("PIN:$pin", secret)

    /**
     * The URL for one account call.
     * @param pin only for [OP_CREATE] and [OP_SIGNIN]; it is encrypted here
     * @param secondsLeft only for [OP_SIGNOUT]: the time the phone still has, banked into the account
     */
    fun signedUrl(
        host: String,
        port: Int,
        op: String,
        deviceId: String,
        secret: String,
        username: String,
        pin: String = "",
        secondsLeft: Int = 0,
        nowMs: Long = BoxClock.nowMs(),
    ): String {
        val user = normalizeUsername(username)
        val ts = nowMs.toString()
        val query = StringBuilder("device_id=${enc(deviceId)}&user=$user")
        val bound = when (op) {
            OP_CREATE, OP_SIGNIN -> {
                val pinEnc = encryptPin(pin, secret)
                query.append("&pin_enc=$pinEnc")
                "$user:$pinEnc"
            }
            OP_SIGNOUT -> {
                val left = secondsLeft.coerceAtLeast(0)
                query.append("&time=$left")
                "$user:$left"
            }
            else -> user
        }
        val sig = signature(secret, op, deviceId, ts, bound)
        return "http://$host:$port/api/account/$op?$query&ts=$ts&sig=$sig"
    }

    /**
     * The extra heartbeat parameters that report how much time the signed-in player still has. The heartbeat's own
     * signature does not cover them, so they carry their own (`asig`); the box ignores them without it.
     */
    fun heartbeatReport(deviceId: String, secret: String, username: String, secondsLeft: Int, ts: String): String {
        val user = normalizeUsername(username)
        val left = secondsLeft.coerceAtLeast(0)
        val asig = signature(secret, OP_REPORT, deviceId, ts, "$user:$left")
        return "&acct=$user&atime=$left&asig=$asig"
    }

    /** The box's answer to an account call. [error] is the box's code (`BAD_PIN`, `LOCKED`, ...) or `NETWORK`/`BAD_REPLY`. */
    data class Reply(
        val success: Boolean,
        val error: String = "",
        val username: String = "",
        val balanceSec: Int = 0,
    )

    fun parseReply(body: String): Reply = try {
        val json = JSONObject(body)
        Reply(
            success = json.optBoolean("success", false),
            error = json.optString("error", ""),
            username = json.optString("username", ""),
            balanceSec = json.optLong("balance_sec", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        )
    } catch (_: Exception) {
        Reply(false, "BAD_REPLY")
    }

    /** Short text for the player for each error code. */
    fun describe(error: String): String = when (error) {
        "BAD_NAME" -> "Use 3 to 16 letters, numbers or _."
        "BAD_PIN_FORMAT" -> "The PIN must be 4 to 6 digits."
        "NAME_TAKEN" -> "That name is taken."
        "ACCOUNTS_FULL" -> "No room for more accounts. Ask the attendant."
        "NO_SUCH_USER" -> "No account with that name."
        "BAD_PIN" -> "Wrong PIN."
        "LOCKED" -> "Too many wrong PINs. Try again later."
        "ALREADY_SIGNED_IN" -> "That account is signed in on another phone."
        "NOT_SIGNED_IN" -> "Not signed in."
        "TOO_FAST" -> "Please wait a moment and try again."
        "SLOT_NOT_PAIRED", "SLOT_EXPIRED" -> "This phone is not active. Ask the attendant."
        "SETUP_REQUIRED" -> "The box is not set up yet."
        "ACCOUNTS_OFF" -> "Accounts are not available on this box."
        "NETWORK" -> "Cannot reach the box."
        else -> "Something went wrong ($error)."
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
