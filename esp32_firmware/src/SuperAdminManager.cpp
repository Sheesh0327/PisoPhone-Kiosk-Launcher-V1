// Super-admin features: vault unmask with a 5-minute auto-reset, revenue split and vault reset.
// The password itself is managed remotely (SuperAdminCreds.cpp); there is no local change option.

#include "SuperAdminManager.h"
#include "Config.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
#include "SuperAdminCreds.h"
#include "Money.h"
#include "PaymentQueueManager.h"
#include "Diagnostics.h"
#include <WebServer.h>
#include <Preferences.h>

// ============================================================================
// SUPER ADMIN STATE DEFINITIONS
// ============================================================================
String superAdminPassword = DEFAULT_SUPER_ADMIN_PW;
int vendorRevenueSplitPercent = DEFAULT_VENDOR_SPLIT_PERCENT;
bool isVaultUnmasked = false;
unsigned long unmaskExpiryTimestamp = 0;

void loadSuperAdminConfig() {
    prefs.begin(NVS_NAMESPACE, false);
    superAdminPassword = prefs.getString("super_admin_pw", DEFAULT_SUPER_ADMIN_PW);
    vendorRevenueSplitPercent = prefs.getInt("vendor_split", DEFAULT_VENDOR_SPLIT_PERCENT);
    if (vendorRevenueSplitPercent < 0 || vendorRevenueSplitPercent > 100) {
        vendorRevenueSplitPercent = DEFAULT_VENDOR_SPLIT_PERCENT;
    }
    prefs.end();
    superAdminCredsLoad();

    isVaultUnmasked = false;
    unmaskExpiryTimestamp = 0;

    Serial.printf("[👑 SUPER ADMIN] Loaded: Split=%d%% Vendor, Password=%s\n", vendorRevenueSplitPercent,
                  (superAdminPassword.length() > 0 ? "Set" : "Default"));
}

bool authenticateSuperAdmin() {
    if (webServer.hasArg("super_admin_pw")) {
        String entered = webServer.arg("super_admin_pw");
        if (superAdminPasswordOk(entered)) {
            return true;
        }
    }
    return superAdminBasicAuthOk();
}

void processSuperAdminLoop() {
    if (isVaultUnmasked) {
        if (millis() >= unmaskExpiryTimestamp) {
            Serial.println("\n=======================================================");
            Serial.println("[👑 SUPER ADMIN] 5-Minute Unmask Timeout Expired!");
            Serial.println("[💰 VAULT] Auto-resetting lifetime vault counters to 0.");
            Serial.println("=======================================================");

            totalCoinsLifetime = 0;
            totalCoinsSession = 0;
            totalCentavosLifetime = 0;
            totalCentavosSession = 0;
            lastSavedTotalCoins = 0;
            lastSavedTotalCentavos = 0;

            prefs.begin(NVS_NAMESPACE, false);
            prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
            prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, 0);
            prefs.end();

            isVaultUnmasked = false;
            unmaskExpiryTimestamp = 0;
        }
    }
}

void handleSuperAdminAuth() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json",
                       "{\"status\":\"error\",\"message\":\"Unauthorized: Invalid Super Admin password.\"}");
        return;
    }

    unsigned long remainingSec = 0;
    if (isVaultUnmasked && millis() < unmaskExpiryTimestamp) {
        remainingSec = (unmaskExpiryTimestamp - millis()) / 1000;
    } else {
        isVaultUnmasked = false;
    }

    String json = "{";
    json += "\"status\":\"ok\",";
    json += "\"is_super_admin\":true,";
    json += "\"session_timeout_seconds\":300,";
    json += "\"vendor_split\":" + String(vendorRevenueSplitPercent) + ",";
    json += "\"is_unmasked\":" + String(isVaultUnmasked ? "true" : "false") + ",";
    json += "\"remaining_seconds\":" + String(remainingSec) + ",";
    json += "\"total_coins\":" + String(totalCoinsLifetime) + ",";
    json += "\"session_coins\":" + String(totalCoinsSession);
    json += "}";

    webServer.send(200, "application/json", json);
}

