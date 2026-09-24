#include <iostream>
#include <string>
#include <cassert>
#include <cstdint>
#include <functional>

// ============================================================================
// SIMULATED COIN SLOT MANAGER ENGINE FOR HOST-BASED INVARIANT TESTING
// ============================================================================

enum class CoinSlotState {
    IDLE,
    RESERVED_ARMING,
    ARMED,
    DRAINING,
    FAULT_MAINTENANCE
};

enum class CoinSlotOwnerType {
    ANY,
    PHONE,
    CONTROLLER
};

typedef std::function<void(const std::string&, int)> CoinPaymentCallback;
typedef std::function<void(const std::string&, const char*)> CoinSessionEndCallback;

static const unsigned long ARM_TTL = 30000;
static const unsigned long MAX_SESSION_DURATION = 1800000;
static const unsigned long INTER_PULSE_TIMEOUT_MS = 280;
static const unsigned long IN_FLIGHT_PULSE_GRACE_MS = 560;
static const unsigned long DRAIN_TIMEOUT_GUARD_MS = 10000;
static const unsigned long IDLE_DRAIN_GRACE_MS = 1000;

struct MockCoinSlotEngine {
    unsigned long currentMillis = 1000;
    bool maintenanceMode = false;
    bool paymentQueueFull = false;
    bool paymentStorageReady = true;
    bool relayPowered = false;

    // ISR variables
    int isrUniversalPulseCount = 0;
    unsigned long isrLastPulseTimeMs = 0;

    // State machine
    CoinSlotState currentState = CoinSlotState::IDLE;
    CoinSlotOwnerType activeOwnerType = CoinSlotOwnerType::ANY;
    std::string activeSessionId = "";
    unsigned long sessionArmedUntil = 0;
    unsigned long sessionStartTimeMs = 0;
    unsigned long drainDeadlineMs = 0;
    unsigned long maxDrainDeadlineMs = 0;
    std::string pendingEndReason = "";
    int sessionAccumulatedPulses = 0;

    CoinPaymentCallback currentPaymentCallback = nullptr;
    CoinSessionEndCallback currentEndCallback = nullptr;

    unsigned long bootStartTimeMs = 0;
    bool bootSuppressionDone = false;

    // Test tracking counters
    int totalPaymentsDelivered = 0;
    int totalPulsesDelivered = 0;
    int totalFinalizations = 0;
    std::string lastEndReason = "";

    void init() {
        bootStartTimeMs = currentMillis;
        bootSuppressionDone = false;
        currentState = CoinSlotState::IDLE;
        activeSessionId = "";
        sessionArmedUntil = 0;
        sessionStartTimeMs = 0;
        drainDeadlineMs = 0;
        maxDrainDeadlineMs = 0;
        pendingEndReason = "";
        sessionAccumulatedPulses = 0;
        currentPaymentCallback = nullptr;
        currentEndCallback = nullptr;
        relayPowered = false;
    }

    bool isStartupSuppressionActive() const {
        if (bootSuppressionDone) return false;
        return ((int32_t)((uint32_t)currentMillis - (uint32_t)bootStartTimeMs) < 3000);
    }

    bool isCoinSlotArmed() const {
        if (currentState == CoinSlotState::ARMED) {
            return ((int32_t)((uint32_t)sessionArmedUntil - (uint32_t)currentMillis) > 0);
        }
        if (currentState == CoinSlotState::DRAINING) {
            return true;
        }
        return false;
    }

    void updateRelayHardware() {
        bool shouldBeOn = isCoinSlotArmed() && !isStartupSuppressionActive();
        relayPowered = shouldBeOn;
    }

