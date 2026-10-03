#ifndef GATEWAY_COINSLOT_H
#define GATEWAY_COINSLOT_H

#include <Arduino.h>

// Network-facing access to the universal coin slot for an external gateway (for example an OpenNDS
// router that must verify a payment before letting a client onto the Wi-Fi).
//
// This module owns the gateway's view of the coin slot: it reserves the slot through
// CoinSlotManager (the same single-owner lock phones and controllers use), records the coins that
// arrive as durable payments, and reports them back. It knows nothing about HTTP; see
// WebServerGateway.cpp for the network API. It never touches phone sessions or phone credit.
//
// Lifecycle of one payment session (id chosen by the gateway):
//   gatewayArm -> customer inserts coins -> gatewayStatus (pulses so far)
//   -> gatewayRelease (drains in-flight coins) -> gatewayAcknowledge (after granting access)
// Coins stay queued, and survive a reboot, until acknowledged.

static const int GATEWAY_DEFAULT_ARM_SECONDS = 60;
static const int GATEWAY_MIN_ARM_SECONDS = 5;
static const int GATEWAY_MAX_ARM_SECONDS = 120; // CoinSlotManager caps any session at 120 s

enum class GatewayArmResult { Ok, Busy, StorageUnavailable, InvalidSession };

struct GatewayStatus {
    String state;          // "armed", "draining" or "idle"
    int armedRemainingSec; // seconds left while armed, otherwise 0
    int pulses;            // coins received and not yet acknowledged for this session
    int minutesPerCoin;    // the box's coin-to-time rate, for the gateway to convert pulses
    bool slotFree;         // nobody (phone, controller or any gateway session) holds the coin slot right now
};

void gatewayInit();                    // load the key from flash; call once after loadAllConfig()
bool gatewayConfigured();              // false until an admin has set a key (feature is off)
bool gatewaySetKey(const String& key); // admin only; empty clears (disables) the gateway
String gatewayKey();                   // copy of the key for request verification

GatewayArmResult gatewayArm(const String& session, int durationSec);
void gatewayRelease(const String& session);
GatewayStatus gatewayStatus(const String& session);
int gatewayAcknowledge(const String& session); // returns the pulses just acknowledged

#endif // GATEWAY_COINSLOT_H
