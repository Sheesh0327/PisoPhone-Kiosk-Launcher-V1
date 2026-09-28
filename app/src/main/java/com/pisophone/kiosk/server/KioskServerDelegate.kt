package com.pisophone.kiosk.server

import com.pisophone.kiosk.repository.PaymentResult

interface KioskServerDelegate {
    fun isInitialized(): Boolean
    fun getDeviceId(): String
    fun getSecretKey(): String
    fun onCreditPayment(
        txId: String,
        seconds: Int,
        amount: Double,
        operationKind: String = "QUICK_ADJUST",
        coinAmount: Int = amount.toInt(),
        pricePerCoin: Double = 0.0,
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult

    fun onDeductPayment(
        seconds: Int,
        txId: String? = null,
        operationKind: String = "MANUAL_DEDUCTION",
        boxInstallationEpoch: Long = 0L,
        phonePairingEpoch: Long = 0L
    ): PaymentResult

    fun onConfigSynced(
        price: Double?,
        minutes: Int?,
        alias: String?,
        adminPin: String?,
        slotNum: Int?
    )

    fun onTriggerAction(action: String, params: Map<String, String>): Boolean
}
