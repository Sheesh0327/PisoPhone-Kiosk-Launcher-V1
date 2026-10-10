// Automatic rollback of a bad firmware update.
//
// The bootloader of the boxes (Arduino-ESP32 2.0.x) is built with app rollback: after an OTA update it starts the new image as
// "pending verification" and goes back to the previous one if the box resets before the image is confirmed. The Arduino core,
// however, confirms the image by itself inside initArduino(), before setup() runs, unless the sketch's verifyRollbackLater()
// says otherwise. Left alone, every update was therefore blessed before any of its own code had run, so a crash, hang or
// power cut right after an update was never rolled back. This file turns that around: the update stays pending until the
// box has run for a minute and passed the checks of OtaConfirm.h; a box that does not pass them in 5 minutes restores the
// previous firmware.

#include "OtaRollback.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "OtaConfirm.h"
#include "PaymentQueueManager.h"

#include <Preferences.h>
#include <WiFi.h>
#include <esp_ota_ops.h>

// Overrides the Arduino core's weak default (esp32-hal-misc.c), which confirms the image at once. It has C linkage because
// the core's symbol does: a plain C++ definition would be a different symbol and be ignored. When the firmware is built
// without rollback support the core never calls it, so it is harmless there.
extern "C" bool verifyRollbackLater() {
    return true;
}

static const char* const OTA_NVS_NAMESPACE = "ota_boot";
static const char* const KEY_WIFI_AT_UPDATE = "wifi";

static bool imagePending = false;     // the running image is waiting for confirmation
static bool wifiRequired = false;     // the box was on Wi-Fi when this update arrived
static bool rolledBack = false;       // the update before this one was reverted
static const char* stateName = "n/a"; // the running image's state

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

    // The bootloader marks the image it gave up on; it stays recorded until the next update overwrites that slot.
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
        diagLog("[OTA] New firmware is on trial: it is confirmed after %lu s if the checks pass (Wi-Fi %s), otherwise "
                "reverted after %lu s. A restart before then also reverts it.\n",
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
#ifdef PISO_TEST_REFUSE_CONFIRM
    health.storageReady =
        false; // hardware test of the rollback (docs/REAL_WORLD_TESTING.md): this image can never pass
#endif

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
        // Restarting must not cut a coin session or lose a payment that exists only in RAM: wait for a quiet moment.
        if (getCoinSlotState() != CoinSlotState::IDLE || hasUnpersistedPayments()) return;
        diagLog("[OTA] New firmware failed its check (%s) after %lu s: restoring the previous firmware.\n",
                otaconfirm::firstFailure(health), now / 1000UL);
        diagNoteRestartReason("ota-rollback");
        flushRevenueNow();
        Serial.flush();
        // Does not return when it works. It fails when there is no earlier firmware to go back to: then the new one stays.
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
