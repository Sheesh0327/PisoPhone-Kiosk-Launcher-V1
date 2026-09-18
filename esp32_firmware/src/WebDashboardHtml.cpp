#include "WebDashboardHtml.h"
#include "WebDashboardStyles.h"
#include "WebDashboardScripts.h"
#include "WebDashboardModals.h"
#include "WebDashboardTemplate.h"
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
    
    if (tag == "MATCH_CARD_CLASS") {
        return matchActive ? "match-active-card" : "";
    }

    if (tag == "MATCH_CARD_STYLE") {
        return matchActive ? "border: 2px solid #8b5cf6; background: linear-gradient(135deg, rgba(139, 92, 246, 0.12) 0%, rgba(15, 23, 42, 0.95) 100%); box-shadow: 0 0 25px rgba(139, 92, 246, 0.25);" : "";
    }

    if (tag == "MATCH_STATUS_BADGE") {
        if (matchActive) {
            return "<span class=\"status-badge\" style=\"background:#8b5cf6; color:#ffffff; border-color:#a78bfa;\">⚔️ ARENA ACTIVE</span>";
        } else {
            return "<span class=\"status-badge accent\">ESPORTS</span>";
        }
    }

    if (tag == "MATCH_CONTROLS") {
        String html = "";
        if (!matchActive) {
            html += "<div style=\"display:flex;gap:12px;flex-wrap:wrap;margin-top:12px;\">";
            html += "<button type=\"submit\" name=\"action\" value=\"activate\" class=\"btn btn-primary\" style=\"background:linear-gradient(135deg, #8b5cf6 0%, #6d28d9 100%);border:none;color:#fff;font-weight:700;padding:10px 20px;border-radius:8px;\">⚔️ Activate 1v1 Mode</button>";
            html += "<button type=\"button\" onclick=\"checkMatchQualification()\" class=\"btn btn-outline\" style=\"border-color:var(--primary);color:var(--primary);\">🔍 Check Qualification</button>";
            html += "</div>";
        } else {
            html += "<div style=\"display:flex;gap:12px;flex-wrap:wrap;margin-top:12px;align-items:center;\">";
            html += "<button type=\"submit\" name=\"action\" value=\"cancel\" class=\"btn btn-outline\" style=\"border-color:#ef4444;color:#ef4444;font-weight:700;\">❌ End / Cancel Match</button>";
            html += "<button type=\"button\" onclick=\"checkMatchQualification()\" class=\"btn btn-outline\" style=\"border-color:var(--primary);color:var(--primary);\">🔍 Check Qualification</button>";
            html += "</div>";
        }
        return html;
    }

    if (tag == "MATCH_ALERT") {
        if (matchStatusMsg.length() > 0) {
            String alert = matchStatusMsg;
            matchStatusMsg = "";
            return alert;
        }
        if (matchActive) {
            return "<div style='background:rgba(234, 88, 12, 0.15);border:1px solid #f97316;color:#fdba74;padding:12px 16px;border-radius:8px;margin-bottom:12px;font-size:13px;line-height:1.5;'>⚔️ <b>1v1 Arena Mode Active!</b><br>⚠️ <b>Warning:</b> Time credits are at stake (<b>" + String(matchMinutes) + " minutes</b>). The loser will forfeit their stake to the winner upon match completion.</div>";
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
