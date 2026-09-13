#include "CoinSlotManager.h"
#include "HardwareManager.h"
#include "Config.h"

// ============================================================================
// TIMING CONSTANTS
// ============================================================================
static const unsigned long INTER_PULSE_TIMEOUT_MS = 280;  // Allan 124A/616A standard inter-pulse window
static const unsigned long IN_FLIGHT_PULSE_GRACE_MS = 600; // Window to consider pulses still in flight
static const unsigned long DRAIN_TIMEOUT_GUARD_MS   = 2000; // Max time to wait for final in-flight pulse delivery

// ============================================================================
// INTERNAL STATE
// ============================================================================
static CoinSlotState currentState = CoinSlotState::IDLE;
static String activeSessionId = "";
static unsigned long sessionArmedUntil = 0;
static unsigned long sessionStartTimeMs = 0;
static unsigned long drainDeadlineMs = 0;
static String pendingEndReason = "";

static CoinPaymentCallback currentPaymentCallback = nullptr;
static CoinSessionEndCallback currentEndCallback = nullptr;
static CoinPaymentCallback globalPaymentCallback = nullptr;

// Internal helper to complete release and invoke the end callback exactly once
static void finalizeSessionRelease(const char* reason) {
    if (activeSessionId.length() == 0 && currentState == CoinSlotState::IDLE) {
        return;
    }

    String endingSession = activeSessionId;
    CoinSessionEndCallback endCb = currentEndCallback;

    Serial.printf("[🪙 COIN SLOT] Finalizing session '%s' (Reason: %s).\n", 
                  endingSession.c_str(), reason);

    // Reset state before callback to prevent re-entrant issues
    currentState = CoinSlotState::IDLE;
    activeSessionId = "";
    sessionArmedUntil = 0;
    sessionStartTimeMs = 0;
    drainDeadlineMs = 0;
    pendingEndReason = "";
    currentPaymentCallback = nullptr;
    currentEndCallback = nullptr;

    // Disarm physical relay
    setRelayHardware(false);

    // Reset hardware pulse accumulators for clean next session
    resetCoinDetectorStates();

    // Trigger end callback exactly once
    if (endCb) {
        endCb(endingSession, reason);
    }
}

void initCoinSlotManager() {
    currentState = CoinSlotState::IDLE;
    activeSessionId = "";
    sessionArmedUntil = 0;
    sessionStartTimeMs = 0;
    drainDeadlineMs = 0;
    pendingEndReason = "";
    currentPaymentCallback = nullptr;
    currentEndCallback = nullptr;
    globalPaymentCallback = nullptr;
    setRelayHardware(false);
    resetCoinDetectorStates();
}

void setGlobalCoinPaymentCallback(CoinPaymentCallback callback) {
    globalPaymentCallback = callback;
}

CoinSlotState getCoinSlotState() {
    return currentState;
}

bool isCoinSlotArmed() {
    return (currentState == CoinSlotState::ARMED && millis() < sessionArmedUntil);
}

bool isCoinSlotBusy(const String& sessionId) {
    // If slot is completely IDLE, it is not busy
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        return false;
    }
    // If held by the SAME session, it is not busy to that session
    if (activeSessionId == sessionId) {
        return false;
    }
    // Held by a different session (ARMED or DRAINING) -> busy
    return true;
}

String getActiveCoinSessionId() {
    return activeSessionId;
}

bool reserveCoinSlot(const String& sessionId, unsigned long ttlMs, 
                     CoinPaymentCallback onPayment, 
                     CoinSessionEndCallback onSessionEnd) {
    if (sessionId.length() == 0) return false;
    
    unsigned long now = millis();

    // If held by another session (or draining), reject reservation
    if (isCoinSlotBusy(sessionId)) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Slot busy with '%s' (State: %d)\n", 
                      sessionId.c_str(), activeSessionId.c_str(), (int)currentState);
        return false;
    }

    if (activeSessionId == sessionId && (currentState == CoinSlotState::ARMED || currentState == CoinSlotState::DRAINING)) {
        // RECONNECTION / RE-ARMING SAME SESSION:
        // Preserve accumulated pulses, restore ARMED state, refresh TTL
        currentState = CoinSlotState::ARMED;
        sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
        drainDeadlineMs = 0;
        pendingEndReason = "";

        if (onPayment) currentPaymentCallback = onPayment;
        if (onSessionEnd) currentEndCallback = onSessionEnd;

        setRelayHardware(true);
        Serial.printf("[🪙 COIN SLOT] Session '%s' RECONNECTED & RE-ARMED (TTL: %lu ms, Preserved Pulses: %d)\n", 
                      activeSessionId.c_str(), ttlMs, isrUniversalPulseCount);
        return true;
    }

    // BRAND NEW SESSION:
    currentState = CoinSlotState::ARMED;
    activeSessionId = sessionId;
    sessionStartTimeMs = now;
    sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
    drainDeadlineMs = 0;
    pendingEndReason = "";

    currentPaymentCallback = onPayment;
    currentEndCallback = onSessionEnd;

    // Reset pulse detector states for fresh session
    resetCoinDetectorStates();

    // Arm hardware relay
    setRelayHardware(true);

    Serial.printf("[🪙 COIN SLOT] Slot RESERVED & ARMED for '%s' (TTL: %lu ms)\n", 
                  activeSessionId.c_str(), ttlMs);
    return true;
}

