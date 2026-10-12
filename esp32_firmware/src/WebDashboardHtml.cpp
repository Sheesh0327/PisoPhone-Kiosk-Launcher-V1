#include "WebDashboardHtml.h"
#include "RevenueVault.h"
#include "WebAssetServer.h"
#include "SuperAdminTemplate.h"
#include "WebDashboardIcons.h"
#include "WebDashboardModals.h"
#include "WebDashboardTemplate.h"
#include "SuperAdminManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include <WebServer.h>
#include <WiFi.h>

extern WebServer webServer;

String escapeHtmlText(String s) {
    s.replace("&", "&amp;");
    s.replace("<", "&lt;");
    s.replace(">", "&gt;");
    s.replace("\"", "&quot;");
    s.replace("'", "&#39;");
    return s;
}

String noteHtml(const char* kind, const char* icon, const String& html) {
    return String("<div class=\"note ") + kind + "\"><svg class=\"ic\"><use href=\"#i-" + icon +
           "\"/></svg><span class=\"grow\">" + html + "</span></div>";
}

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
    if (tag == "TOTAL_COINS") return String(vaultCoins()); // since the last collection
    if (tag == "SESSION_COINS") return String(totalCoinsSession);
    if (tag == "DEVICE_OPTIONS") return renderDeviceOptions("");
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
    // In legacy mode phones still use the old key, so provisioning links carry no secret until the box is switched.
    if (tag == "SHARED_SECRET") return isLegacyKeyMode() ? String("") : getBoxSecret();
    if (tag == "DEVICE_SLOTS_MANAGER") return renderPhoneSlotsHtml();
    if (tag == "MAX_SLOTS") return String(MAX_SUPPORTED_SLOTS);
    if (tag == "ASSET_V_PORTAL_CSS") return webAssetVersion("portal.css");
    if (tag == "ASSET_V_PORTAL_CORE") return webAssetVersion("portal-core.js");
    if (tag == "ASSET_V_PORTAL_MODALS") return webAssetVersion("portal-modals.js");
    if (tag == "ASSET_V_SUPERADMIN") return webAssetVersion("superadmin.js");

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
        return matchActive ? "live" : "";
    }

    if (tag == "MATCH_STATUS_BADGE") {
        return matchActive ? "<span class=\"tag accent\">Live</span>" : "";
    }

    if (tag == "MATCH_CONTENT_CLASS") {
        return matchActive ? "" : "hidden";
    }

    if (tag == "MATCH_HEADER_ACTION") {
        if (matchActive) return "";
        return "<button type=\"button\" id=\"match_toggle_btn\" onclick=\"toggle1v1MatchBox()\" class=\"btn sm\">"
               "<svg class=\"ic\"><use href=\"#i-users\"/></svg>Start a match</button>";
    }

    if (tag == "MATCH_CONTROLS") {
        String html = "<div class=\"actions\">";
        if (!matchActive) {
            html +=
                "<button type=\"submit\" name=\"action\" value=\"activate\" class=\"btn primary\">Start match</button>";
        } else {
            html += "<button type=\"submit\" name=\"action\" value=\"cancel\" class=\"btn danger\">End match</button>";
        }
        html += "<button type=\"button\" onclick=\"checkMatchQualification()\" class=\"btn\">"
                "<svg class=\"ic\"><use href=\"#i-search\"/></svg>Check both phones</button>";
        html += "</div>";
        return html;
    }

    if (tag == "MATCH_ALERT") {
        if (matchStatusMsg.length() > 0) {
            String alert = matchStatusMsg;
            matchStatusMsg = "";
            return alert;
        }
        if (matchActive) {
            return "<div class=\"note warn\"><svg class=\"ic\"><use href=\"#i-alert\"/></svg><span class=\"grow\"><b>A match is on.</b> "
                   "Both players staked " +
                   String(matchMinutes) +
                   " minutes. The loser's stake goes to the winner when the match ends.</span></div>";
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

                if (isTagMatch(tagStart, "PORTAL_ICONS")) {
                    webServer.sendContent_P(PORTAL_ICONS_HTML);
                } else if (isTagMatch(tagStart, "SUPER_ADMIN_TAB")) {
                    // Straight from flash: no 25 KB String copy on the heap.
                    webServer.sendContent_P(SUPER_ADMIN_HTML);
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
