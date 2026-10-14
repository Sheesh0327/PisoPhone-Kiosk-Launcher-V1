// USB serial command line used during bring-up. The *-production-encrypted builds (BUILD_ENV_PRODUCTION) do not have it: the
// "add" command gives free time to every phone and asks for no password.

#include "SerialCli.h"
#include "DeviceNetwork.h"
#include <Arduino.h>

void processSerialCli() {
    if (!Serial.available()) return;
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

#ifdef BUILD_ENV_PRODUCTION
    if (line.equalsIgnoreCase("help")) Serial.println("\nNo serial commands in this build.");
    return;
#else
    if (line.startsWith("add ") || line.startsWith("add")) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace != -1) {
            int minutes = line.substring(firstSpace + 1).toInt();
            sendAddTime(minutes, "ALL");
        }
    } else if (line.equalsIgnoreCase("help")) {
        Serial.println("\nCommands: add <minutes> | help");
    }
#endif
}
