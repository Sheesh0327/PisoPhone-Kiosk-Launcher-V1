#ifndef DISCOVERY_H
#define DISCOVERY_H

#include <Arduino.h>
#include <IPAddress.h>

void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort);
void processUdpDiscovery();

#endif // DISCOVERY_H