    bool isCoinSlotBusy(const std::string& sessionId, CoinSlotOwnerType ownerType) const {
        if (currentState == CoinSlotState::FAULT_MAINTENANCE) {
            return true;
        }
        if (currentState == CoinSlotState::IDLE || activeSessionId.empty()) {
            return false;
        }
        if (currentState == CoinSlotState::RESERVED_ARMING) {
            if ((long)(currentMillis - sessionArmedUntil) >= 0) {
                return false;
            }
        }
        if (currentState == CoinSlotState::ARMED) {
            if ((long)(currentMillis - sessionArmedUntil) >= 0 || (sessionStartTimeMs > 0 && (long)(currentMillis - sessionStartTimeMs) >= (long)MAX_SESSION_DURATION)) {
                return false;
            }
        }
        if (currentState == CoinSlotState::DRAINING) {
            return true;
        }
        if (!sessionId.empty() && activeSessionId == sessionId) {
            if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
                return false;
            }
        }
        return true;
    }

    bool tryClaimCoinSlotForArming(const std::string& sessionId, CoinSlotOwnerType ownerType, unsigned long timeoutMs) {
        if (sessionId.empty()) return false;
        if (maintenanceMode || paymentQueueFull || !paymentStorageReady) return false;

        if (isCoinSlotBusy(sessionId, ownerType)) {
            return false;
        }

        if (currentState == CoinSlotState::ARMED && activeSessionId != sessionId) {
            finalizeSessionRelease("TTL_EXPIRED");
        }

        if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY)) {
            sessionArmedUntil = currentMillis + timeoutMs;
            return true;
        }

        currentState = CoinSlotState::RESERVED_ARMING;
        activeSessionId = sessionId;
        activeOwnerType = ownerType;
        sessionStartTimeMs = currentMillis;
        sessionArmedUntil = currentMillis + timeoutMs;
        drainDeadlineMs = 0;
        pendingEndReason = "";
        return true;
    }

    void cancelCoinSlotClaim(const std::string& sessionId, CoinSlotOwnerType ownerType) {
        if (currentState == CoinSlotState::RESERVED_ARMING && activeSessionId == sessionId) {
            if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) {
                currentState = CoinSlotState::IDLE;
                activeSessionId = "";
                activeOwnerType = CoinSlotOwnerType::ANY;
                sessionArmedUntil = 0;
                sessionStartTimeMs = 0;
            }
        }
    }

    void finalizeSessionRelease(const char* reason) {
        if (activeSessionId.empty() && currentState == CoinSlotState::IDLE) {
            return;
        }
        std::string endingSession = activeSessionId;
        CoinSessionEndCallback endCb = currentEndCallback;

        totalFinalizations++;
        lastEndReason = reason ? reason : "";

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

        sessionAccumulatedPulses = 0;
        isrUniversalPulseCount = 0;
        isrLastPulseTimeMs = 0;

        updateRelayHardware();

        if (endCb) {
            endCb(endingSession, reason);
        }
    }

    void initiateSessionRelease(const char* reason, bool force) {
        if (activeSessionId.empty() || currentState == CoinSlotState::IDLE) return;
        const char* terminalReason = (reason != nullptr && reason[0] != '\0') ? reason : "RELEASED";

        if (currentState == CoinSlotState::DRAINING) {
            if (force) {
                finalizeSessionRelease(terminalReason);
            }
            return;
        }

        if (!force) {
            int currentPulses = isrUniversalPulseCount;
            unsigned long lastPulse = isrLastPulseTimeMs;

            currentState = CoinSlotState::DRAINING;
            pendingEndReason = terminalReason;
            maxDrainDeadlineMs = currentMillis + DRAIN_TIMEOUT_GUARD_MS;

            if (currentPulses > 0 || (lastPulse > 0 && ((long)(currentMillis - lastPulse) < (long)IN_FLIGHT_PULSE_GRACE_MS))) {
                drainDeadlineMs = maxDrainDeadlineMs;
            } else {
                drainDeadlineMs = currentMillis + IDLE_DRAIN_GRACE_MS;
            }
            updateRelayHardware();
            return;
        }

        finalizeSessionRelease(terminalReason);
    }

    bool reserveCoinSlot(const std::string& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs,
                         CoinPaymentCallback onPayment, CoinSessionEndCallback onSessionEnd) {
        if (sessionId.empty()) return false;
        if (maintenanceMode || paymentQueueFull || !paymentStorageReady) return false;
        if (currentState == CoinSlotState::DRAINING) return false;
        if (isCoinSlotBusy(sessionId, ownerType)) return false;

        if (currentState == CoinSlotState::ARMED && activeSessionId != sessionId) {
            finalizeSessionRelease("TTL_EXPIRED");
        }

        if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY) &&
            currentState == CoinSlotState::ARMED) {
            if (ownerType != CoinSlotOwnerType::ANY) activeOwnerType = ownerType;
            sessionArmedUntil = currentMillis + (ttlMs > 0 ? ttlMs : ARM_TTL);
            drainDeadlineMs = 0;
            pendingEndReason = "";
            if (onPayment) currentPaymentCallback = onPayment;
            if (onSessionEnd) currentEndCallback = onSessionEnd;
            updateRelayHardware();
            return true;
        }

        currentState = CoinSlotState::ARMED;
        activeSessionId = sessionId;
        activeOwnerType = ownerType;
        sessionStartTimeMs = currentMillis;
        sessionArmedUntil = currentMillis + (ttlMs > 0 ? ttlMs : ARM_TTL);
        drainDeadlineMs = 0;
        pendingEndReason = "";
        currentPaymentCallback = onPayment;
        currentEndCallback = onSessionEnd;
        sessionAccumulatedPulses = 0;
        isrUniversalPulseCount = 0;

        updateRelayHardware();
        return true;
    }

    void releaseCoinSlot(const std::string& sessionId, CoinSlotOwnerType ownerType, bool force, const char* reason = "RELEASED") {
        if (activeSessionId.empty() || currentState == CoinSlotState::IDLE) return;
        if (!force && activeSessionId != sessionId) return;
        if (!force && ownerType != CoinSlotOwnerType::ANY && activeOwnerType != ownerType) return;
        initiateSessionRelease(reason, force);
    }

    void processCoinSlotSession() {
        // 0. Maintenance Mode Check
        if (maintenanceMode) {
            if (currentState != CoinSlotState::FAULT_MAINTENANCE) {
                if (currentState == CoinSlotState::ARMED) {
                    initiateSessionRelease("MAINTENANCE", false);
                } else if (currentState == CoinSlotState::RESERVED_ARMING) {
                    currentState = CoinSlotState::FAULT_MAINTENANCE;
                    activeSessionId = "";
                    activeOwnerType = CoinSlotOwnerType::ANY;
                    sessionArmedUntil = 0;
                    updateRelayHardware();
                } else if (currentState == CoinSlotState::IDLE) {
                    currentState = CoinSlotState::FAULT_MAINTENANCE;
                    updateRelayHardware();
                }
            }
        } else {
            if (currentState == CoinSlotState::FAULT_MAINTENANCE) {
                currentState = CoinSlotState::IDLE;
                updateRelayHardware();
            }
        }

        // 0b. Auto-expire handshake timeout in RESERVED_ARMING state
        if (currentState == CoinSlotState::RESERVED_ARMING) {
            if ((int32_t)((uint32_t)currentMillis - (uint32_t)sessionArmedUntil) >= 0) {
                currentState = CoinSlotState::IDLE;
                activeSessionId = "";
                activeOwnerType = CoinSlotOwnerType::ANY;
                sessionArmedUntil = 0;
                sessionStartTimeMs = 0;
                updateRelayHardware();
                return;
            }
        }

        // 1. Boot pulse suppression
        if (!bootSuppressionDone) {
            if ((int32_t)((uint32_t)currentMillis - (uint32_t)bootStartTimeMs) < 3000) {
                if (isrUniversalPulseCount > 0) {
                    isrUniversalPulseCount = 0;
                }
                updateRelayHardware();
                return;
            } else {
                bootSuppressionDone = true;
                if (isrUniversalPulseCount > 0) {
                    isrUniversalPulseCount = 0;
                }
            }
        }

        // 2. Read pulses
        int newPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        unsigned long lastPulseTime = isrLastPulseTimeMs;

        // 3. Strict isolation
        if (currentState == CoinSlotState::IDLE || activeSessionId.empty()) {
            if (newPulses > 0 || sessionAccumulatedPulses > 0) {
                sessionAccumulatedPulses = 0;
            }
            updateRelayHardware();
            return;
        }

        if (newPulses > 0) {
            sessionAccumulatedPulses += newPulses;
        }

        // 3b. Upgrade idle grace deadline if pulse detected during DRAINING
        if (currentState == CoinSlotState::DRAINING && newPulses > 0) {
            if ((long)(maxDrainDeadlineMs - drainDeadlineMs) > 0) {
                drainDeadlineMs = maxDrainDeadlineMs;
            }
        }

        // 4. Inter-pulse timeout
        if (sessionAccumulatedPulses > 0 && ((long)(currentMillis - (lastPulseTime + INTER_PULSE_TIMEOUT_MS)) >= 0)) {
            int finalPulses = sessionAccumulatedPulses;
            sessionAccumulatedPulses = 0;

            if (finalPulses > 0) {
                totalPaymentsDelivered++;
                totalPulsesDelivered += finalPulses;
                if (currentPaymentCallback) {
                    currentPaymentCallback(activeSessionId, finalPulses);
                }
            }

            if (currentState == CoinSlotState::DRAINING) {
                std::string reason = !pendingEndReason.empty() ? pendingEndReason : "RELEASED";
                finalizeSessionRelease(reason.c_str());
                return;
            }
        }

        // 5. DRAINING timeout guard
        if (currentState == CoinSlotState::DRAINING) {
            bool drainExpired = ((long)(currentMillis - drainDeadlineMs) >= 0);
            bool maxCapExpired = (maxDrainDeadlineMs > 0 && ((long)(currentMillis - maxDrainDeadlineMs) >= 0));
            if (drainExpired || maxCapExpired) {
                int trailingPulses = isrUniversalPulseCount;
                isrUniversalPulseCount = 0;
                int remainingPulses = sessionAccumulatedPulses + trailingPulses;
                sessionAccumulatedPulses = 0;

                if (remainingPulses > 0) {
                    totalPaymentsDelivered++;
                    totalPulsesDelivered += remainingPulses;
                    if (currentPaymentCallback) {
                        currentPaymentCallback(activeSessionId, remainingPulses);
                    }
                }

                std::string reason = !pendingEndReason.empty() ? pendingEndReason : "DRAIN_TIMEOUT";
                finalizeSessionRelease(reason.c_str());
                return;
            }
        }

        // 6. Guards for ARMED
        if (currentState == CoinSlotState::ARMED) {
            if (paymentQueueFull || !paymentStorageReady) {
                initiateSessionRelease("STORAGE_UNAVAILABLE", false);
                return;
            }

            bool ttlExpired = ((long)(currentMillis - sessionArmedUntil) >= 0);
            bool maxDurationExpired = (sessionStartTimeMs > 0 && ((long)(currentMillis - (sessionStartTimeMs + MAX_SESSION_DURATION)) >= 0));
            if (ttlExpired || maxDurationExpired) {
                const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
                initiateSessionRelease(reason, false);
            }
        }

        updateRelayHardware();
    }
};

