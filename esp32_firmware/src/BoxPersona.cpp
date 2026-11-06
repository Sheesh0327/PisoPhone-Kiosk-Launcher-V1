#include "BoxPersona.h"
#include <Preferences.h>
#include <WiFi.h>

static Preferences prefs;
static uint8_t localSlot = 1;
static char localName[16] = "Esmeralda";
static char localRole[12] = "primary";
static uint32_t localEpoch = 1;
static bool initialized = false;

static const char* PERSONA_NAMES[4] = {
    "Esmeralda",
    "Valentina",
    "Isabella",
    "Magdalena"
};

static const char* DEFAULT_IPS[4] = {
    "10.0.0.10",
    "10.0.0.11",
    "10.0.0.12",
    "10.0.0.13"
};

const char* getPersonaForSlot(uint8_t slot) {
    if (slot >= 1 && slot <= 4) {
        return PERSONA_NAMES[slot - 1];
    }
    return "Esmeralda";
}

const char* getDefaultIpForSlot(uint8_t slot) {
    if (slot >= 1 && slot <= 4) {
        return DEFAULT_IPS[slot - 1];
    }
    return "10.0.0.10";
}

void initBoxPersona() {
    if (initialized) return;
    prefs.begin("piso_box", false);
    
    localSlot = prefs.getUChar("slot", 0);
    String n = prefs.getString("name", "");
    String r = prefs.getString("role", "");
    localEpoch = prefs.getUInt("epoch", 1);

    if (localSlot < 1 || localSlot > 4) {
        // Auto-detect slot based on fourth octet of local IP (10 -> 1, 11 -> 2, etc.)
        IPAddress ip = WiFi.localIP();
        uint8_t lastOctet = ip[3];
        if (lastOctet >= 10 && lastOctet <= 13) {
            localSlot = (lastOctet - 10) + 1;
        } else {
            localSlot = 1;
        }
    }

    if (n.length() > 0 && n.length() < sizeof(localName)) {
        strncpy(localName, n.c_str(), sizeof(localName) - 1);
        localName[sizeof(localName) - 1] = '\0';
    } else {
        strncpy(localName, getPersonaForSlot(localSlot), sizeof(localName) - 1);
        localName[sizeof(localName) - 1] = '\0';
    }

    if (r.length() > 0 && r.length() < sizeof(localRole)) {
        strncpy(localRole, r.c_str(), sizeof(localRole) - 1);
        localRole[sizeof(localRole) - 1] = '\0';
    } else {
        strncpy(localRole, (localSlot == 1) ? "primary" : "standby", sizeof(localRole) - 1);
        localRole[sizeof(localRole) - 1] = '\0';
    }

    prefs.putUChar("slot", localSlot);
    prefs.putString("name", localName);
    prefs.putString("role", localRole);
    prefs.putUInt("epoch", localEpoch);
    prefs.end();

    initialized = true;
    Serial.printf("[+] Box Persona: Slot %u (%s), Role: %s, Epoch: %u\n",
                  (unsigned)localSlot, localName, localRole, (unsigned)localEpoch);
}

uint8_t getLocalSlot() {
    if (!initialized) initBoxPersona();
    return localSlot;
}

const char* getLocalPersonaName() {
    if (!initialized) initBoxPersona();
    return localName;
}

const char* getLocalRole() {
    if (!initialized) initBoxPersona();
    return localRole;
}

bool isLocalPrimary() {
    if (!initialized) initBoxPersona();
    return strcmp(localRole, "primary") == 0;
}

uint32_t getLocalEpoch() {
    if (!initialized) initBoxPersona();
    return localEpoch;
}

void setLocalPersona(uint8_t slot, const char* name, const char* role, uint32_t epoch) {
    if (slot >= 1 && slot <= 4) {
        localSlot = slot;
    }
    if (name && strlen(name) > 0) {
        strncpy(localName, name, sizeof(localName) - 1);
        localName[sizeof(localName) - 1] = '\0';
    }
    if (role && strlen(role) > 0) {
        strncpy(localRole, role, sizeof(localRole) - 1);
        localRole[sizeof(localRole) - 1] = '\0';
    }
    if (epoch > 0) {
        localEpoch = epoch;
    }

    prefs.begin("piso_box", false);
    prefs.putUChar("slot", localSlot);
    prefs.putString("name", localName);
    prefs.putString("role", localRole);
    prefs.putUInt("epoch", localEpoch);
    prefs.end();

    Serial.printf("[+] Updated Box Persona: Slot %u (%s), Role: %s, Epoch: %u\n",
                  (unsigned)localSlot, localName, localRole, (unsigned)localEpoch);
}
