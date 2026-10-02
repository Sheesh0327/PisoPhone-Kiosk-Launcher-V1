#include "Diagnostics.h"
#include "DiagRing.h"
#include "FirmwareVersion.h"
#include "Config.h"
#include "CoinSlotManager.h"
#include "PaymentQueueManager.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include <ArduinoJson.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <stdarg.h>

static const size_t DIAG_LINES = 40;
static const size_t DIAG_LINE_LEN = 120;
static const char* DIAG_NVS_NAMESPACE = "diag";
static const size_t DIAG_HISTORY_ENTRIES = 8;

static DiagRing<DIAG_LINES, DIAG_LINE_LEN> diagRing;
static SemaphoreHandle_t diagMutex = nullptr;
static uint32_t diagCounters[(size_t)DiagCounter::Count] = {0};
static const char* diagResetReason = "UNKNOWN";
static uint32_t diagBootCount = 0;
static String diagResetHistory = "";
static String diagRestartCause = ""; // set by diagNoteRestartReason() before the previous restart, "" if none

static const char* resetReasonName(esp_reset_reason_t reason) {
    switch (reason) {
    case ESP_RST_POWERON:
        return "POWERON";
    case ESP_RST_EXT:
        return "EXT_PIN";
    case ESP_RST_SW:
        return "SOFTWARE";
    case ESP_RST_PANIC:
        return "PANIC";
    case ESP_RST_INT_WDT:
        return "INT_WDT";
    case ESP_RST_TASK_WDT:
        return "TASK_WDT";
    case ESP_RST_WDT:
        return "WDT";
    case ESP_RST_DEEPSLEEP:
        return "DEEPSLEEP";
    case ESP_RST_BROWNOUT:
        return "BROWNOUT";
    case ESP_RST_SDIO:
        return "SDIO";
    default:
        return "UNKNOWN";
    }
}

static const char* coinSlotStateName(CoinSlotState state) {
    switch (state) {
    case CoinSlotState::IDLE:
        return "IDLE";
    case CoinSlotState::ARMED:
        return "ARMED";
    case CoinSlotState::DRAINING:
        return "DRAINING";
    default:
        return "UNKNOWN";
    }
}

void diagInit() {
    if (diagMutex == nullptr) diagMutex = xSemaphoreCreateMutex();

    diagResetReason = resetReasonName(esp_reset_reason());

    Preferences store;
    if (store.begin(DIAG_NVS_NAMESPACE, false)) {
        diagBootCount = store.getUInt("boots", 0) + 1;
        String history = store.getString("hist", "");
        history = String(diagResetReason) + (history.length() ? "," : "") + history;
        // Keep only the newest entries so the string stays small.
        int commas = 0;
        for (size_t i = 0; i < history.length(); i++) {
            if (history[i] == ',' && ++commas == (int)DIAG_HISTORY_ENTRIES) {
                history = history.substring(0, i);
                break;
            }
        }
        diagRestartCause = store.getString("why", "");
        store.remove("why");
        store.putUInt("boots", diagBootCount);
        store.putString("hist", history);
        store.end();
        diagResetHistory = history;
    }

    diagLog("[DIAG] Boot #%u, reset reason: %s%s%s, firmware v%s", (unsigned)diagBootCount, diagResetReason,
            diagRestartCause.length() ? " / cause: " : "", diagRestartCause.c_str(), PISO_FW_VERSION);
}

void diagNoteRestartReason(const char* why) {
    Preferences store;
    if (store.begin(DIAG_NVS_NAMESPACE, false)) {
        store.putString("why", why ? why : "");
        store.end();
    }
}

void diagLog(const char* fmt, ...) {
    char line[DIAG_LINE_LEN + 40];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);

    Serial.print(line);
    if (line[0] == '\0' || line[strlen(line) - 1] != '\n') Serial.println();

    if (diagMutex != nullptr) xSemaphoreTake(diagMutex, portMAX_DELAY);
    diagRing.push(millis(), line);
    if (diagMutex != nullptr) xSemaphoreGive(diagMutex);
}

void diagCount(DiagCounter counter, uint32_t amount) {
    if ((size_t)counter >= (size_t)DiagCounter::Count) return;
    __atomic_fetch_add(&diagCounters[(size_t)counter], amount, __ATOMIC_RELAXED);
}

