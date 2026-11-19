#include "CoinSlotManager.h"
#include "CoinSettle.h"
#include "Config.h"
#include "Diagnostics.h"
#include "GatewaySessionStore.h"
#include "HardwareManager.h"
#include "SetupGate.h"

static const unsigned long INTER_PULSE_TIMEOUT_MS = 280;
static const unsigned long IN_FLIGHT_PULSE_GRACE_MS = 560;
static const unsigned long DRAIN_TIMEOUT_GUARD_MS = 5000;
static const unsigned long IDLE_DRAIN_GRACE_MS = 1000;

static CoinSlotState currentState = CoinSlotState::IDLE;
static CoinSlotOwnerType activeOwnerType = CoinSlotOwnerType::ANY;
static String activeSessionId = "";
static unsigned long sessionArmedUntil = 0;
static unsigned long sessionStartTimeMs = 0;
static unsigned long drainDeadlineMs = 0;
static unsigned long maxDrainDeadlineMs = 0;
static String pendingEndReason = "";
static unsigned long ignorePulsesUntilMs = 0;
static int sessionAccumulatedPulses = 0;

static CoinPaymentCallback currentPaymentCallback = nullptr;
static CoinSessionEndCallback currentEndCallback = nullptr;

static void finalizeSessionRelease(const char* reason) {
    if (activeSessionId.length() == 0 && currentState == CoinSlotState::IDLE) {
        return;
    }

    String endingSession = activeSessionId;
    CoinSessionEndCallback endCb = currentEndCallback;

    diagLog("[COIN SLOT] Finalizing session '%s' (Reason: %s).\n", endingSession.c_str(), reason);

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
    ignorePulsesUntilMs = 0;

    setRelayHardware(false);
    sessionAccumulatedPulses = 0;
    resetCoinDetectorStates();

    if (endCb) {
        endCb(endingSession, reason);
    }
}

static void initiateSessionRelease(const char* reason, bool force) {
    if (activeSessionId.length() == 0 || currentState == CoinSlotState::IDLE) return;

    unsigned long now = millis();
    const char* terminalReason = (reason != nullptr && strlen(reason) > 0) ? reason : "RELEASED";

    if (currentState == CoinSlotState::DRAINING) {
        if (force) {
            finalizeSessionRelease(terminalReason);
        }
        return;
    }

    if (!force) {
        unsigned long lastPulse = 0;
        int currentPulses = coinPulsesPeek(&lastPulse);

        currentState = CoinSlotState::DRAINING;
        pendingEndReason = terminalReason;
        maxDrainDeadlineMs = now + DRAIN_TIMEOUT_GUARD_MS;

        if (currentPulses > 0 || (lastPulse > 0 && (now - lastPulse < IN_FLIGHT_PULSE_GRACE_MS))) {
            Serial.printf(
                "[COIN SLOT] Release requested for '%s' while pulses in flight (%d pulses). Entering full DRAINING state...\n",
                activeSessionId.c_str(), currentPulses);
            drainDeadlineMs = maxDrainDeadlineMs;
        } else {
            Serial.printf(
                "[COIN SLOT] Release requested for '%s' while idle. Entering DRAINING state with %lu ms idle grace...\n",
                activeSessionId.c_str(), IDLE_DRAIN_GRACE_MS);
            drainDeadlineMs = now + IDLE_DRAIN_GRACE_MS;
        }
        return;
    }

    finalizeSessionRelease(terminalReason);
}

void initCoinSlotManager() {
    currentState = CoinSlotState::IDLE;
    activeSessionId = "";
    sessionArmedUntil = 0;
    sessionStartTimeMs = 0;
    drainDeadlineMs = 0;
    maxDrainDeadlineMs = 0;
    pendingEndReason = "";
    currentPaymentCallback = nullptr;
    currentEndCallback = nullptr;
    setRelayHardware(false);
    sessionAccumulatedPulses = 0;
    resetCoinDetectorStates();
    initGatewaySessionStore();
}

CoinSlotState getCoinSlotState() {
    return currentState;
}

bool isCoinSlotArmed() {
    return currentState == CoinSlotState::ARMED || currentState == CoinSlotState::DRAINING;
}

bool isCoinSlotBusy(const String& sessionId, CoinSlotOwnerType ownerType) {
    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        return false;
    }

    unsigned long now = millis();

    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = ((long)(now - sessionArmedUntil) >= 0);
        bool maxDurationExpired =
            (sessionStartTimeMs > 0 && ((long)(now - (sessionStartTimeMs + MAX_SESSION_DURATION)) >= 0));
        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            Serial.printf("[COIN SLOT] Active session '%s' expired during busy check (%s). Initiating drain.\n",
                          activeSessionId.c_str(), reason);
            initiateSessionRelease(reason, false);
            return true;
        }
    }

    if (currentState == CoinSlotState::DRAINING) {
        return true;
    }

    if (sessionId.length() > 0 && activeSessionId == sessionId) {
        if (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == CoinSlotOwnerType::ANY ||
            activeOwnerType == ownerType) {
            return false;
        }
    }
    return true;
}

