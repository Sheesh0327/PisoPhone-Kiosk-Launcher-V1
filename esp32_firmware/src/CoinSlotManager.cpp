#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "HardwareManager.h"
#include "DeviceManager.h"
#include "Config.h"

// ============================================================================
// TIMING CONSTANTS (Milliseconds)
// ============================================================================
static const unsigned long INTER_PULSE_TIMEOUT_MS   = 250;   // Inactivity window indicating end of coin pulse train
static const unsigned long IN_FLIGHT_PULSE_GRACE_MS = 800;   // Time window to consider pulses still actively arriving
static const unsigned long IDLE_DRAIN_GRACE_MS      = 1500;  // Idle grace period to allow mechanical drop to trigger switch
static const unsigned long DRAIN_TIMEOUT_GUARD_MS   = 8000;  // Maximum absolute time to remain in DRAINING state
static const unsigned long MAX_SESSION_DURATION_MS  = 120000;// 2 minutes hard limit per arming session

// ============================================================================
// INTERNAL MUTEX & LIFECYCLE STATE
// ============================================================================
static CoinSlotState currentState = CoinSlotState::IDLE;
static CoinSlotOwnerType activeOwnerType = CoinSlotOwnerType::ANY;
static String activeSessionId = "";
static unsigned long sessionArmedUntil = 0;
static unsigned long sessionStartTimeMs = 0;
static unsigned long drainDeadlineMs = 0;
static unsigned long maxDrainDeadlineMs = 0;
static String pendingEndReason = "";

static CoinPaymentCallback currentPaymentCallback = nullptr;
static CoinSessionEndCallback currentEndCallback = nullptr;
static CoinPaymentCallback globalPaymentCallback = nullptr;

// ============================================================================
// INTERNAL RELEASE HELPERS
// ============================================================================

static void finalizeSessionRelease(const char* reason) {
    if (activeSessionId.length() == 0 && currentState == CoinSlotState::IDLE) {
        return;
    }

    String endingSession = activeSessionId;
    CoinSessionEndCallback endCb = currentEndCallback;

    Serial.printf("[🪙 COIN SLOT] Finalizing session '%s' (Reason: %s)\n", 
                  endingSession.c_str(), reason ? reason : "UNKNOWN");

    // Atomically reset state machine before callback invocation
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

    // Disarm physical relay hardware (pin reverts to high-impedance INPUT)
    setRelayHardware(false);

    // Reset detector pulse buffers for the next clean session
    resetCoinDetectorStates();

    // Trigger end callback exactly once
    if (endCb) {
        endCb(endingSession, reason ? reason : "RELEASED");
    }
}

static void initiateSessionRelease(const char* reason, bool force) {
    if (activeSessionId.length() == 0 || currentState == CoinSlotState::IDLE) {
        return;
    }

    unsigned long now = millis();
    const char* terminalReason = (reason != nullptr && strlen(reason) > 0) ? reason : "RELEASED";

    if (currentState == CoinSlotState::DRAINING) {
        if (force) {
            finalizeSessionRelease(terminalReason);
        }
        return;
    }

    if (!force) {
        noInterrupts();
        int currentPulses = isrUniversalPulseCount;
        unsigned long lastPulse = isrLastPulseTimeMs;
        interrupts();

        currentState = CoinSlotState::DRAINING;
        pendingEndReason = terminalReason;
        maxDrainDeadlineMs = now + DRAIN_TIMEOUT_GUARD_MS;

        // If pulses are currently accumulating or arrived recently, grant full drain timeout
        if (currentPulses > 0 || (lastPulse > 0 && (now - lastPulse < IN_FLIGHT_PULSE_GRACE_MS))) {
            Serial.printf("[🪙 COIN SLOT] Pulses in flight (%d). Entering full DRAINING state...\n", currentPulses);
            drainDeadlineMs = maxDrainDeadlineMs;
        } else {
            Serial.printf("[🪙 COIN SLOT] Session ending while idle. Draining with %lu ms grace...\n", IDLE_DRAIN_GRACE_MS);
            drainDeadlineMs = now + IDLE_DRAIN_GRACE_MS;
        }
        return;
    }

    // Immediate forced release
    finalizeSessionRelease(terminalReason);
}

// ============================================================================
// PUBLIC API IMPLEMENTATION
// ============================================================================

void initCoinSlotManager() {
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
    globalPaymentCallback = nullptr;

    setRelayHardware(false);
    resetCoinDetectorStates();
    initPaymentQueue();
    Serial.println("[🪙 COIN SLOT] Subsystem initialized. Relay in safe standby.");
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
        return true;
    }
    return false;
}

bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType) {
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        return false;
    }

    // Auto-reclaim expired sessions to prevent lockups
    unsigned long now = millis();
    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = (now >= sessionArmedUntil);
        bool maxDurationExpired = (sessionStartTimeMs > 0 && (now - sessionStartTimeMs >= MAX_SESSION_DURATION_MS));
        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[🪙 COIN SLOT] Session '%s' expired during busy check (%s). Auto-releasing.\n",
                          activeSessionId.c_str(), reason);
            finalizeSessionRelease(reason);
            return false;
        }
    }

    // Same session ID is not busy to itself
    if (sessionId.length() > 0) {
        if (activeSessionId == sessionId) {
            if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
                return false;
            }
        }
        int activeSlot = findSlotIndexForDevice(activeSessionId, "");
        int incomingSlot = findSlotIndexForDevice(sessionId, "");
        if (activeSlot >= 0 && activeSlot == incomingSlot) {
            if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
                return false;
            }
        }
    }

    return true;
}

String getActiveCoinSessionId() {
    return activeSessionId;
}

CoinSlotOwnerType getActiveCoinOwnerType() {
    return activeOwnerType;
}

bool reserveCoinSlot(
    const String& sessionId,
    CoinSlotOwnerType ownerType,
    unsigned long ttlMs,
    CoinPaymentCallback onPayment,
    CoinSessionEndCallback onSessionEnd
) {
    if (sessionId.length() == 0) return false;

    if (isPaymentQueueFull()) {
        Serial.printf("[🪙 COIN SLOT] Rejected '%s': Payment queue full.\n", sessionId.c_str());
        return false;
    }

    unsigned long now = millis();

    // Check if slot is held by another terminal
    if (isCoinSlotBusy(sessionId, ownerType)) {
        Serial.printf("[🪙 COIN SLOT] Rejected '%s': Slot busy with '%s'.\n", 
                      sessionId.c_str(), activeSessionId.c_str());
        return false;
    }

    // Reconnection / re-arming for identical active session: refresh TTL and keep state
    if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY) && 
        (currentState == CoinSlotState::ARMED || currentState == CoinSlotState::DRAINING)) {
        currentState = CoinSlotState::ARMED;
        if (ownerType != CoinSlotOwnerType::ANY) activeOwnerType = ownerType;
        sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
        drainDeadlineMs = 0;
        pendingEndReason = "";

        if (onPayment) currentPaymentCallback = onPayment;
        if (onSessionEnd) currentEndCallback = onSessionEnd;

        setRelayHardware(true);
        Serial.printf("[🪙 COIN SLOT] Session '%s' re-armed (TTL: %lu ms).\n", activeSessionId.c_str(), ttlMs);
        return true;
    }

    // New arming reservation
    currentState = CoinSlotState::ARMED;
    activeSessionId = sessionId;
    activeOwnerType = ownerType;
    sessionStartTimeMs = now;
    sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
    drainDeadlineMs = 0;
    pendingEndReason = "";

    currentPaymentCallback = onPayment;
    currentEndCallback = onSessionEnd;

    resetCoinDetectorStates();
    setRelayHardware(true);

    Serial.printf("[🪙 COIN SLOT] Slot ARMED for '%s' (TTL: %lu ms).\n", activeSessionId.c_str(), ttlMs);
    return true;
}

bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs) {
    if (activeSessionId.length() > 0 && activeSessionId == sessionId && 
        (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) && 
        currentState == CoinSlotState::ARMED) {
        sessionArmedUntil = millis() + (ttlMs > 0 ? ttlMs : ARM_TTL);
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

// ============================================================================
// CONTINUOUS SESSION RUNNER
// ============================================================================

void processCoinSlotSession() {
    unsigned long now = millis();

    // Process pending offline/retry payment dispatches
    processPendingPaymentRetries();

    // 1. Suppress boot power transients (< 3000 ms)
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

    // 3. Strict isolation: discard pulses if not reserved
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        if (pulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
            Serial.printf("[🪙 COIN SLOT] Discarded %d spurious pulse(s) received while IDLE.\n", pulseCount);
        }
        return;
    }

    // 4. In DRAINING state, if a pulse is detected, extend drain window up to max cap
    if (currentState == CoinSlotState::DRAINING && pulseCount > 0) {
        if (drainDeadlineMs < maxDrainDeadlineMs) {
            drainDeadlineMs = maxDrainDeadlineMs;
        }
    }

    // 5. Complete pulse train after inter-pulse gap
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

        // If draining, pulse delivery completes the session
        if (currentState == CoinSlotState::DRAINING) {
            Serial.println("[🪙 COIN SLOT] In-flight pulses delivered. Completing release.");
            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "RELEASED";
            finalizeSessionRelease(reason.c_str());
            return;
        }
    }

    // 6. Handle DRAINING timeout
    if (currentState == CoinSlotState::DRAINING) {
        if (now >= drainDeadlineMs || (maxDrainDeadlineMs > 0 && now >= maxDrainDeadlineMs)) {
            // Deliver any residual pulses before release
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

    // 7. Enforce ARMED TTL and MAX session duration
    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = (now >= sessionArmedUntil);
        bool maxDurationExpired = (sessionStartTimeMs > 0 && (now - sessionStartTimeMs >= MAX_SESSION_DURATION_MS));

        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[🪙 COIN SLOT] Session %s for '%s'. Checking in-flight pulses...\n",
                          reason, activeSessionId.c_str());
            initiateSessionRelease(reason, false);
        }
    }
}
