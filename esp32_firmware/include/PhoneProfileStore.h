#ifndef PHONE_PROFILE_STORE_H
#define PHONE_PROFILE_STORE_H

// The rental phone profiles on the box (the rules are in PhoneProfiles.h): saved in the box's settings memory, one profile for
// each phone slot, and the rate a coin is worth for a given phone.

#include <Arduino.h>

#include "PhoneProfiles.h"

// Reads the profiles and the slots' profiles from the settings memory (call while the settings are open for reading).
void loadPhoneProfilesFromPrefs();
// A factory reset forgets them (every slot uses the standard rate again).
void resetPhoneProfiles();

int phoneProfileList(phoneprofiles::Profile* out, int maxOut);
// Saves a new list; a slot that used a profile that is no longer in it goes back to the standard rate. False: not saved.
bool phoneProfilesSave(const phoneprofiles::Profile* list, int n);
// 0 = the standard rate.
int phoneProfileIdForSlotIndex(int slotIdx);
bool phoneProfileAssign(int slotIdx, int profileId);

// Minutes per peso for a slot (0-based index) and for a phone, found by its device ID / address. A phone that is not in a slot
// gets the standard rate.
int minutesPerCoinForSlotIndex(int slotIdx);
int minutesPerCoinForDevice(const String& devId, const String& ip);

// {"standard":N,"max":8,"profiles":[{"id":1,"name":"..","minutes":6}],"slots":[0,1,...]}
String phoneProfilesJson();
// Tells every paired phone its own rate (a rate-only update).
void pushRatesToPhones();
// Tells one phone its rate.
void pushRateToSlotIndex(int slotIdx);

#endif
