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
static int sessionAccumulatedPulses = 0; // Buffer for pulses accumulated across consecutive bursts

static CoinPaymentCallback currentPaymentCallback = nullptr;
static CoinSessionEndCallback currentEndCallback = nullptr;
static CoinPaymentCallback globalPaymentCallback = nullptr;

static unsigned long bootStartTimeMs = 0;
static bool bootSuppressionDone = false;

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
    sessionAccumulatedPulses = 0;
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
    bootStartTimeMs = millis();
    bootSuppressionDone = false;
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
    sessionAccumulatedPulses = 0;
    resetCoinDetectorStates();
    initPaymentQueue();
}

void setGlobalCoinPaymentCallback(CoinPaymentCallback callback) {
    globalPaymentCallback = callback;
}

CoinSlotState getCoinSlotState() {
    return currentState;
}

int getSessionAccumulatedPulses() {
    return sessionAccumulatedPulses;
}

bool isStartupSuppressionActive() {
    if (bootSuppressionDone) return false;
    return ((int32_t)((uint32_t)millis() - (uint32_t)bootStartTimeMs) < 3000);
}

bool isCoinSlotArmed() {
    if (currentState == CoinSlotState::ARMED) {
        return ((int32_t)((uint32_t)sessionArmedUntil - (uint32_t)millis()) > 0);
    }
    if (currentState == CoinSlotState::DRAINING) {
        return true; // Keep physical relay energized throughout DRAINING lifecycle
    }
    return false;
}

bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType) {
    // In FAULT_MAINTENANCE state, the slot is always busy/unavailable
    if (currentState == CoinSlotState::FAULT_MAINTENANCE) {
        return true;
    }

    // If slot is completely IDLE or no session is active, it is not busy
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        return false;
    }

    unsigned long now = millis();

    // Check if RESERVED_ARMING has expired without mutating state here (mutation is owned by processCoinSlotSession)
    if (currentState == CoinSlotState::RESERVED_ARMING) {
        if ((long)(now - sessionArmedUntil) >= 0) {
            // Expired arming reservation is no longer considered busy to incoming callers
            return false;
        }
    }

    // Check if ARMED session has expired: must remain unavailable until processCoinSlotSession completes drain
    if (currentState == CoinSlotState::ARMED) {
        if ((long)(now - sessionArmedUntil) >= 0 || (sessionStartTimeMs > 0 && (long)(now - sessionStartTimeMs) >= (long)MAX_SESSION_DURATION)) {
            // Expired armed session remains busy to all callers (including rearming expired owner)
            return true;
        }
    }

    // While draining, slot is strictly busy for all reservations
    if (currentState == CoinSlotState::DRAINING) {
        return true;
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

bool tryClaimCoinSlotForArming(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long timeoutMs) {
    if (sessionId.length() == 0) return false;
    if (isMaintenanceMode()) {
        Serial.printf("[🪙 COIN SLOT] Claim rejected for '%s': Device is in maintenance mode.\n", sessionId.c_str());
        return false;
    }
    if (isPaymentQueueFull() || !isPaymentStorageReady()) return false;

    unsigned long now = millis();

    // Check if busy with another session or draining
    if (isCoinSlotBusy(sessionId, ownerType)) {
        return false;
    }

    // If already armed or reserved by this exact session, permit claim refresh
    if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY)) {
        sessionArmedUntil = now + timeoutMs;
        return true;
    }

    // Atomically claim slot into RESERVED_ARMING state
    currentState = CoinSlotState::RESERVED_ARMING;
    activeSessionId = sessionId;
    activeOwnerType = ownerType;
    sessionStartTimeMs = now;
    sessionArmedUntil = now + timeoutMs;
    drainDeadlineMs = 0;
    pendingEndReason = "";
    return true;
}

void cancelCoinSlotClaim(const String& sessionId, CoinSlotOwnerType ownerType) {
    if (currentState == CoinSlotState::RESERVED_ARMING && activeSessionId == sessionId) {
        if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
            Serial.printf("[🪙 COIN SLOT] Canceling arming claim for '%s'. Returning to IDLE.\n", sessionId.c_str());
            currentState = CoinSlotState::IDLE;
            activeSessionId = "";
            activeOwnerType = CoinSlotOwnerType::ANY;
            sessionArmedUntil = 0;
            sessionStartTimeMs = 0;
        }
    }
}

bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs, 
                     CoinPaymentCallback onPayment, 
                     CoinSessionEndCallback onSessionEnd) {
    if (sessionId.length() == 0) return false;

    if (isMaintenanceMode()) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Device is in maintenance mode.\n", sessionId.c_str());
        return false;
    }

    if (isPaymentQueueFull() || !isPaymentStorageReady()) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Storage unavailable or queue full!\n", sessionId.c_str());
        return false;
    }
    
    unsigned long now = millis();

    // Reject reservations while draining
    if (currentState == CoinSlotState::DRAINING) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Slot is currently DRAINING.\n", 
                      sessionId.c_str());
        return false;
    }

    // If held by another session or expired armed session, reject reservation
    if (isCoinSlotBusy(sessionId, ownerType)) {
        Serial.printf("[🪙 COIN SLOT] Reservation rejected for '%s': Slot busy with '%s' (State: %d)\n", 
                      sessionId.c_str(), activeSessionId.c_str(), (int)currentState);
        return false;
    }

    if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY) && 
        currentState == CoinSlotState::ARMED) {
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

    // BRAND NEW SESSION (or finalizing arming from RESERVED_ARMING claim):
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
    sessionAccumulatedPulses = 0;
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

    // 0. Handle FAULT_MAINTENANCE transition if maintenanceMode is active
    if (isMaintenanceMode()) {
        if (currentState != CoinSlotState::FAULT_MAINTENANCE) {
            if (currentState == CoinSlotState::ARMED) {
                Serial.printf("[🪙 COIN SLOT] Entering FAULT_MAINTENANCE from ARMED: initiating drain for '%s'...\n", activeSessionId.c_str());
                initiateSessionRelease("MAINTENANCE", false);
            } else if (currentState == CoinSlotState::RESERVED_ARMING) {
                Serial.printf("[🪙 COIN SLOT] Entering FAULT_MAINTENANCE: aborting arming claim '%s'.\n", activeSessionId.c_str());
                currentState = CoinSlotState::FAULT_MAINTENANCE;
                activeSessionId = "";
                activeOwnerType = CoinSlotOwnerType::ANY;
                sessionArmedUntil = 0;
                setRelayHardware(false);
            } else if (currentState == CoinSlotState::IDLE) {
                currentState = CoinSlotState::FAULT_MAINTENANCE;
                setRelayHardware(false);
            }
        }
    } else {
        if (currentState == CoinSlotState::FAULT_MAINTENANCE) {
            Serial.println("[🪙 COIN SLOT] Maintenance mode cleared. Returning to IDLE.");
            currentState = CoinSlotState::IDLE;
            setRelayHardware(false);
        }
    }

    // 0b. Auto-expire handshake timeout in RESERVED_ARMING state
    if (currentState == CoinSlotState::RESERVED_ARMING) {
        if ((int32_t)((uint32_t)now - (uint32_t)sessionArmedUntil) >= 0) {
            Serial.printf("[🪙 COIN SLOT] Arming handshake timed out for '%s'. Returning to IDLE.\n", activeSessionId.c_str());
            currentState = CoinSlotState::IDLE;
            activeSessionId = "";
            activeOwnerType = CoinSlotOwnerType::ANY;
            sessionArmedUntil = 0;
            sessionStartTimeMs = 0;
            return;
        }
    }

    // 1. One-time boot pulse suppression (3000ms after boot)
    if (!bootSuppressionDone) {
        if ((int32_t)((uint32_t)now - (uint32_t)bootStartTimeMs) < 3000) {
            if (isrUniversalPulseCount > 0) {
                noInterrupts();
                isrUniversalPulseCount = 0;
                interrupts();
            }
            return;
        } else {
            bootSuppressionDone = true;
            if (isrUniversalPulseCount > 0) {
                noInterrupts();
                isrUniversalPulseCount = 0;
                interrupts();
            }
            Serial.println("[🪙 COIN SLOT] Startup pulse suppression complete.");
        }
    }

    // 2. Read and harvest new pulses atomically from ISR counter into session buffer
    int newPulses = 0;
    unsigned long lastPulseTime = 0;
    noInterrupts();
    newPulses = isrUniversalPulseCount;
    isrUniversalPulseCount = 0;
    lastPulseTime = isrLastPulseTimeMs;
    interrupts();

    // 3. Strict Isolation: If no active or draining session, discard any spurious pulses
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        if (newPulses > 0 || sessionAccumulatedPulses > 0) {
            sessionAccumulatedPulses = 0;
            Serial.printf("[🪙 COIN SLOT] Discarded %d spurious pulse(s) received while IDLE/unreserved.\n", 
                          newPulses + sessionAccumulatedPulses);
        }
        return;
    }

    if (newPulses > 0) {
        sessionAccumulatedPulses += newPulses;
    }

    // 3b. Upgrade idle grace deadline if pulse detected during DRAINING, capped strictly at maxDrainDeadlineMs
    if (currentState == CoinSlotState::DRAINING && newPulses > 0) {
        if ((int32_t)((uint32_t)maxDrainDeadlineMs - (uint32_t)drainDeadlineMs) > 0) {
            drainDeadlineMs = maxDrainDeadlineMs;
            int32_t remMs = (int32_t)((uint32_t)maxDrainDeadlineMs - (uint32_t)now);
            Serial.printf("[🪙 COIN SLOT] Pulse detected during DRAINING idle grace. Timeout set to hard cap (%lu ms remaining).\n",
                          remMs > 0 ? (unsigned long)remMs : 0UL);
        }
    }

    // 4. Process completed pulse train after inter-pulse silence timeout
    if (sessionAccumulatedPulses > 0 && ((int32_t)((uint32_t)now - (uint32_t)lastPulseTime) >= (int32_t)INTER_PULSE_TIMEOUT_MS)) {
        int finalPulses = sessionAccumulatedPulses;

        if (finalPulses > 0) {
            String deliveringSession = activeSessionId;
            Serial.printf("[🪙 COIN SLOT] Detected %d pulse(s) for session '%s'. Delivering payment...\n", 
                          finalPulses, deliveringSession.c_str());

            bool retained = false;
            if (currentPaymentCallback) {
                retained = currentPaymentCallback(deliveringSession, finalPulses);
            } else if (globalPaymentCallback) {
                retained = globalPaymentCallback(deliveringSession, finalPulses);
            }

            if (retained) {
                sessionAccumulatedPulses = 0; // Clear pulse copy only after verified retention
                if (currentState == CoinSlotState::DRAINING) {
                    Serial.println("[🪙 COIN SLOT] In-flight pulses drained and delivered. Completing release.");
                    String reason = pendingEndReason.length() > 0 ? pendingEndReason : "RELEASED";
                    finalizeSessionRelease(reason.c_str());
                    return;
                }
            } else {
                Serial.printf("[🪙 COIN SLOT] CRITICAL: Payment callback failed to retain transaction for '%s'! Retaining pulse copy and stopping admission.\n",
                              deliveringSession.c_str());
                setMaintenanceMode(true);
                return; // Do NOT finalize release or clear pulses!
            }
        }
    }

    // 5. Check DRAINING timeout guard (respecting hard cap maxDrainDeadlineMs)
    if (currentState == CoinSlotState::DRAINING) {
        bool drainExpired = ((int32_t)((uint32_t)now - (uint32_t)drainDeadlineMs) >= 0);
        bool maxCapExpired = (maxDrainDeadlineMs > 0 && ((int32_t)((uint32_t)now - (uint32_t)maxDrainDeadlineMs) >= 0));
        if (drainExpired || maxCapExpired) {
            Serial.println("[🪙 COIN SLOT] Drain guard timeout reached. Delivering remaining pulses and finalizing release.");
            
            // Salvage any pulses that accumulated before the guard tripped
            noInterrupts();
            int trailingPulses = isrUniversalPulseCount;
            isrUniversalPulseCount = 0;
            interrupts();
            
            int remainingPulses = sessionAccumulatedPulses + trailingPulses;
            
            if (remainingPulses > 0) {
                bool retained = false;
                if (currentPaymentCallback) {
                    retained = currentPaymentCallback(activeSessionId, remainingPulses);
                } else if (globalPaymentCallback) {
                    retained = globalPaymentCallback(activeSessionId, remainingPulses);
                }
                if (!retained) {
                    sessionAccumulatedPulses = remainingPulses; // Preserve pulse copy!
                    Serial.printf("[🪙 COIN SLOT] CRITICAL: Drain timeout delivery failed for '%s'! Retaining pulses and stopping admission.\n",
                                  activeSessionId.c_str());
                    setMaintenanceMode(true);
                    return; // Do NOT finalize session release!
                }
                sessionAccumulatedPulses = 0;
            }

            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "DRAIN_TIMEOUT";
            finalizeSessionRelease(reason.c_str());
            return;
        }
    }

    // 6. Check session state guards (Storage failure, Queue full, TTL, MAX duration) for ARMED state
    if (currentState == CoinSlotState::ARMED) {
        if (isPaymentQueueFull() || !isPaymentStorageReady()) {
            Serial.printf("[🪙 COIN SLOT] Storage/queue failure during active session '%s'. Draining and releasing...\n",
                          activeSessionId.c_str());
            initiateSessionRelease("STORAGE_UNAVAILABLE", false);
            return;
        }

        bool ttlExpired = ((int32_t)((uint32_t)now - (uint32_t)sessionArmedUntil) >= 0);
        bool maxDurationExpired = (sessionStartTimeMs > 0 && ((int32_t)((uint32_t)now - (uint32_t)sessionStartTimeMs) >= (int32_t)MAX_SESSION_DURATION));

        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[🪙 COIN SLOT] Session %s for '%s'. Checking in-flight pulses...\n",
                           reason, activeSessionId.c_str());

            initiateSessionRelease(reason, false);
        }
    }
}
