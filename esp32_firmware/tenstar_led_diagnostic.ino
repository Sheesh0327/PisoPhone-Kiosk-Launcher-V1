// ============================================================================
// TENSTAR ROBOT ESP32-C3 SUPERMINI - HARDWARE & LED DIAGNOSTIC SKETCH
//
// Forum & Documentation Findings for Tenstar Robot ESP32-C3:
// 1. USB CDC: Native USB port requires "USB CDC On Boot: Enabled" in Arduino IDE.
// 2. ROM Bootloader Trap: After uploading, press the physical "RST" button on 
//    the board once. Native USB chips often do not auto-reboot after flashing.
// 3. Dual Hardware LEDs:
//    - RED LED: Power rail only (hardwired to 3.3V). Always solid on when powered.
//    - BLUE LED: User LED connected to GPIO 8 (Active LOW on most clones).
// 4. Batch Variance: Some clones use WS2812 RGB on pin 8, or routed LED to GPIO 2/7.
//
// This sketch automatically sweeps all possible pin modes, polarities, and types
// while printing real-time status to USB Serial (115200 baud).
// ============================================================================

#include <Arduino.h>

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=======================================================");
    Serial.println("  TENSTAR ROBOT ESP32-C3 LED HARDWARE DIAGNOSTIC TOOL  ");
    Serial.println("=======================================================");
    Serial.println("[*] CPU Clock: " + String(ESP.getCpuFreqMHz()) + " MHz");
    Serial.println("[*] Chip Model: " + String(ESP.getChipModel()) + " Rev " + String(ESP.getChipRevision()));
    Serial.println("[*] If you see this output, CPU & USB CDC are working 100%!");
    Serial.println("-------------------------------------------------------");
    Serial.println("Beginning diagnostic sweep of all potential LED pins...\n");
}

void testPin(int pin, const char* description, bool activeLow) {
    Serial.printf("[TESTING] GPIO %d (%s) -> 3 FAST BLINKS...\n", pin, description);
    pinMode(pin, OUTPUT);
    for (int i = 0; i < 3; i++) {
        digitalWrite(pin, activeLow ? LOW : HIGH); // Turn ON
        delay(200);
        digitalWrite(pin, activeLow ? HIGH : LOW); // Turn OFF
        delay(200);
    }
}

void loop() {
    // 1. Test GPIO 8 Active LOW (Default Tenstar Robot SuperMini Blue LED)
    Serial.println("\n>>> [PHASE 1] GPIO 8 - Active LOW (Standard Tenstar Robot Blue LED)");
    testPin(8, "GPIO 8 Active LOW", true);
    delay(800);

    // 2. Test GPIO 8 Active HIGH (External LED or inverted clone)
    Serial.println(">>> [PHASE 2] GPIO 8 - Active HIGH (External LED / Anode to GPIO)");
    testPin(8, "GPIO 8 Active HIGH", false);
    delay(800);

    // 3. Test GPIO 8 WS2812 Addressable RGB LED (SuperMini Plus / DevKitM-1 style)
    Serial.println(">>> [PHASE 3] GPIO 8 - Addressable WS2812 NeoPixel Pulse");
    for (int i = 0; i < 3; i++) {
        neopixelWrite(8, 0, 0, 80); // Blue pulse
        delay(200);
        neopixelWrite(8, 0, 0, 0);  // Off
        delay(200);
    }
    delay(800);

    // 4. Test GPIO 2 (Alternate clone pinout / traditional ESP32 LED)
    Serial.println(">>> [PHASE 4] GPIO 2 - Active LOW & Active HIGH (Alternate Pin)");
    testPin(2, "GPIO 2 Active LOW", true);
    testPin(2, "GPIO 2 Active HIGH", false);
    delay(800);

    // 5. Test GPIO 7 & GPIO 9 (Secondary clone pins)
    Serial.println(">>> [PHASE 5] GPIO 7 & GPIO 9 (Secondary clone candidates)");
    testPin(7, "GPIO 7", true);
    testPin(9, "GPIO 9", true);

    Serial.println("\n--- Cycle complete. Pausing 3 seconds before next sweep ---\n");
    delay(3000);
}
