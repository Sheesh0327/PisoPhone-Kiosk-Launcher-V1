#ifndef GATEWAY_COINSLOT_H
#define GATEWAY_COINSLOT_H

#include <Arduino.h>

static const int GATEWAY_DEFAULT_ARM_SECONDS = 60;
static const int GATEWAY_MIN_ARM_SECONDS = 5;
static const int GATEWAY_MAX_ARM_SECONDS = 120;

enum class GatewayArmResult { Ok, Busy, StorageUnavailable, InvalidSession, SetupRequired };

enum class GatewayAckStatus { Ok, Busy, StorageError };

struct GatewayStatus {
    String state;
    int armedRemainingSec;
    int pulses;
    int minutesPerCoin;
    unsigned long readyInMs;
    bool slotFree;
};

void gatewayInit();
bool gatewayConfigured();
bool gatewaySetKey(const String& key);
String gatewayKey();

GatewayArmResult gatewayArm(const String& session, int durationSec);
void gatewaySetEventTarget(const String& session, const String& wid, IPAddress ip, uint16_t port);
void gatewayEventsLoop();
void gatewayRelease(const String& session);
GatewayStatus gatewayStatus(const String& session);
GatewayAckStatus gatewayAcknowledge(const String& session, int& acknowledgedOut);

#endif // GATEWAY_COINSLOT_H
