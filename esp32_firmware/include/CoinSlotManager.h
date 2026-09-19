#ifndef COIN_SLOT_MANAGER_H
#define COIN_SLOT_MANAGER_H

#include <Arduino.h>
#include <functional>

// ============================================================================
// CALLBACK SIGNATURES & ENUMS
// ============================================================================
typedef std::function<void(const String& sessionId, int pulses)> CoinPaymentCallback;
typedef std::function<void(const String& sessionId, const char* reason)> CoinSessionEndCallback;

enum class CoinSlotOwnerType {
    ANY,
    PHONE,
    CONTROLLER
};

enum class CoinSlotState {
    IDLE,               // No session, relay OFF, acceptor disabled
    RESERVED_ARMING,    // Atomically claimed by incoming connection, awaiting socket readiness/handshake completion
    ARMED,              // Active session running, relay ON, accepting coins
    DRAINING            // Session closing/timed-out, relay ON, waiting for in-flight pulses to finish
};

// ============================================================================
// COIN SLOT MANAGER INTERFACE
// ============================================================================

/**
 * Initialize the coin slot manager subsystem and default state.
 */
void initCoinSlotManager();

/**
 * Atomically attempts to claim the slot during handshake phase before network I/O.
 * Returns true if successfully transitioned to RESERVED_ARMING for this session.
 */
bool tryClaimCoinSlotForArming(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long timeoutMs = 5000);

/**
 * Cancels a pending claim if handshake or socket upgrade fails.
 */
void cancelCoinSlotClaim(const String& sessionId, CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY);

/**
 * Attempts to reserve the coin slot for a specific session/device.
 * - If currently IDLE or RESERVED_ARMING by same session: arms acceptor relay, resets detector, starts session.
 * - If already reserved by SAME sessionId & ownerType: refreshes TTL and preserves accumulated pulses.
 * - If reserved/draining for ANOTHER session or different ownerType: rejects request (returns false).
 */
bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs, 
                     CoinPaymentCallback onPayment = nullptr, 
                     CoinSessionEndCallback onSessionEnd = nullptr);

/**
 * Releases the coin slot reservation and de-energizes the acceptor relay.
 */
void releaseCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY, bool force = false, const char* reason = "RELEASED");

/**
 * Extends/refreshes the active reservation TTL for the current session.
 */
bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs);

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
 * Returns true if the slot is currently reserved or draining for a different session or owner.
 */
bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType = CoinSlotOwnerType::ANY);

/**
 * Returns the current state of the coin slot manager.
 */
CoinSlotState getCoinSlotState();

/**
 * Returns the session ID of the current active/draining reservation, or empty string.
 */
String getActiveCoinSessionId();

/**
 * Returns the owner type of the current active reservation.
 */
CoinSlotOwnerType getActiveCoinOwnerType();

/**
 * Registers a global fallback payment callback if no session-specific callback is set.
 */
void setGlobalCoinPaymentCallback(CoinPaymentCallback callback);

#endif // COIN_SLOT_MANAGER_H
