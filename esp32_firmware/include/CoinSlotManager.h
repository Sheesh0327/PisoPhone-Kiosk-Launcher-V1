#ifndef COIN_SLOT_MANAGER_H
#define COIN_SLOT_MANAGER_H

#include <Arduino.h>
#include <functional>

// ============================================================================
// COIN SLOT SUBSYSTEM TYPES & CALLBACKS
// ============================================================================

typedef std::function<void(const String& sessionId, int pulses)> CoinPaymentCallback;
typedef std::function<void(const String& sessionId, const char* reason)> CoinSessionEndCallback;

enum class CoinSlotOwnerType {
    ANY,
    PHONE,
    CONTROLLER
};

enum class CoinSlotState {
    IDLE,       // Relay OFF (hi-Z INPUT), pulses discarded
    ARMED,      // Relay ON (OUTPUT), pulses accepted for active session
    DRAINING    // Relay ON, draining pending in-flight pulses before shutdown
};

// ============================================================================
// COIN SLOT MANAGER API
// ============================================================================

/**
 * Initializes the coin slot manager, resets detectors, and ensures relay is disarmed.
 */
void initCoinSlotManager();

/**
 * Attempts to reserve and arm the coin slot for a specific session ID and owner type.
 * Returns true if successfully armed or re-armed; false if busy with another session.
 */
bool reserveCoinSlot(
    const String& sessionId,
    CoinSlotOwnerType ownerType,
    unsigned long ttlMs,
    CoinPaymentCallback onPayment = nullptr,
    CoinSessionEndCallback onSessionEnd = nullptr
);

/**
 * Releases the coin slot reservation and returns relay to safe disarmed state.
 */
void releaseCoinSlot(
    const String& sessionId,
    CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY,
    bool force = false,
    const char* reason = "RELEASED"
);

/**
 * Extends the TTL of the currently active session.
 */
bool refreshCoinSlotTtl(
    const String& sessionId,
    CoinSlotOwnerType ownerType,
    unsigned long ttlMs
);

/**
 * Core non-blocking event processor called every loop tick to debounce pulses,
 * deliver payment callbacks, and enforce session expiration.
 */
void processCoinSlotSession();

/**
 * Returns true if the coin slot relay is currently energized (ARMED or DRAINING).
 */
bool isCoinSlotArmed();

/**
 * Checks if the coin slot is currently busy with another active or draining session.
 */
bool isCoinSlotBusy(
    const String& sessionId,
    CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY
);

/**
 * Returns the current lifecycle state of the coin slot.
 */
CoinSlotState getCoinSlotState();

/**
 * Returns the session ID holding the active reservation, or empty string.
 */
String getActiveCoinSessionId();

/**
 * Returns the owner type of the active reservation.
 */
CoinSlotOwnerType getActiveCoinOwnerType();

/**
 * Sets a fallback payment callback when no session callback is registered.
 */
void setGlobalCoinPaymentCallback(CoinPaymentCallback callback);

#endif // COIN_SLOT_MANAGER_H