// ============================================================================
// UNIT TESTS
// ============================================================================

void testStartupSuppressionAndRelayPowerLock() {
    MockCoinSlotEngine engine;
    engine.init();

    assert(engine.isStartupSuppressionActive() == true);
    assert(engine.relayPowered == false);

    // Attempting to reserve during suppression should not energize the relay
    bool ok = engine.reserveCoinSlot("phone_1", CoinSlotOwnerType::PHONE, 10000, nullptr, nullptr);
    assert(ok == true);
    assert(engine.currentState == CoinSlotState::ARMED);
    // Relay must remain OFF during startup suppression!
    assert(engine.relayPowered == false);

    // Any pulses in startup suppression window are suppressed
    engine.isrUniversalPulseCount = 5;
    engine.currentMillis = 2000;
    engine.processCoinSlotSession();
    assert(engine.isrUniversalPulseCount == 0);
    assert(engine.sessionAccumulatedPulses == 0);
    assert(engine.relayPowered == false);

    // Advance past startup suppression (3000ms)
    engine.currentMillis = 4001;
    engine.processCoinSlotSession();
    assert(engine.isStartupSuppressionActive() == false);
    // Now relay can be safely energized!
    assert(engine.relayPowered == true);
    std::cout << "[PASS] testStartupSuppressionAndRelayPowerLock\n";
}

