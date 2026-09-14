#include "WebDashboard.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include <WiFi.h>

String renderDeviceOptions(String selectedIp) {
    String opts = "";
    int startIdx = 0, devNum = 1;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                String name = cfg.name.length() > 0 ? cfg.name : ("PisoPhone " + String(devNum));
                String sel = (cfg.ip == selectedIp) ? " selected" : "";
                int slotIdx = findSlotIndexForDevice(cfg.id, cfg.ip);
                bool isActive = isSlotActive(slotIdx);
                bool isInactive = !isActive;
                String expAttr = isInactive ? " data-inactive=\"true\"" : " data-inactive=\"false\"";
                String badge = isInactive ? " [🔴 INACTIVE]" : "";
                opts += "<option value=\"" + cfg.ip + "\"" + sel + expAttr + ">" + name + " (" + cfg.ip + ")" + badge + "</option>";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    return opts;
}

String renderDeviceIpInputs() {
    String html = "<div id=\"dev_ip_container\" style=\"background-color: var(--sub-bg); border: 1px solid var(--border); border-radius: 8px; padding: 12px; margin-top: 6px;\">";
    int startIdx = 0, devNum = 1;
    bool hasDevices = false;
    while (startIdx < androidIps.length()) {
        int comma = androidIps.indexOf(',', startIdx);
        if (comma == -1) comma = androidIps.length();
        String entry = androidIps.substring(startIdx, comma);
        entry.trim();
        if (entry.length() > 0) {
            DeviceConfig cfg;
            if (parseDeviceEntry(entry, cfg)) {
                hasDevices = true;
                String name = cfg.name.length() > 0 ? cfg.name : ("PisoPhone " + String(devNum));
                html += "<div class=\"dev-ip-row\" style=\"display: block; padding: 10px; border-bottom: 1px solid var(--border);\">";
                html += "<div style=\"display: flex; align-items: center; gap: 8px;\">";
                html += "<span class=\"dev-label\" data-default-name=\"" + name + "\" style=\"min-width: 95px; font-size: 13px; font-weight: 700;\">" + name + ":</span>";
                html += "<input type=\"hidden\" class=\"dev-id-field\" value=\"" + cfg.id + "\">";
                html += "<input type=\"text\" class=\"dev-ip-field\" value=\"" + cfg.ip + "\" placeholder=\"192.168.1.X\" style=\"flex: 1; min-width: 140px;\" readonly title=\"IP dynamically bound to MAC/Device ID\">";
                html += "<button type=\"button\" class=\"remove-btn\" onclick=\"this.closest('.dev-ip-row').remove(); updateDeviceLabels();\" title=\"Remove Device\">&times;</button>";
                html += "</div></div>";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    String noDevDisplay = hasDevices ? "none" : "block";
    html += "<div id=\"no_dev_msg\" style=\"display: " + noDevDisplay + "; color: var(--text-muted); font-size: 13px; text-align: center; padding: 14px 8px;\">No devices registered. Connected Android terminals will appear automatically, or you can add IP manually below.</div>";
    html += "</div>";
    html += "<div style=\"display: flex; gap: 8px; margin-top: 8px;\">";
    html += "<button type=\"button\" class=\"btn btn-outline\" style=\"font-size: 12px; padding: 6px 12px;\" onclick=\"addDeviceIpRow()\">+ Add IP Manually</button>";
    html += "<button type=\"button\" class=\"btn btn-outline\" style=\"font-size: 12px; padding: 6px 12px; color: var(--danger); border-color: var(--danger);\" onclick=\"clearAllDevices()\">🗑️ Clear All Devices</button>";
    html += "</div>";
    html += "<input type=\"hidden\" id=\"ips_hidden\" name=\"ips\" value=\"" + androidIps + "\">";
    html += "<script>";
    html += "window.updateDeviceLabels = function() {";
    html += "  const rows = document.querySelectorAll('.dev-ip-row');";
    html += "  rows.forEach((row, idx) => {";
    html += "    const label = row.querySelector('.dev-label'); if (label) {";
    html += "      const defName = label.getAttribute('data-default-name');";
    html += "      label.textContent = (defName && defName.trim() !== '') ? (defName + ':') : ('PisoPhone ' + (idx + 1) + ':');";
    html += "    }";
    html += "  });";
    html += "  const msg = document.getElementById('no_dev_msg');";
    html += "  if (msg) msg.style.display = (rows.length === 0) ? 'block' : 'none';";
    html += "};";
    html += "window.clearAllDevices = function() {";
    html += "  if (confirm('Remove all registered devices? Click Save after clearing.')) {";
    html += "    document.querySelectorAll('.dev-ip-row').forEach(r => r.remove());";
    html += "    updateDeviceLabels();";
    html += "  }";
    html += "};";
    html += "window.addDeviceIpRow = function() {";
    html += "  const container = document.getElementById('dev_ip_container');";
    html += "  const devNum = container.querySelectorAll('.dev-ip-row').length + 1;";
    html += "  const div = document.createElement('div');";
    html += "  div.className = 'dev-ip-row'; div.style.display = 'block'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';";
    html += "  div.innerHTML = '<div style=\"display: flex; align-items: center; gap: 8px;\"><span class=\"dev-label\" style=\"min-width: 95px; font-size: 13px; font-weight: 700;\">PisoPhone ' + devNum + ':</span>' +";
    html += "                  '<input type=\"hidden\" class=\"dev-id-field\" value=\"\">' +";
    html += "                  '<input type=\"text\" class=\"dev-ip-field\" value=\"\" placeholder=\"192.168.1.X\" style=\"flex: 1; min-width: 140px;\">' +";
    html += "                  '<button type=\"button\" class=\"remove-btn\" onclick=\"this.closest(\\\'.dev-ip-row\\\').remove(); updateDeviceLabels();\" title=\"Remove Device\">&times;</button></div>';";
    html += "  container.appendChild(div);";
    html += "  updateDeviceLabels();";
    html += "};";
    html += "</script>";
    return html;
}

String renderLicenseSlotsHtml() {
    String myIp = WiFi.localIP().toString();
    if (myIp == "0.0.0.0" || myIp.length() == 0) myIp = "kioskmanager.local";

    int installedCount = 0;
    for (int i = 0; i < maxLicensedSlots; i++) {
        if (licenseSlots[i].deviceId.length() > 0) {
            installedCount++;
        }
    }

    int unassignedCount = 0;
    unsigned long currentMillis = millis();
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId.length() == 0) continue;
        if (findSlotIndexForDevice(trackedDevices[i].deviceId, trackedDevices[i].lastKnownIp) >= 0) continue;
        if (currentMillis - trackedDevices[i].lastSeenMs < 300000) {
            unassignedCount++;
        }
    }

    String html = "<div class=\"master-vault-card\" style=\"background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 20px 24px; box-shadow: var(--card-shadow); margin-bottom: 16px;\">";
    
    // Top Row: Title, Subtitle & Action Buttons
    html += "<div style=\"display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 12px;\">";
    html += "<div style=\"background: rgba(16, 185, 129, 0.12); color: var(--primary); width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 20px;\">💳</div>";
    html += "<div>";
    html += "<div style=\"font-size: 15px; font-weight: 800; color: var(--text-main); letter-spacing: 0.3px; text-transform: uppercase;\">Hardware Slot License Manager</div>";
    html += "<div style=\"font-size: 11px; color: var(--text-muted); margin-top: 4px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;\">";
    html += "<span>Permanent Seat Activations • ₱500/Seat</span>";
    html += "</div>";
    html += "</div>";
    html += "</div>";

    html += "<div style=\"display: flex; align-items: center; gap: 10px; flex-wrap: wrap;\">";
    html += "<a href=\"https://pisophone.pages.dev/?ip=" + myIp + "&secret=" + sharedSecret + "\" target=\"_self\" style=\"font-size: 12px; font-weight: 700; padding: 8px 16px; background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%); color: #ffffff; border-radius: 10px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; box-shadow: 0 4px 12px rgba(59, 130, 246, 0.25); transition: transform 0.15s ease;\">";
    html += "<span style=\"font-size: 14px;\">📥</span> Install & Provision</a>";
    html += "<a href=\"https://pisophone.pages.dev/?ip=" + myIp + "&mode=deprovision\" target=\"_self\" style=\"font-size: 12px; font-weight: 700; padding: 8px 16px; background: linear-gradient(135deg, #e11d48 0%, #be123c 100%); color: #ffffff; border-radius: 10px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; box-shadow: 0 4px 12px rgba(225, 29, 72, 0.25); transition: transform 0.15s ease;\">";
    html += "<span style=\"font-size: 14px;\">🗑️</span> Deprovision</a>";
    html += "</div>";
    html += "</div>";

    // Middle Row: Capacity and Active Seats Summary
    html += "<div style=\"display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border);\">";
    
    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">🗄️ Total Capacity</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: var(--primary); margin-top: 2px;\">" + String(maxLicensedSlots) + " / " + String(MAX_SUPPORTED_SLOTS) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Seats</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">📱 Paired Terminals</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #3b82f6; margin-top: 2px;\">" + String(installedCount) + " / " + String(maxLicensedSlots) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Active</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">🛡️ License Status</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #10b981; margin-top: 2px;\">Permanent <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Lifetime</span></div>";
    html += "</div>";

    html += "</div>";

    String boxCode = getBoxMachineCode();
    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px; margin-top: 14px; display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px;\">";
    html += "<div>";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase;\">🔑 Box Request Code (Send to Vendor for Slot Upgrades)</div>";
    html += "<div style=\"font-family: monospace; font-size: 13px; font-weight: 800; color: #34d399; margin-top: 2px;\">" + boxCode + "</div>";
    html += "</div>";
    html += "<button type=\"button\" class=\"btn btn-outline btn-sm\" onclick=\"copyToClipboard('" + boxCode + "', this)\" style=\"padding: 4px 10px; font-size: 11px; font-weight: 700;\">📋 Copy Code</button>";
    html += "</div>";

    // Dropdown Trigger for Hardware Terminal Slots
    html += "<div style=\"margin-top: 16px;\">";
    html += "<button type=\"button\" onclick=\"toggleInstalledDevicesDropdown()\" style=\"width: 100%; background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; color: var(--text-main); font-family: inherit; font-size: 13px; font-weight: 700; transition: background 0.15s ease;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 8px;\">";
    html += "<span>🗄️ Hardware Slot Seats & Assigned Terminals</span>";
    html += "<span style=\"font-size: 11px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); padding: 2px 8px; border-radius: 12px;\">" + String(maxLicensedSlots) + " / " + String(MAX_SUPPORTED_SLOTS) + " Active Seats</span>";
    if (unassignedCount > 0) {
        html += "<span style=\"font-size: 11px; font-weight: 800; background: rgba(245, 158, 11, 0.2); color: #f59e0b; padding: 2px 8px; border-radius: 12px; border: 1px solid rgba(245, 158, 11, 0.4);\">🟡 " + String(unassignedCount) + " Connection Request(s)</span>";
    }
    html += "</div>";
    html += "<span id=\"vault-dropdown-arrow\" style=\"font-size: 12px; color: var(--text-muted); transition: transform 0.2s ease;\">▼</span>";
    html += "</button>";

    // Dropdown Content
    html += "<div id=\"installed-devices-dropdown-content\" style=\"display: none; margin-top: 14px; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 14px;\">";

    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        int sNum = i + 1;
        bool isUnlocked = (i < maxLicensedSlots);

        if (isUnlocked) {
            String devId = licenseSlots[i].deviceId;
            String ip = licenseSlots[i].ip;
            bool isBound = (devId.length() > 0);
            String fullSlotName = isBound ? (licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone Slot #" + String(sNum))) : ("Slot #" + String(sNum));
            String shortName = isBound ? (licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone #" + String(sNum))) : ("Empty #" + String(sNum));
            
            bool isActive = isSlotActive(i);

            String borderCol = "rgba(16, 185, 129, 0.35)";
            String dotCol = "#10b981";
            String expCol = "var(--primary)";
            String plusBg = "rgba(16, 185, 129, 0.12)";
            String plusCol = "var(--primary)";
            String plusShadow = "rgba(16, 185, 129, 0.15)";
            String statusText = "Active Seat";
            String expInfo = "Permanent";

            if (!isActive) {
                borderCol = "rgba(239, 68, 68, 0.4)";
                dotCol = "#ef4444";
                expCol = "var(--danger)";
                plusBg = "rgba(239, 68, 68, 0.12)";
                plusCol = "#ef4444";
                plusShadow = "rgba(239, 68, 68, 0.15)";
                statusText = "Inactive";
                expInfo = "No License";
            }

            String escapedName = fullSlotName;
            escapedName.replace("'", "\\'");
            escapedName.replace("\"", "&quot;");

            html += "<div class=\"slot-square-card\" onclick=\"openSlotActivationModal(" + String(sNum) + ", '" + escapedName + "', '" + ip + "', '" + devId + "', '" + expInfo + "', " + String(isActive ? 0 : 2) + ", " + (isBound ? "true" : "false") + ")\" style=\"background: var(--input-bg); border: 1px solid " + borderCol + "; border-radius: 14px; padding: 16px 12px; min-height: 140px; display: flex; flex-direction: column; align-items: center; justify-content: space-between; cursor: pointer; position: relative; transition: all 0.2s ease; box-shadow: 0 2px 8px rgba(0,0,0,0.04); text-align: center; user-select: none;\">";
            
            // Top Row: Slot # & Status Dot
            html += "<div style=\"display: flex; align-items: center; justify-content: space-between; width: 100%;\">";
            html += "<span style=\"font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.12); color: var(--text-main); padding: 2px 6px; border-radius: 6px; font-family: monospace;\">#" + String(sNum) + "</span>";
            html += "<span style=\"width: 8px; height: 8px; border-radius: 50%; background: " + dotCol + "; display: inline-block;\" title=\"" + statusText + "\"></span>";
            html += "</div>";

            // Center: Plus Symbol
            html += "<div style=\"width: 42px; height: 42px; border-radius: 50%; background: " + plusBg + "; color: " + plusCol + "; display: flex; align-items: center; justify-content: center; font-size: 24px; font-weight: 800; line-height: 1; transition: transform 0.15s ease; box-shadow: 0 4px 10px " + plusShadow + ";\">+</div>";

            // Bottom: Minimal Text
            html += "<div style=\"width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">";
            html += "<div style=\"font-size: 12px; font-weight: 700; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">" + shortName + "</div>";
            html += "<div style=\"font-size: 10px; font-weight: 700; color: " + expCol + "; margin-top: 2px;\">" + expInfo + "</div>";
            html += "</div>";

            html += "</div>";
        } else {
            // Locked Slot (Requires License Key to unlock)
            html += "<div class=\"slot-square-card locked-slot\" onclick=\"openTokenModal()\" style=\"background: rgba(0, 0, 0, 0.04); border: 1px dashed rgba(148, 163, 184, 0.4); border-radius: 14px; padding: 16px 12px; min-height: 140px; display: flex; flex-direction: column; align-items: center; justify-content: space-between; cursor: pointer; position: relative; transition: all 0.2s ease; text-align: center; user-select: none; opacity: 0.75;\">";
            
            // Top Row: Slot # & Locked Dot
            html += "<div style=\"display: flex; align-items: center; justify-content: space-between; width: 100%;\">";
            html += "<span style=\"font-size: 10px; font-weight: 800; background: rgba(148, 163, 184, 0.15); color: var(--text-muted); padding: 2px 6px; border-radius: 6px; font-family: monospace;\">#" + String(sNum) + "</span>";
            html += "<span style=\"width: 8px; height: 8px; border-radius: 50%; background: #64748b; display: inline-block;\" title=\"Locked Slot\"></span>";
            html += "</div>";

            // Center: Lock Icon
            html += "<div style=\"width: 42px; height: 42px; border-radius: 50%; background: rgba(148, 163, 184, 0.12); color: #64748b; display: flex; align-items: center; justify-content: center; font-size: 20px; font-weight: 800; line-height: 1;\">🔒</div>";

            // Bottom: Minimal Text
            html += "<div style=\"width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">";
            html += "<div style=\"font-size: 12px; font-weight: 700; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">Locked Seat</div>";
            html += "<div style=\"font-size: 10px; font-weight: 700; color: #f59e0b; margin-top: 2px;\">Tap to Upgrade</div>";
            html += "</div>";

            html += "</div>";
        }
    }

    html += "</div>";
    html += "</div>";
    html += "</div>";
    return html;
}

String renderSlotOptions() {
    String html = "";
    for (int i = 0; i < maxLicensedSlots; i++) {
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String name = licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("Slot #" + String(sNum));
        String status = (devId.length() > 0) ? " (Armed: " + devId + ")" : " (Available / Empty)";
        html += "<option value=\"" + String(sNum) + "\">Slot #" + String(sNum) + ": " + name + status + "</option>";
    }
    return html;
}
#include "WebDashboard.h"
#include "WebDashboardResources.h"



#include "SuperAdminManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include <WebServer.h>
#include <WiFi.h>

extern WebServer webServer;

static int getWifiQuality(int rssi) {
    if (rssi <= -100) return 0;
    if (rssi >= -50) return 100;
    return 2 * (rssi + 100);
}

static String getPlaceholderValue(const String& tag) {
    if (tag == "WIFI_SSID") return wifiSsid;
    if (tag == "WIFI_PASS") return wifiPass;
    if (tag == "PORT") return String(targetPort);
    if (tag == "U_COIN_PIN") return String(universalCoinPin);
    if (tag == "LED_PIN") return String(ledPin);
    if (tag == "RELAY_PIN") return String(relayPin);
    if (tag == "MINUTES_PER_COIN") return String(minutesPerCoin);
    if (tag == "ADMIN_PASSWORD") return webPassword;
    if (tag == "MATCH_MINUTES") return String(matchMinutes);
    if (tag == "TOTAL_COINS") return String(totalCoinsLifetime);
    if (tag == "SESSION_COINS") return String(totalCoinsSession);
    if (tag == "DEVICE_OPTIONS") return renderDeviceOptions("");
    if (tag == "DEVICE_IPS_CONTAINER") return renderDeviceIpInputs();
    if (tag == "LED_ACTIVE_LOW_SELECTED") return ledActiveLow ? "selected" : "";
    if (tag == "LED_ACTIVE_HIGH_SELECTED") return !ledActiveLow ? "selected" : "";
    if (tag == "RELAY_HIGH_SELECTED") return !relayActiveLow ? "selected" : "";
    if (tag == "RELAY_LOW_SELECTED") return relayActiveLow ? "selected" : "";
    if (tag == "P1_OPTIONS") return renderDeviceOptions(p1Ip);
    if (tag == "P2_OPTIONS") return renderDeviceOptions(p2Ip);
    
    if (tag == "IP_ADDRESS") {
        String ip = WiFi.localIP().toString();
        if (ip == "0.0.0.0" || ip.length() == 0) ip = "kioskmanager.local";
        return ip;
    }
    
    if (tag == "MAC_ADDRESS") return macAddressStr;
    if (tag == "BOX_CODE") return getBoxMachineCode();
    if (tag == "SHARED_SECRET") return sharedSecret;
    if (tag == "DEVICE_SLOTS_MANAGER") return renderLicenseSlotsHtml();
    if (tag == "MAX_SLOTS") return String(maxLicensedSlots);
    if (tag == "MAX_SUPPORTED_SLOTS") return String(MAX_SUPPORTED_SLOTS);
    if (tag == "SLOT_OPTIONS") return renderSlotOptions();
    if (tag == "SUPER_ADMIN_TAB") return renderSuperAdminTabHtml();
    if (tag == "SUPER_ADMIN_SCRIPTS") return renderSuperAdminScripts();
    
    if (tag == "WIFI_RSSI") {
        if (WiFi.status() == WL_CONNECTED) {
            return String(WiFi.RSSI());
        }
        return "-";
    }
    
    if (tag == "WIFI_QUALITY") {
        if (WiFi.status() == WL_CONNECTED) {
            return String(getWifiQuality(WiFi.RSSI()));
        }
        return "0";
    }

    if (tag == "QUICK_TIME_ALERT") {
        if (quickTimeStatusMsg.length() > 0) {
            String alert = quickTimeStatusMsg;
            quickTimeStatusMsg = "";
            return alert;
        }
        return "";
    }
    
    if (tag == "MATCH_ALERT") {
        if (matchStatusMsg.length() > 0) {
            String alert = matchStatusMsg;
            matchStatusMsg = "";
            return alert;
        }
        return "";
    }

    return "";
}

static bool isTagMatch(const char* ptr, const char* tag) {
    size_t len = strlen(tag);
    return (strncmp_P(ptr, tag, len) == 0 && pgm_read_byte(ptr + len) == '}');
}

static bool isValidTagFormat(const char* start, const char* end) {
    if (start >= end) return false;
    for (const char* p = start; p < end; p++) {
        char c = (char)pgm_read_byte(p);
        if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
            return false;
        }
    }
    return true;
}

static void streamProgmemContent(const char* p) {
    const char* chunkStart = p;
    size_t chunkLen = 0;

    while (true) {
        char c = pgm_read_byte(p);
        if (c == '\0') {
            if (chunkLen > 0) {
                webServer.sendContent_P(chunkStart, chunkLen);
            }
            break;
        }

        if (c == '{') {
            const char* tagStart = p + 1;
            const char* tagEnd = tagStart;
            while (pgm_read_byte(tagEnd) != '}' && pgm_read_byte(tagEnd) != '\0' && (tagEnd - tagStart) < 40) {
                tagEnd++;
            }

            if (pgm_read_byte(tagEnd) == '}' && isValidTagFormat(tagStart, tagEnd)) {
                if (chunkLen > 0) {
                    webServer.sendContent_P(chunkStart, chunkLen);
                    chunkLen = 0;
                }

                if (isTagMatch(tagStart, "PORTAL_STYLES")) {
                    webServer.sendContent_P(PORTAL_CSS);
                } else if (isTagMatch(tagStart, "PORTAL_SCRIPTS_CORE")) {
                    streamProgmemContent(PORTAL_JS_CORE);
                } else if (isTagMatch(tagStart, "PORTAL_SCRIPTS_MODALS")) {
                    streamProgmemContent(PORTAL_JS_MODALS);
                } else if (isTagMatch(tagStart, "PORTAL_MODALS")) {
                    streamProgmemContent(PORTAL_MODALS_HTML);
                } else {
                    String tag = "";
                    for (const char* t = tagStart; t < tagEnd; t++) {
                        tag += (char)pgm_read_byte(t);
                    }
                    String val = getPlaceholderValue(tag);
                    if (val.length() > 0) {
                        webServer.sendContent(val);
                    }
                }

                p = tagEnd + 1;
                chunkStart = p;
                continue;
            }
        }

        p++;
        chunkLen++;

        if (chunkLen >= 1024) {
            webServer.sendContent_P(chunkStart, chunkLen);
            chunkStart = p;
            chunkLen = 0;
        }
    }
}

void streamPortalHtml() {
    webServer.setContentLength(CONTENT_LENGTH_UNKNOWN);
    webServer.send(200, "text/html", "");
    streamProgmemContent(PORTAL_HTML_TEMPLATE);
    webServer.sendContent("");
}
