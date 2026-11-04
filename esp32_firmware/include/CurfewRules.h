#ifndef PISO_CURFEW_RULES_H
#define PISO_CURFEW_RULES_H

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace curfewrules {

enum CurfewReason {
    CURFEW_NONE = 0,
    CURFEW_BEDTIME = 1,
    CURFEW_STUDY = 2
};

struct CurfewConfig {
    bool bedtimeEnabled = true;
    int bedtimeStartMin = 1260; // 21:00 (9:00 PM)
    int bedtimeEndMin = 420;    // 07:00 (7:00 AM)
    bool studyEnabled = false;
    int studyStartMin = 960;    // 16:00 (4:00 PM)
    int studyEndMin = 1080;     // 18:00 (6:00 PM)
};

inline int parseMinutes(const char* str) {
    if (!str || std::strlen(str) < 5) {
        return -1;
    }
    char hBuf[3] = {str[0], str[1], '\0'};
    if (str[2] != ':') {
        return -1;
    }
    char mBuf[3] = {str[3], str[4], '\0'};
    for (int i = 0; i < 2; i++) {
        if (hBuf[i] < '0' || hBuf[i] > '9') return -1;
        if (mBuf[i] < '0' || mBuf[i] > '9') return -1;
    }
    int h = std::atoi(hBuf);
    int m = std::atoi(mBuf);
    if (h < 0 || h > 23 || m < 0 || m > 59) {
        return -1;
    }
    return h * 60 + m;
}

inline void formatMinutes(int mins, char* out, size_t outSize) {
    if (!out || outSize < 6) return;
    if (mins < 0) mins = 0;
    if (mins >= 1440) mins = 1439;
    int h = mins / 60;
    int m = mins % 60;
    std::snprintf(out, outSize, "%02d:%02d", h, m);
}

inline bool isCurfewActive(int currentMinutes, int startMinutes, int endMinutes, bool enabled) {
    if (!enabled) return false;
    if (startMinutes == endMinutes) return false;
    if (currentMinutes < 0 || currentMinutes >= 1440) return false;
    if (startMinutes < endMinutes) {
        return (currentMinutes >= startMinutes && currentMinutes < endMinutes);
    } else {
        // Midnight wrap-around: e.g. 21:00 (1260) to 07:00 (420)
        return (currentMinutes >= startMinutes || currentMinutes < endMinutes);
    }
}

inline bool isAnyCurfewActive(const CurfewConfig& cfg, int currentMinutes, CurfewReason* outReason) {
    if (outReason) *outReason = CURFEW_NONE;
    if (isCurfewActive(currentMinutes, cfg.bedtimeStartMin, cfg.bedtimeEndMin, cfg.bedtimeEnabled)) {
        if (outReason) *outReason = CURFEW_BEDTIME;
        return true;
    }
    if (isCurfewActive(currentMinutes, cfg.studyStartMin, cfg.studyEndMin, cfg.studyEnabled)) {
        if (outReason) *outReason = CURFEW_STUDY;
        return true;
    }
    return false;
}

} // namespace curfewrules

#endif // PISO_CURFEW_RULES_H
