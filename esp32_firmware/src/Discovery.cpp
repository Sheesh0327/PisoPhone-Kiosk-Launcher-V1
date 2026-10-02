// UDP discovery: answers phones that look for the box and announces it every few seconds.

#include "Discovery.h"
#include "Config.h"
#include "Security.h"
#include "WebServerModule.h"
#include "Diagnostics.h"
#include <WiFi.h>
#include <WiFiUdp.h>

void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort) {
    if (WiFi.status() != WL_CONNECTED) return;

    String secKey = getSharedSecret();
    String ipStr = WiFi.localIP().toString();
    String sig = calculateHMAC("DISCOVERY:" + macAddressStr + ":" + ipStr, secKey);

    String resp = "{\"type\":\"PISOPHONE_ESP32_RESPONSE\","
                  "\"device\":\"PISOPHONE_MASTER\","
                  "\"mac\":\"" +
                  macAddressStr +
                  "\","
                  "\"ip\":\"" +
                  ipStr +
                  "\","
                  "\"sig\":\"" +
                  sig +
                  "\","
                  "\"port\":80,"
                  "\"ws_port\":81,"
                  "\"device_name\":\"PisoPhone Master\","
                  "\"slots\":" +
                  String(maxLicensedSlots) +
                  ","
                  "\"minutes\":" +
                  String(minutesPerCoin) +
                  ","
                  "\"price\":1.0,"
                  "\"uptime\":" +
                  String(millis() / 1000) + "}";

    // 1. Direct unicast response to client
    if (targetIp != IPAddress(0, 0, 0, 0) && targetPort > 0) {
        udpServer.beginPacket(targetIp, targetPort);
        udpServer.write((const uint8_t*)resp.c_str(), resp.length());
        udpServer.endPacket();
    }

    // 2. Local broadcast on discovery port 8888 (handles clients listening on fixed port)
    IPAddress bcast(255, 255, 255, 255);
    udpServer.beginPacket(bcast, UDP_DISCOVERY_PORT);
    udpServer.write((const uint8_t*)resp.c_str(), resp.length());
    udpServer.endPacket();
}

void processUdpDiscovery() {
    if (WiFi.status() != WL_CONNECTED) return;

    int packetSize = udpServer.parsePacket();
    if (packetSize > 0) {
        char packetBuffer[512];
        int len = udpServer.read(packetBuffer, sizeof(packetBuffer) - 1);
        if (len > 0) {
            packetBuffer[len] = '\0';
            String msg = String(packetBuffer);
            msg.trim();

            if (msg.indexOf("PISOPHONE_DISCOVER") >= 0) {
                // If specific target_mac is specified in probe, only respond if matching this ESP32
                int targetMacIdx = msg.indexOf("\"target_mac\":\"");
                if (targetMacIdx >= 0) {
                    int valStart = targetMacIdx + 14;
                    int valEnd = msg.indexOf("\"", valStart);
                    if (valEnd > valStart) {
                        String reqMac = msg.substring(valStart, valEnd);
                        reqMac.toUpperCase();
                        String curMac = macAddressStr;
                        curMac.toUpperCase();
                        String cleanReq = "";
                        for (size_t i = 0; i < reqMac.length(); i++)
                            if (reqMac[i] != ':') cleanReq += reqMac[i];
                        String cleanCur = "";
                        for (size_t i = 0; i < curMac.length(); i++)
                            if (curMac[i] != ':') cleanCur += curMac[i];
                        if (cleanReq != cleanCur) {
                            return; // Probe targeted another box MAC
                        }
                    }
                }

                IPAddress remoteIp = udpServer.remoteIP();
                uint16_t remotePort = udpServer.remotePort();
                Serial.printf("[⚡ UDP Discovery] Valid probe received from %s:%d. Responding...\n",
                              remoteIp.toString().c_str(), remotePort);
                sendUdpDiscoveryResponse(remoteIp, remotePort);
            }
        }
    }

    // Periodic announcement beacon (every 2.5 seconds while connected to WiFi for fast DHCP recovery)
    static unsigned long lastUdpAnnounceMs = 0;
    if (millis() - lastUdpAnnounceMs > 2500 || lastUdpAnnounceMs == 0) {
        lastUdpAnnounceMs = millis();
        sendUdpDiscoveryResponse(IPAddress(255, 255, 255, 255), UDP_DISCOVERY_PORT);
    }
}
