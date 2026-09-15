package com.pisophone.kiosk.repository

/**
 * Result states for processing coin top-up transactions.
 */
enum class PaymentResult {
    /** Payment was successfully credited and committed to the database. */
    APPLIED,

    /** Identical receipt already exists; acknowledged without extending time. */
    ALREADY_APPLIED,

    /** Transaction ID already exists but with different payment values. */
    CONFLICT,

    /** Device is not currently eligible to accept new payments (e.g. lockdown). */
    NOT_ELIGIBLE,

    /** Database or internal transaction error; transaction was rolled back. */
    FAILED
}
