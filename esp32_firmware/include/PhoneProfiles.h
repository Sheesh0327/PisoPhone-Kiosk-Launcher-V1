#ifndef PHONE_PROFILES_H
#define PHONE_PROFILES_H

// Rental phone profiles: a name and its own minutes per peso, for each kind of rental device (a basic phone, a gaming phone, a
// tablet). Each phone slot is given a profile. Profile 0 is the box's standard rate (the "Minutes per P1" setting) and always
// exists; it is not stored here. A slot whose profile is unknown (never set, or deleted) uses the standard rate, so a lookup
// never produces a rate nobody chose. Pure functions, tested on a PC (host_tests/phone_profiles_test.cpp).
//
// Stored as text, one entry per profile: "id|name|minutes;id|name|minutes". The ids are the profiles' identity (a slot refers to
// an id, so renaming or deleting another profile never moves a slot to the wrong rate).

#include <cstdio>
#include <string>

namespace phoneprofiles {

constexpr int MAX_PROFILES = 8;   // besides the standard one
constexpr int MAX_NAME_LEN = 20;  // bytes
constexpr int MIN_MINUTES = 1;    // per peso
constexpr int MAX_MINUTES = 1440; // one day per peso, as the standard setting allows
constexpr int MAX_PROFILE_ID = 255;

struct Profile {
    int id;
    std::string name;
    int minutes;
};

// A name the dashboard and the JSON can carry without escaping trouble: 1..MAX_NAME_LEN bytes, no control characters and none of
// | ; " \ < > &, no space at either end.
inline bool validName(const std::string& name) {
    if (name.empty() || name.size() > (size_t)MAX_NAME_LEN) return false;
    if (name[0] == ' ' || name[name.size() - 1] == ' ') return false;
    for (size_t i = 0; i < name.size(); i++) {
        unsigned char c = (unsigned char)name[i];
        if (c < 0x20 || c == 0x7F) return false;
        if (c == '|' || c == ';' || c == '"' || c == '\\' || c == '<' || c == '>' || c == '&') return false;
    }
    return true;
}

inline bool validMinutes(int minutes) {
    return minutes >= MIN_MINUTES && minutes <= MAX_MINUTES;
}

// A whole number from text ("12", not "", "12a" or "-3"); -1 when it is not one.
inline int parseNumber(const std::string& text) {
    if (text.empty() || text.size() > 5) return -1;
    int value = 0;
    for (size_t i = 0; i < text.size(); i++) {
        if (text[i] < '0' || text[i] > '9') return -1;
        value = value * 10 + (text[i] - '0');
    }
    return value;
}

// (std::to_string is not available in every ESP32 toolchain)
inline std::string numberText(int value) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%d", value);
    return std::string(buf);
}

// Reads the stored text. Entries that are not valid are skipped, ids that repeat keep the first. Returns how many were read.
inline int parseList(const std::string& raw, Profile* out, int maxOut) {
    int n = 0;
    size_t pos = 0;
    while (pos <= raw.size() && n < maxOut) {
        size_t end = raw.find(';', pos);
        if (end == std::string::npos) end = raw.size();
        std::string item = raw.substr(pos, end - pos);
        pos = end + 1;
        size_t p1 = item.find('|');
        size_t p2 = (p1 == std::string::npos) ? std::string::npos : item.find('|', p1 + 1);
        if (p1 == std::string::npos || p2 == std::string::npos) continue;
        int id = parseNumber(item.substr(0, p1));
        std::string name = item.substr(p1 + 1, p2 - p1 - 1);
        int minutes = parseNumber(item.substr(p2 + 1));
        if (id < 1 || id > MAX_PROFILE_ID || !validName(name) || !validMinutes(minutes)) continue;
        bool seen = false;
        for (int i = 0; i < n; i++) {
            if (out[i].id == id) seen = true;
        }
        if (seen) continue;
        out[n].id = id;
        out[n].name = name;
        out[n].minutes = minutes;
        n++;
    }
    return n;
}

inline std::string serializeList(const Profile* list, int n) {
    std::string raw;
    for (int i = 0; i < n; i++) {
        if (i > 0) raw += ";";
        raw += numberText(list[i].id) + "|" + list[i].name + "|" + numberText(list[i].minutes);
    }
    return raw;
}

// The minutes per peso for a profile id. 0, an id that is not in the list and anything else unknown give the standard rate.
inline int rateFor(const Profile* list, int n, int id, int standardMinutes) {
    if (id > 0) {
        for (int i = 0; i < n; i++) {
            if (list[i].id == id && validMinutes(list[i].minutes)) return list[i].minutes;
        }
    }
    return validMinutes(standardMinutes) ? standardMinutes : MIN_MINUTES;
}

inline bool hasId(const Profile* list, int n, int id) {
    for (int i = 0; i < n; i++) {
        if (list[i].id == id) return true;
    }
    return false;
}

// The lowest id from 1 up that no profile uses (0 when all are used).
inline int freeId(const Profile* list, int n) {
    for (int id = 1; id <= MAX_PROFILE_ID; id++) {
        if (!hasId(list, n, id)) return id;
    }
    return 0;
}

// What each slot uses: "0,2,0,1,...", one profile id per slot in slot order. A missing or unreadable entry is 0 (standard).
inline void parseAssign(const std::string& raw, int* ids, int slots) {
    for (int i = 0; i < slots; i++)
        ids[i] = 0;
    size_t pos = 0;
    for (int i = 0; i < slots && pos <= raw.size(); i++) {
        size_t end = raw.find(',', pos);
        if (end == std::string::npos) end = raw.size();
        int id = parseNumber(raw.substr(pos, end - pos));
        ids[i] = (id >= 1 && id <= MAX_PROFILE_ID) ? id : 0;
        pos = end + 1;
    }
}

inline std::string serializeAssign(const int* ids, int slots) {
    std::string raw;
    for (int i = 0; i < slots; i++) {
        if (i > 0) raw += ",";
        raw += numberText(ids[i]);
    }
    return raw;
}

// After the profile list changed: a slot that points to a profile that is gone goes back to the standard rate (0). Returns how many.
inline int sanitizeAssign(int* ids, int slots, const Profile* list, int n) {
    int changed = 0;
    for (int i = 0; i < slots; i++) {
        if (ids[i] != 0 && !hasId(list, n, ids[i])) {
            ids[i] = 0;
            changed++;
        }
    }
    return changed;
}

} // namespace phoneprofiles

#endif
