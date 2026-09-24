package com.pisophone.kiosk.server

import com.pisophone.kiosk.repository.PaymentResult
import org.json.JSONObject

interface KioskServerDelegate {
    fun isReady(): Boolean = true
    fun getSecretKey(): String
    fun getDeviceId(): String = ""
    fun onHeartbeat(clientIp: String?)
    fun getStatusJson(): JSONObject
    fun getSessionTimeRemaining(): Int
    fun getAppState(): Int
    fun getAuditEventsJson(): String
    fun creditPayment(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String,
        coinAmount: Int,
        pricePerCoin: Double,
        boxInstallationEpoch: Long,
        phonePairingEpoch: Long
    ): PaymentResult

    fun onDeductTime(
        seconds: Int,
        txId: String?,
        operationKind: String,
        boxInstallationEpoch: Long,
        phonePairingEpoch: Long
    ): PaymentResult

    fun onConfigUpdated(price: Double?, minutes: Int?, deviceName: String?, adminPin: String?, slotNum: Int? = null)
    fun onTriggerAction(action: String, slotNum: Int? = null, extra: Map<String, String>? = null)
    fun getCrashLog(): String?
}
