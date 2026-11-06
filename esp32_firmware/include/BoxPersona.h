#ifndef BOX_PERSONA_H
#define BOX_PERSONA_H

#include <Arduino.h>

struct BoxInfo {
    uint8_t slot;         // 1..4
    char name[16];        // e.g. "Esmeralda", "Valentina", "Isabella", "Magdalena"
    char ip[16];          // e.g. "10.0.0.10", "10.0.0.11", etc.
    char role[12];        // "primary" or "standby"
    bool online;
    uint32_t lastSeenMs;
};

void initBoxPersona();
uint8_t getLocalSlot();
const char* getLocalPersonaName();
const char* getLocalRole();
bool isLocalPrimary();
uint32_t getLocalEpoch();
void setLocalPersona(uint8_t slot, const char* name, const char* role, uint32_t epoch);
const char* getPersonaForSlot(uint8_t slot);
const char* getDefaultIpForSlot(uint8_t slot);

#endif // BOX_PERSONA_H
