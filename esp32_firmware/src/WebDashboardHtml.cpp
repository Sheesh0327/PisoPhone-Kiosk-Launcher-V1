#include "WebDashboardHtml.h"
#include "Config.h"
#include "DeviceManager.h"
#include <WebServer.h>
#include <WiFi.h>

extern WebServer webServer;

String renderDeviceOptions(String selectedIp) {
    String opts = "";
    int startIdx = 0, devNum = 1;
    uint64_t currentMs = getCurrentMasterTimeMs();
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
                int daysLeft = -1;
                int expStatus = getSlotExpirationStatus(slotIdx, currentMs, daysLeft);
                bool isExpired = (expStatus == 2);
                String expAttr = isExpired ? " data-expired=\"true\"" : " data-expired=\"false\"";
                String badge = isExpired ? " [🔴 EXPIRED]" : "";
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
    uint64_t currentMs = getCurrentMasterTimeMs();
    String myIp = WiFi.localIP().toString();
    if (myIp == "0.0.0.0" || myIp.length() == 0) myIp = "192.168.4.1";

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
    
    // Top Row: Title, Subtitle & Professional Buy Button
    html += "<div style=\"display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 16px;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 12px;\">";
    html += "<div style=\"background: rgba(16, 185, 129, 0.12); color: var(--primary); width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 20px;\">💳</div>";
    html += "<div>";
    html += "<div style=\"font-size: 15px; font-weight: 800; color: var(--text-main); letter-spacing: 0.3px; text-transform: uppercase;\">Master Credit Vault</div>";
    html += "<div style=\"font-size: 11px; color: var(--text-muted); margin-top: 2px;\">1 Credit = 1 Paired Terminal Seat • Manual Operator Allocation</div>";
    html += "</div>";
    html += "</div>";

    html += "<div style=\"display: flex; align-items: center; gap: 10px; flex-wrap: wrap;\">";
    html += "<a href=\"https://pisophone.pages.dev/installer/?mac=" + macAddressStr + "&ip=" + myIp + "&secret=" + sharedSecret + "\" target=\"_blank\" style=\"font-size: 12px; font-weight: 700; padding: 8px 16px; background: linear-gradient(135deg, #3b82f6 0%, #2563eb 100%); color: #ffffff; border-radius: 10px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; box-shadow: 0 4px 12px rgba(59, 130, 246, 0.25); transition: transform 0.15s ease;\">";
    html += "<span style=\"font-size: 14px;\">📥</span> Install</a>";
    html += "<a href=\"https://pisophone.pages.dev/purchase.html?esp32_ip=" + myIp + "\" target=\"_blank\" style=\"font-size: 12px; font-weight: 700; padding: 8px 16px; background: linear-gradient(135deg, #10b981 0%, #059669 100%); color: #ffffff; border-radius: 10px; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; box-shadow: 0 4px 12px rgba(16, 185, 129, 0.25); transition: transform 0.15s ease;\">";
    html += "<span style=\"font-size: 14px;\">➕</span> Buy Terminal Credits</a>";
    html += "</div>";
    html += "</div>";

    // Middle Row: Clearly Visible Displays of Total Credit Types
    html += "<div style=\"display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border);\">";
    
    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">📅 1-Month (₱50)</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: var(--primary); margin-top: 2px;\">" + String(monthlyCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">👑 1-Year (₱500)</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #3b82f6; margin-top: 2px;\">" + String(annualCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "<div style=\"background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 14px;\">";
    html += "<div style=\"font-size: 10px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;\">⚡ 2-Min Test</div>";
    html += "<div style=\"font-size: 18px; font-weight: 800; color: #f59e0b; margin-top: 2px;\">" + String(testCredits) + " <span style=\"font-size: 11px; font-weight: 600; color: var(--text-muted);\">Credits</span></div>";
    html += "</div>";

    html += "</div>";

    // Dropdown Trigger for Hardware Terminal Slots & Credit Allocation
    html += "<div style=\"margin-top: 16px;\">";
    html += "<button type=\"button\" onclick=\"toggleInstalledDevicesDropdown()\" style=\"width: 100%; background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; color: var(--text-main); font-family: inherit; font-size: 13px; font-weight: 700; transition: background 0.15s ease;\">";
    html += "<div style=\"display: flex; align-items: center; gap: 8px;\">";
    html += "<span>🗄️ Hardware Slot Seats & Credit Allocator</span>";
    html += "<span style=\"font-size: 11px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); padding: 2px 8px; border-radius: 12px;\">" + String(maxLicensedSlots) + " Slots</span>";
    if (unassignedCount > 0) {
        html += "<span style=\"font-size: 11px; font-weight: 800; background: rgba(245, 158, 11, 0.2); color: #f59e0b; padding: 2px 8px; border-radius: 12px; border: 1px solid rgba(245, 158, 11, 0.4);\">🟡 " + String(unassignedCount) + " Connection Request(s)</span>";
    }
    html += "</div>";
    html += "<span id=\"vault-dropdown-arrow\" style=\"font-size: 12px; color: var(--text-muted); transition: transform 0.2s ease;\">▼</span>";
    html += "</button>";

    // Dropdown Content (Contains ALL Hardware Slots in a Square Grid Layout)
    html += "<div id=\"installed-devices-dropdown-content\" style=\"display: none; margin-top: 14px; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 12px;\">";

    for (int i = 0; i < maxLicensedSlots; i++) {
        int sNum = licenseSlots[i].slotNum;
        String devId = licenseSlots[i].deviceId;
        String ip = licenseSlots[i].ip;
        bool isBound = (devId.length() > 0);
        String fullSlotName = isBound ? (licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone Slot #" + String(sNum))) : ("Slot #" + String(sNum));
        String shortName = isBound ? (licenseSlots[i].name.length() > 0 ? licenseSlots[i].name : ("PisoPhone #" + String(sNum))) : ("Empty #" + String(sNum));
        
        int daysLeft = -1;
        int expStatus = getSlotExpirationStatus(i, currentMs, daysLeft);

        String borderCol = "rgba(16, 185, 129, 0.35)";
        String dotCol = "#10b981";
        String expCol = "var(--primary)";
        String plusBg = "rgba(16, 185, 129, 0.12)";
        String plusCol = "var(--primary)";
        String plusShadow = "rgba(16, 185, 129, 0.15)";
        String statusText = "Active Seat";

        if (expStatus == 2) {
            borderCol = "rgba(239, 68, 68, 0.4)";
            dotCol = "#ef4444";
            expCol = "var(--danger)";
            plusBg = "rgba(239, 68, 68, 0.12)";
            plusCol = "#ef4444";
            plusShadow = "rgba(239, 68, 68, 0.15)";
            statusText = "Expired / Uncredited";
        } else if (expStatus == 1) {
            borderCol = "rgba(245, 158, 11, 0.4)";
            dotCol = "#f59e0b";
            expCol = "#f59e0b";
            plusBg = "rgba(245, 158, 11, 0.12)";
            plusCol = "#f59e0b";
            plusShadow = "rgba(245, 158, 11, 0.15)";
            statusText = "Expiring Soon";
        }

        String expInfo = "No Credit";
        if (licenseSlots[i].expiresAt > 0) {
            if (licenseSlots[i].expiresAt <= currentMs) {
                expInfo = "Expired";
            } else {
                uint64_t diffMs = licenseSlots[i].expiresAt - currentMs;
                if (diffMs > 86400000ULL) {
                    expInfo = String((int)(diffMs / 86400000ULL)) + "d left";
                } else {
                    expInfo = String((int)(diffMs / 60000ULL)) + "m left";
                }
            }
        }

        String escapedName = fullSlotName;
        escapedName.replace("'", "\\'");
        escapedName.replace("\"", "&quot;");

        html += "<div class=\"slot-square-card\" onclick=\"openSlotCreditModal(" + String(sNum) + ", '" + escapedName + "', '" + ip + "', '" + devId + "', '" + expInfo + "', " + String(expStatus) + ", " + (isBound ? "true" : "false") + ")\" style=\"aspect-ratio: 1 / 1; background: var(--input-bg); border: 1px solid " + borderCol + "; border-radius: 14px; padding: 12px; display: flex; flex-direction: column; align-items: center; justify-content: space-between; cursor: pointer; position: relative; transition: all 0.2s ease; box-shadow: 0 2px 8px rgba(0,0,0,0.04); text-align: center; user-select: none;\">";
        
        // Top Row: Slot # & Status Dot
        html += "<div style=\"display: flex; align-items: center; justify-content: space-between; width: 100%;\">";
        html += "<span style=\"font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.12); color: var(--text-main); padding: 2px 6px; border-radius: 6px; font-family: monospace;\">#" + String(sNum) + "</span>";
        html += "<span style=\"width: 8px; height: 8px; border-radius: 50%; background: " + dotCol + "; display: inline-block;\" title=\"" + statusText + "\"></span>";
        html += "</div>";

        // Center: Prominent Big Plus Symbol
        html += "<div style=\"width: 42px; height: 42px; border-radius: 50%; background: " + plusBg + "; color: " + plusCol + "; display: flex; align-items: center; justify-content: center; font-size: 24px; font-weight: 800; line-height: 1; transition: transform 0.15s ease; box-shadow: 0 4px 10px " + plusShadow + ";\">+</div>";

        // Bottom: Minimal Text (Name & Expiry)
        html += "<div style=\"width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">";
        html += "<div style=\"font-size: 12px; font-weight: 700; color: var(--text-main); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;\">" + shortName + "</div>";
        html += "<div style=\"font-size: 10px; font-weight: 700; color: " + expCol + "; margin-top: 2px;\">" + expInfo + "</div>";
        html += "</div>";

        html += "</div>";
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

const char PORTAL_HTML_TEMPLATE[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Admin Console</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrious/4.0.2/qrious.min.js"></script>
    <style>
        :root {
            --bg: #F1F5F9;
            --card-bg: #FFFFFF;
            --input-bg: #F8FAFC;
            --text-main: #0F172A;
            --text-muted: #64748B;
            --primary: #059669;
            --primary-hover: #047857;
            --primary-glow: rgba(5, 150, 105, 0.15);
            --border: #E2E8F0;
            --border-focus: #10B981;
            --danger: #EF4444;
            --danger-hover: #DC2626;
            --warning: #F59E0B;
            --warning-hover: #D97706;
            --success: #10B981;
            --card-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03);
            --radius-lg: 16px;
            --radius-md: 12px;
            --radius-sm: 8px;
            
            --status-good: #10B981;
            --status-good-bg: rgba(16, 185, 129, 0.1);
            --status-good-border: rgba(16, 185, 129, 0.2);
            --status-warning: #F59E0B;
            --status-warning-bg: rgba(245, 158, 11, 0.1);
            --status-warning-border: rgba(245, 158, 11, 0.2);
            --status-critical: #EF4444;
            --status-critical-bg: rgba(239, 68, 68, 0.1);
            --status-critical-border: rgba(239, 68, 68, 0.2);
        }
        [data-theme="dark"] {
            --bg: #060B14;
            --card-bg: #0F172A;
            --input-bg: #0B0F19;
            --text-main: #F8FAFC;
            --text-muted: #94A3B8;
            --primary: #10B981;
            --primary-hover: #34D399;
            --primary-glow: rgba(16, 185, 129, 0.2);
            --border: rgba(255, 255, 255, 0.08);
            --border-focus: #10B981;
            --danger: #EF4444;
            --danger-hover: #F87171;
            --warning: #F59E0B;
            --warning-hover: #FBBF24;
            --success: #10B981;
            --card-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.5);
            
            --status-good: #34D399;
            --status-good-bg: rgba(52, 211, 153, 0.1);
            --status-good-border: rgba(52, 211, 153, 0.2);
            --status-warning: #FBBF24;
            --status-warning-bg: rgba(251, 191, 36, 0.1);
            --status-warning-border: rgba(251, 191, 36, 0.2);
            --status-critical: #F87171;
            --status-critical-bg: rgba(248, 113, 113, 0.1);
            --status-critical-border: rgba(248, 113, 113, 0.2);
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
            background: var(--bg);
            color: var(--text-main);
            padding: 24px 16px;
            line-height: 1.6;
            transition: background-color 0.3s ease, color 0.3s ease;
            -webkit-font-smoothing: antialiased;
        }
        .app-container {
            max-width: 960px;
            margin: 0 auto;
            display: flex;
            flex-direction: column;
            gap: 24px;
        }
        .header-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            background: var(--card-bg);
            padding: 16px 24px;
            border-radius: var(--radius-lg);
            box-shadow: var(--card-shadow);
            border: 1px solid var(--border);
            flex-wrap: wrap;
            gap: 16px;
        }
        .logo-container {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .logo-icon {
            color: var(--primary);
            filter: drop-shadow(0 0 4px var(--primary-glow));
            animation: pulse-glow 2s infinite alternate;
        }
        @keyframes pulse-glow {
            0% { filter: drop-shadow(0 0 2px var(--primary-glow)); }
            100% { filter: drop-shadow(0 0 8px var(--primary)); }
        }
        .logo-text {
            font-size: 20px;
            font-weight: 800;
            letter-spacing: -0.5px;
            text-transform: uppercase;
            display: flex;
            align-items: center;
        }
        .logo-piso {
            color: var(--text-main);
        }
        .logo-phone {
            color: var(--primary);
        }
        .badge-pill {
            background: var(--primary-glow);
            color: var(--primary);
            border: 1px solid var(--border-focus);
            padding: 3px 10px;
            border-radius: 999px;
            font-size: 11px;
            font-weight: 700;
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }
        .btn {
            background: var(--primary);
            color: #ffffff;
            border: none;
            padding: 12px 20px;
            border-radius: var(--radius-md);
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s ease;
            font-size: 14px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            text-decoration: none;
            min-height: 48px;
            box-shadow: 0 4px 12px var(--primary-glow);
        }
        .btn:hover {
            background: var(--primary-hover);
            transform: translateY(-1px);
            box-shadow: 0 6px 16px var(--primary-glow);
        }
        .btn:active {
            transform: translateY(1px);
        }
        .btn-sm {
            padding: 8px 16px;
            min-height: 38px;
            font-size: 13px;
            border-radius: var(--radius-sm);
        }
        .btn-danger {
            background: var(--danger);
            box-shadow: 0 4px 12px rgba(239, 68, 68, 0.15);
        }
        .btn-danger:hover {
            background: var(--danger-hover);
            box-shadow: 0 6px 16px rgba(239, 68, 68, 0.25);
        }
        .btn-warning {
            background: var(--warning);
            color: #0F172A;
            box-shadow: 0 4px 12px rgba(245, 158, 11, 0.15);
        }
        .btn-warning:hover {
            background: var(--warning-hover);
            box-shadow: 0 6px 16px rgba(245, 158, 11, 0.25);
        }
        .btn-outline {
            background: transparent;
            border: 1.5px solid var(--border);
            color: var(--text-main);
            box-shadow: none;
        }
        .btn-outline:hover {
            background: var(--input-bg);
            border-color: var(--text-muted);
        }
        .tabs {
            display: flex;
            background: var(--card-bg);
            border: 1px solid var(--border);
            padding: 6px;
            border-radius: var(--radius-md);
            gap: 4px;
            overflow-x: auto;
            box-shadow: var(--card-shadow);
        }
        .tab {
            flex: 1;
            padding: 10px 16px;
            border-radius: var(--radius-sm);
            font-weight: 600;
            font-size: 14px;
            color: var(--text-muted);
            cursor: pointer;
            transition: all 0.2s ease;
            text-align: center;
            white-space: nowrap;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 6px;
        }
        .tab:hover:not(.active) {
            background: var(--input-bg);
            color: var(--text-main);
        }
        .tab.active {
            background: var(--primary);
            color: #ffffff;
            box-shadow: 0 4px 12px var(--primary-glow);
        }
        .tab-content {
            display: none;
            animation: scaleIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
        }
        .tab-content.active {
            display: block;
        }
        @keyframes scaleIn {
            from { opacity: 0; transform: scale(0.98) translateY(4px); }
            to { opacity: 1; transform: scale(1) translateY(0); }
        }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
            gap: 20px;
        }
        .grid-full {
            grid-column: 1 / -1;
        }
        .card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: var(--radius-lg);
            padding: 24px;
            box-shadow: var(--card-shadow);
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        .card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid var(--border);
            padding-bottom: 12px;
            margin-bottom: 4px;
        }
        .card-title {
            margin: 0;
            font-size: 16px;
            font-weight: 700;
            color: var(--text-main);
            display: flex;
            align-items: center;
            gap: 8px;
        }
        .form-group {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        label {
            font-weight: 700;
            font-size: 11px;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.75px;
        }
        input[type=text], input[type=password], input[type=number], select {
            width: 100%;
            height: 48px;
            padding: 0 16px;
            border: 1.5px solid var(--border);
            border-radius: var(--radius-md);
            font-size: 15px;
            background: var(--input-bg);
            transition: all 0.2s ease;
            color: var(--text-main);
            font-family: inherit;
        }
        input:focus, select:focus {
            outline: none;
            border-color: var(--border-focus);
            background: var(--card-bg);
            box-shadow: 0 0 0 3px var(--primary-glow);
        }
        .hint {
            font-size: 12px;
            color: var(--text-muted);
            line-height: 1.5;
        }
        .danger-hint {
            color: var(--danger);
            font-weight: 500;
        }
        .status-badge {
            background: var(--primary-glow);
            color: var(--primary);
            border: 1px solid var(--border-focus);
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 11px;
            font-weight: 700;
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }
        .status-badge.accent {
            background: rgba(126, 34, 206, 0.1);
            color: #A78BFA;
            border: 1px solid rgba(126, 34, 206, 0.3);
        }
        [data-theme="light"] .status-badge.accent {
            background: #F3E8FF;
            color: #7E22CE;
            border: 1px solid #E9D5FF;
        }
        .device-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
            gap: 16px;
        }
        .device-list {
            display: flex;
            flex-direction: column;
            gap: 12px;
        }
        .device-row {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: var(--radius-md);
            padding: 14px 18px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 16px;
            box-shadow: var(--card-shadow);
            transition: all 0.2s ease;
        }
        .device-row:hover {
            border-color: var(--primary);
            box-shadow: 0 4px 16px -2px rgba(16, 185, 129, 0.15);
        }
        .device-row.empty {
            border: 1.5px dashed var(--border);
            background: var(--input-bg);
        }
        .device-row.offline {
            opacity: 0.7;
        }
        .device-row-identity {
            display: flex;
            align-items: center;
            gap: 14px;
            min-width: 220px;
        }
        .device-slot-badge {
            background: rgba(16, 185, 129, 0.15);
            color: var(--primary);
            font-size: 11px;
            font-weight: 800;
            padding: 5px 10px;
            border-radius: 8px;
            border: 1px solid rgba(16, 185, 129, 0.25);
            white-space: nowrap;
        }
        .device-row-info {
            display: flex;
            flex-direction: column;
        }
        .device-row-name {
            font-size: 15px;
            font-weight: 700;
            color: var(--text-main);
        }
        .device-row-sub {
            font-size: 12px;
            font-family: monospace;
            color: var(--text-muted);
        }
        .device-row-metrics {
            display: flex;
            align-items: center;
            gap: 20px;
            flex-wrap: wrap;
        }
        .device-row-timer {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 15px;
            font-weight: 800;
            color: var(--text-main);
            font-variant-numeric: tabular-nums;
        }
        .device-row-battery {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 5px 10px;
            border-radius: 8px;
            background: var(--input-bg);
            border: 1px solid var(--border);
        }
        .device-row-actions {
            display: flex;
            align-items: center;
            gap: 8px;
            flex-shrink: 0;
        }
        .device-card {
            background: var(--card-bg);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 20px;
            display: flex;
            flex-direction: column;
            gap: 16px;
            box-shadow: var(--card-shadow);
            transition: all 0.3s ease;
            position: relative;
            overflow: hidden;
        }
        .device-card:hover {
            transform: translateY(-2px);
            border-color: var(--primary);
            box-shadow: 0 8px 24px -4px rgba(16, 185, 129, 0.12);
        }
        .device-card.offline {
            opacity: 0.65;
        }
        .device-card-header {
            display: flex;
            justify-content: space-between;
            align-items: flex-start;
        }
        .device-identity {
            display: flex;
            flex-direction: column;
        }
        .device-name {
            font-size: 16px;
            font-weight: 700;
            color: var(--text-main);
            margin-bottom: 2px;
        }
        .device-ip {
            font-size: 12px;
            font-family: monospace;
            color: var(--text-muted);
        }
        .device-stats {
            text-align: right;
        }
        .device-timer {
            font-size: 20px;
            font-weight: 800;
            color: var(--text-main);
            font-variant-numeric: tabular-nums;
            line-height: 1.2;
            margin-bottom: 4px;
        }
        .device-badge {
            display: inline-block;
            padding: 4px 8px;
            border-radius: 6px;
            font-size: 11px;
            font-weight: 700;
            text-transform: uppercase;
        }
        .device-badge.active {
            background: rgba(16, 185, 129, 0.15);
            color: #10B981;
            border: 1px solid rgba(16, 185, 129, 0.3);
        }
        .device-badge.standby {
            background: rgba(148, 163, 184, 0.15);
            color: var(--text-muted);
            border: 1px solid rgba(148, 163, 184, 0.3);
        }
        .device-badge.offline {
            background: rgba(239, 68, 68, 0.15);
            color: #EF4444;
            border: 1px solid rgba(239, 68, 68, 0.3);
        }
        .battery-section {
            padding: 12px;
            border-radius: 12px;
        }
        .battery-info {
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 12px;
            font-weight: 700;
            margin-bottom: 6px;
        }
        .battery-bar-bg {
            height: 8px;
            background: var(--border);
            border-radius: 999px;
            overflow: hidden;
        }
        .battery-bar-fill {
            height: 100%;
            border-radius: 999px;
            transition: width 0.4s ease-in-out, background-color 0.3s ease;
        }
        .battery-section.status-good {
            background: var(--status-good-bg);
            border: 1px solid var(--status-good-border);
        }
        .battery-section.status-good .battery-label,
        .battery-section.status-good .battery-status-tag {
            color: var(--status-good);
        }
        .battery-section.status-good .battery-bar-fill {
            background: var(--status-good);
        }
        .battery-section.status-warning {
            background: var(--status-warning-bg);
            border: 1px solid var(--status-warning-border);
        }
        .battery-section.status-warning .battery-label,
        .battery-section.status-warning .battery-status-tag {
            color: var(--status-warning);
        }
        .battery-section.status-warning .battery-bar-fill {
            background: var(--status-warning);
        }
        .battery-section.status-critical {
            background: var(--status-critical-bg);
            border: 1px solid var(--status-critical-border);
        }
        .battery-section.status-critical .battery-label,
        .battery-section.status-critical .battery-status-tag {
            color: var(--status-critical);
        }
        .battery-section.status-critical .battery-bar-fill {
            background: var(--status-critical);
        }
        .alert-box {
            padding: 16px;
            border-radius: var(--radius-md);
            font-size: 14px;
            line-height: 1.5;
            margin-top: 16px;
            display: none;
        }
        .alert-box.info {
            background: var(--input-bg);
            border: 1px solid var(--border);
            color: var(--text-main);
        }
        .alert-box.success {
            background: var(--status-good-bg);
            border: 1px solid var(--status-good-border);
            color: var(--status-good);
        }
        .alert-box.error {
            background: var(--status-critical-bg);
            border: 1px solid var(--status-critical-border);
            color: var(--status-critical);
        }
        .dev-ip-row {
            display: flex;
            align-items: center;
            gap: 12px;
            background: var(--input-bg);
            padding: 12px 16px;
            border: 1px solid var(--border);
            border-radius: var(--radius-md);
            margin-bottom: 12px;
            flex-wrap: wrap;
        }
        .dev-ip-row input {
            flex: 1;
            min-width: 140px;
        }
        .remove-btn {
            background: transparent;
            border: none;
            color: var(--danger);
            font-size: 22px;
            font-weight: bold;
            cursor: pointer;
            width: 44px;
            height: 44px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            border-radius: var(--radius-sm);
            transition: all 0.2s;
        }
        .remove-btn:hover {
            background: rgba(239, 68, 68, 0.1);
        }
        @media (max-width: 640px) {
            body {
                padding: 10px 8px;
            }
            .app-container {
                gap: 14px;
            }
            .header-bar {
                flex-direction: column;
                align-items: stretch;
                gap: 12px;
                padding: 14px 16px;
            }
            .header-right {
                justify-content: space-between;
                width: 100%;
            }
            .tabs {
                display: flex;
                overflow-x: auto;
                -webkit-overflow-scrolling: touch;
                scrollbar-width: none;
                gap: 4px;
                padding: 4px;
            }
            .tabs::-webkit-scrollbar {
                display: none;
            }
            .tab {
                flex: 0 0 auto;
                padding: 8px 14px;
                font-size: 13px;
                white-space: nowrap;
            }
            .grid {
                grid-template-columns: 1fr !important;
                gap: 14px;
            }
            .card {
                padding: 16px;
            }
            .device-row {
                flex-direction: column;
                align-items: stretch;
                gap: 12px;
                padding: 14px;
            }
            .device-row-identity {
                width: 100%;
                min-width: unset;
                justify-content: flex-start;
            }
            .device-row-metrics {
                width: 100%;
                justify-content: space-between;
                gap: 8px;
            }
            .device-row-actions {
                width: 100%;
                display: grid;
                grid-template-columns: 1fr 1fr;
                gap: 8px;
            }
            .device-row-actions .btn {
                width: 100%;
                text-align: center;
                justify-content: center;
            }
            input, select, textarea {
                font-size: 16px !important;
            }
        }
    </style>
    <script>
        // Apply saved theme immediately to prevent flashing
        (function() {
            const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
            document.documentElement.setAttribute('data-theme', savedTheme);
        })();

        window.toggleTheme = function() {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            const next = cur === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('kiosk_theme', next);
            updateThemeButtonText();
        };

        window.toggleInstalledDevicesDropdown = function() {
            const content = document.getElementById('installed-devices-dropdown-content');
            const arrow = document.getElementById('vault-dropdown-arrow');
            if (content) {
                if (content.style.display === 'none' || content.style.display === '') {
                    content.style.display = 'grid';
                    if (arrow) arrow.textContent = '▲';
                } else {
                    content.style.display = 'none';
                    if (arrow) arrow.textContent = '▼';
                }
            }
        };

        function updateThemeButtonText() {
            const btn = document.getElementById('theme_toggle_btn');
            if (btn) {
                const cur = document.documentElement.getAttribute('data-theme') || 'light';
                btn.innerHTML = cur === 'dark' ? '☀️ Light Mode' : '🌙 Dark Mode';
            }
        }

        window.switchTab = function(tabId) {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            const tabBtn = document.querySelector(`[onclick="switchTab('${tabId}')"]`);
            if (tabBtn) tabBtn.classList.add('active');
            const tabEl = document.getElementById(tabId);
            if (tabEl) tabEl.classList.add('active');
            localStorage.setItem('activeTab', tabId);
        }
        document.addEventListener('DOMContentLoaded', () => {
            let savedTab = localStorage.getItem('activeTab') || 'tab-dashboard';
            const path = window.location.pathname.toLowerCase();
            const search = window.location.search.toLowerCase();
            const hash = window.location.hash.toLowerCase();
            if (path.includes('install') || path.includes('provision') || search.includes('install') || search.includes('provision') || hash.includes('install') || hash.includes('provision')) {
                savedTab = 'tab-install';
            }
            if(document.getElementById(savedTab)) switchTab(savedTab);
            updateThemeButtonText();
        });
        window.triggerCoin = function() {
            fetch('/insert_coin', { method: 'POST', credentials: 'include' })
                .then(res => { if(res.ok) alert('✅ Simple beam coin drop (GPIO 4) simulated successfully!'); else alert('❌ Auth failed or error!'); })
                .catch(err => alert('Error: ' + err));
        }
        window.triggerUniversalCoin = function(pulses) {
            fetch('/insert_ucoin?pulses=' + pulses, { method: 'POST', credentials: 'include' })
                .then(res => { if(res.ok) alert('✅ Universal ' + pulses + ' PHP coin drop (' + pulses + ' pulses) simulated successfully!'); else alert('❌ Auth failed or error!'); })
                .catch(err => alert('Error: ' + err));
        }
    </script>
</head>
<body>
    <div class="app-container">
        <!-- Brand Header Bar -->
        <div class="header-bar">
            <div class="logo-container">
                <svg class="logo-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2.5" />
                    <path d="M13 7L8 13H12L11 17L16 11H12L13 7Z" fill="currentColor" />
                </svg>
                <h1 class="logo-text">
                    <span class="logo-piso">Piso</span><span class="logo-phone">Phone</span>
                </h1>
                <span class="badge-pill">Kiosk Admin</span>
                <div id="wifi_quality_pill" class="badge-pill" style="background: rgba(16, 185, 129, 0.15); color: #10B981; border: 1px solid rgba(16, 185, 129, 0.3); display: inline-flex; align-items: center; gap: 6px; font-weight: 700; padding: 4px 10px; border-radius: 20px;" title="ESP32 Real-Time Wi-Fi RSSI Signal Quality">
                    <span id="wifi_icon">📶</span>
                    <span id="wifi_signal_text">Wi-Fi: {WIFI_RSSI} dBm ({WIFI_QUALITY}%)</span>
                </div>
            </div>
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <button type="button" id="theme_toggle_btn" onclick="toggleTheme()" class="btn btn-outline btn-sm">🌙 Dark Mode</button>
                <span class="status-badge">🟢 ONLINE</span>
                <a href="/logout" onclick="return confirm('Log out?');" class="btn btn-outline btn-sm">🚪 Logout</a>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="tabs">
            <div class="tab active" onclick="switchTab('tab-dashboard')">📊 Dashboard</div>
            <div class="tab" onclick="switchTab('tab-settings')">🛠️ Settings</div>
            <div class="tab" onclick="switchTab('tab-tools')">⚡ Advanced Tools</div>
        </div>

        <!-- TAB 1: DASHBOARD -->
        <div id="tab-dashboard" class="tab-content active">
            <div class="grid">
                <!-- Master Credit Vault & Seat Slots Manager -->
                <div class="grid-full">
                    {DEVICE_SLOTS_MANAGER}
                </div>

                <!-- Live Devices List (Horizontal) -->
                <div class="card grid-full">
                    <div class="card-header">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <h3 class="card-title">📡 Live Device Status</h3>
                            <span id="available_slots_badge" class="badge" style="background: rgba(16, 185, 129, 0.15); color: var(--primary); font-weight: 700; padding: 3px 10px; border-radius: 12px; font-size: 11px;">
                                {MAX_SLOTS} Seats
                            </span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span class="status-badge" style="background: var(--status-good-bg); color: var(--status-good); border-color: var(--status-good-border);">LIVE SYNC</span>
                            <button type="button" class="btn btn-outline btn-sm" onclick="fetchDeviceStatus()">🔄 Refresh</button>
                        </div>
                    </div>
                    <div id="live_devices_container" class="device-list">
                        <div style="padding: 24px; text-align: center; color: var(--text-muted);">Loading devices...</div>
                    </div>
                </div>

                <!-- Vault Stats -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">💰 Revenue Vault</h3>
                    </div>
                    <div style="background: var(--input-bg); padding: 24px; border-radius: var(--radius-lg); text-align: center; border: 1px solid var(--border); margin-bottom: 4px;">
                        <div style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.75px; margin-bottom: 6px;">Total Coins (PHP)</div>
                        <div style="font-size: 36px; font-weight: 800; color: var(--primary);">₱{TOTAL_COINS}</div>
                    </div>
                    <div style="font-size: 13px; text-align: center; color: var(--text-muted); background: var(--bg); padding: 12px; border-radius: var(--radius-md); border: 1px solid var(--border); font-weight: 500;">
                        Session Coins: <b style="color: var(--text-main); font-weight: 700;">₱{SESSION_COINS}</b>
                    </div>
                    <div style="margin-top: 8px; display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" onclick="triggerCoin()">🪙 Simulate Simple Beam Coin (₱{PRICE})</button>
                        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(1)">₱1</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(5)">₱5</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(10)">₱10</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(20)">₱20</button>
                        </div>
                    </div>
                </div>

                <!-- Quick Add Time -->
                <form id="quick_adjust_form" action="/add_time" method="POST" class="card" onsubmit="return validateQuickAdjust(event)">
                    <div class="card-header">
                        <h3 class="card-title">⏱️ Quick Adjust Time</h3>
                    </div>
                    {QUICK_TIME_ALERT}
                    <div class="form-group">
                        <label>Target Device</label>
                        <select id="quick_adjust_target" name="target_ip" onchange="checkQuickAdjustExpired()">
                            <option value="ALL">All Devices (Broadcast)</option>
                            {DEVICE_OPTIONS}
                        </select>
                        <div id="quick_adjust_warn" style="display: none; margin-top: 6px; padding: 6px 10px; background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 6px; font-size: 11px; font-weight: 700;">
                            🚫 Selected device is EXPIRED. Manual time adjustment is blocked until credits are allocated.
                        </div>
                    </div>
                    <div class="form-group">
                        <label>Minutes</label>
                        <input type="number" name="add_minutes" value="60" min="1">
                    </div>
                    <div style="display: flex; gap: 12px; margin-top: 12px;">
                        <button type="submit" name="adjust_action" value="add" class="btn btn-warning" style="flex: 1;" onclick="return validateQuickAdjust(event, 'add')">+ Add</button>
                        <button type="submit" name="adjust_action" value="subtract" class="btn btn-danger" style="flex: 1;" onclick="return validateQuickAdjust(event, 'subtract')">- Subtract</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- TAB 2: SETTINGS -->
        <div id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="
                event.preventDefault();
                const formData = new FormData(this);
                fetch('/save', { method: 'POST', body: new URLSearchParams(formData) })
                    .then(res => { if (res.ok) alert('✅ Configuration saved & pushed live!'); else alert('❌ Failed to save configuration.'); })
                    .catch(err => alert('Error: ' + err));
            ">
                <div class="grid">
                    <!-- Network -->
                    <div class="card">
                        <h3 class="card-title">📡 Wi-Fi & Network</h3>
                        <div class="form-group" style="background: var(--input-bg); padding: 14px; border-radius: var(--radius-md); border: 1px solid var(--border); margin-bottom: 14px;">
                            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                <span style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;">Real-Time Wi-Fi Quality (RSSI)</span>
                                <span id="wifi_status_badge" class="badge" style="background: var(--status-good-bg); color: var(--status-good); font-weight: 700; padding: 2px 8px; border-radius: 10px; font-size: 11px;">Live</span>
                            </div>
                            <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px;">
                                <span id="wifi_rssi_display" style="font-size: 20px; font-weight: 800; color: var(--text-main); font-family: monospace;">{WIFI_RSSI} dBm</span>
                                <span id="wifi_quality_pct" style="font-size: 13px; font-weight: 700; color: var(--primary);">{WIFI_QUALITY}% Quality</span>
                            </div>
                            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: 10px; height: 8px; overflow: hidden; margin-bottom: 6px;">
                                <div id="wifi_meter_fill" style="background: var(--primary); height: 100%; width: {WIFI_QUALITY}%; transition: width 0.4s ease, background-color 0.4s ease;"></div>
                            </div>
                            <div style="display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 4px;">
                                <span>SSID: <b id="wifi_ssid_display" style="color: var(--text-main);">{WIFI_SSID}</b></span>
                                <span>IP: <b id="wifi_ip_display" style="color: var(--text-main);">{IP_ADDRESS}</b></span>
                            </div>
                        </div>
                        <div class="form-group">
                            <label>SSID</label>
                            <input type="text" name="wifi_ssid" value="{WIFI_SSID}">
                        </div>
                        <div class="form-group">
                            <label>Password</label>
                            <input type="password" name="wifi_pass" value="{WIFI_PASS}">
                        </div>
                        <div class="form-group">
                            <label>Android App Port</label>
                            <input type="number" name="port" value="{PORT}">
                            <div class="hint">Default is 8080.</div>
                        </div>
                        <!-- MAC card removed -->
                    </div>

                    <!-- Pricing & Rules -->
                    <div class="card">
                        <div class="card-header" style="margin-bottom: 0;">
                            <h3 class="card-title">🪙 Simple Beam Pricing</h3>
                            <span class="status-badge" style="background: var(--status-warning-bg); color: var(--status-warning); border-color: var(--status-warning-border);">GPIO 4</span>
                        </div>
                        <div class="hint" style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; font-size: 12px; line-height: 1.4;">
                            ⚠️ These pricing settings apply <b>exclusively to the Simple optical sensor</b> (GPIO 4). The multi-coin acceptor dynamically calculates rate from coin pulses.
                        </div>
                        <div class="form-group">
                            <label>Coin Price (PHP)</label>
                            <input type="number" step="0.01" name="price" value="{PRICE}">
                        </div>
                        <div class="form-group">
                            <label>Minutes granted per Coin Drop</label>
                            <input type="number" name="minutes" value="{MINUTES}">
                        </div>
                        <div class="form-group">
                            <label>Debounce lock (ms)</label>
                            <input type="number" name="debounce" value="{DEBOUNCE}">
                        </div>
                    </div>

                    <!-- Advanced Security & Pins -->
                    <div class="card">
                        <h3 class="card-title">🔒 Security Vault & Admin Console Password</h3>
                        <div class="form-group">
                            <label>Admin Web & Kiosk App Password</label>
                            <input type="password" name="admin_pw" value="{ADMIN_PASSWORD}">
                            <div class="hint" style="margin-top: 8px; color: var(--text-muted); line-height: 1.4;">
                                💡 Changing this password updates access to both this ESP32 Admin Web Portal and the 
                                <strong>Security Vault & Admin Console</strong> on all paired PisoPhone Android kiosk terminals. 
                                Changes are pushed and synchronized automatically to all active paired devices.
                            </div>
                        </div>
                    </div>

                    <div class="card">
                        <h3 class="card-title">🔌 Hardware GPIO Pins</h3>
                        <div class="form-group">
                            <label>Simple Beam Sensor GPIO</label>
                            <input type="number" name="coin_pin" value="{COIN_PIN}">
                            <div class="hint">GPIO 4 is typical.</div>
                        </div>
                        <div class="form-group">
                            <label>Multi-Coin Slot GPIO</label>
                            <input type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                            <div class="hint">Pulse slot on GPIO 3.</div>
                        </div>
                        <div class="form-group">
                            <label>Indicator LED GPIO</label>
                            <input type="number" name="led_pin" value="{LED_PIN}">
                        </div>
                        <div class="form-group">
                            <label>LED Polarity Logic</label>
                            <select name="led_active_low">
                                <option value="1" {LED_ACTIVE_LOW_SELECTED}>Active LOW (Onboard blue LED)</option>
                                <option value="0" {LED_ACTIVE_HIGH_SELECTED}>Active HIGH (Standard External LED)</option>
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Relay Power GPIO</label>
                            <input type="number" name="relay_pin" value="{RELAY_PIN}">
                            <div class="hint">Coin slot enable / power relay (GPIO 5).</div>
                        </div>
                        <div class="form-group">
                            <label>Relay Power Mode</label>
                            <select name="relay_mode">
                                <option value="0" {RELAY_MODE_ALWAYS_SELECTED}>Always Powered ON (Recommended)</option>
                                <option value="1" {RELAY_MODE_ARMED_SELECTED}>Armed-Only (Powered during Insert Coin)</option>
                            </select>
                            <div class="hint">Always Powered keeps coin acceptor energized 24/7.</div>
                        </div>
                        <div class="form-group">
                            <label>Relay Polarity</label>
                            <select name="relay_active_low">
                                <option value="0" {RELAY_HIGH_SELECTED}>Active HIGH (Direct 3.3V/5V drive)</option>
                                <option value="1" {RELAY_LOW_SELECTED}>Active LOW (Optocoupler relay boards)</option>
                            </select>
                            <div class="hint">Invert if relay is ON when it should be OFF.</div>
                        </div>
                    </div>

                    <div class="grid-full">
                        <button type="submit" class="btn" style="width: 100%; font-size: 16px;">💾 Save & Push Configuration Live</button>
                    </div>
                </div>
            </form>
        </div>

        <!-- TAB 3: TOOLS -->
        <div id="tab-tools" class="tab-content">
            <div class="grid">
                <!-- 1v1 Match -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">⚔️ 1v1 Match Mode</h3>
                        <span class="status-badge accent">ESPORTS</span>
                    </div>
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" style="display: flex; flex-direction: column; gap: 16px;">
                        <div class="form-group" style="max-width: 200px;">
                            <label>Stake Minutes</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px;">
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--primary);">🎮 Player 1</label>
                                <select id="p1_select" name="p1_ip" style="margin-bottom: 12px;">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P1</button>
                            </div>
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--danger);">🎮 Player 2</label>
                                <select id="p2_select" name="p2_ip" style="margin-bottom: 12px;">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P2</button>
                            </div>
                        </div>
                        
                        <button type="button" onclick="checkMatchQualification()" class="btn btn-outline" style="align-self: flex-start; border-color: var(--primary); color: var(--primary);">🔍 Verify Both Players' Balances</button>
                    </form>
                    <div id="match_qual_result" class="alert-box"></div>
                </div>

                <!-- OTA Update -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">🚀 Firmware Upgrade</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted);">Upload a compiled binary to upgrade your Kiosk controller wirelessly without USB cables.</p>
                    <a href="/update" class="btn btn-outline" style="width: 100%;">Upload Firmware (OTA) &rarr;</a>
                </div>

                <!-- System Recovery -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">⚠️ System Recovery</h3>
                    </div>
                    
                    <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset lifetime coin counts?');" style="margin-bottom: 12px;">
                        <label>Reset Vault Counters</label>
                        <div style="display: flex; gap: 8px; margin-top: 6px;">
                            <input type="password" name="reset_pw" placeholder="Admin password">
                            <button type="submit" class="btn btn-danger btn-sm" style="min-height:48px;">Reset</button>
                        </div>
                    </form>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <label>⚡ Relay Pin 5 Hardware Test</label>
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; margin-bottom: 12px;">
                        <button type="button" class="btn" style="background: #10b981;" onclick="fetch('/api/relay?state=1').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned ON! State: ' + d.state))">⚡ Turn Relay ON</button>
                        <button type="button" class="btn btn-outline" onclick="fetch('/api/relay?state=0').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned OFF! State: ' + d.state))">Turn Relay OFF</button>
                    </div>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <div style="display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" style="background: #0284c7; box-shadow: 0 4px 12px rgba(2, 132, 199, 0.2);" onclick="if(confirm('🔄 Reboot controller?')) { fetch('/reboot', {method: 'POST'}).then(() => { alert('Rebooting... returning in 5 seconds.'); setTimeout(() => window.location.reload(), 5000); }); }">
                            🔄 Reboot Controller
                        </button>
                        <button type="button" class="btn btn-danger" onclick="if(confirm('⚠️ Factory Reset? All settings will be wiped.')) { fetch('/factory_reset', {method: 'POST'}).then(() => { alert('Resetting...'); setTimeout(() => window.location.reload(), 6000); }); }">
                            Restore Factory Defaults
                        </button>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
    window.fetchDeviceStatus = function() {
        fetch('/api/status')
            .then(res => res.json())
            .then(data => {
                const devices = Array.isArray(data) ? data : (data.devices || []);
                const wifi = data.wifi || null;

                if (wifi) {
                    const rssi = typeof wifi.rssi === 'number' ? wifi.rssi : -100;
                    const quality = typeof wifi.quality === 'number' ? wifi.quality : 0;
                    const status = wifi.status || (quality >= 50 ? 'Good' : 'Weak');
                    const isConnected = !!wifi.connected;
                    
                    let wifiColor = 'var(--status-good)';
                    let wifiBg = 'var(--status-good-bg)';
                    let wifiBorder = 'var(--status-good-border)';
                    let icon = '📶';

                    if (!isConnected || quality <= 0) {
                        wifiColor = 'var(--status-critical)';
                        wifiBg = 'var(--status-critical-bg)';
                        wifiBorder = 'var(--status-critical-border)';
                        icon = '❌';
                    } else if (quality < 25) {
                        wifiColor = 'var(--status-critical)';
                        wifiBg = 'var(--status-critical-bg)';
                        wifiBorder = 'var(--status-critical-border)';
                        icon = '⚠️';
                    } else if (quality < 50) {
                        wifiColor = 'var(--status-warning)';
                        wifiBg = 'var(--status-warning-bg)';
                        wifiBorder = 'var(--status-warning-border)';
                        icon = '📶';
                    }

                    const pill = document.getElementById('wifi_quality_pill');
                    const sigText = document.getElementById('wifi_signal_text');
                    const iconEl = document.getElementById('wifi_icon');
                    if (pill && sigText) {
                        pill.style.background = wifiBg;
                        pill.style.color = wifiColor;
                        pill.style.borderColor = wifiBorder;
                        if (iconEl) iconEl.textContent = icon;
                        sigText.textContent = isConnected ? ('Wi-Fi: ' + rssi + ' dBm (' + quality + '%)') : 'Wi-Fi: Disconnected';
                    }

                    const rssiDisp = document.getElementById('wifi_rssi_display');
                    const qualPct = document.getElementById('wifi_quality_pct');
                    const fill = document.getElementById('wifi_meter_fill');
                    const badge = document.getElementById('wifi_status_badge');
                    const ssidDisp = document.getElementById('wifi_ssid_display');
                    const ipDisp = document.getElementById('wifi_ip_display');

                    if (rssiDisp) rssiDisp.textContent = isConnected ? (rssi + ' dBm') : 'Disconnected';
                    if (qualPct) {
                        qualPct.textContent = quality + '% ' + status;
                        qualPct.style.color = wifiColor;
                    }
                    if (fill) {
                        fill.style.width = quality + '%';
                        fill.style.backgroundColor = wifiColor;
                    }
                    if (badge) {
                        badge.textContent = status;
                        badge.style.background = wifiBg;
                        badge.style.color = wifiColor;
                    }
                    if (ssidDisp && wifi.ssid) ssidDisp.textContent = wifi.ssid;
                    if (ipDisp && wifi.ip) ipDisp.textContent = wifi.ip;
                }

                const container = document.getElementById('live_devices_container');
                if (!container) return;
                
                window.latestDevicesList = devices;
                const unassigned = data.unassigned_devices || [];
                window.unassignedDevices = unassigned;

                let availableSlotsCount = 0;
                let totalActiveSlotsCount = 0;

                devices.forEach(dev => {
                    const isExp = dev.isExpiredOrInactive || (dev.expStatus === 2) || (!dev.active) || (dev.expiresAt === "0" || dev.expiresAt === 0);
                    if (!isExp) {
                        totalActiveSlotsCount++;
                        if (!dev.isBound) {
                            availableSlotsCount++;
                        }
                    }
                });

                const availableBadge = document.getElementById('available_slots_badge');
                if (availableBadge) {
                    availableBadge.innerHTML = '<b>' + availableSlotsCount + '</b> Open / ' + totalActiveSlotsCount + ' Active Seats';
                    if (availableSlotsCount > 0) {
                        availableBadge.style.background = 'rgba(16, 185, 129, 0.15)';
                        availableBadge.style.color = 'var(--primary)';
                    } else {
                        availableBadge.style.background = 'rgba(239, 68, 68, 0.15)';
                        availableBadge.style.color = '#ef4444';
                    }
                }

                if (devices.length === 0 && unassigned.length === 0) {
                    container.innerHTML = '<div style="padding: 24px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">No PisoPhone devices registered.</div>';
                    return;
                }
                let html = '';
                devices.forEach((dev, idx) => {
                    const name = dev.name || ('PisoPhone ' + dev.slotNum);
                    const battery = (typeof dev.battery === 'number' && dev.battery >= 0) ? dev.battery : 100;
                    const isCharging = !!dev.charging;
                    const isExp = dev.isExpiredOrInactive || (dev.expStatus === 2) || (!dev.active) || (dev.expiresAt === "0" || dev.expiresAt === 0);
                    
                    let batteryStatusClass = 'status-good';
                    let statusLabel = 'GOOD CHARGE';
                    
                    if (battery <= 15) {
                        batteryStatusClass = 'status-critical';
                        statusLabel = 'CRITICAL LOW';
                    } else if (battery <= 30) {
                        batteryStatusClass = 'status-warning';
                        statusLabel = 'LOW BATTERY';
                    }

                    if (!dev.isBound) {
                        if (isExp) {
                            html += '<div class="device-row empty expired" style="opacity: 0.55; filter: grayscale(0.8); background: rgba(255, 255, 255, 0.02); border: 1px dashed rgba(239, 68, 68, 0.35); pointer-events: none;">' +
                                        '<div class="device-row-identity">' +
                                            '<span class="device-slot-badge" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3);">Slot #' + dev.slotNum + ' • EXPIRED</span>' +
                                            '<div class="device-row-info">' +
                                                '<span class="device-row-name" style="color: var(--text-muted);">Empty Slot #' + dev.slotNum + ' (Expired/Uncredited)</span>' +
                                                '<span class="device-row-sub" style="color: #ef4444; font-weight: 600;">Allocate credits in Vault to activate seat</span>' +
                                            '</div>' +
                                        '</div>' +
                                        '<div style="color: #ef4444; font-size: 12px; font-weight: 700;">' +
                                            '⛔ Slot Inactive' +
                                        '</div>' +
                                        '<div class="device-row-actions">' +
                                            '<button type="button" class="btn btn-secondary btn-sm" disabled style="padding: 8px 16px; font-weight: 700; opacity: 0.5; cursor: not-allowed; pointer-events: none; background: rgba(255, 255, 255, 0.05); color: var(--text-muted); border: 1px solid var(--border);">' +
                                                '⚡ Occupy slot (Disabled)' +
                                            '</button>' +
                                        '</div>' +
                                    '</div>';
                        } else {
                            html += '<div class="device-row empty">' +
                                        '<div class="device-row-identity">' +
                                            '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                            '<div class="device-row-info">' +
                                                '<span class="device-row-name">Empty Slot #' + dev.slotNum + '</span>' +
                                                '<span class="device-row-sub">Seat open & ready for setup</span>' +
                                            '</div>' +
                                        '</div>' +
                                        '<div style="color: var(--text-muted); font-size: 13px; font-style: italic;">' +
                                            'No terminal bound' +
                                        '</div>' +
                                        '<div class="device-row-actions">' +
                                            '<button type="button" class="btn btn-primary btn-sm" onclick="occupySlot(' + dev.slotNum + ')" style="padding: 8px 16px; font-weight: 700;">' +
                                                '⚡ Occupy slot' +
                                            '</button>' +
                                        '</div>' +
                                    '</div>';
                        }
                    } else if (dev.online) {
                        const mins = Math.floor(dev.time / 60);
                        const secs = dev.time % 60;
                        const timeStr = mins + 'm ' + secs + 's';
                        const active = dev.time > 0;
                        
                        let badgeHtml = active 
                            ? '<span class="device-badge active">ACTIVE</span>'
                            : '<span class="device-badge standby">STANDBY</span>';

                        if (isExp) {
                            badgeHtml += ' <span class="device-badge expired" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3);">EXPIRED SLOT</span>';
                        }
                            
                        const batteryIcon = isCharging ? '⚡' : '🔋';
                        const batteryText = (isCharging ? '⚡ ' : '') + battery + '%';

                        html += '<div class="device-row" ' + (isExp ? 'style="opacity: 0.8;"' : '') + '>' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">' + name + '</span>' +
                                            '<span class="device-row-sub">' + dev.ip + (dev.deviceId ? ' • ' + dev.deviceId : '') + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-metrics">' +
                                        '<div class="device-row-timer">' +
                                            '<span>⏱️ ' + timeStr + '</span>' +
                                            badgeHtml +
                                        '</div>' +
                                        '<div class="device-row-battery ' + batteryStatusClass + '">' +
                                            '<span style="font-size: 12px; font-weight: 700;">' + batteryIcon + ' ' + batteryText + '</span>' +
                                            '<div class="battery-bar-bg" style="width: 50px; height: 6px; display: inline-block; margin-left: 4px;">' +
                                                '<div class="battery-bar-fill" style="width: ' + battery + '%;"></div>' +
                                            '</div>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                            '🔓 Unpair' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    } else {
                        html += '<div class="device-row offline">' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">' + name + '</span>' +
                                            '<span class="device-row-sub">' + dev.ip + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-metrics">' +
                                        '<span class="device-badge offline">OFFLINE / DISCONNECTED</span>' +
                                        (isExp ? ' <span class="device-badge expired" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3);">EXPIRED</span>' : '') +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                            '🔓 Unpair' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    }
                });

                if (unassigned.length > 0) {
                    html += '<div style="grid-column: 1/-1; margin-top: 18px; margin-bottom: 6px;">' +
                            '<div style="font-size: 12px; font-weight: 800; color: #f59e0b; display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.3); border-radius: var(--radius-md);">' +
                                '<div style="display: flex; align-items: center; gap: 8px;">' +
                                    '<span>🟡 PENDING CONNECTION REQUESTS</span>' +
                                    '<span style="font-size: 10px; background: #f59e0b; color: #000000; font-weight: 800; padding: 2px 8px; border-radius: 10px;">' + unassigned.length + ' Terminal(s)</span>' +
                                '</div>' +
                                '<span style="font-size: 11px; color: var(--text-muted); font-weight: 600;">Unassigned devices requesting pairing</span>' +
                            '</div>' +
                            '</div>';

                    unassigned.forEach((uDev) => {
                        const uName = uDev.name || 'PisoPhone Terminal';
                        const uBat = (typeof uDev.battery === 'number' && uDev.battery >= 0) ? uDev.battery : 100;
                        const uChg = !!uDev.charging;
                        const uBatText = (uChg ? '⚡ ' : '🔋 ') + uBat + '%';

                        html += '<div class="device-row" style="border-left: 4px solid #f59e0b; background: rgba(245, 158, 11, 0.04);">' +
                                    '<div class="device-row-identity">' +
                                        '<span class="device-slot-badge" style="background: rgba(245, 158, 11, 0.2); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.4);">🟡 UNASSIGNED</span>' +
                                        '<div class="device-row-info">' +
                                            '<span class="device-row-name">' + uName + '</span>' +
                                            '<span class="device-row-sub">IP: <b>' + uDev.ip + '</b> • HW: <b>' + uDev.id + '</b></span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-metrics">' +
                                        '<div class="device-row-battery status-good">' +
                                            '<span style="font-size: 12px; font-weight: 700;">' + uBatText + '</span>' +
                                        '</div>' +
                                    '</div>' +
                                    '<div class="device-row-actions">' +
                                        '<button type="button" class="btn btn-primary btn-sm" onclick="showSelectSlotModalForDevice(\'' + uDev.id + '\', \'' + uDev.ip + '\', \'' + uName.replace(/'/g, "\\'") + '\')" style="padding: 8px 14px; font-weight: 700; background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); border: none;">' +
                                            '⚡ Confirm Connection' +
                                        '</button>' +
                                    '</div>' +
                                '</div>';
                    });
                }

                container.innerHTML = html;
            })
            .catch(err => console.log('Status polling error', err));
    }
    setInterval(fetchDeviceStatus, 3000);
    document.addEventListener("DOMContentLoaded", fetchDeviceStatus);

    window.checkMatchQualification = function() {
        const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
        const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
        const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
        const resDiv = document.getElementById('match_qual_result');
        if (!p1 || !p2) { alert('Select both players.'); return; }
        if (p1 === p2) { resDiv.style.display = 'block'; resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b'; resDiv.innerHTML = '❌ <b>Error:</b> Players cannot be the same device.'; return; }
        resDiv.style.display = 'block'; resDiv.style.background = '#f8fafc'; resDiv.style.border = '1px solid #e2e8f0'; resDiv.style.color = '#334155'; resDiv.innerHTML = '⏳ Verifying balances...';
        fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    if (data.qualified) {
                        resDiv.style.background = '#f0fdf4'; resDiv.style.border = '1px solid #bbf7d0'; resDiv.style.color = '#166534';
                        resDiv.innerHTML = '✅ <b>BOTH QUALIFIED FOR ' + data.stake_minutes + 'm MATCH!</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted;
                    } else {
                        resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b';
                        resDiv.innerHTML = '❌ <b>NOT QUALIFIED</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted + '<br><i>' + data.message + '</i>';
                    }
                } else { resDiv.innerHTML = '❌ Error checking qualification.'; }
            }).catch(err => resDiv.innerHTML = '❌ Network error.');
    }
    </script>

    <!-- Slot Credit Allocation Modal -->
    <div id="slot_credit_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); backdrop-filter: blur(4px); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: 20px; padding: 24px; max-width: 420px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.3);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 1px solid var(--border); padding-bottom: 14px;">
                <div style="display: flex; align-items: center; gap: 10px;">
                    <div style="width: 38px; height: 38px; border-radius: 10px; background: rgba(16, 185, 129, 0.15); color: var(--primary); display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 800;">💳</div>
                    <div>
                        <h3 id="modal_slot_title" style="margin: 0; font-size: 16px; font-weight: 800; color: var(--text-main);">Slot #1 Allocation</h3>
                        <div id="modal_slot_sub" style="font-size: 11px; color: var(--text-muted); margin-top: 2px;">Manage seat subscription & credit</div>
                    </div>
                </div>
                <button type="button" onclick="closeSlotCreditModal()" style="background: none; border: none; font-size: 22px; cursor: pointer; color: var(--text-muted); padding: 4px; line-height: 1;">&times;</button>
            </div>

            <!-- Current Expiry & Binding Info Box -->
            <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px 16px; margin-bottom: 18px; display: flex; justify-content: space-between; align-items: center;">
                <div>
                    <div style="font-size: 11px; color: var(--text-muted); font-weight: 600;">Current Expiry</div>
                    <div id="modal_slot_expiry_text" style="font-size: 14px; font-weight: 800; color: var(--text-main); margin-top: 2px;">30 day(s) left</div>
                </div>
                <div id="modal_slot_status_badge">
                    <span style="font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); padding: 3px 8px; border-radius: 6px;">🟢 ACTIVE</span>
                </div>
            </div>

            <!-- Choose Credit Option Header -->
            <div style="font-size: 11px; font-weight: 800; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 10px;">
                Select Credit Type To Apply
            </div>

            <!-- Credit Options Buttons -->
            <div style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px;">
                <!-- Monthly Credit Option -->
                <button type="button" onclick="submitModalCredit('month')" style="background: var(--input-bg); border: 1px solid rgba(16, 185, 129, 0.4); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: all 0.15s ease; text-align: left;" onmouseover="this.style.background='rgba(16, 185, 129, 0.08)';" onmouseout="this.style.background='var(--input-bg)';">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <span style="font-size: 22px;">📅</span>
                        <div>
                            <div style="font-size: 14px; font-weight: 800; color: var(--primary);">+30 Days (1 Monthly Credit)</div>
                            <div style="font-size: 11px; color: var(--text-muted); margin-top: 1px;">Standard 30-day seat extension</div>
                        </div>
                    </div>
                    <span style="font-size: 12px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); padding: 4px 10px; border-radius: 8px;">Apply</span>
                </button>

                <!-- Annual Credit Option -->
                <button type="button" onclick="submitModalCredit('year')" style="background: var(--input-bg); border: 1px solid rgba(59, 130, 246, 0.4); border-radius: 12px; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: all 0.15s ease; text-align: left;" onmouseover="this.style.background='rgba(59, 130, 246, 0.08)';" onmouseout="this.style.background='var(--input-bg)';">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <span style="font-size: 22px;">👑</span>
                        <div>
                            <div style="font-size: 14px; font-weight: 800; color: #3b82f6;">+1 Year (1 Annual Credit)</div>
                            <div style="font-size: 11px; color: var(--text-muted); margin-top: 1px;">Full 365-day seat license</div>
                        </div>
                    </div>
                    <span style="font-size: 12px; font-weight: 800; background: rgba(59, 130, 246, 0.15); color: #3b82f6; padding: 4px 10px; border-radius: 8px;">Apply</span>
                </button>

                <!-- Test Credit Option -->
                <button type="button" onclick="submitModalCredit('test')" style="background: var(--input-bg); border: 1px solid rgba(245, 158, 11, 0.4); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; transition: all 0.15s ease; text-align: left;" onmouseover="this.style.background='rgba(245, 158, 11, 0.08)';" onmouseout="this.style.background='var(--input-bg)';">
                    <div style="display: flex; align-items: center; gap: 12px;">
                        <span style="font-size: 20px;">⚡</span>
                        <div>
                            <div style="font-size: 13px; font-weight: 700; color: #f59e0b;">+2 Minutes (Test Credit)</div>
                            <div style="font-size: 10px; color: var(--text-muted);">Short diagnostic run</div>
                        </div>
                    </div>
                    <span style="font-size: 11px; font-weight: 700; background: rgba(245, 158, 11, 0.15); color: #f59e0b; padding: 3px 8px; border-radius: 6px;">Test</span>
                </button>
            </div>

            <div id="modal_unpair_container" style="display: none; border-top: 1px solid var(--border); pt-3; margin-top: 12px; padding-top: 12px;">
                <button type="button" onclick="submitModalUnpair()" class="btn btn-outline" style="width: 100%; border-color: rgba(239, 68, 68, 0.4); color: var(--danger); font-size: 12px; font-weight: 700; padding: 8px 12px;">
                    🔓 Unpair Terminal Seat
                </button>
            </div>
        </div>
    </div>

    <!-- Token Upgrade Modal -->
    <div id="token_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 500px; width: 100%; box-shadow: var(--shadow-lg);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">🔑 Upgrade Hardware Capacity</h3>
                <button type="button" onclick="closeTokenModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">Paste the signed Slot Token issued from your Pisophone Cloud Dashboard to expand terminal capacity on this box.</p>
            <div class="form-group">
                <label>PISOSLOT Token</label>
                <textarea id="token_input" rows="3" placeholder="PISOSLOT.AA:BB:CC:DD:EE:FF.5.1798761600000.abcd..." style="width: 100%; font-family: monospace; font-size: 12px; padding: 10px; border-radius: var(--radius-sm); border: 1px solid var(--border); background: var(--bg);"></textarea>
            </div>
            <div id="token_error" style="display: none; color: var(--danger); font-size: 12px; margin-bottom: 12px; font-weight: 600;"></div>
            <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px;">
                <button type="button" class="btn btn-outline" onclick="closeTokenModal()">Cancel</button>
                <button type="button" class="btn btn-primary" onclick="submitSlotToken()">Verify & Upgrade</button>
            </div>
        </div>
    </div>

    <!-- QR Code Handshake Pairing Modal -->
    <div id="qr_pair_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.7); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 440px; width: 100%; box-shadow: var(--shadow-lg); text-align: center;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Pair Phone to Slot #<span id="qr_slot_title">1</span></h3>
                <button type="button" onclick="closePairingQrModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">
                Open the PisoPhone Kiosk App on your phone and scan this QR code to initialize pairing and exchange shared cryptographic keys.
            </p>
            
            <div style="background: white; padding: 16px; border-radius: 12px; display: inline-block; box-shadow: 0 4px 12px rgba(0,0,0,0.1); margin-bottom: 14px;">
                <canvas id="qr_canvas" width="260" height="260" style="display: block; margin: 0 auto;"></canvas>
            </div>
            
            <div id="qr_fallback_text" style="display: none; font-family: monospace; font-size: 11px; word-break: break-all; color: var(--primary); margin-bottom: 12px;"></div>

            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 10px; font-size: 12px; text-align: left; margin-bottom: 16px;">
                <div style="display: flex; justify-content: space-between;">
                    <span style="color: var(--text-muted);">Cabinet IP:</span>
                    <span id="qr_modal_ip" style="font-family: monospace; font-weight: 600;">{IP_ADDRESS}</span>
                </div>
            </div>

            <button type="button" class="btn btn-outline" style="width: 100%;" onclick="closePairingQrModal()">Done</button>
        </div>
    </div>

    <!-- WebUSB 1-Click Provisioning Modal -->
    <div id="provision_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 480px; width: 100%; box-shadow: var(--shadow-lg);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;">📱 Start Sideload & Pair (Slot #<span id="prov_slot_num">1</span>)</h3>
                <button type="button" onclick="closeProvisionModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
                To bypass browser USB security blocks and enjoy zero-flag setup, PisoPhone uses an HTTPS-secured cloud flasher to flash, authorize, and link your terminal.
            </p>
            
            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; margin-bottom: 18px; font-size: 12px; display: flex; flex-direction: column; gap: 8px;">
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> Secure HTTPS WebUSB Tunnel
                </div>
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> Over-The-Air APK Cache Delivery
                </div>
                <div style="display: flex; align-items: center; gap: 8px; color: var(--success); font-weight: 600;">
                    <span>✔</span> 1-Click Redirect Slot Association
                </div>
            </div>

            <div style="display: flex; justify-content: flex-end; gap: 8px;">
                <button type="button" class="btn btn-outline" onclick="closeProvisionModal()">Close</button>
                <button type="button" class="btn btn-primary" onclick="launchHttpsFlasher()">⚡ Proceed to HTTPS Flasher</button>
            </div>
        </div>
    </div>

    <!-- Unassigned Device Connection Request Pairing Modal -->
    <div id="unassigned_pair_modal" class="modal-overlay" style="display: none; position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 10000; align-items: center; justify-content: center; padding: 16px;">
        <div style="background: var(--card-bg); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: 24px; max-width: 500px; width: 100%; box-shadow: var(--shadow-lg);">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                <h3 style="margin: 0; font-size: 18px; font-weight: 700;" id="unassigned_modal_title">⚡ Confirm Connection</h3>
                <button type="button" onclick="closeUnassignedPairModal()" style="background: none; border: none; font-size: 20px; cursor: pointer; color: var(--text-muted);">&times;</button>
            </div>
            
            <div id="unassigned_modal_body" style="display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px;">
                <!-- Populated dynamically -->
            </div>

            <div style="display: flex; justify-content: space-between; align-items: center; pt-3; border-top: 1px solid var(--border); margin-top: 16px;">
                <button type="button" class="btn btn-outline" onclick="openInstallerForActiveSlot()" style="font-size: 12px; font-weight: 600;">📥 Open Web Installer</button>
                <button type="button" class="btn btn-outline" onclick="closeUnassignedPairModal()">Close</button>
            </div>
        </div>
    </div>

    <script>
    const ESP32_MAC = "{MAC_ADDRESS}";
    const ESP32_SECRET = "{SHARED_SECRET}";
    const ESP32_HOST = window.location.hostname;
    let activeSlotNum = 1;
    let localApkBytes = null;
    window.unassignedDevices = [];

    window.closeUnassignedPairModal = function() {
        const modal = document.getElementById('unassigned_pair_modal');
        if (modal) modal.style.display = 'none';
    };

    window.openInstallerForActiveSlot = function() {
        const s = activeSlotNum || 1;
        const targetUrl = 'https://pisophone.pages.dev/installer/?mac=' + encodeURIComponent(ESP32_MAC) + 
                          '&ip=' + encodeURIComponent(ESP32_HOST) + 
                          '&slot=' + encodeURIComponent(s) + 
                          '&secret=' + encodeURIComponent(ESP32_SECRET) +
                          '&name=' + encodeURIComponent('PisoPhone ' + s);
        window.open(targetUrl, '_blank');
        closeUnassignedPairModal();
    };

    window.confirmConnection = function(devId, devIp, slotNum, devName) {
        const slot = slotNum || activeSlotNum || 1;
        const name = devName || ('PisoPhone Slot #' + slot);
        fetch('/api/slots/pair?slot=' + slot + '&id=' + encodeURIComponent(devId) + '&ip=' + encodeURIComponent(devIp) + '&name=' + encodeURIComponent(name), { method: 'POST' })
            .then(res => {
                if (!res.ok) {
                    return res.text().then(text => { throw new Error('HTTP ' + res.status + ': ' + text); });
                }
                return res.json();
            })
            .then(data => {
                if (data.success) {
                    alert('✅ Successfully paired terminal (' + devIp + ') to Slot #' + slot + '!');
                    closeUnassignedPairModal();
                    if (typeof fetchDeviceStatus === 'function') fetchDeviceStatus();
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    alert('❌ Failed to pair: ' + (data.error || 'Unknown error'));
                }
            })
            .catch(err => alert('❌ Error pairing device: ' + (err.message || err)));
    };

    window.showSelectSlotModalForDevice = function(devId, devIp, devName) {
        const modal = document.getElementById('unassigned_pair_modal');
        const title = document.getElementById('unassigned_modal_title');
        const body = document.getElementById('unassigned_modal_body');
        if (!modal || !body) return;

        if (title) title.innerHTML = '⚡ Assign Device to Slot Seat';

        let html = '<div style="font-size: 13px; color: var(--text-muted); margin-bottom: 8px;">' +
                   'Select an available slot seat to pair terminal <b>' + (devName || devId) + '</b> (' + devIp + '):' +
                   '</div>' +
                   '<div style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px;">';

        const devicesList = window.latestDevicesList || [];
        const maxS = devicesList.length > 0 ? devicesList.length : 10;

        for (let s = 1; s <= maxS; s++) {
            const slotInfo = devicesList.find(d => d.slotNum === s);
            const isBound = slotInfo ? slotInfo.isBound : false;
            const isExp = slotInfo ? (slotInfo.isExpiredOrInactive || slotInfo.expStatus === 2 || !slotInfo.active || slotInfo.expiresAt === "0" || slotInfo.expiresAt === 0) : false;

            if (isBound) {
                html += '<button disabled class="btn btn-outline" style="padding: 10px; font-size: 13px; font-weight: 700; opacity: 0.4; cursor: not-allowed; background: rgba(255,255,255,0.03); border-color: var(--border); color: var(--text-muted); pointer-events: none;">' +
                        'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: var(--text-muted);">(Occupied)</span>' +
                        '</button>';
            } else if (isExp) {
                html += '<button disabled class="btn btn-outline" style="padding: 10px; font-size: 13px; font-weight: 700; opacity: 0.45; cursor: not-allowed; background: rgba(239, 68, 68, 0.05); border-color: rgba(239, 68, 68, 0.2); color: #ef4444; pointer-events: none;">' +
                        'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: #ef4444;">(Expired)</span>' +
                        '</button>';
            } else {
                html += '<button type="button" class="btn btn-primary" style="padding: 10px; font-size: 13px; font-weight: 700; background: linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%); border: none; color: #ffffff;" onclick="confirmConnection(\'' + devId + '\', \'' + devIp + '\', ' + s + ', \'' + (devName || '').replace(/'/g, "\\'") + '\')">' +
                        'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: rgba(255,255,255,0.8);">(Available)</span>' +
                        '</button>';
            }
        }
        html += '</div>';

        body.innerHTML = html;
        modal.style.display = 'flex';
    };

    window.copyMacToClipboard = function(mac) {
        const val = (mac && mac !== '{MAC_ADDRESS}') ? mac : ESP32_MAC;
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(val).then(() => {
                alert('Copied MAC Address: ' + val);
            }).catch(() => {
                prompt('Copy MAC address:', val);
            });
        } else {
            prompt('Copy MAC address:', val);
        }
    };

    window.openInstaller = function() {
        const targetUrl = 'https://pisophone.pages.dev/installer/?mac=' + encodeURIComponent(ESP32_MAC) + 
                          '&ip=' + encodeURIComponent(ESP32_HOST) + 
                          '&secret=' + encodeURIComponent(ESP32_SECRET);
        window.open(targetUrl, '_blank');
    };

    window.occupySlot = function(slot) {
        activeSlotNum = slot || 1;
        const devicesList = window.latestDevicesList || [];
        const slotInfo = devicesList.find(d => d.slotNum === activeSlotNum);
        if (slotInfo && (slotInfo.isExpiredOrInactive || slotInfo.expStatus === 2 || !slotInfo.active || slotInfo.expiresAt === "0" || slotInfo.expiresAt === 0)) {
            alert('⛔ Cannot occupy Slot #' + activeSlotNum + ': This slot seat is expired or uncredited.\n\nPlease allocate credits in the Master Credit Vault to activate this slot.');
            return;
        }

        const modal = document.getElementById('unassigned_pair_modal');
        const title = document.getElementById('unassigned_modal_title');
        const body = document.getElementById('unassigned_modal_body');
        if (!modal || !body) {
            openInstallerForActiveSlot();
            return;
        }

        if (title) title.innerHTML = '⚡ Occupy Slot #' + activeSlotNum;

        const unassigned = window.unassignedDevices || [];
        if (unassigned.length > 0) {
            let html = '<div style="font-size: 13px; color: var(--text-muted); margin-bottom: 8px;">' +
                       'Unassigned terminals requesting connection found on network! Click to pair to <b>Slot #' + activeSlotNum + '</b>:' +
                       '</div>';

            unassigned.forEach(uDev => {
                const uName = uDev.name || 'PisoPhone Terminal';
                html += '<div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; display: flex; align-items: center; justify-content: space-between; gap: 10px;">' +
                        '<div>' +
                            '<div style="font-size: 13px; font-weight: 700; color: var(--text-main);">' + uName + '</div>' +
                            '<div style="font-size: 11px; color: var(--text-muted); font-family: monospace;">IP: ' + uDev.ip + ' • HW: ' + uDev.id + '</div>' +
                        '</div>' +
                        '<button type="button" class="btn btn-primary btn-sm" onclick="confirmConnection(\'' + uDev.id + '\', \'' + uDev.ip + '\', ' + activeSlotNum + ', \'' + uName.replace(/'/g, "\\'") + '\')" style="padding: 6px 12px; font-weight: 700; font-size: 12px;">' +
                            '⚡ Pair to Slot #' + activeSlotNum +
                        '</button>' +
                        '</div>';
            });

            body.innerHTML = html;
        } else {
            body.innerHTML = '<div style="background: var(--input-bg); border: 1px dashed var(--border); border-radius: var(--radius-md); padding: 18px; text-align: center; color: var(--text-muted); font-size: 12px;">' +
                             '<div style="font-size: 24px; margin-bottom: 6px;">📱</div>' +
                             '<div style="font-weight: 700; color: var(--text-main);">No Unassigned Terminal Requesting Connection</div>' +
                             '<div style="margin-top: 6px; line-height: 1.4; color: var(--text-muted);">Make sure your Android terminal is powered on and connected to this ESP32 Wi-Fi network.<br>Alternatively, click below to open the Web Installer to sideload the PisoPhone Kiosk App.</div>' +
                             '</div>';
        }

        modal.style.display = 'flex';
    };

    window.showPairingQrModal = function(slotNum) {
        activeSlotNum = slotNum || 1;
        const modal = document.getElementById('qr_pair_modal');
        if (!modal) return;
        const slotTitle = document.getElementById('qr_slot_title');
        if (slotTitle) slotTitle.textContent = activeSlotNum;
        const ipElem = document.getElementById('qr_modal_ip');
        const hostIp = window.location.hostname || "192.168.4.1";
        if (ipElem) ipElem.textContent = hostIp;

        const payloadObj = {
            pisophone_pair: 1,
            ip: hostIp,
            port: 80,
            ws_port: 81,
            mac: ESP32_MAC,
            secret: ESP32_SECRET,
            slot: activeSlotNum,
            name: "Slot #" + activeSlotNum
        };
        const payloadStr = JSON.stringify(payloadObj);

        modal.style.display = 'flex';
        try {
            if (typeof QRious !== 'undefined') {
                new QRious({
                    element: document.getElementById('qr_canvas'),
                    value: payloadStr,
                    size: 260,
                    level: 'M'
                });
            } else {
                const fb = document.getElementById('qr_fallback_text');
                if (fb) {
                    fb.textContent = payloadStr;
                    fb.style.display = 'block';
                }
            }
        } catch (e) {
            console.error("QR render error:", e);
        }
    };

    window.closePairingQrModal = function() {
        const modal = document.getElementById('qr_pair_modal');
        if (modal) modal.style.display = 'none';
    };

    window.openSecureOriginModal = function() {
        const modal = document.getElementById('secure_origin_modal');
        if (modal) modal.style.display = 'flex';
    };
    window.closeSecureOriginModal = function() {
        const modal = document.getElementById('secure_origin_modal');
        if (modal) modal.style.display = 'none';
    };
    window.copyFlagUrl = function() {
        const input = document.getElementById('flag_url_input');
        if (input) {
            navigator.clipboard.writeText(input.value);
            alert('Copied flag URL to clipboard: ' + input.value + '\n\nPaste this into your browser address bar.');
        }
    };
    window.copyOriginsList = function() {
        const input = document.getElementById('origins_list_input');
        if (input) {
            navigator.clipboard.writeText(input.value);
            alert('Copied origins to clipboard: ' + input.value + '\n\nPaste this into the flag text box and click Relaunch.');
        }
    };

    function initSecureOriginDetection() {
        const originInput = document.getElementById('origins_list_input');
        if (originInput) {
            const host = window.location.hostname;
            const port = window.location.port ? (':' + window.location.port) : '';
            const currentOrigin = 'http://' + host + port;
            const origins = ['http://kioskmanager.local', 'http://192.168.4.1', currentOrigin];
            const uniqueOrigins = [...new Set(origins)].join(',');
            originInput.value = uniqueOrigins;
        }

        const isHttp = window.location.protocol === 'http:';
        const isLocalhost = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
        if (isHttp && !isLocalhost && !navigator.usb) {
            const banner = document.getElementById('secure_context_banner');
            if (banner) banner.style.display = 'block';
        }
    }
    document.addEventListener('DOMContentLoaded', initSecureOriginDetection);

    window.openTokenModal = function() {
        document.getElementById('token_modal').style.display = 'flex';
        document.getElementById('token_input').value = '';
        document.getElementById('token_error').style.display = 'none';
    };
    window.closeTokenModal = function() {
        document.getElementById('token_modal').style.display = 'none';
    };

    window.submitSlotToken = function() {
        const token = document.getElementById('token_input').value.trim();
        const errDiv = document.getElementById('token_error');
        if (!token) {
            errDiv.textContent = 'Please paste a token.';
            errDiv.style.display = 'block';
            return;
        }
        errDiv.style.display = 'none';
        fetch('/api/slots/apply_token?token=' + encodeURIComponent(token), { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    alert('🎉 Capacity upgraded to ' + data.maxSlots + ' seats!');
                    location.reload();
                } else {
                    errDiv.textContent = data.error || 'Invalid token.';
                    errDiv.style.display = 'block';
                }
            })
            .catch(err => {
                errDiv.textContent = 'Network error: ' + err.message;
                errDiv.style.display = 'block';
            });
    };

    window.syncCloudSnapshot = function(btn) {
        btn.textContent = '⏳ Syncing...';
        btn.disabled = true;
        fetch('/api/slots/cloud_sync', { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                alert('☁️ Cloud snapshot report sent successfully!');
                btn.textContent = '☁️ Sync to Cloud';
                btn.disabled = false;
            })
            .catch(err => {
                alert('Cloud sync failed: ' + err.message);
                btn.textContent = '☁️ Sync to Cloud';
                btn.disabled = false;
            });
    };

    window.unpairSlot = function(slot) {
        if (!confirm('Unpair Slot #' + slot + '? This will free the seat slot on this ESP32. The coin slot will no longer accept coins for this device until paired again.')) return;
        fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    location.reload();
                } else {
                    alert('Failed to unpair slot: ' + (data.error || 'Unknown error'));
                }
            })
            .catch(err => {
                alert('Network error unpairing slot: ' + err.message);
            });
    };

    let targetModalSlot = 1;

    window.openSlotCreditModal = function(sNum, name, ip, devId, expInfo, expStatus, isBound) {
        targetModalSlot = sNum;
        const modal = document.getElementById('slot_credit_modal');
        if (!modal) return;

        const titleEl = document.getElementById('modal_slot_title');
        const subEl = document.getElementById('modal_slot_sub');
        const expTextEl = document.getElementById('modal_slot_expiry_text');
        const badgeEl = document.getElementById('modal_slot_status_badge');
        const unpairCont = document.getElementById('modal_unpair_container');

        if (titleEl) titleEl.textContent = 'Slot #' + sNum + ' Credit Allocation';
        if (subEl) subEl.textContent = name || ('PisoPhone Slot #' + sNum);
        if (expTextEl) expTextEl.textContent = expInfo || 'No Credit';

        if (badgeEl) {
            if (expStatus === 2) {
                badgeEl.innerHTML = '<span style="font-size: 10px; font-weight: 800; background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); padding: 3px 8px; border-radius: 6px;">🔴 UNCREDITED / EXPIRED</span>';
            } else if (expStatus === 1) {
                badgeEl.innerHTML = '<span style="font-size: 10px; font-weight: 800; background: rgba(245, 158, 11, 0.15); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.3); padding: 3px 8px; border-radius: 6px;">⚠️ EXPIRING SOON</span>';
            } else {
                badgeEl.innerHTML = '<span style="font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); border: 1px solid rgba(16, 185, 129, 0.3); padding: 3px 8px; border-radius: 6px;">🟢 ACTIVE</span>';
            }
        }

        if (unpairCont) {
            unpairCont.style.display = isBound ? 'block' : 'none';
        }

        modal.style.display = 'flex';
    };

    window.closeSlotCreditModal = function() {
        const modal = document.getElementById('slot_credit_modal');
        if (modal) modal.style.display = 'none';
    };

    window.submitModalCredit = function(type) {
        closeSlotCreditModal();
        allocateSlotCredit(targetModalSlot, type);
    };

    window.submitModalUnpair = function() {
        closeSlotCreditModal();
        unpairSlot(targetModalSlot);
    };

    window.allocateSlotCredit = function(slot, type) {
        const desc = type === 'month' ? '30 Days (1 Month Credit)' : (type === 'year' ? '1 Year (1 Annual Credit)' : '2 Minutes (Test Credit)');
        if (!confirm('Allocate ' + desc + ' to Slot #' + slot + '?')) return;
        fetch('/api/credits/allocate?slot=' + slot + '&type=' + type, { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    location.reload();
                } else {
                    alert('Credit Allocation Failed: ' + (data.error || 'Check vault credits balance'));
                }
            })
            .catch(err => alert('Network error: ' + err.message));
    };

    window.openProvisionModal = function(slot) {
        activeSlotNum = slot;
        document.getElementById('prov_slot_num').textContent = slot;
        document.getElementById('provision_modal').style.display = 'flex';
    };
    window.closeProvisionModal = function() {
        document.getElementById('provision_modal').style.display = 'none';
    };
    window.launchHttpsFlasher = function() {
        window.open('https://pisophone.pages.dev/installer/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + activeSlotNum, '_blank');
        closeProvisionModal();
    };
    window.launchHttpsFlasherMain = function() {
        const slotSelect = document.getElementById('main_prov_slot_select');
        const slot = slotSelect ? parseInt(slotSelect.value) : (activeSlotNum || 1);
        window.open('https://pisophone.pages.dev/installer/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + slot, '_blank');
    };

    window.openDeprovisionModal = function(slot, devId) {
        activeSlotNum = slot;
        document.getElementById('deprov_slot_num').textContent = slot;
        document.getElementById('deprov_log').textContent = 'Connect phone via USB and click Confirm Deprovision.\n';
        document.getElementById('deprovision_modal').style.display = 'flex';
    };
    window.closeDeprovisionModal = function() {
        document.getElementById('deprovision_modal').style.display = 'none';
    };

    window.executeDeprovisionFlow = async function() {
        const btn = document.getElementById('start_deprov_btn');
        const log = document.getElementById('deprov_log');
        const pin = document.getElementById('deprov_pin_input').value.trim() || '1234';
        btn.disabled = true;

        const appendLog = (msg) => {
            log.textContent += msg + '\n';
            log.scrollTop = log.scrollHeight;
        };

        try {
            if (!navigator.usb) throw new Error('WebUSB not supported in this browser.');
            if (!window.webADB) throw new Error('WebADB library not loaded.');

            appendLog('[1/3] Connecting USB device...');
            await window.webADB.connect((t) => appendLog(t));

            appendLog('[2/3] Deprovisioning Device Owner and removing kiosk package...');
            await window.webADB.deprovisionDevice(pin, (t) => appendLog(t));

            appendLog('[3/3] Freeing Slot #' + activeSlotNum + ' on ESP32...');
            await fetch('/api/slots/unpair?slot=' + activeSlotNum, { method: 'POST' });

            appendLog('\n✅ DEPROVISION COMPLETE! Phone restored, seat slot is open.');
            setTimeout(() => location.reload(), 1500);
        } catch (err) {
            appendLog('\n[ERROR] ' + (err.message || err));
            btn.disabled = false;
        }
    };

    window.checkQuickAdjustExpired = function() {
        const sel = document.getElementById('quick_adjust_target');
        const warn = document.getElementById('quick_adjust_warn');
        if (!sel || !warn) return false;
        let hasExpired = false;
        if (sel.value === 'ALL') {
            const expiredOpts = sel.querySelectorAll('option[data-expired="true"]');
            hasExpired = (expiredOpts.length > 0);
            if (hasExpired) {
                warn.innerHTML = '🚫 <b>Broadcast Notice:</b> ' + expiredOpts.length + ' registered device(s) are EXPIRED. Adjusting time is blocked until credits are allocated.';
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }
        } else {
            const opt = sel.options[sel.selectedIndex];
            hasExpired = (opt && opt.getAttribute('data-expired') === 'true');
            if (hasExpired) {
                const label = opt ? opt.text : 'Selected Device';
                warn.innerHTML = '🚫 <b>Device Expired:</b> ' + label + ' is EXPIRED. Manual time adjustment is blocked until credits are allocated.';
                warn.style.display = 'block';
            } else {
                warn.style.display = 'none';
            }
        }
        return hasExpired;
    };

    window.validateQuickAdjust = function(e, action) {
        if (window.checkQuickAdjustExpired()) {
            if (e) {
                e.preventDefault();
                e.stopPropagation();
            }
            const sel = document.getElementById('quick_adjust_target');
            const isAll = (sel && sel.value === 'ALL');
            const msg = isAll 
                ? "❌ Action Blocked: One or more devices in broadcast are EXPIRED!\n\nPlease allocate credits in the Master Credit Vault to pair and reactivate all devices before adjusting time."
                : "❌ Action Blocked: The selected device is EXPIRED!\n\nPlease allocate credits in the Master Credit Vault to pair and reactivate the device before adjusting time.";
            alert(msg);
            return false;
        }
        return true;
    };

    document.addEventListener('DOMContentLoaded', function() {
        if (window.checkQuickAdjustExpired) window.checkQuickAdjustExpired();
        
        // Auto-handle deprovision/unpair callback
        const urlParams = new URLSearchParams(window.location.search);
        const action = urlParams.get('action');
        const slot = urlParams.get('slot');
        if (action === 'unpair' && slot) {
            fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
                .then(res => res.json())
                .then(data => {
                    if (data.success) {
                        window.history.replaceState({}, document.title, window.location.pathname);
                        location.reload();
                    } else {
                        alert('Unpair auto-action failed: ' + data.error);
                    }
                })
                .catch(err => alert('Unpair auto-action network error: ' + err.message));
        }
    });
    </script>

</body>
</html>
)HTML";

static bool isPlaceholderTag(const char* start, const char* end) {
    int len = end - start + 1;
    if (len < 3 || len > 32) return false;
    if (*start != '{' || *end != '}') return false;
    for (const char* p = start + 1; p < end; p++) {
        char c = *p;
        if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
            return false;
        }
    }
    return true;
}