String diagBuildJson() {
    DynamicJsonDocument doc(10240);

    doc["firmware"] = PISO_FW_VERSION;
    doc["built"] = __DATE__ " " __TIME__;
#if CONFIG_IDF_TARGET_ESP32C3
    doc["chip"] = "esp32c3";
#else
    doc["chip"] = "esp32";
#endif
    doc["mac"] = macAddressStr;
    doc["uptime_s"] = millis() / 1000UL;

    JsonObject heap = doc.createNestedObject("heap");
    heap["free"] = ESP.getFreeHeap();
    heap["min_free"] = ESP.getMinFreeHeap();
    heap["largest_block"] = ESP.getMaxAllocHeap();

    JsonObject wifi = doc.createNestedObject("wifi");
    bool connected = WiFi.status() == WL_CONNECTED;
    wifi["connected"] = connected;
    if (connected) {
        wifi["ssid"] = WiFi.SSID();
        wifi["rssi"] = WiFi.RSSI();
        wifi["channel"] = WiFi.channel();
        wifi["ip"] = WiFi.localIP().toString();
    }

    JsonObject reset = doc.createNestedObject("reset");
    reset["reason"] = diagResetReason;
    reset["boots"] = diagBootCount;
    reset["history"] = diagResetHistory;
    reset["cause"] = diagRestartCause;

    doc["clock_synced"] = getCurrentMasterTimeMs() > 0;

    JsonObject coin = doc.createNestedObject("coin_slot");
    coin["state"] = coinSlotStateName(getCoinSlotState());
    coin["session"] = getActiveCoinSessionId();
    coin["pending_payments"] = getPendingPaymentCount();
    coin["unpersisted_payments"] = hasUnpersistedPayments();

    JsonArray slots = doc.createNestedArray("slots");
    for (int i = 0; i < maxLicensedSlots; i++) {
        JsonObject s = slots.createNestedObject();
        s["slot"] = licenseSlots[i].slotNum;
        s["active"] = licenseSlots[i].active;
        s["paired"] = licenseSlots[i].deviceId.length() > 0;
        s["ip"] = licenseSlots[i].ip;
    }

    JsonObject counters = doc.createNestedObject("counters");
    counters["coin_events"] = diagCounters[(size_t)DiagCounter::CoinEvents];
    counters["coin_pulses"] = diagCounters[(size_t)DiagCounter::CoinPulses];
    counters["payments_queued"] = diagCounters[(size_t)DiagCounter::PaymentsQueued];
    counters["payments_acked"] = diagCounters[(size_t)DiagCounter::PaymentsAcked];
    counters["payments_evicted"] = diagCounters[(size_t)DiagCounter::PaymentsEvicted];
    counters["persist_failures"] = diagCounters[(size_t)DiagCounter::PersistFailures];
    counters["slot_reservations"] = diagCounters[(size_t)DiagCounter::SlotReservations];
    counters["ws_connects"] = diagCounters[(size_t)DiagCounter::WsConnects];
    counters["wifi_reconnects"] = diagCounters[(size_t)DiagCounter::WifiReconnects];
    counters["ota_attempts"] = diagCounters[(size_t)DiagCounter::OtaAttempts];

    JsonArray log = doc.createNestedArray("log");
    if (diagMutex != nullptr) xSemaphoreTake(diagMutex, portMAX_DELAY);
    for (size_t i = 0; i < diagRing.size(); i++) {
        log.add(String(diagRing.at(i))); // copies the text, so later writes cannot change it
    }
    doc["log_total"] = diagRing.total();
    if (diagMutex != nullptr) xSemaphoreGive(diagMutex);

    if (doc.overflowed()) doc["truncated"] = true;

    String out;
    serializeJson(doc, out);
    return out;
}

void handleApiDiagnostics() {
    if (!checkAdminAuth()) return;
    // The response is built in RAM; refuse instead of risking an out-of-memory restart.
    if (ESP.getMaxAllocHeap() < 24000) {
        webServer.send(503, "application/json", "{\"error\":\"LOW_MEMORY\"}");
        return;
    }
    webServer.sendHeader("Cache-Control", "no-store");
    webServer.send(200, "application/json", diagBuildJson());
}
