#include "SuperAdminManager.h"
#include "SuperAdminTemplate.h"
#include "Config.h"
#include "WebServerModule.h"
#include "WebServerAuth.h"
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
    lockNvs();
    prefs.begin(NVS_NAMESPACE, false);
    superAdminPassword = prefs.getString("super_admin_pw", DEFAULT_SUPER_ADMIN_PW);
    vendorRevenueSplitPercent = prefs.getInt("vendor_split", DEFAULT_VENDOR_SPLIT_PERCENT);
    if (vendorRevenueSplitPercent < 0 || vendorRevenueSplitPercent > 100) {
        vendorRevenueSplitPercent = DEFAULT_VENDOR_SPLIT_PERCENT;
    }
    prefs.end();
    unlockNvs();
    
    isVaultUnmasked = false;
    unmaskExpiryTimestamp = 0;
    
    Serial.printf("[👑 SUPER ADMIN] Loaded: Split=%d%% Vendor, Password=%s\n", 
                  vendorRevenueSplitPercent, (superAdminPassword.length() > 0 ? "Set" : "Default"));
}

bool authenticateSuperAdmin() {
    if (webServer.hasArg("super_admin_pw")) {
        String entered = webServer.arg("super_admin_pw");
        if (entered == superAdminPassword) {
            return true;
        }
    }
    if (webServer.authenticate("superadmin", superAdminPassword.c_str())) {
        return true;
    }
    return false;
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
            totalEarningsLifetime = 0.0f;
            totalEarningsSession = 0.0f;
            lastSavedTotalCoins = 0;
            lastSavedTotalEarnings = 0.0f;
            
            lockNvs();
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
            prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, 0.0f);
            prefs.end();
            unlockNvs();
            
            isVaultUnmasked = false;
            unmaskExpiryTimestamp = 0;
        }
    }
}

void handleSuperAdminAuth() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json", "{\"status\":\"error\",\"message\":\"Unauthorized: Invalid Super Admin password.\"}");
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
        webServer.send(401, "application/json", "{\"status\":\"error\",\"message\":\"Unauthorized Super Admin request.\"}");
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
    json += "\"total_earnings\":" + String(totalEarningsLifetime, 2) + ",";
    json += "\"vendor_split\":" + String(vendorRevenueSplitPercent);
    json += "}";
    
    webServer.send(200, "application/json", json);
}

void handleSuperAdminResetVault() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json", "{\"status\":\"error\",\"message\":\"Unauthorized: Super Admin access required.\"}");
        return;
    }
    
    totalCoinsLifetime = 0;
    totalCoinsSession = 0;
    totalEarningsLifetime = 0.0f;
    totalEarningsSession = 0.0f;
    lastSavedTotalCoins = 0;
    lastSavedTotalEarnings = 0.0f;
    
    lockNvs();
    prefs.begin(NVS_NAMESPACE, false);
    prefs.putULong(NVS_KEY_TOTAL_COINS, 0);
    prefs.putFloat(NVS_KEY_TOTAL_EARNINGS, 0.0f);
    prefs.end();
    unlockNvs();
    
    isVaultUnmasked = false;
    unmaskExpiryTimestamp = 0;
    
    Serial.println("[👑 SUPER ADMIN] Manual vault reset completed by Vendor.");
    webServer.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Vault counters successfully reset to 0.\"}");
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
            lockNvs();
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putInt("vendor_split", vendorRevenueSplitPercent);
            prefs.end();
            unlockNvs();
            Serial.printf("[👑 SUPER ADMIN] Vendor revenue split updated to %d%%.\n", vendorRevenueSplitPercent);
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"vendor_split\":" + String(vendorRevenueSplitPercent) + "}");
            return;
        }
    }
    webServer.send(400, "application/json", "{\"status\":\"error\",\"message\":\"Invalid split percentage (0-100).\"}");
}

void handleSuperAdminChangePassword() {
    if (!authenticateSuperAdmin()) {
        webServer.send(401, "application/json", "{\"status\":\"error\",\"message\":\"Unauthorized: Current password invalid.\"}");
        return;
    }
    
    if (webServer.hasArg("new_pw")) {
        String newPw = webServer.arg("new_pw");
        newPw.trim();
        if (newPw.length() >= 4) {
            superAdminPassword = newPw;
            lockNvs();
            prefs.begin(NVS_NAMESPACE, false);
            prefs.putString("super_admin_pw", superAdminPassword);
            prefs.end();
            unlockNvs();
            Serial.println("[👑 SUPER ADMIN] Super Admin password successfully updated.");
            webServer.send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Super Admin password updated.\"}");
            return;
        } else {
            webServer.send(400, "application/json", "{\"status\":\"error\",\"message\":\"Password must be at least 4 characters.\"}");
            return;
        }
    }
    webServer.send(400, "application/json", "{\"status\":\"error\",\"message\":\"Missing new_pw argument.\"}");
}

String renderSuperAdminTabHtml() {
    return String(FPSTR(SUPER_ADMIN_HTML));
}

String renderSuperAdminScripts() {
    return String(FPSTR(SUPER_ADMIN_JS));
}
