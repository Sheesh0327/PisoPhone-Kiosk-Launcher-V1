// Network gateway view of the universal coin slot. See include/GatewayCoinslot.h for the flow.
#include "GatewayCoinslot.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "SetupGate.h"
#include "DeviceNetwork.h"
#include "Diagnostics.h"
#include "GatewayAuth.h"
#include "PaymentQueueManager.h"
#include "GatewayEvent.h"
#include <WiFiUdp.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

static const char* NVS_KEY_GATEWAY_KEY = "gw_key";

// The slot's session id is the gateway's id with this prefix, so a gateway session can never
// collide with a phone's device id or a controller's session id.
static String slotSessionId(const String& session) {
    return "GW:" + session;
}

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

// ---------------------------------------------------------------------------
// Coin events to the router (see GatewayEvent.h). Only touched from the main loop (the HTTP handlers and the coin
// callbacks run there too), so no lock is needed.
// ---------------------------------------------------------------------------
struct EventTarget {
    String session;
    String wid;
    IPAddress ip;
    uint16_t port = 0; // 0 = no events wanted
    uint32_t seq = 0;
    bool readySent = false;
    bool sawActive = false;
};
static EventTarget evt;
static WiFiUDP evUdp;

static void sendEvent(const char* type, int pulses) {
    if (evt.port == 0) return;
    std::string l =
        gatewayevent::line(gatewayKey().c_str(), evt.session.c_str(), evt.wid.c_str(), ++evt.seq, type, pulses);
    if (l.empty()) return;
    // Twice: Wi-Fi drops single datagrams now and then. The router keeps the highest total, so a repeat is harmless.
    for (int i = 0; i < 2; i++) {
        if (evUdp.beginPacket(evt.ip, evt.port)) {
            evUdp.write((const uint8_t*)l.data(), l.size());
            evUdp.endPacket();
        }
    }
}

void gatewaySetEventTarget(const String& session, const String& wid, IPAddress ip, uint16_t port) {
    if (evt.port != 0 && evt.session == session && evt.wid == wid) { // the same window armed again: keep its sequence
        evt.ip = ip;
        evt.port = port;
        return;
    }
    evt = EventTarget();
    evt.session = session;
    evt.wid = wid;
    evt.ip = ip;
    evt.port = port;
}

static void notifyCoin(const String& slotId) {
    if (evt.port == 0 || slotSessionId(evt.session) != slotId) return;
    sendEvent("coin", getPendingGatewayPulses(slotId));
}

void gatewayEventsLoop() {
    if (evt.port == 0) return;
    String slotId = slotSessionId(evt.session);
    bool active = getActiveCoinSessionId() == slotId && getActiveCoinOwnerType() == CoinSlotOwnerType::GATEWAY;
    if (active) {
        evt.sawActive = true;
        if (!evt.readySent && getCoinSlotState() == CoinSlotState::ARMED && getCoinSlotSettleRemainingMs() == 0) {
            evt.readySent = true;
            sendEvent("ready", getPendingGatewayPulses(slotId));
        }
    } else if (evt.sawActive) { // drained and released: the final total for this window
        sendEvent("end", getPendingGatewayPulses(slotId));
        evt.port = 0;
    }
}

GatewayArmResult gatewayArm(const String& session, int durationSec) {
    if (!gatewayauth::validSessionId(session.c_str())) return GatewayArmResult::InvalidSession;
    if (!setupgate::usageAllowed(adminPwChanged)) return GatewayArmResult::SetupRequired;
    if (durationSec < GATEWAY_MIN_ARM_SECONDS) durationSec = GATEWAY_MIN_ARM_SECONDS;
    if (durationSec > GATEWAY_MAX_ARM_SECONDS) durationSec = GATEWAY_MAX_ARM_SECONDS;

    String slotId = slotSessionId(session);
    if (isCoinSlotBusy(slotId, CoinSlotOwnerType::GATEWAY)) return GatewayArmResult::Busy;
    // reserveCoinSlot also refuses while storage is unwritable or the queue is nearly full, so a
    // coin is never accepted that cannot be recorded.
    if (!isPaymentStorageReady() || isPaymentQueueFull()) return GatewayArmResult::StorageUnavailable;

    bool ok = reserveCoinSlot(
        slotId, CoinSlotOwnerType::GATEWAY, durationSec * 1000UL,
        // A coin arrived: record it durably first, then count it as revenue. Nothing is pushed to a
        // phone; the gateway collects it with gatewayStatus()/gatewayRelease().
        [](const String& id, int pulses) {
            String txId = generateTxId("gw-");
            if (!enqueuePendingPayment(txId, id, pulses, CoinSlotOwnerType::GATEWAY)) {
                diagLog("[GATEWAY] CRITICAL: could not retain %d pulse(s) for '%s'.\n", pulses, id.c_str());
            }
            recordCoinRevenue(pulses);
            diagLog("[GATEWAY] %d pulse(s) recorded for '%s' (tx_id=%s).\n", pulses, id.c_str(), txId.c_str());
            notifyCoin(id); // the router learns about the coin now instead of at its next poll
        },
        [](const String& id, const char* reason) {
            diagLog("[GATEWAY] Session '%s' ended (%s).\n", id.c_str(), reason);
        });
    return ok ? GatewayArmResult::Ok : GatewayArmResult::Busy;
}

void gatewayRelease(const String& session) {
    // Not forced: CoinSlotManager drains in-flight pulses before letting go of the slot.
    releaseCoinSlot(slotSessionId(session), CoinSlotOwnerType::GATEWAY, false, "GATEWAY_DONE");
}

GatewayStatus gatewayStatus(const String& session) {
    GatewayStatus st;
    String slotId = slotSessionId(session);
    st.state = "idle";
    st.armedRemainingSec = 0;
    if (getActiveCoinSessionId() == slotId && getActiveCoinOwnerType() == CoinSlotOwnerType::GATEWAY) {
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
    st.pulses = getPendingGatewayPulses(slotId);
    st.minutesPerCoin = minutesPerCoin;
    st.readyInMs = (getActiveCoinSessionId() == slotId) ? getCoinSlotSettleRemainingMs() : 0UL;
    st.slotFree = getCoinSlotState() == CoinSlotState::IDLE;
    return st;
}

int gatewayAcknowledge(const String& session) {
    return acknowledgeGatewayPayments(slotSessionId(session));
}