void testPureQueryDoesNotMutateState() {
    MockCoinSlotEngine engine;
    engine.init();
    engine.currentMillis = 5000;
    engine.processCoinSlotSession(); // complete suppression

    engine.reserveCoinSlot("phone_1", CoinSlotOwnerType::PHONE, 5000, nullptr, nullptr);
    assert(engine.currentState == CoinSlotState::ARMED);

    // Advance time past TTL
    engine.currentMillis = 11000;

    // Call isCoinSlotBusy query multiple times
    bool busyOther = engine.isCoinSlotBusy("phone_2", CoinSlotOwnerType::PHONE);
    assert(busyOther == true);
    // State MUST NOT have been mutated into DRAINING or IDLE by a busy query!
    assert(engine.currentState == CoinSlotState::ARMED);

    bool busySame = engine.isCoinSlotBusy("phone_1", CoinSlotOwnerType::PHONE);
    assert(busySame == false);
    assert(engine.currentState == CoinSlotState::ARMED);

    // State transition happens ONLY when processCoinSlotSession() runs
    engine.processCoinSlotSession();
    assert(engine.currentState == CoinSlotState::DRAINING);
    std::cout << "[PASS] testPureQueryDoesNotMutateState\n";
}

void testUnifiedDrainPathPreservesInFlightPulses() {
    MockCoinSlotEngine engine;
    engine.init();
    engine.currentMillis = 5000;
    engine.processCoinSlotSession(); // complete suppression

    int deliveredCount = 0;
    int receivedPulses = 0;
    std::string deliveredSession = "";

    engine.reserveCoinSlot("phone_1", CoinSlotOwnerType::PHONE, 10000,
        [&](const std::string& sess, int pulses) {
            deliveredCount++;
            receivedPulses += pulses;
            deliveredSession = sess;
        },
        nullptr
    );

    // Simulate user inserting coin, 3 pulses captured in ISR
    engine.currentMillis = 6000;
    engine.isrUniversalPulseCount = 3;
    engine.isrLastPulseTimeMs = 6000;

    // WebSocket disconnects / client calls release while pulses in flight!
    engine.releaseCoinSlot("phone_1", CoinSlotOwnerType::PHONE, false, "DISCONNECTED");
    assert(engine.currentState == CoinSlotState::DRAINING);
    // Relay must remain ON during DRAINING so the physical acceptor does not drop power
    assert(engine.relayPowered == true);

    // Run main loop to harvest pulses
    engine.currentMillis = 6050;
    engine.processCoinSlotSession();
    assert(engine.sessionAccumulatedPulses == 3);
    assert(engine.currentState == CoinSlotState::DRAINING);
    assert(deliveredCount == 0); // Not delivered yet until silence interval

    // Advance past inter-pulse gap (280ms)
    engine.currentMillis = 6000 + INTER_PULSE_TIMEOUT_MS + 10;
    engine.processCoinSlotSession();

    // Pulses delivered to original session!
    assert(deliveredCount == 1);
    assert(receivedPulses == 3);
    assert(deliveredSession == "phone_1");

    // Session is now finalized to IDLE exactly once
    assert(engine.currentState == CoinSlotState::IDLE);
    assert(engine.totalFinalizations == 1);
    assert(engine.lastEndReason == "DISCONNECTED");
    assert(engine.relayPowered == false);
    std::cout << "[PASS] testUnifiedDrainPathPreservesInFlightPulses\n";
}

