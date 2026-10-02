// Host test for OtaCheck.h. Build and run with host_tests/run.sh (no hardware needed).
#include "../include/OtaCheck.h"
#include <stdio.h>
#include <string.h>

using namespace otacheck;

static int checks = 0;
#define CHECK(c) do { checks++; if (!(c)) { printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

static void header(uint8_t* h, uint8_t segs, uint16_t chip) {
    memset(h, 0, 24);
    h[0] = 0xE9; h[1] = segs; h[12] = chip & 0xFF; h[13] = chip >> 8;
}

int main() {
    uint8_t h[24];
    header(h, 4, CHIP_ESP32_C3);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32_C3) == OK);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32) == WRONG_CHIP);
    header(h, 5, CHIP_ESP32);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32) == OK);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32_C3) == WRONG_CHIP);
    CHECK(checkImageHeader(h, 15, CHIP_ESP32) == TOO_SHORT);
    CHECK(checkImageHeader(h, 0, CHIP_ESP32) == TOO_SHORT);
    h[0] = 0x7F;
    CHECK(checkImageHeader(h, 24, CHIP_ESP32) == BAD_MAGIC);
    header(h, 0, CHIP_ESP32);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32) == BAD_SEGMENTS);
    header(h, 17, CHIP_ESP32);
    CHECK(checkImageHeader(h, 24, CHIP_ESP32) == BAD_SEGMENTS);
    printf("ota_check_test: %d checks passed\n", checks);
    return 0;
}
