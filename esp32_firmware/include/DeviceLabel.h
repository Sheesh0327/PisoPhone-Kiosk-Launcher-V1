#ifndef DEVICE_LABEL_H
#define DEVICE_LABEL_H

// The label a phone shows as the title of its lock and home screens (feature 024): the name of the phone profile its slot has, so a
// tablet says PisoTab and a gaming phone says PisoPhone Pro. Display only: the profile's rate is never touched here. Pure functions,
// tested on a PC (host_tests/device_label_test.cpp).
//
// A slot on the standard rate (profile 0) or on a profile that no longer exists has NO label: its phone keeps showing the box-wide
// header title the owner set (feature 013) or the default "PisoPhone". A label is the profile's own name, already limited by
// phoneprofiles::validName (1 to 20 bytes, no | ; " \ < > &), so it needs no cleaning here.

#include <string>

#include "PhoneProfiles.h"
#include "ShopBranding.h"

namespace devicelabel {

// The three presets the admin page offers with one tap; any other valid name works too.
constexpr int PRESET_COUNT = 3;
inline const char* presetName(int i) {
    static const char* const names[PRESET_COUNT] = {"PisoPhone", "PisoTab", "PisoPhone Pro"};
    return (i >= 0 && i < PRESET_COUNT) ? names[i] : "";
}

// The label of a slot that has profile `profileId`: its name, or "" for the standard rate and for an unknown profile.
inline std::string labelFor(const phoneprofiles::Profile* list, int n, int profileId) {
    if (profileId <= 0) return "";
    for (int i = 0; i < n; i++)
        if (list[i].id == profileId) return list[i].name;
    return "";
}

// The params of the label push, a /config push of its own (the branding push's buffer is nearly full). `label=""` is sent on purpose
// when the owner has just changed profiles, so a phone whose slot went back to the standard rate drops its old label.
inline std::string pushBody(const std::string& label) {
    return "device_label=" + shopbranding::urlEncode(label);
}

// True when the label push fits the box's 240-character limit for push params (a 20-byte name encodes to at most 60 characters).
inline bool fitsInPush(const std::string& label) {
    return (int)pushBody(label).size() <= shopbranding::MAX_PUSH_CHARS;
}

} // namespace devicelabel

#endif // DEVICE_LABEL_H