bool refreshCoinSlotTtl(const String& sessionId, unsigned long ttlMs) {
    if (activeSessionId.length() > 0 && activeSessionId == sessionId && currentState == CoinSlotState::ARMED) {
        unsigned long now = millis();
        sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
        return true;
    }
    return false;
}

void releaseCoinSlot(const String& sessionId, bool force, const char* reason) {
    if (activeSessionId.length() == 0 || currentState == CoinSlotState::IDLE) return;
    if (activeSessionId != sessionId && !force) return;

    unsigned long now = millis();
    const char* terminalReason = (reason != nullptr && strlen(reason) > 0) ? reason : "RELEASED";

    // Disarm hardware relay immediately so no further coins enter
    setRelayHardware(false);

    if (!force) {
        noInterrupts();
        int currentPulses = isrUniversalPulseCount;
        unsigned long lastPulse = isrLastPulseTimeMs;
        interrupts();

        // If coin pulses are in flight or arrived recently, enter DRAINING state
        if (currentPulses > 0 || (lastPulse > 0 && (now - lastPulse < IN_FLIGHT_PULSE_GRACE_MS))) {
            Serial.printf("[🪙 COIN SLOT] Release requested for '%s' while pulses in flight (%d pulses). Entering DRAINING state...\n", 
                          activeSessionId.c_str(), currentPulses);
            currentState = CoinSlotState::DRAINING;
            pendingEndReason = terminalReason;
            drainDeadlineMs = now + DRAIN_TIMEOUT_GUARD_MS;
            return;
        }
    }

    // Immediate finalize release
    finalizeSessionRelease(terminalReason);
}

void processCoinSlotSession() {
    unsigned long now = millis();

    // 1. Ignore early boot spikes (< 3000ms)
    if (now < 3000) {
        if (isrUniversalPulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
        }
        return;
    }

    // 2. Read pulse accumulator atomically
    noInterrupts();
    int pulseCount = isrUniversalPulseCount;
    unsigned long lastPulseTime = isrLastPulseTimeMs;
    interrupts();

    // 3. Strict Isolation: If no active or draining session, discard any spurious pulses
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        if (pulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
            Serial.printf("[🪙 COIN SLOT] Discarded %d spurious pulse(s) received while IDLE/unreserved.\n", pulseCount);
        }
        return;
    }

    // 4. Process completed pulse train after inter-pulse timeout
    if (pulseCount > 0 && (now - lastPulseTime >= INTER_PULSE_TIMEOUT_MS)) {
        noInterrupts();
        int finalPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        interrupts();

        if (finalPulses > 0) {
            String deliveringSession = activeSessionId;
            Serial.printf("[🪙 COIN SLOT] Detected %d pulse(s) for session '%s'. Delivering payment...\n", 
                          finalPulses, deliveringSession.c_str());

            if (currentPaymentCallback) {
                currentPaymentCallback(deliveringSession, finalPulses);
            } else if (globalPaymentCallback) {
                globalPaymentCallback(deliveringSession, finalPulses);
            }
        }

        // If in DRAINING state, in-flight pulses are now delivered. Finalize session release.
        if (currentState == CoinSlotState::DRAINING) {
            Serial.println("[🪙 COIN SLOT] In-flight pulses drained and delivered. Completing release.");
            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "RELEASED";
            finalizeSessionRelease(reason.c_str());
            return;
        }
    }

    // 5. Check DRAINING timeout guard
    if (currentState == CoinSlotState::DRAINING) {
        if (now >= drainDeadlineMs) {
            Serial.println("[🪙 COIN SLOT] Drain guard timeout reached. Finalizing release.");
            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "DRAIN_TIMEOUT";
            finalizeSessionRelease(reason.c_str());
        }
        return;
    }

    // 6. Check session TTL and MAX duration expiration for ARMED state
    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = (now >= sessionArmedUntil);
        bool maxDurationExpired = (sessionStartTimeMs > 0 && (now - sessionStartTimeMs >= MAX_SESSION_DURATION));

        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[🪙 COIN SLOT] Session %s for '%s'. Disarming relay and checking in-flight pulses...\n", 
                          reason, activeSessionId.c_str());

            // De-energize relay immediately
            setRelayHardware(false);

            // Check if pulses are in flight or arrived recently
            noInterrupts();
            int currentPulses = isrUniversalPulseCount;
            unsigned long lastPulse = isrLastPulseTimeMs;
            interrupts();

            if (currentPulses > 0 || (lastPulse > 0 && (now - lastPulse < IN_FLIGHT_PULSE_GRACE_MS))) {
                Serial.printf("[🪙 COIN SLOT] Pulses in flight (%d pulses) during timeout. Entering DRAINING state...\n", currentPulses);
                currentState = CoinSlotState::DRAINING;
                pendingEndReason = reason;
                drainDeadlineMs = now + DRAIN_TIMEOUT_GUARD_MS;
            } else {
                finalizeSessionRelease(reason);
            }
        }
    }
}
