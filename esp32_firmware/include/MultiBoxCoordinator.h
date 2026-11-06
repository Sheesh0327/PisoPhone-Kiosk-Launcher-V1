#ifndef MULTI_BOX_COORDINATOR_H
#define MULTI_BOX_COORDINATOR_H

#include <Arduino.h>
#include "BoxPersona.h"

void initMultiBoxCoordinator();
void multiBoxCoordinatorLoop();

const BoxInfo* getBoxInfo(uint8_t slot);
int getOnlineBoxCount();
String getBoxesJson();

// Slot operations (local or remote)
bool armSlot(uint8_t slot, const String& session, int duration, String& out_err);
bool getSlotStatus(uint8_t slot, const String& session, int& out_pulses, int& out_remaining, String& out_state, String& out_err);
bool releaseSlot(uint8_t slot, const String& session);
int ackSlot(uint8_t slot, const String& session);

// Router uhttpd CGI interaction (/cgi-bin/piso-nds)
bool grantRouterAccess(const String& mac, int minutes, uint32_t upKbps, uint32_t downKbps, String& out_err);
bool deauthRouterAccess(const String& mac, String& out_err);

#endif // MULTI_BOX_COORDINATOR_H
