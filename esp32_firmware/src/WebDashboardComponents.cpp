#include "WebDashboardComponents.h"
#include "WebDashboardHtml.h"
#include "Config.h"
#include "DeviceManager.h"
#include "Security.h"
#include <WiFi.h>

static String htmlEscape(String s) {
    s.replace("&", "&amp;");
    s.replace("<", "&lt;");
    s.replace(">", "&gt;");
    s.replace("\"", "&quot;");
    return s;
}

static String icon(const char* name) {
    return String("<svg class=\"ic\"><use href=\"#i-") + name + "\"/></svg>";
}

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
                bool isInactive = !isSlotActive(slotIdx);
                String expAttr = isInactive ? " data-inactive=\"true\"" : " data-inactive=\"false\"";
                String badge = isInactive ? " [not paired]" : "";
                opts += "<option value=\"" + cfg.ip + "\"" + sel + expAttr + ">" + htmlEscape(name) + " (" + cfg.ip +
                        ")" + badge + "</option>";
                devNum++;
            }
        }
        startIdx = comma + 1;
    }
    return opts;
}

static String miniStat(const String& label, const String& value) {
    return "<div class=\"mini\"><div class=\"k\">" + label + "</div><div class=\"v\">" + value + "</div></div>";
}

String renderPhoneSlotsHtml() {
    int installedCount = 0;
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        if (phoneSlots[i].deviceId.length() > 0) {
            installedCount++;
        }
    }

    int unassignedCount = 0;
    unsigned long currentMillis = millis();
    for (int i = 0; i < trackedDeviceCount; i++) {
        if (trackedDevices[i].deviceId.length() == 0 && trackedDevices[i].lastKnownIp.length() == 0) continue;
        bool isFromApp = trackedDevices[i].isApp ||
                         (trackedDevices[i].deviceId.length() > 0 && !trackedDevices[i].deviceId.startsWith("DEV_")) ||
                         (trackedDevices[i].batteryLevel >= 0);
        if (!isFromApp) continue;
        String dId = trackedDevices[i].deviceId;
        if (dId.length() == 0) dId = trackedDevices[i].lastKnownIp;
        if (findSlotIndexForDevice(dId, trackedDevices[i].lastKnownIp) >= 0) continue;
        if (currentMillis - trackedDevices[i].lastSeenMs < 300000) {
            unassignedCount++;
        }
    }

    String setupBase = "https://pisophone.pages.dev/?mac=" + macAddressStr;
    String secret = isLegacyKeyMode() ? String("") : getBoxSecret();

    String html = "<div class=\"panel\">";

    // Head: title and the two setup links
    html +=
        "<div class=\"panel-head\"><h2>" + icon("phone") +
        "Phone slots <span class=\"panel-sub\">Up to " + String(MAX_SUPPORTED_SLOTS) + " phones</span></h2><div class=\"actions\">";
    html += "<a class=\"btn primary sm\" href=\"" + setupBase + "&secret=" + secret + "\">" + icon("download") +
            "Set up a phone</a>";
    html +=
        "<a class=\"btn sm\" href=\"" + setupBase + "&mode=deprovision\">" + icon("trash") + "Remove from a phone</a>";
    html += "</div></div>";

    html += "<div class=\"panel-body\">";

    html += "<div class=\"mini-stats\">";
    html += miniStat("Phones paired",
                     String(installedCount) + " <small class=\"muted\">of " + String(MAX_SUPPORTED_SLOTS) + "</small>");
    if (unassignedCount > 0) html += miniStat("Waiting to connect", String(unassignedCount));
    html += "</div>";

    if (unassignedCount > 0) {
        html += "<div class=\"note warn\">" + icon("phone") + "<span class=\"grow\"><b>" + String(unassignedCount) +
                " phone" + (unassignedCount > 1 ? "s are" : " is") +
                " asking to connect.</b> Choose a free slot below to pair " + (unassignedCount > 1 ? "them" : "it") +
                ".</span></div>";
    }

    // Slots
    html += "<div class=\"slots\">";
    for (int i = 0; i < MAX_SUPPORTED_SLOTS; i++) {
        int sNum = i + 1;
        String devId = phoneSlots[i].deviceId;
        String ip = phoneSlots[i].ip;
        bool isBound = (devId.length() > 0);
        String fullName =
            isBound ? (phoneSlots[i].name.length() > 0 ? phoneSlots[i].name : ("PisoPhone " + String(sNum)))
                    : ("Slot " + String(sNum));
        String shown = isBound ? fullName : "Empty";
        String jsName = fullName;
        jsName.replace("\\", "\\\\");
        jsName.replace("'", "\\'");
        jsName.replace("\"", "&quot;");
        jsName.replace("<", "&lt;");
        String cls = isBound ? "slot used" : "slot";
        String sub = isBound ? "Paired" : "Tap to set up";

        html += "<button type=\"button\" class=\"" + cls + "\" onclick=\"openSlotActivationModal(" + String(sNum) +
                ", '" + jsName + "', '" + ip + "', '" + devId + "', " + (isBound ? "true" : "false") + ")\">";
        html += "<span class=\"slot-top\"><span>#" + String(sNum) + "</span><span class=\"dot\"></span></span>";
        html += "<span class=\"slot-ic\">" + icon(isBound ? "phone" : "plus") + "</span>";
        html += "<span><span class=\"slot-name\">" + htmlEscape(shown) + "</span><span class=\"slot-sub\">" + sub +
                "</span></span>";
        html += "</button>";
    }
    html += "</div>";

    html += "</div></div>";
    return html;
}
