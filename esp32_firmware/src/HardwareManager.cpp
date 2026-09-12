#include "HardwareManager.h"
#include "Config.h"
#include "DeviceManager.h"
#include <WiFi.h>

// Forward declarations of WebSocket client from WebServerModule
extern WiFiClient wsClient;
extern bool isWsConnected;

// ============================================================================
// NON-BLOCKING LED INDICATOR STATE MACHINE
// ============================================================================
LedSystemState currentLedState = LED_STATE_CONNECTING;

static unsigned long lastLedToggleTime = 0;
static const unsigned long LED_RAPID_TOGGLE_MS = 100;
static const unsigned long LED_SLOW_TOGGLE_MS  = 500;
static const unsigned long LED_COIN_PULSE_MS   = 60;
static int ledBlinksRemaining = 0;
static bool ledState = false;

void setLedHardware(bool on) {
    pinMode(ledPin, OUTPUT);
    digitalWrite(ledPin, (on ^ ledActiveLow) ? HIGH : LOW);
}

void triggerLedBlink(int blinkCount) {
    ledBlinksRemaining = blinkCount * 2 - 1;
    ledState = false;
    setLedHardware(false);
    lastLedToggleTime = millis();
}

void processLedBlink() {
    unsigned long now = millis();

    // 1. Transient Coin Insertion Double Blink Override
    if (ledBlinksRemaining > 0) {
        if (now - lastLedToggleTime >= LED_COIN_PULSE_MS) {
            lastLedToggleTime = now;
            ledBlinksRemaining--;
            ledState = !ledState;
            setLedHardware(ledState);
            if (ledBlinksRemaining == 0) {
                if (currentLedState == LED_STATE_CONNECTED) {
                    setLedHardware(true);
                    ledState = true;
                }
            }
        }
        return;
    }

    // 2. Wi-Fi Status LED Indicator Patterns
    switch (currentLedState) {
        case LED_STATE_CONNECTING:
            if (now - lastLedToggleTime >= LED_RAPID_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_FAILED:
            if (now - lastLedToggleTime >= LED_SLOW_TOGGLE_MS) {
                lastLedToggleTime = now;
                ledState = !ledState;
                setLedHardware(ledState);
            }
            break;

        case LED_STATE_CONNECTED:
            if (!ledState) {
                setLedHardware(true);
                ledState = true;
            }
            break;
    }
}

// ============================================================================
// RELAY POWER CONTROLLER & POWER-ON BLANKING / STABILIZATION
// ============================================================================
volatile bool coinSlotWarmupActive = true;
static unsigned long relayPowerOnTimeMs = 0;
static unsigned long lineSteadyHighSinceMs = 0;
static unsigned long lastTransientDetectedMs = 0;
static const unsigned long COIN_SLOT_WARMUP_MS = 2500;       // 2500ms power stabilization window for coin acceptor MCU boot
static const unsigned long COIN_SLOT_STEADY_IDLE_MS = 300;   // 300ms steady HIGH confirmation with zero transients
static bool isRelayCurrentlyActive = false;

// Forward declaration of coin detector reset helper
static void resetCoinDetectorStates();

void setRelayHardware(bool active) {
    if (active) {
        if (!isRelayCurrentlyActive) {
            isRelayCurrentlyActive = true;
            coinSlotWarmupActive = true;
            relayPowerOnTimeMs = millis();
            lineSteadyHighSinceMs = 0;
            lastTransientDetectedMs = 0;
            resetCoinDetectorStates();
            Serial.printf("[⚡ RELAY] Power applied to coin slot. Suppressing boot transient pulses for %lums...\n", COIN_SLOT_WARMUP_MS);
        }
        pinMode(relayPin, OUTPUT);
        bool pinLevel = relayActiveLow ? LOW : HIGH;
        digitalWrite(relayPin, pinLevel);
    } else {
        // High Impedance / floating so neither +V nor GND activates it when idle
        pinMode(relayPin, INPUT);
        if (isRelayCurrentlyActive) {
            isRelayCurrentlyActive = false;
            coinSlotWarmupActive = true;
            relayPowerOnTimeMs = 0;
            lineSteadyHighSinceMs = 0;
            lastTransientDetectedMs = 0;
            resetCoinDetectorStates();
            Serial.println("[⚡ RELAY] Coin slot powered down into standby mode.");
        }
    }
}

bool isSlotArmed() {
    return (armedIp.length() > 0 && millis() < armedUntil);
}

void processRelayState() {
    bool shouldBeOn = (relayMode == 0) ? (WiFi.status() == WL_CONNECTED || millis() > 4000) : isSlotArmed();
    static int lastAppliedRelayState = -1;
    int cur = shouldBeOn ? 1 : 0;
    if (cur != lastAppliedRelayState) {
        lastAppliedRelayState = cur;
        setRelayHardware(shouldBeOn);
        Serial.printf("[⚡ RELAY] Pin %d set to %s (ActiveLow=%s, Mode=%d, SlotArmed=%s)\n",
            relayPin, shouldBeOn ? "ON (POWERED)" : "OFF (STANDBY)",
            relayActiveLow ? "true" : "false", relayMode, isSlotArmed() ? "true" : "false");
    }
}

// ============================================================================
// HARDWARE DEBOUNCER STATE MACHINE - LINEAR BEAM SENSOR (GPIO 4)
// ============================================================================
enum CoinState {
    COIN_IDLE,
    COIN_DETECTING,
    COIN_LOCKOUT
};

static CoinState currentCoinState = COIN_IDLE;
static unsigned long pulseStartMs = 0;
static unsigned long lockoutStartMs = 0;
static const unsigned long MIN_PULSE_WIDTH_MS = 20;

// Internal helper to reset states and flush pulse counters
static void resetCoinDetectorStates() {
    noInterrupts();
    isrUniversalPulseCount = 0;
    isrLastPulseTimeMs = 0;
    interrupts();
    pulseTrainStartTime = 0;
    currentCoinState = COIN_IDLE;
    pulseStartMs = 0;
    lockoutStartMs = 0;
}

// State machine to supervise warmup stabilization after relay energizes
static void processCoinSlotWarmup() {
    if (!isRelayCurrentlyActive) {
        coinSlotWarmupActive = true;
        lineSteadyHighSinceMs = 0;
        lastTransientDetectedMs = 0;
        return;
    }

    if (!coinSlotWarmupActive) {
        return; // Already stabilized and ready
    }

    unsigned long now = millis();

    // Check if any line is actively LOW or if an ISR pulse arrived
    bool universalActive = (digitalRead(universalCoinPin) == LOW);
    bool beamActive = (digitalRead(coinPin) == LOW);

    if (universalActive || beamActive || isrUniversalPulseCount > 0) {
        // Boot pulse or contact chatter detected during warmup - flush and record transient time
        noInterrupts();
        isrUniversalPulseCount = 0;
        isrLastPulseTimeMs = 0;
        interrupts();
        pulseTrainStartTime = 0;
        currentCoinState = COIN_IDLE;
        lineSteadyHighSinceMs = 0;
        lastTransientDetectedMs = now;
        return;
    }

    // If still within baseline warmup blanking window, stay in warmup mode
    if (now - relayPowerOnTimeMs < COIN_SLOT_WARMUP_MS) {
        lineSteadyHighSinceMs = 0;
        return;
    }

    // Baseline warmup elapsed: verify both lines remain steady HIGH for COIN_SLOT_STEADY_IDLE_MS
    // and that at least COIN_SLOT_STEADY_IDLE_MS has passed since any transient was observed.
    if (!universalActive && !beamActive) {
        if (lineSteadyHighSinceMs == 0) {
            lineSteadyHighSinceMs = now;
        } else if (now - lineSteadyHighSinceMs >= COIN_SLOT_STEADY_IDLE_MS &&
                   (lastTransientDetectedMs == 0 || now - lastTransientDetectedMs >= COIN_SLOT_STEADY_IDLE_MS)) {
            // Signal lines are genuinely stabilized and quiet!
            resetCoinDetectorStates();
            coinSlotWarmupActive = false;
            Serial.println("[⚡ COIN] Coinslot power stabilized & idle lines verified HIGH. Armed for genuine coins.");
        }
    } else {
        lineSteadyHighSinceMs = 0;
    }
}

void processCoinDetector() {
    processCoinSlotWarmup();
    if (coinSlotWarmupActive) {
        currentCoinState = COIN_IDLE;
        return;
    }

    unsigned long now = millis();
    if (now < 3000) {
        currentCoinState = COIN_IDLE;
        return;
    }

    int pinVal = digitalRead(coinPin);

    switch (currentCoinState) {
        case COIN_IDLE:
            if (pinVal == LOW) {
                pulseStartMs = now;
                currentCoinState = COIN_DETECTING;
            }
            break;

        case COIN_DETECTING:
            if (pinVal == LOW) {
                if (now - pulseStartMs >= MIN_PULSE_WIDTH_MS) {
                    Serial.printf("[⚡ COIN BEAM] Pin %d pulse verified (%lu ms LOW)! Triggering coin event...\n", coinPin, now - pulseStartMs);
                    triggerCoinEvent();
                    lockoutStartMs = now;
                    currentCoinState = COIN_LOCKOUT;
                }
            } else {
                currentCoinState = COIN_IDLE;
            }
            break;

        case COIN_LOCKOUT:
            if (pinVal == HIGH) {
                if (now - lockoutStartMs >= (unsigned long)lockoutDebounceMs) {
                    currentCoinState = COIN_IDLE;
                }
            } else {
                lockoutStartMs = now;
            }
            break;
    }
}

// ============================================================================
// HARDWARE PULSE ISR & DEBOUNCER - UNIVERSAL MULTI-COIN ACCEPTOR (GPIO 3)
// ============================================================================
volatile int isrUniversalPulseCount = 0;
volatile unsigned long isrLastPulseTimeMs = 0;
static const unsigned long U_MIN_PULSE_DEBOUNCE_MS = 8;
static const unsigned long U_INTER_PULSE_TIMEOUT_MS = 280;

void IRAM_ATTR universalCoinIsr() {
    if (coinSlotWarmupActive) {
        return; // Suppress interrupt pulses during coinslot power-on blanking
    }
    unsigned long now = millis();
    if (now - isrLastPulseTimeMs >= U_MIN_PULSE_DEBOUNCE_MS) {
        isrUniversalPulseCount++;
        isrLastPulseTimeMs = now;
    }
}

void processUniversalCoinDetector() {
    processCoinSlotWarmup();
    if (coinSlotWarmupActive) {
        if (isrUniversalPulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
        }
        return;
    }

    unsigned long now = millis();
    if (now < 3000) {
        if (isrUniversalPulseCount > 0) {
            noInterrupts();
            isrUniversalPulseCount = 0;
            interrupts();
        }
        return;
    }

    noInterrupts();
    int count = isrUniversalPulseCount;
    unsigned long lastPulseTime = isrLastPulseTimeMs;
    interrupts();

    if (count > 0 && pulseTrainStartTime == 0) {
        pulseTrainStartTime = lastPulseTime;
        pulseTrainDeviceId = (armedIp.length() > 0) ? armedIp : lastArmedDeviceId;
        pulseTrainDeviceIp = (armedIp.length() > 0) ? getIpFromDeviceId(armedIp) : lastArmedIp;
        pulseTrainWasArmed = isSlotArmed() || (millis() - lastArmedTimeMs < 10000);
    }

    if (count > 0 && (now - lastPulseTime >= U_INTER_PULSE_TIMEOUT_MS)) {
        noInterrupts();
        int finalPulses = isrUniversalPulseCount;
        isrUniversalPulseCount = 0;
        interrupts();

        pulseTrainStartTime = 0;

        if (finalPulses > 0) {
            Serial.printf("[⚡ UNIVERSAL COIN] Detected %d pulse(s) on GPIO %d! Triggering coin event...\n", finalPulses, universalCoinPin);
            triggerUniversalCoinEvent(finalPulses);
        }

        if (pendingWsGracefulClose) {
            pendingWsGracefulClose = false;
            if (isWsConnected && wsClient.connected()) {
                wsClient.stop();
            }
            isWsConnected = false;
            armedIp = "";
            armedUntil = 0;
            sessionStartTime = 0;
            Serial.println("[*] Graceful WS session close completed after delivering final coin pulses.");
        }
    }
}

// ============================================================================
// HARDWARE RESET PIN SUPERVISOR (GPIO 2 -> GND for 5 seconds)
// ============================================================================
static unsigned long resetPinLowStart = 0;

void processHardwareResetPin() {
    if (digitalRead(HARDWARE_RESET_PIN) == LOW) {
        if (resetPinLowStart == 0) {
            resetPinLowStart = millis();
            Serial.println("[⚠️] GPIO 2 connected to GND. Hold for 5 seconds to factory reset...");
        } else if (millis() - resetPinLowStart >= 5000) {
            Serial.println("\n[⚠️ RESET] GPIO 2 held to GND for > 5 seconds! Triggering Factory Reset...");
            factoryResetDefaults();
            delay(1000);
            ESP.restart();
        }
    } else {
        if (resetPinLowStart != 0) {
            Serial.println("[*] GPIO 2 released before 5 seconds. Reset cancelled.");
            resetPinLowStart = 0;
        }
    }
}
