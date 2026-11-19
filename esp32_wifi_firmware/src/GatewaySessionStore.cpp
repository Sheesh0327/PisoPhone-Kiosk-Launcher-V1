#include "GatewaySessionStore.h"
#include "CoinSlotManager.h"
#include "Config.h"
#include "Diagnostics.h"
#include "HardwareManager.h"
#include "RestartGuard.h"

static NvsPreferencesStore nvsPayStore;
static sessionstore::GatewaySessionStore<NvsPreferencesStore> storeInstance(nvsPayStore);

void initGatewaySessionStore() {
    if (storeInstance.init((uint32_t)millis())) {
        Serial.printf("[PAY QUEUE] Ready with %d pending session(s), life_pulses=%u.\n", storeInstance.activeCount(),
                      (unsigned)storeInstance.lifetimePulses());
    } else {
        Serial.println("[PAY QUEUE] Failed to open persistent payment storage.");
    }
}

sessionstore::GatewaySessionStore<NvsPreferencesStore>& gatewayStore() {
    return storeInstance;
}

bool isPaymentStorageReady() {
    return storeInstance.isStorageReady();
}

bool hasUnpersistedPayments() {
    return storeInstance.hasUnpersisted();
}

int getPendingPaymentCount() {
    return storeInstance.activeCount();
}

bool canPerformRebootOrOta() {
    if (!mayRestart(hasUnpersistedPayments(), getCoinSlotState() == CoinSlotState::IDLE, coinPulsesPeek())) {
        static unsigned long lastLogMs = 0;
        if (lastLogMs == 0 || millis() - lastLogMs > 2000) {
            lastLogMs = millis();
            Serial.println(
                "[PAY QUEUE] Reboot/OTA blocked: unpersisted transactions in RAM, or a coin may be in flight.");
        }
        return false;
    }
    return true;
}

void processGatewaySessionStore() {
    String active = getActiveCoinSessionId();
    storeInstance.tick((uint32_t)millis(), getCurrentMasterTimeMs(), std::string(active.c_str()));
}