void testDrainTimeoutGuardSalvagesTrailingPulses() {
    MockCoinSlotEngine engine;
    engine.init();
    engine.currentMillis = 5000;
    engine.processCoinSlotSession();

    int receivedPulses = 0;
    engine.reserveCoinSlot("phone_guard", CoinSlotOwnerType::PHONE, 5000,
        [&](const std::string& sess, int p) {
            receivedPulses += p;
        },
        nullptr
    );

    // Entering DRAINING
    engine.currentMillis = 6000;
    engine.releaseCoinSlot("phone_guard", CoinSlotOwnerType::PHONE, false, "DONE");
    assert(engine.currentState == CoinSlotState::DRAINING);

    // A pulse arrives continuously, preventing normal inter-pulse gap
    engine.sessionAccumulatedPulses = 2;
    engine.isrUniversalPulseCount = 1;

    // Advance time past hard drain guard (10 seconds)
    engine.currentMillis = 6000 + DRAIN_TIMEOUT_GUARD_MS + 100;
    engine.processCoinSlotSession();

    // Guard trips, trailing pulses salvaged (2 accumulated + 1 ISR = 3)
    assert(receivedPulses == 3);
    assert(engine.currentState == CoinSlotState::IDLE);
    assert(engine.totalFinalizations == 1);
    assert(engine.lastEndReason == "DONE");
    std::cout << "[PASS] testDrainTimeoutGuardSalvagesTrailingPulses\n";
}

