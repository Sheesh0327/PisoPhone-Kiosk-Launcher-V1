package com.pisophone.kiosk.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure cryptographic and protocol framing helpers for the PisoPhone contract (PISOPHONE-PROTOCOL-V1).
 * Independent of Android framework classes, ensuring complete testability on local JVM.
 */
object KioskProtocol {
    const val PROTOCOL_VERSION = "PISOPHONE-PROTOCOL-V1"
    const val MAX_TIMESTAMP_SKEW_MS = 60000L

    /**
     * Pure UTF-8 byte-length prefix framing: "<byte_length>:<field>"
     * Guarantees exact parity with C++ strlen on UTF-8 strings.
     */
    fun utf8Frame(field: String): String {
        val bytes = field.toByteArray(Charsets.UTF_8)
        return "${bytes.size}:$field"
    }

    fun formatHttpReqData(
        method: String,
        endpoint: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String
    ): String {
        return utf8Frame("HTTP_REQ") +
                utf8Frame(method) +
                utf8Frame(endpoint) +
                utf8Frame(recipient) +
                utf8Frame(txId) +
                utf8Frame(ts) +
                utf8Frame(payload)
    }

    fun formatWsPayData(
        event: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String
    ): String {
        return utf8Frame("WS_PAY") +
                utf8Frame(event) +
                utf8Frame(recipient) +
                utf8Frame(txId) +
                utf8Frame(ts) +
                utf8Frame(payload)
    }

    fun formatAckData(
        deviceId: String,
        txId: String,
        amount: Int,
        seconds: Int,
        ts: String,
        status: String
    ): String {
        return utf8Frame("ACK") +
                utf8Frame(deviceId) +
                utf8Frame(txId) +
                utf8Frame(amount.toString()) +
                utf8Frame(seconds.toString()) +
                utf8Frame(ts) +
                utf8Frame(status)
    }

    fun calculateHmac(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        val aLower = a.lowercase()
        val bLower = b.lowercase()
        if (aLower.length != bLower.length) return false
        var result = 0
        for (i in aLower.indices) {
            result = result or (aLower[i].code xor bLower[i].code)
        }
        return result == 0
    }

    fun calculateHttpReqSignature(
        method: String,
        endpoint: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        secret: String
    ): String {
        return calculateHmac(formatHttpReqData(method, endpoint, recipient, txId, ts, payload), secret)
    }

    fun verifyHttpReqSignature(
        method: String,
        endpoint: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        sig: String,
        secret: String
    ): Boolean {
        if (sig.isBlank()) return false
        val expected = calculateHttpReqSignature(method, endpoint, recipient, txId, ts, payload, secret)
        return constantTimeEquals(sig, expected)
    }

    fun calculateWsPaySignature(
        event: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        secret: String
    ): String {
        return calculateHmac(formatWsPayData(event, recipient, txId, ts, payload), secret)
    }

    fun verifyWsPaySignature(
        event: String,
        recipient: String,
        txId: String,
        ts: String,
        payload: String,
        sig: String,
        secret: String
    ): Boolean {
        if (sig.isBlank()) return false
        val expected = calculateWsPaySignature(event, recipient, txId, ts, payload, secret)
        return constantTimeEquals(sig, expected)
    }

    fun calculateAckSignature(
        deviceId: String,
        txId: String,
        amount: Int,
        seconds: Int,
        ts: String,
        status: String,
        secret: String
    ): String {
        return calculateHmac(formatAckData(deviceId, txId, amount, seconds, ts, status), secret)
    }

    fun verifyAckSignature(
        deviceId: String,
        txId: String,
        amount: Int,
        seconds: Int,
        ts: String,
        status: String,
        sig: String,
        secret: String
    ): Boolean {
        if (sig.isBlank()) return false
        val expected = calculateAckSignature(deviceId, txId, amount, seconds, ts, status, secret)
        return constantTimeEquals(sig, expected)
    }

    data class VerifiedEnvelope(
        val event: String,
        val recipient: String,
        val txId: String,
        val ts: Long,
        val payload: String,
        val seconds: Int? = null,
        val amount: Double? = null
    )

    data class DecryptedPaymentContext(
        val deviceId: String,
        val txId: String,
        val ts: Long,
        val seconds: Int,
        val amount: Double,
        val minutes: Int? = null
    )

    sealed class ProtocolValidationResult<out T> {
        data class Valid<out T>(val value: T) : ProtocolValidationResult<T>()
        data class Invalid(val reason: String, val code: String) : ProtocolValidationResult<Nothing>()
    }

    fun reconcilePaymentContext(
        envelope: VerifiedEnvelope,
        decrypted: DecryptedPaymentContext
    ): ProtocolValidationResult<DecryptedPaymentContext> {
        if (envelope.recipient.isNotBlank() && decrypted.deviceId.isNotBlank() &&
            !envelope.recipient.equals(decrypted.deviceId, ignoreCase = true)
        ) {
            return ProtocolValidationResult.Invalid("Envelope recipient does not match decrypted deviceId", "MUTATED_DEVICE_ID")
        }
        if (envelope.txId.isNotBlank() && decrypted.txId.isNotBlank() &&
            envelope.txId != decrypted.txId
        ) {
            return ProtocolValidationResult.Invalid("Envelope tx_id does not match decrypted tx_id", "MUTATED_TRANSACTION_ID")
        }
        if (envelope.ts > 0L && decrypted.ts > 0L && envelope.ts != decrypted.ts) {
            return ProtocolValidationResult.Invalid("Envelope ts does not match decrypted ts", "MUTATED_TIMESTAMP")
        }
        if (envelope.seconds != null && envelope.seconds != decrypted.seconds) {
            return ProtocolValidationResult.Invalid("Envelope seconds does not match decrypted seconds", "MUTATED_SECONDS")
        }
        if (envelope.amount != null && envelope.amount != decrypted.amount) {
            return ProtocolValidationResult.Invalid("Envelope amount does not match decrypted amount", "MUTATED_AMOUNT")
        }
        return ProtocolValidationResult.Valid(decrypted)
    }
}
