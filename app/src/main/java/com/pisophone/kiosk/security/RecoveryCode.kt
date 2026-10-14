package com.pisophone.kiosk.security

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The recovery QR code that removes PisoPhone from a phone without a USB cable (scripts/make_recovery_code.py makes it).
 *
 * "PISOREC1.<expiry, Unix seconds>.<signature, base64url>": an ECDSA P-256 signature, by the owner key, over
 * "pisophone-recover-v1|<expiry>". The phone checks it against the owner public key built into the app
 * (BuildConfig.OWNER_PUBKEY_B64, from tools/pisoportal/owner_key.b64). The code works on a phone that never reached its box,
 * and a code nobody signed with the owner key does nothing. It expires, so a photo of it is useless later; a code that
 * claims to live longer than [MAX_AHEAD_SEC] is refused, so one signed far into the future is not a standing key.
 */
object RecoveryCode {
    const val PREFIX = "PISOREC1."
    const val MAX_AHEAD_SEC = 25L * 3600L

    enum class Result { OK, NOT_RECOVERY, MALFORMED, NO_KEY, EXPIRED, TOO_FAR, BAD_SIGNATURE }

    fun looksLikeRecovery(text: String): Boolean = text.startsWith(PREFIX)

    fun message(expirySec: Long): ByteArray = "pisophone-recover-v1|$expirySec".toByteArray(Charsets.UTF_8)

    fun verify(text: String, ownerPublicKeyB64: String, nowSec: Long): Result {
        if (!looksLikeRecovery(text)) return Result.NOT_RECOVERY
        val parts = text.trim().split('.')
        if (parts.size != 3) return Result.MALFORMED
        val expiry = parts[1].toLongOrNull() ?: return Result.MALFORMED
        val signature = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return Result.MALFORMED
        }
        if (signature.isEmpty() || signature.size > 80) return Result.MALFORMED
        if (ownerPublicKeyB64.isBlank()) return Result.NO_KEY
        // The signature is checked first, so a forged code is never told how long it would have lived.
        val ok = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(ownerPublicKeyB64.trim())))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(message(expiry))
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) return Result.BAD_SIGNATURE
        if (nowSec > expiry) return Result.EXPIRED
        if (expiry - nowSec > MAX_AHEAD_SEC) return Result.TOO_FAR
        return Result.OK
    }

    fun describe(result: Result): String = when (result) {
        Result.OK -> "Recovery code accepted."
        Result.NOT_RECOVERY, Result.MALFORMED -> "That is not a PisoPhone recovery code."
        Result.NO_KEY -> "This app was built without an owner key, so it cannot check recovery codes."
        Result.EXPIRED -> "This recovery code has expired (or this phone's date and time are wrong). Make a new one."
        Result.TOO_FAR -> "This recovery code is valid for too long. Make one with a shorter time."
        Result.BAD_SIGNATURE -> "This recovery code was not made with your owner key."
    }
}