String getActiveCoinSessionId() {
    return activeSessionId;
}

unsigned long getCoinSlotSettleRemainingMs() {
    return coinSettleRemainingMs(millis(), ignorePulsesUntilMs);
}

unsigned long getCoinSlotArmedUntilMs() {
    return (currentState == CoinSlotState::ARMED) ? sessionArmedUntil : 0;
}

CoinSlotOwnerType getActiveCoinOwnerType() {
    return activeOwnerType;
}

bool reserveCoinSlot(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs,
                     CoinPaymentCallback onPayment, CoinSessionEndCallback onSessionEnd) {
    if (sessionId.length() == 0) return false;

    if (!setupgate::usageAllowed(adminPwChanged)) {
        diagLog("[COIN SLOT] Reservation rejected for '%s': default admin password has not been changed.\n",
                sessionId.c_str());
        return false;
    }

    if (!gatewayStore().canAcceptSession(std::string(sessionId.c_str()), (uint32_t)millis(), getCurrentMasterTimeMs(),
                                         std::string(activeSessionId.c_str()))) {
        diagLog("[COIN SLOT] Reservation rejected for '%s': Storage unavailable or session queue full!\n",
                sessionId.c_str());
        return false;
    }

    unsigned long now = millis();

    if (currentState == CoinSlotState::DRAINING) {
        diagLog("[COIN SLOT] Reservation rejected for '%s': Slot is currently DRAINING.\n", sessionId.c_str());
        return false;
    }

    if (isCoinSlotBusy(sessionId, ownerType)) {
        diagLog("[COIN SLOT] Reservation rejected for '%s': Slot busy with '%s' (State: %d)\n", sessionId.c_str(),
                activeSessionId.c_str(), (int)currentState);
        return false;
    }

    if (activeSessionId == sessionId && (activeOwnerType == ownerType || ownerType == CoinSlotOwnerType::ANY) &&
        currentState == CoinSlotState::ARMED) {
        currentState = CoinSlotState::ARMED;
        if (ownerType != CoinSlotOwnerType::ANY) activeOwnerType = ownerType;
        sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
        drainDeadlineMs = 0;
        pendingEndReason = "";

        if (onPayment) currentPaymentCallback = onPayment;
        if (onSessionEnd) currentEndCallback = onSessionEnd;

        setRelayHardware(true);
        diagLog("[COIN SLOT] Session '%s' RE-ARMED (TTL: %lu ms)\n", activeSessionId.c_str(), ttlMs);
        return true;
    }

    currentState = CoinSlotState::ARMED;
    activeSessionId = sessionId;
    activeOwnerType = ownerType;
    sessionStartTimeMs = now;
    sessionArmedUntil = now + (ttlMs > 0 ? ttlMs : ARM_TTL);
    drainDeadlineMs = 0;
    pendingEndReason = "";

    currentPaymentCallback = onPayment;
    currentEndCallback = onSessionEnd;

    sessionAccumulatedPulses = 0;
    resetCoinDetectorStates();
    diagCount(DiagCounter::SlotReservations);

    setRelayHardware(true);
    ignorePulsesUntilMs = millis() + PISO_COIN_SETTLE_MS;

    diagLog("[COIN SLOT] Slot RESERVED & ARMED for '%s' (TTL: %lu ms)\n", activeSessionId.c_str(), ttlMs);
    return true;
}

