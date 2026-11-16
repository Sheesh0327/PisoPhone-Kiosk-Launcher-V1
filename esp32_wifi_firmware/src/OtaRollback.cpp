// Automatic rollback of a bad firmware update using OtaConfirm.h.

#include "OtaRollback.h"
#include "CoinSlotManager.h"
#include "Diagnostics.h"
#include "GatewaySessionStore.h"
#include "OtaConfirm.h"

#include <Preferences.h>
#include <WiFi.h>
#include <esp_ota_ops.h>

extern "C" bool verifyRollbackLater() {
    return true;
}

static const char* const OTA_NVS_NAMESPACE = "ota_boot";
static const char* const KEY_WIFI_AT_UPDATE = "wifi";

static bool imagePending = false;
static bool wifiRequired = false;
static bool rolledBack = false;
static const char* stateName = "n/a";

static const char* describeState(esp_ota_img_states_t s) {
    switch (s) {
    case ESP_OTA_IMG_NEW:
        return "new";
    case ESP_OTA_IMG_PENDING_VERIFY:
        return "pending_verify";
    case ESP_OTA_IMG_VALID:
        return "valid";
    case ESP_OTA_IMG_INVALID:
        return "invalid";
    case ESP_OTA_IMG_ABORTED:
        return "aborted";
    default:
        return "undefined";
    }
}

void otaNoteUploadStart(bool wifiConnected) {
    Preferences store;
    if (store.begin(OTA_NVS_NAMESPACE, false)) {
        store.putBool(KEY_WIFI_AT_UPDATE, wifiConnected);
        store.end();
    }
}

void otaRollbackBegin() {
    const esp_partition_t* running = esp_ota_get_running_partition();
    esp_ota_img_states_t state;
    if (running != nullptr && esp_ota_get_state_partition(running, &state) == ESP_OK) {
        stateName = describeState(state);
        imagePending = (state == ESP_OTA_IMG_PENDING_VERIFY);
    }

    const esp_partition_t* bad = esp_ota_get_last_invalid_partition();
    if (bad != nullptr && bad != running) {
        rolledBack = true;
        diagLog(
            "[OTA] The last update failed its start-up check on '%s' and was reverted; this is the previous firmware.\n",
            bad->label);
    }

    if (imagePending) {
        Preferences store;
        if (store.begin(OTA_NVS_NAMESPACE, true)) {
            wifiRequired = store.getBool(KEY_WIFI_AT_UPDATE, false);
            store.end();
        }
        diagLog("[OTA] New firmware is on trial: confirmed after %lu s if healthy (Wi-Fi %s), reverted after %lu s.\n",
                otaconfirm::MIN_STABLE_MS / 1000UL, wifiRequired ? "required" : "not required",
                otaconfirm::GIVE_UP_MS / 1000UL);
    }
}

void otaRollbackLoop() {
    if (!imagePending) return;
    static unsigned long lastCheckMs = 0;
    unsigned long now = millis();
    if (lastCheckMs != 0 && now - lastCheckMs < 1000UL) return;
    lastCheckMs = now ? now : 1;

    otaconfirm::Health health;
    health.storageReady = isPaymentStorageReady();
    health.wifiRequired = wifiRequired;
    health.wifiUp = WiFi.status() == WL_CONNECTED;

    switch (otaconfirm::decide(now, health)) {
    case otaconfirm::Action::Wait:
        return;
    case otaconfirm::Action::Confirm: {
        esp_err_t r = esp_ota_mark_app_valid_cancel_rollback();
        imagePending = false;
        stateName = "valid";
        diagLog("[OTA] New firmware confirmed after %lu s (%s).\n", now / 1000UL, esp_err_to_name(r));
        return;
    }
    case otaconfirm::Action::Rollback: {
        if (getCoinSlotState() != CoinSlotState::IDLE || hasUnpersistedPayments()) return;
        diagLog("[OTA] New firmware failed its check (%s) after %lu s: restoring the previous firmware.\n",
                otaconfirm::firstFailure(health), now / 1000UL);
        diagNoteRestartReason("ota-rollback");
        Serial.flush();
        esp_err_t r = esp_ota_mark_app_invalid_rollback_and_reboot();
        diagLog("[OTA] No earlier firmware to restore (%s): keeping the new one.\n", esp_err_to_name(r));
        esp_ota_mark_app_valid_cancel_rollback();
        imagePending = false;
        stateName = "valid";
        return;
    }
    }
}

bool otaImageOnTrial() {
    return imagePending;
}

const char* otaImageStateName() {
    return stateName;
}

bool otaLastUpdateWasRolledBack() {
    return rolledBack;
}