void testFaultMaintenanceBlocksReservationsAndDrainsActive() {
    MockCoinSlotEngine engine;
    engine.init();
    engine.currentMillis = 5000;
    engine.processCoinSlotSession();

    // Active session
    engine.reserveCoinSlot("phone_active", CoinSlotOwnerType::PHONE, 10000, nullptr, nullptr);
    assert(engine.currentState == CoinSlotState::ARMED);
    assert(engine.relayPowered == true);

    // OTA or admin sets maintenance mode
    engine.maintenanceMode = true;
    engine.processCoinSlotSession();

    // Must initiate drain for active session
    assert(engine.currentState == CoinSlotState::DRAINING);
    assert(engine.pendingEndReason == "MAINTENANCE");

    // Fast-forward to complete drain
    engine.currentMillis = 10000;
    engine.processCoinSlotSession();
    assert(engine.currentState == CoinSlotState::IDLE);

    // Next loop cycle detects maintenanceMode and enters FAULT_MAINTENANCE
    engine.processCoinSlotSession();
    assert(engine.currentState == CoinSlotState::FAULT_MAINTENANCE);
    assert(engine.relayPowered == false);

    // Slot is busy for all incoming claims while in FAULT_MAINTENANCE
    assert(engine.isCoinSlotBusy("phone_new", CoinSlotOwnerType::PHONE) == true);
    bool claimOk = engine.tryClaimCoinSlotForArming("phone_new", CoinSlotOwnerType::PHONE, 5000);
    assert(claimOk == false);
    bool reserveOk = engine.reserveCoinSlot("phone_new", CoinSlotOwnerType::PHONE, 5000, nullptr, nullptr);
    assert(reserveOk == false);

    // Clearing maintenance mode restores IDLE state
    engine.maintenanceMode = false;
    engine.processCoinSlotSession();
    assert(engine.currentState == CoinSlotState::IDLE);
    assert(engine.isCoinSlotBusy("phone_new", CoinSlotOwnerType::PHONE) == false);
    std::cout << "[PASS] testFaultMaintenanceBlocksReservationsAndDrainsActive\n";
}

void testRolloverSafeArithmetic() {
    MockCoinSlotEngine engine;
    engine.init();

    // Set time right near unsigned long overflow (0xFFFFFFFF)
    engine.bootStartTimeMs = 0xFFFFFF00UL;
    engine.currentMillis = 0xFFFFFF50UL;

    // Check suppression with rollover arithmetic
    assert(engine.isStartupSuppressionActive() == true);

    // Move past overflow
    engine.currentMillis = 5000UL;
    assert((int32_t)((uint32_t)engine.currentMillis - (uint32_t)engine.bootStartTimeMs) >= 3000);
    engine.processCoinSlotSession();
    assert(engine.bootSuppressionDone == true);
    assert(engine.isStartupSuppressionActive() == false);
    std::cout << "[PASS] testRolloverSafeArithmetic\n";
}

int main() {
    std::cout << "=== Running CoinSlotManager Hardened State Machine Tests ===\n";
    testStartupSuppressionAndRelayPowerLock();
    testPureQueryDoesNotMutateState();
    testUnifiedDrainPathPreservesInFlightPulses();
    testDrainTimeoutGuardSalvagesTrailingPulses();
    testFaultMaintenanceBlocksReservationsAndDrainsActive();
    testRolloverSafeArithmetic();
    std::cout << "=== All CoinSlotManager Tests Passed Successfully! ===\n";
    return 0;
}
