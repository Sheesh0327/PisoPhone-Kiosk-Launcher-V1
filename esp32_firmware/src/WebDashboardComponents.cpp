#include "WebDashboardComponents.h"
#include "Product.h"

// Everything here is about the rental phones: the PisoWiFi-only image compiles nothing from this file.
#if PISO_HAS_PHONES
#include "WebDashboardHtml.h"
#include "Config.h"
#include "DeviceManager.h"
#include "DeviceNetwork.h"
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
    html += "<div class=\"panel-head\"><h2>" + icon("phone") + "Phone slots <span class=\"panel-sub\">" +
            String(getDeviceCap()) + " of " + String(MAX_SUPPORTED_SLOTS) +
            " unlocked</span></h2><div class=\"actions\">";
    // the router gives the box the phones' Wi-Fi password, so the setup page needs no typing
    // The box secret and the Wi-Fi password go after the "#": a browser never sends that part to the website's server, so it
    // stays out of the host's logs, and the setup page removes it from the address bar as soon as it has read it.
    // (the router's own network name goes first, so a reader of the link can find "&wifi_ssid=NAME&wifi_pass=")
    String kioskLink = "";
    String linkSsid = kioskWifiSsid;
    String linkPass = kioskWifiPass;
    bool linkVisible = false;
#if !PISO_HAS_GATEWAY
    // PisoPhone only has no router to name a PisoKiosk network: the phones join the shop's own Wi-Fi, the one this box joined. Without this
    // the link carried no Wi-Fi at all and a new phone could not be set up (feature 028).
    if (linkPass.length() == 0 && wifiSsid.length() > 0 && wifiPass.length() >= 8) {
        linkSsid = wifiSsid;
        linkPass = wifiPass;
        linkVisible = true;
    }
#endif
    if (linkPass.length() > 0) {
        if (linkSsid.length() > 0) kioskLink += String("&wifi_ssid=") + urlEncode(linkSsid);
        kioskLink += String("&wifi_pass=") + urlEncode(linkPass);
        if (linkVisible) kioskLink += "&wifi_hidden=0"; // the shop's normal network is not hidden
    }
    html += "<a class=\"btn primary sm\" href=\"" + setupBase + "#secret=" + secret + kioskLink + "\">" +
            icon("download") + "Set up a phone</a>";
    html +=
        "<a class=\"btn sm\" href=\"" + setupBase + "&mode=deprovision\">" + icon("trash") + "Remove from a phone</a>";
    html += "</div></div>";

    html += "<div class=\"panel-body\">";

    html += "<div class=\"mini-stats\">";
    html += miniStat("Phones paired",
                     String(installedCount) + " <small class=\"muted\">of " + String(getDeviceCap()) + "</small>");
    if (unassignedCount > 0) html += miniStat("Waiting to connect", String(unassignedCount));
    html += "</div>";

    // Slot licenses (SlotLicense.h): two slots are free, every other one needs a key the owner made for this box's code
    bool full = installedCount >= getDeviceCap();
    html +=
        String("<div class=\"note") + (full ? " warn" : "") + "\" id=\"licensebox\">" + icon("key") +
        "<span class=\"grow\"><b>" + String(slotlicense::FREE_SLOTS) + " slots are free. Each more slot needs a P" +
        String(slotlicense::PRICE_PHP) + " license key.</b> " + String(getLicenseKeyCount()) + " license" +
        (getLicenseKeyCount() == 1 ? "" : "s") + " added." +
        (getLicenseFloor() > slotlicense::FREE_SLOTS + getLicenseKeyCount()
             ? String(" This box also keeps the ") + String(getLicenseFloor()) + " slots it had before licensing."
             : String("")) +
        " Give your vendor this <b>box code</b> when you buy one: <b class=\"mono\">" + macAddressStr +
        "</b><span class=\"licrow\"><span class=\"field\"><input type=\"text\" id=\"lic_token\" placeholder=\"Paste the license key (PSLOT1...)\" autocomplete=\"off\" spellcheck=\"false\"></span>"
        "<button type=\"button\" class=\"btn sm primary\" onclick=\"redeemSlotLicense()\">Add slot</button></span></span></div>";

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
        String fullName = isBound
                              ? (phoneSlots[i].name.length() > 0 ? phoneSlots[i].name : ("PisoPhone " + String(sNum)))
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

#endif // PISO_HAS_PHONES
