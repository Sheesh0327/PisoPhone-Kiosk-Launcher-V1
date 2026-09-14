#ifndef COIN_SLOT_MANAGER_H
#define COIN_SLOT_MANAGER_H

#include <Arduino.h>
#include <functional>

// ============================================================================
// CALLBACK SIGNATURES & ENUMS
// ============================================================================
typedef std::function<void(const String& sessionId, int pulses)> CoinPaymentCallback;
typedef std::function<void(const String& sessionId, const char* reason)> CoinSessionEndCallback;

enum class CoinSlotState {
    IDLE,       // No session, relay OFF, acceptor disabled
    ARMED,      // Active session running, relay ON, accepting coins
    DRAINING    // Session closing/timed-out, relay ON, waiting for in-flight pulses to finish
};

// ============================================================================
// COIN SLOT MANAGER INTERFACE
// ============================================================================

/**
 * Initialize the coin slot manager subsystem and default state.
 */
void initCoinSlotManager();

/**
 * Attempts to reserve the coin slot for a specific session/device.
 * - If currently IDLE: arms acceptor relay, resets detector, starts session.
 * - If already reserved by SAME sessionId: refreshes TTL and preserves accumulated pulses.
 * - If reserved/draining for ANOTHER session: rejects request (returns false).
 *
 * @param sessionId Unique identifier for the calling session/device.
 * @param ttlMs Time-to-live for the reservation in milliseconds.
 * @param onPayment Optional callback fired when completed coin pulses are detected.
 * @param onSessionEnd Optional callback fired exactly once when session ends.
 * @return true if reserved and armed, false if slot is busy with another session.
 */
bool reserveCoinSlot(const String& sessionId, unsigned long ttlMs, 
                     CoinPaymentCallback onPayment = nullptr, 
                     CoinSessionEndCallback onSessionEnd = nullptr);

/**
 * Releases the coin slot reservation and de-energizes the acceptor relay.
 * If coin pulses are actively in flight, holds session ownership in DRAINING
 * state until the pulse train completes so payment is safely credited.
 *
 * @param sessionId Session requesting release.
 * @param force If true, immediately disarms without waiting for in-flight pulses.
 * @param reason Reason reported to the end callback (default "RELEASED").
 */
void releaseCoinSlot(const String& sessionId, bool force = false, const char* reason = "RELEASED");

/**
 * Extends/refreshes the active reservation TTL for the current session.
 */
bool refreshCoinSlotTtl(const String& sessionId, unsigned long ttlMs);

/**
 * Non-blocking main loop processor for pulse accumulation, debouncing,
 * payment callback dispatching, and session timeout management.
 */
void processCoinSlotSession();

/**
 * Returns true if the coin slot is currently reserved and powered/armed.
 */
bool isCoinSlotArmed();

/**
 * Returns true if the slot is currently reserved or draining for a different session.
 */
bool isCoinSlotBusy(const String& sessionId);

/**
 * Returns the current state of the coin slot manager.
 */
CoinSlotState getCoinSlotState();

/**
 * Returns the session ID of the current active/draining reservation, or empty string.
 */
String getActiveCoinSessionId();

/**
 * Registers a global fallback payment callback if no session-specific callback is set.
 */
void setGlobalCoinPaymentCallback(CoinPaymentCallback callback);

#endif // COIN_SLOT_MANAGER_H
