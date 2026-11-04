// Curfew & Bedtime rules for PisoHome (include/CurfewRules.h, feature 064).
#include <cstdio>
#include <string>

#include "../include/CurfewRules.h"

using namespace curfewrules;

static int failures = 0;
#define CHECK(cond, msg)                                                                                               \
    do {                                                                                                               \
        if (!(cond)) {                                                                                                 \
            std::printf("FAIL: %s\n", msg);                                                                            \
            failures++;                                                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // 1. Time parsing and formatting
    CHECK(parseMinutes("00:00") == 0, "parse 00:00");
    CHECK(parseMinutes("07:00") == 420, "parse 07:00");
    CHECK(parseMinutes("21:00") == 1260, "parse 21:00");
    CHECK(parseMinutes("23:59") == 1439, "parse 23:59");
    CHECK(parseMinutes("") == -1, "parse empty fails");
    CHECK(parseMinutes("24:00") == -1, "parse 24:00 fails");
    CHECK(parseMinutes("12:60") == -1, "parse 12:60 fails");
    CHECK(parseMinutes("invalid") == -1, "parse invalid string fails");

    char buf[8];
    formatMinutes(0, buf, sizeof(buf));
    CHECK(std::string(buf) == "00:00", "format 0 -> 00:00");
    formatMinutes(420, buf, sizeof(buf));
    CHECK(std::string(buf) == "07:00", "format 420 -> 07:00");
    formatMinutes(1260, buf, sizeof(buf));
    CHECK(std::string(buf) == "21:00", "format 1260 -> 21:00");
    formatMinutes(1439, buf, sizeof(buf));
    CHECK(std::string(buf) == "23:59", "format 1439 -> 23:59");

    // 2. Daytime window (e.g. Study curfew: 16:00 (960) to 18:00 (1080))
    CHECK(!isCurfewActive(959, 960, 1080, true), "daytime: before window is inactive");
    CHECK(isCurfewActive(960, 960, 1080, true), "daytime: start minute is active");
    CHECK(isCurfewActive(1000, 960, 1080, true), "daytime: inside window is active");
    CHECK(isCurfewActive(1079, 960, 1080, true), "daytime: last minute is active");
    CHECK(!isCurfewActive(1080, 960, 1080, true), "daytime: end minute is inactive");
    CHECK(!isCurfewActive(1100, 960, 1080, true), "daytime: after window is inactive");

    // 3. Overnight window (e.g. Bedtime curfew: 21:00 (1260) to 07:00 (420))
    CHECK(!isCurfewActive(1259, 1260, 420, true), "overnight: evening before window is inactive");
    CHECK(isCurfewActive(1260, 1260, 420, true), "overnight: 21:00 start minute is active");
    CHECK(isCurfewActive(1439, 1260, 420, true), "overnight: 23:59 before midnight is active");
    CHECK(isCurfewActive(0, 1260, 420, true), "overnight: 00:00 midnight is active");
    CHECK(isCurfewActive(419, 1260, 420, true), "overnight: 06:59 morning is active");
    CHECK(!isCurfewActive(420, 1260, 420, true), "overnight: 07:00 morning boundary is inactive");
    CHECK(!isCurfewActive(720, 1260, 420, true), "overnight: 12:00 noon is inactive");

    // 4. Disabled flag
    CHECK(!isCurfewActive(1300, 1260, 420, false), "disabled window is never active");
    CHECK(!isCurfewActive(1000, 960, 1080, false), "disabled daytime is never active");

    // 5. Zero-length window
    CHECK(!isCurfewActive(500, 500, 500, true), "equal start and end is inactive");

    // 6. CurfewConfig & isAnyCurfewActive
    CurfewConfig cfg;
    cfg.bedtimeEnabled = true;
    cfg.bedtimeStartMin = 1260; // 21:00
    cfg.bedtimeEndMin = 420;    // 07:00
    cfg.studyEnabled = true;
    cfg.studyStartMin = 960; // 16:00
    cfg.studyEndMin = 1080;  // 18:00

    CurfewReason reason = CURFEW_NONE;
    CHECK(isAnyCurfewActive(cfg, 1300, &reason) && reason == CURFEW_BEDTIME, "bedtime detected at 21:40");
    CHECK(isAnyCurfewActive(cfg, 1000, &reason) && reason == CURFEW_STUDY, "study detected at 16:40");
    CHECK(!isAnyCurfewActive(cfg, 800, &reason) && reason == CURFEW_NONE, "no curfew at 13:20");

    // When study is disabled, only bedtime triggers
    cfg.studyEnabled = false;
    CHECK(!isAnyCurfewActive(cfg, 1000, &reason) && reason == CURFEW_NONE, "study ignored when disabled");

    if (failures > 0) {
        std::printf("TOTAL FAILURES: %d\n", failures);
        return 1;
    }
    std::printf("OK: all curfew rules checks passed\n");
    return 0;
}
