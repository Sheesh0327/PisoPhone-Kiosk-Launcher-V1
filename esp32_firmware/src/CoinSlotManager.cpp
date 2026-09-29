#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "HardwareManager.h"
#include "Config.h"

// ============================================================================
// TIMING CONSTANTS
// ============================================================================
static const unsigned long INTER_PULSE_TIMEOUT_MS = 280;  // 280ms gap of silence to finish accumulating continuous pulses
static const unsigned long IN_FLIGHT_PULSE_GRACE_MS = 560; // 2x inter-pulse window to consider pulses still in flight
static const unsigned long DRAIN_TIMEOUT_GUARD_MS   = 10000; // Max time to wait for final in-flight pulse delivery (10s)
static const unsigned long IDLE_DRAIN_GRACE_MS      = 1000;  // Grace window to wait if ending while idle

// ============================================================================
// INTERNAL STATE
// ============================================================================
static CoinSlotState currentState = CoinSlotState::IDLE;
static CoinSlotOwnerType activeOwnerType = CoinSlotOwnerType::ANY;
static String activeSessionId = "";
static unsigned long sessionArmedUntil = 0;
static unsigned long sessionStartTimeMs = 0;
static unsigned long drainDeadlineMs = 0;
static unsigned long maxDrainDeadlineMs = 0; // Hard maximum deadline for draining
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
    activeOwnerType = CoinSlotOwnerType::ANY;
    activeSessionId = "";
    sessionArmedUntil = 0;
    sessionStartTimeMs = 0;
    drainDeadlineMs = 0;
    maxDrainDeadlineMs = 0;
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

// Internal helper to safely start session release. Disarms if idle, otherwise enters DRAINING state
static void initiateSessionRelease(const char* reason, bool force) {
    if (activeSessionId.length() == 0 || currentState == CoinSlotState::IDLE) return;
        
    unsigned long now = millis();
    const char* terminalReason = (reason != nullptr && strlen(reason) > 0) ? reason : "RELEASED";
    
    if (currentState == CoinSlotState::DRAINING) {
        if (force) {
            finalizeSessionRelease(terminalReason);
        }
        return; // Do not reset deadlines or re-enter DRAINING state if already draining
    }

    if (!force) {
        noInterrupts();
        int currentPulses = isrUniversalPulseCount;
        unsigned long lastPulse = isrLastPulseTimeMs;
        interrupts();

        currentState = CoinSlotState::DRAINING;
        pendingEndReason = terminalReason;

        // Hard absolute deadline cap (10s max)
        maxDrainDeadlineMs = now + DRAIN_TIMEOUT_GUARD_MS;

        // If coin pulses are in flight or arrived recently, set full timeout, otherwise set a short idle grace window
        if (currentPulses > 0 || (lastPulse > 0 && (now - lastPulse < IN_FLIGHT_PULSE_GRACE_MS))) {
            Serial.printf("[🪙 COIN SLOT] Release requested for '%s' while pulses in flight (%d pulses). Entering full DRAINING state...\n",
                           activeSessionId.c_str(), currentPulses);
            drainDeadlineMs = maxDrainDeadlineMs;
        } else {
            Serial.printf("[🪙 COIN SLOT] Release requested for '%s' while idle. Entering DRAINING state with %lu ms idle grace...\n",
                           activeSessionId.c_str(), IDLE_DRAIN_GRACE_MS);
            drainDeadlineMs = now + IDLE_DRAIN_GRACE_MS;
        }
        // Keep hardware relay powered ON to finish reading potential incoming pulses.
        return;
    }
        
    // Immediate finalize release (this disarms the hardware relay)
    finalizeSessionRelease(terminalReason);
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
    initPaymentQueue();
}

void setGlobalCoinPaymentCallback(CoinPaymentCallback callback) {
    globalPaymentCallback = callback;
}

CoinSlotState getCoinSlotState() {
    return currentState;
}

bool isCoinSlotArmed() {
    if (currentState == CoinSlotState::ARMED) {
        return (millis() < sessionArmedUntil);
    }
    if (currentState == CoinSlotState::DRAINING) {
        return true; // Keep physical relay energized throughout DRAINING lifecycle
    }
    return false;
}

bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType) {
    // If slot is completely IDLE, it is not busy
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        return false;
    }

    unsigned long now = millis();

    // Auto-reclaim expired ARMED session to prevent lockup
    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = (now >= sessionArmedUntil);
        bool maxDurationExpired = (sessionStartTimeMs > 0 && (now - sessionStartTimeMs >= MAX_SESSION_DURATION));
        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[🪙 COIN SLOT] Active session '%s' expired during busy check (%s). Auto-releasing.\n",
                          activeSessionId.c_str(), reason);
            finalizeSessionRelease(reason);
            return false;
        }
    }

    // Auto-reclaim expired DRAINING session to prevent lockup
    if (currentState == CoinSlotState::DRAINING) {
        if (now >= drainDeadlineMs || (maxDrainDeadlineMs > 0 && now >= maxDrainDeadlineMs)) {
            Serial.printf("[🪙 COIN SLOT] Draining session '%s' expired during busy check. Auto-releasing.\n",
                          activeSessionId.c_str());
            finalizeSessionRelease("DRAIN_TIMEOUT");
            return false;
        }
    }

    // If held by the SAME session (or owner ANY match), it is not busy to that session
    if (sessionId.length() > 0 && activeSessionId == sessionId) {
        if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
            return false;
        }
    }
    // Held by a different session -> busy
    return true;
}

String getActiveCoinSessionId() {
    return activeSessionId;
}

CoinSlotOwnerType getActiveCoinOwnerType() {
    return activeOwnerType;
}

bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs, 
                     CoinPaymentCallback onPayment, 
                     CoinSessionEndCallback onSessionEnd) {
    if (sessionId.length() == 0) return false;

    if (isPaymentQueueFull()) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Persistent Payment Queue is FULL!\n", sessionId.c_str());
        return false;
    }
    
    unsigned long now = millis();

    // If held by another session (or draining), reject reservation
    if (isCoinSlotBusy(sessionId, ownerType)) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Slot busy with '%s' (State: %d)\n", 
                      sessionId.c_str(), activeSessionId.c_str(), (int)currentState);
        return false;
    }

    if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY) && 
        (currentState == CoinSlotState::ARMED || currentState == CoinSlotState::DRAINING)) {
        // RECONNECTION / RE-ARMING SAME SESSION:
        // Preserve accumulated pulses, restore ARMED state, refresh TTL
        currentState = CoinSlotState::ARMED;
        if (ownerType != CoinSlotOwnerType::ANY) activeOwnerType = ownerType;
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
    activeOwnerType = ownerType;
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

bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs) {
    if (activeSessionId.length() > 0 && activeSessionId == sessionId && 
        (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) && 
        currentState == CoinSlotState::ARMED) {
        unsigned long now = millis();
        sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
        return true;
    }
    return false;
}

void releaseCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, bool force, const char* reason) {
    if (activeSessionId.length() == 0 || currentState == CoinSlotState::IDLE) return;
    if (!force && activeSessionId != sessionId) return;
    if (!force && ownerType != CoinSlotOwnerType::ANY && activeOwnerType != ownerType) return;
    
    initiateSessionRelease(reason, force);
}

void processCoinSlotSession() {
    unsigned long now = millis();

    // Periodically retry unacknowledged payment dispatches
    processPendingPaymentRetries();

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

    // 3b. Upgrade idle grace deadline if pulse detected during DRAINING, capped strictly at maxDrainDeadlineMs
    if (currentState == CoinSlotState::DRAINING && pulseCount > 0) {
        if (drainDeadlineMs < maxDrainDeadlineMs) {
            drainDeadlineMs = maxDrainDeadlineMs;
            Serial.printf("[🪙 COIN SLOT] Pulse detected during DRAINING idle grace. Timeout set to hard cap (%lu ms remaining).\n",
                          maxDrainDeadlineMs > now ? (maxDrainDeadlineMs - now) : 0);
        }
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

    // 5. Check DRAINING timeout guard (respecting hard cap maxDrainDeadlineMs)
    if (currentState == CoinSlotState::DRAINING) {
        if (now >= drainDeadlineMs || (maxDrainDeadlineMs > 0 && now >= maxDrainDeadlineMs)) {
            Serial.println("[🪙 COIN SLOT] Drain guard timeout reached. Delivering remaining pulses and finalizing release.");
            
            // Salvage any pulses that accumulated before the guard tripped
            noInterrupts();
            int remainingPulses = isrUniversalPulseCount;
            isrUniversalPulseCount = 0;
            interrupts();
            
            if (remainingPulses > 0) {
                if (currentPaymentCallback) {
                    currentPaymentCallback(activeSessionId, remainingPulses);
                } else if (globalPaymentCallback) {
                    globalPaymentCallback(activeSessionId, remainingPulses);
                }
            }

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
            Serial.printf("[🪙 COIN SLOT] Session %s for '%s'. Checking in-flight pulses...\n",
                           reason, activeSessionId.c_str());

            initiateSessionRelease(reason, false);
        }
    }
}