bool refreshCoinSlotTtl(const String& sessionId, CoinSlotOwnerType ownerType, unsigned long ttlMs) {
    if (activeSessionId.length() > 0 && activeSessionId == sessionId &&
        (ownerType == CoinSlotOwnerType::ANY || activeOwnerType == ownerType) && currentState == CoinSlotState::ARMED) {
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

void processCoinSlotSession() {
    unsigned long now = millis();

    serviceCoinSlotHardwareConfig();
    processGatewaySessionStore();

    // Ignore early boot spikes for the first 3 seconds.
    static bool bootSettled = false;
    if (!bootSettled) {
        if (now < 3000) {
            coinPulsesTake();
            return;
        }
        bootSettled = true;
    }

    // Log ignored noise pulses at most once a second.
    {
        static unsigned long lastShortCheckMs = 0;
        static uint32_t shortReported = 0;
        static uint32_t longReported = 0;
        if (now - lastShortCheckMs >= 1000) {
            lastShortCheckMs = now;
            PulseStats ps = coinPulseStats();
            if (ps.shortN < shortReported) shortReported = 0;
            if (ps.shortN > shortReported) {
                diagLog("[COIN SLOT] %u pulse(s) too short to be a coin (shortest %u us): %s\n",
                        (unsigned)(ps.shortN - shortReported), (unsigned)ps.minUs,
                        coinPulseGuard ? "ignored." : "COUNTED (pulse guard off).");
                shortReported = ps.shortN;
            }
            if (ps.longN < longReported) longReported = 0;
            if (ps.longN > longReported) {
                diagLog("[COIN SLOT] %u pulse(s) too long to be a coin (>180 ms): ignored.\n",
                        (unsigned)(ps.longN - longReported));
                longReported = ps.longN;
            }
        }
    }

    unsigned long lastPulseTime = 0;
    int newPulses = coinPulsesTake(&lastPulseTime);

    // Ignore stray pulses while the acceptor settles after power-on.
    if (newPulses > 0 &&
        (coinSettleActive(now, ignorePulsesUntilMs) || coinSettleCovers(lastPulseTime, ignorePulsesUntilMs))) {
        ignorePulsesUntilMs = coinSettleExtend(ignorePulsesUntilMs, lastPulseTime, INTER_PULSE_TIMEOUT_MS);
        diagLog("[COIN SLOT] Ignored %d stray pulse(s) during power-on settle.\n", newPulses);
        newPulses = 0;
        sessionAccumulatedPulses = 0;
    }

    if (currentState == CoinSlotState::IDLE || activeSessionId.length() == 0) {
        if (newPulses > 0 || sessionAccumulatedPulses > 0) {
            int dropped = newPulses + sessionAccumulatedPulses;
            sessionAccumulatedPulses = 0;
            diagLog("[COIN SLOT] Ignored %d pulse(s) while IDLE (no active session).\n", dropped);
        }
        return;
    }

    if (newPulses > 0) {
        sessionAccumulatedPulses += newPulses;
    }

    if (currentState == CoinSlotState::DRAINING && newPulses > 0) {
        if ((long)(maxDrainDeadlineMs - drainDeadlineMs) > 0) {
            drainDeadlineMs = maxDrainDeadlineMs;
        }
    }

    if (sessionAccumulatedPulses > 0 && ((long)(now - (lastPulseTime + INTER_PULSE_TIMEOUT_MS)) >= 0)) {
        int finalPulses = sessionAccumulatedPulses;
        sessionAccumulatedPulses = 0;

        if (finalPulses > 0) {
            String deliveringSession = activeSessionId;
            diagCount(DiagCounter::CoinEvents);
            diagCount(DiagCounter::CoinPulses, (uint32_t)finalPulses);
            diagLog("[COIN SLOT] Detected %d pulse(s) for session '%s'.\n", finalPulses, deliveringSession.c_str());

            if (currentPaymentCallback) {
                currentPaymentCallback(deliveringSession, finalPulses);
            }
        }

        if (currentState == CoinSlotState::DRAINING) {
            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "RELEASED";
            finalizeSessionRelease(reason.c_str());
            return;
        }
    }

    if (currentState == CoinSlotState::DRAINING) {
        bool drainExpired = ((long)(now - drainDeadlineMs) >= 0);
        bool maxCapExpired = (maxDrainDeadlineMs > 0 && ((long)(now - maxDrainDeadlineMs) >= 0));
        if (drainExpired || maxCapExpired) {
            int trailingPulses = coinPulsesTake();
            int remainingPulses = sessionAccumulatedPulses + trailingPulses;
            sessionAccumulatedPulses = 0;

            if (remainingPulses > 0) {
                diagCount(DiagCounter::CoinEvents);
                diagCount(DiagCounter::CoinPulses, (uint32_t)remainingPulses);
                if (currentPaymentCallback) {
                    currentPaymentCallback(activeSessionId, remainingPulses);
                }
            }

            String reason = pendingEndReason.length() > 0 ? pendingEndReason : "DRAIN_TIMEOUT";
            finalizeSessionRelease(reason.c_str());
            return;
        }
    }

    if (currentState == CoinSlotState::ARMED) {
        bool ttlExpired = ((long)(now - sessionArmedUntil) >= 0);
        bool maxDurationExpired =
            (sessionStartTimeMs > 0 && ((long)(now - (sessionStartTimeMs + MAX_SESSION_DURATION)) >= 0));

        if (ttlExpired || maxDurationExpired) {
            const char* reason = ttlExpired ? "TTL_EXPIRED" : "MAX_DURATION";
            diagLog("[COIN SLOT] Session %s for '%s'. Draining...\n", reason, activeSessionId.c_str());
            initiateSessionRelease(reason, false);
        }
    }
}