void handleSuperAdminUnmask() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json",
                       "{\"status\":\"error\",\"message\":\"Unauthorized Super Admin request.\"}");
        return;
    }

    isVaultUnmasked = true;
    unmaskExpiryTimestamp = millis() + (VAULT_UNMASK_TIMEOUT_SECONDS * 1000UL);

    Serial.printf("[👑 SUPER ADMIN] Coin vault unmasked! 5-Minute auto-reset timer armed (Expires in %u s).\n",
                  VAULT_UNMASK_TIMEOUT_SECONDS);

    String json = "{";
    json += "\"status\":\"ok\",";
    json += "\"message\":\"Vault unmasked. Auto-reset timer initiated.\",";
    json += "\"timeout_seconds\":" + String(VAULT_UNMASK_TIMEOUT_SECONDS) + ",";
    json += "\"total_coins\":" + String(totalCoinsLifetime) + ",";
    json += "\"session_coins\":" + String(totalCoinsSession) + ",";
    char earnings[24];
    money::formatPesos(totalCentavosLifetime, earnings, sizeof(earnings));
    json += "\"total_earnings\":" + String(earnings) + ",";
    json += "\"vendor_split\":" + String(vendorRevenueSplitPercent);
    json += "}";

    webServer.send(200, "application/json", json);
}

void handleSuperAdminResetVault() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json",
                       "{\"status\":\"error\",\"message\":\"Unauthorized: Super Admin access required.\"}");
        return;
    }

    totalCoinsLifetime = 0;
    totalCoinsSession = 0;
    totalCentavosLifetime = 0;
    totalCentavosSession = 0;
    lastSavedTotalCoins = 0;
    lastSavedTotalCentavos = 0;

    prefs.begin(NVS_NAMESPACE, false);
    prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
    prefs.putULong(NVS_KEY_TOTAL_CENTAVOS, 0);
    prefs.end();

    isVaultUnmasked = false;
    unmaskExpiryTimestamp = 0;

    Serial.println("[👑 SUPER ADMIN] Manual vault reset completed by Vendor.");
    webServer.send(200, "application/json",
                   "{\"status\":\"ok\",\"message\":\"Vault counters successfully reset to 0.\"}");
}

void handleSuperAdminSaveSplit() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json", "{\"status\":\"error\",\"message\":\"Unauthorized.\"}");
        return;
    }

    if (webServer.hasArg("vendor_split")) {
        int split = webServer.arg("vendor_split").toInt();
        if (split >= 0 && split <= 100) {
            vendorRevenueSplitPercent = split;
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putInt("vendor_split", vendorRevenueSplitPercent);
            prefs.end();
            Serial.printf("[👑 SUPER ADMIN] Vendor revenue split updated to %d%%.\n", vendorRevenueSplitPercent);
            webServer.send(200, "application/json",
                           "{\"status\":\"ok\",\"vendor_split\":" + String(vendorRevenueSplitPercent) + "}");
            return;
        }
    }
    webServer.send(400, "application/json", "{\"status\":\"error\",\"message\":\"Invalid split percentage (0-100).\"}");
}

// Owner-only full wipe, including the license, lifetime revenue and vendor split. The dashboard's operator
// factory reset keeps those (OwnerData.h).
void handleSuperAdminFactoryReset() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json",
                       "{\"status\":\"error\",\"message\":\"Unauthorized: Super Admin access required.\"}");
        return;
    }
    if (!canPerformRebootOrOta()) {
        webServer.send(409, "application/json",
                       "{\"status\":\"error\",\"message\":\"BUSY: Unpersisted transactions in RAM\"}");
        return;
    }
    webServer.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Full wipe, rebooting\"}");
    delay(500);
    factoryResetDefaults(true);
    diagNoteRestartReason("superadmin-factory-reset");
    ESP.restart();
}
