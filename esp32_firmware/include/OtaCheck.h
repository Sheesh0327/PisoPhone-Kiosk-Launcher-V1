#ifndef OTA_CHECK_H
#define OTA_CHECK_H

// Portable so it is covered by host_tests/ota_check_test.cpp.
#include <stddef.h>
#include <stdint.h>

namespace otacheck {

static const uint16_t CHIP_ESP32 = 0x0000;
static const uint16_t CHIP_ESP32_C3 = 0x0005;

enum Result { OK, TOO_SHORT, BAD_MAGIC, BAD_SEGMENTS, WRONG_CHIP };

// Checks the 24-byte ESP image header of an uploaded firmware: magic byte, a sane segment count and
// the chip id, so a file built for another board (or not firmware at all) is refused before
// anything is written to flash.
inline Result checkImageHeader(const uint8_t* buf, size_t len, uint16_t expectedChip) {
    if (len < 16) return TOO_SHORT;
    if (buf[0] != 0xE9) return BAD_MAGIC;
    if (buf[1] == 0 || buf[1] > 16) return BAD_SEGMENTS;
    uint16_t chip = (uint16_t)(buf[12] | (buf[13] << 8));
    if (chip != expectedChip) return WRONG_CHIP;
    return OK;
}

inline const char* describe(Result r) {
    switch (r) {
    case OK:
        return "ok";
    case TOO_SHORT:
        return "file too short to be firmware";
    case BAD_MAGIC:
        return "not an ESP32 firmware image";
    case BAD_SEGMENTS:
        return "corrupt firmware header";
    case WRONG_CHIP:
        return "firmware is built for a different chip";
    }
    return "unknown";
}

} // namespace otacheck

#endif // OTA_CHECK_H
