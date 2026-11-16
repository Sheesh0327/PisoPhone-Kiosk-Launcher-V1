#include "GatewayCoinslot.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "GatewayAuth.h"
#include "GatewayEvent.h"
#include "GatewaySessionStore.h"
#include "HardwareManager.h"
#include "SetupGate.h"
#include <WiFiUdp.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

static SemaphoreHandle_t keyMutex = nullptr;
static String gatewayKeyValue;

void gatewayInit() {
    if (!keyMutex) keyMutex = xSemaphoreCreateMutex();
    prefs.begin(NVS_NAMESPACE, false);
    String stored = prefs.getString(NVS_KEY_GATEWAY_KEY, "");
    prefs.end();
    xSemaphoreTake(keyMutex, portMAX_DELAY);
    gatewayKeyValue = (stored.length() >= gatewayauth::MIN_KEY_LENGTH) ? stored : "";
    xSemaphoreGive(keyMutex);
    if (gatewayKeyValue.length() > 0) diagLog("[GATEWAY] Coin-slot gateway enabled.\n");
}

bool gatewayConfigured() {
    return gatewayKey().length() > 0;
}

String gatewayKey() {
    if (!keyMutex) return "";
    xSemaphoreTake(keyMutex, portMAX_DELAY);
    String copy = gatewayKeyValue;
    xSemaphoreGive(keyMutex);
    return copy;
}

bool gatewaySetKey(const String& key) {
    if (key.length() != 0 && key.length() < gatewayauth::MIN_KEY_LENGTH) return false;
    prefs.begin(NVS_NAMESPACE, false);
    if (key.length() == 0) {
        prefs.remove(NVS_KEY_GATEWAY_KEY);
    } else {
        prefs.putString(NVS_KEY_GATEWAY_KEY, key);
    }
    prefs.end();
    if (!keyMutex) keyMutex = xSemaphoreCreateMutex();
    xSemaphoreTake(keyMutex, portMAX_DELAY);
    gatewayKeyValue = key;
    xSemaphoreGive(keyMutex);
    diagLog(key.length() == 0 ? "[GATEWAY] Coin-slot gateway disabled.\n" : "[GATEWAY] Gateway key updated.\n");
    return true;
}

struct EventTarget {
    String session;
    String wid;
    IPAddress ip;
    uint16_t port = 0;
    uint32_t seq = 0;
    bool readySent = false;
    bool windowActive = false;
    bool endSent = false;
};
static EventTarget evt;
static WiFiUDP evUdp;

static void sendEvent(const char* type, int pulses) {
    if (evt.port == 0) return;
    std::string l =
        gatewayevent::line(gatewayKey().c_str(), evt.session.c_str(), evt.wid.c_str(), ++evt.seq, type, pulses);
    if (l.empty()) return;
    for (int i = 0; i < 2; i++) {
        if (evUdp.beginPacket(evt.ip, evt.port)) {
            evUdp.write((const uint8_t*)l.data(), l.size());
            evUdp.endPacket();
        }
    }
}

void gatewaySetEventTarget(const String& session, const String& wid, IPAddress ip, uint16_t port) {
    if (evt.session == session && evt.wid == wid) {
        evt.ip = ip;
        evt.port = port;
        if (evt.endSent) {
            evt.readySent = false;
            evt.endSent = false;
        }
        evt.windowActive = true;
        return;
    }
    if (evt.port != 0 && evt.windowActive && !evt.endSent) {
        sendEvent("end", gatewayStore().pendingPulses(evt.session.c_str()));
    }
    evt = EventTarget();
    evt.session = session;
    evt.wid = wid;
    evt.ip = ip;
    evt.port = port;
    evt.windowActive = true;
}

static void notifyCoin(const String& session) {
    if (evt.port == 0 || evt.session != session) return;
    sendEvent("coin", gatewayStore().pendingPulses(session.c_str()));
}

static void notifyEnd(const String& session) {
    if (evt.port == 0 || evt.session != session || !evt.windowActive || evt.endSent) return;
    sendEvent("end", gatewayStore().pendingPulses(session.c_str()));
    evt.endSent = true;
    evt.windowActive = false;
    evt.port = 0;
}

