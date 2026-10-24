#ifndef DISCOVERY_H
#define DISCOVERY_H

#include <Arduino.h>
#include <IPAddress.h>

// `alsoBroadcast`: also send a copy to the broadcast address (port 8888), for a phone whose listener is not the one that probed.
void sendUdpDiscoveryResponse(IPAddress targetIp, uint16_t targetPort, bool alsoBroadcast = true);
void processUdpDiscovery();

#endif // DISCOVERY_H