static String getPlaceholderValue(const String& tag) {
    if (tag == "{WIFI_SSID}") return wifiSsid;
    if (tag == "{WIFI_PASS}") return wifiPass;
    if (tag == "{MAC_ADDRESS}") return macAddressStr;
    if (tag == "{COIN_PIN}") return String(coinPin);
    if (tag == "{U_COIN_PIN}") return String(universalCoinPin);
    if (tag == "{LED_PIN}") return String(ledPin);
    if (tag == "{LED_ACTIVE_LOW_SELECTED}") return ledActiveLow ? "selected" : "";
    if (tag == "{LED_ACTIVE_HIGH_SELECTED}") return !ledActiveLow ? "selected" : "";
    if (tag == "{RELAY_PIN}") return String(relayPin);
    if (tag == "{RELAY_MODE_ALWAYS_SELECTED}") return (relayMode == 0) ? "selected" : "";
    if (tag == "{RELAY_MODE_ARMED_SELECTED}") return (relayMode == 1) ? "selected" : "";
    if (tag == "{RELAY_LOW_SELECTED}") return relayActiveLow ? "selected" : "";
    if (tag == "{RELAY_HIGH_SELECTED}") return !relayActiveLow ? "selected" : "";
    if (tag == "{IPS}") return androidIps;
    if (tag == "{DEVICE_SLOTS_MANAGER}") return renderLicenseSlotsHtml();
    if (tag == "{SLOT_OPTIONS}") return renderSlotOptions();
    if (tag == "{MAX_SLOTS}") return String(maxLicensedSlots);
    if (tag == "{PORT}") return String(targetPort);
    if (tag == "{ADMIN_PASSWORD}") return webPassword;
    if (tag == "{PRICE}") return String(coinPrice);
    if (tag == "{MINUTES}") return String(minutesPerCoin);
    if (tag == "{DEBOUNCE}") return String(lockoutDebounceMs);
    if (tag == "{DEVICE_OPTIONS}") return renderDeviceOptions("");
    if (tag == "{MATCH_MINUTES}") return String(matchMinutes);
    if (tag == "{P1_OPTIONS}") return renderDeviceOptions(p1Ip.length() > 0 ? p1Ip : getFirstKnownIp());
    if (tag == "{P2_OPTIONS}") return renderDeviceOptions(p2Ip);
    if (tag == "{MATCH_ALERT}") {
        if (matchStatusMsg.length() > 0) {
            String msg = matchStatusMsg;
            matchStatusMsg = "";
            return msg;
        }
        return "";
    }
    if (tag == "{QUICK_TIME_ALERT}") {
        if (quickTimeStatusMsg.length() > 0) {
            String msg = quickTimeStatusMsg;
            quickTimeStatusMsg = "";
            return msg;
        }
        return "";
    }
    if (tag == "{TOTAL_COINS}") return String(totalCoinsLifetime);
    if (tag == "{SESSION_COINS}") return String(totalCoinsSession);
    if (tag == "{SHARED_SECRET}") return sharedSecret;
    if (tag == "{IP_ADDRESS}") return WiFi.localIP().toString();
    if (tag == "{WIFI_RSSI}") return String(WiFi.RSSI());
    if (tag == "{WIFI_QUALITY}") {
        int rssi = WiFi.RSSI();
        if (WiFi.status() != WL_CONNECTED || rssi <= -100) return "0";
        if (rssi >= -50) return "100";
        return String(2 * (rssi + 100));
    }
    return tag;
}