void gatewayEventsLoop() {
    if (evt.port == 0 || !evt.windowActive) return;
    bool active = getActiveCoinSessionId() == evt.session && getActiveCoinOwnerType() == CoinSlotOwnerType::GATEWAY;
    if (active && !evt.readySent && getCoinSlotState() == CoinSlotState::ARMED && getCoinSlotSettleRemainingMs() == 0) {
        evt.readySent = true;
        sendEvent("ready", gatewayStore().pendingPulses(evt.session.c_str()));
    }
}

GatewayArmResult gatewayArm(const String& session, int durationSec) {
    if (!gatewayauth::validSessionId(session.c_str())) return GatewayArmResult::InvalidSession;
    if (!setupgate::usageAllowed(adminPwChanged)) return GatewayArmResult::SetupRequired;
    if (durationSec < GATEWAY_MIN_ARM_SECONDS) durationSec = GATEWAY_MIN_ARM_SECONDS;
    if (durationSec > GATEWAY_MAX_ARM_SECONDS) durationSec = GATEWAY_MAX_ARM_SECONDS;

    if (isCoinSlotBusy(session, CoinSlotOwnerType::GATEWAY)) return GatewayArmResult::Busy;

    String active = getActiveCoinSessionId();
    if (!gatewayStore().canAcceptSession(std::string(session.c_str()), (uint32_t)millis(), getCurrentMasterTimeMs(),
                                         std::string(active.c_str()))) {
        return GatewayArmResult::StorageUnavailable;
    }

    bool ok = reserveCoinSlot(
        session, CoinSlotOwnerType::GATEWAY, (unsigned long)durationSec * 1000UL,
        [](const String& id, int pulses) {
            if (!gatewayStore().recordPulses(std::string(id.c_str()), pulses, (uint32_t)millis(),
                                             getCurrentMasterTimeMs())) {
                diagLog("[GATEWAY] CRITICAL: could not retain %d pulse(s) for '%s'.\n", pulses, id.c_str());
            } else {
                diagLog("[GATEWAY] %d pulse(s) recorded for '%s' (total=%d, life=%u).\n", pulses, id.c_str(),
                        gatewayStore().pendingPulses(id.c_str()), (unsigned)gatewayStore().lifetimePulses());
            }
            triggerLedBlink(2);
            notifyCoin(id);
        },
        [](const String& id, const char* reason) {
            diagLog("[GATEWAY] Session '%s' ended (%s).\n", id.c_str(), reason);
            notifyEnd(id);
        });
    return ok ? GatewayArmResult::Ok : GatewayArmResult::Busy;
}

void gatewayRelease(const String& session) {
    releaseCoinSlot(session, CoinSlotOwnerType::GATEWAY, false, "GATEWAY_DONE");
}

GatewayStatus gatewayStatus(const String& session) {
    GatewayStatus st;
    st.state = "idle";
    st.armedRemainingSec = 0;
    if (getActiveCoinSessionId() == session && getActiveCoinOwnerType() == CoinSlotOwnerType::GATEWAY) {
        CoinSlotState s = getCoinSlotState();
        if (s == CoinSlotState::ARMED) {
            st.state = "armed";
            unsigned long until = getCoinSlotArmedUntilMs();
            long remainingMs = (long)(until - millis());
            st.armedRemainingSec = remainingMs > 0 ? (int)(remainingMs / 1000L) : 0;
        } else if (s == CoinSlotState::DRAINING) {
            st.state = "draining";
        }
    }
    st.pulses = gatewayStore().pendingPulses(session.c_str());
    st.minutesPerCoin = minutesPerCoin;
    st.readyInMs = (getActiveCoinSessionId() == session) ? getCoinSlotSettleRemainingMs() : 0UL;
    st.slotFree = getCoinSlotState() == CoinSlotState::IDLE;
    return st;
}

GatewayAckStatus gatewayAcknowledge(const String& session, int& acknowledgedOut) {
    bool sessionBusy = (getActiveCoinSessionId() == session && getCoinSlotState() != CoinSlotState::IDLE);
    switch (gatewayStore().acknowledge(std::string(session.c_str()), sessionBusy, acknowledgedOut)) {
    case sessionstore::AckResult::Ok:
        return GatewayAckStatus::Ok;
    case sessionstore::AckResult::Busy:
        return GatewayAckStatus::Busy;
    case sessionstore::AckResult::StorageError:
        return GatewayAckStatus::StorageError;
    }
    return GatewayAckStatus::StorageError;
}
