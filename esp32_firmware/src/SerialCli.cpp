// USB serial command line used during bring-up.

#include "SerialCli.h"
#include "DeviceNetwork.h"
#include <Arduino.h>

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: add <minutes> | help");
    }
}