void streamPortalHtml() {
    webServer.setContentLength(CONTENT_LENGTH_UNKNOWN);
    webServer.send(200, "text/html; charset=utf-8", "");

    const char* ptr = PORTAL_HTML_TEMPLATE;
    const char* chunkStart = ptr;

    while (*ptr != '\0') {
        if (*ptr == '{') {
            const char* tagEnd = strchr(ptr, '}');
            if (tagEnd && isPlaceholderTag(ptr, tagEnd)) {
                if (ptr > chunkStart) {
                    while (chunkStart < ptr) {
                        int len = min((int)(ptr - chunkStart), 1024);
                        char buf[1025];
                        memcpy(buf, chunkStart, len);
                        buf[len] = '\0';
                        webServer.sendContent(buf);
                        chunkStart += len;
                    }
                }
                
                int tagLen = tagEnd - ptr + 1;
                char tagBuf[33];
                memcpy(tagBuf, ptr, tagLen);
                tagBuf[tagLen] = '\0';
                
                String val = getPlaceholderValue(String(tagBuf));
                if (val.length() > 0) {
                    webServer.sendContent(val);
                }
                
                ptr = tagEnd + 1;
                chunkStart = ptr;
                continue;
            }
        }
        ptr++;
    }

    if (ptr > chunkStart) {
        while (chunkStart < ptr) {
            int len = min((int)(ptr - chunkStart), 1024);
            char buf[1025];
            memcpy(buf, chunkStart, len);
            buf[len] = '\0';
            webServer.sendContent(buf);
            chunkStart += len;
        }
    }

    webServer.sendContent("");
}

