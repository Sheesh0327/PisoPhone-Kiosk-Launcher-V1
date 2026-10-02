#ifndef CRED_GEN_H
#define CRED_GEN_H

// Pure (no Arduino types) random password generation for first-boot credentials, so every box gets
// its own setup Wi-Fi and admin passwords. The random source is passed in (esp_fill_random on the
// ESP32, a fake in host_tests/cred_gen_test.cpp).

#include <cstddef>
#include <cstdint>
#include <string>

namespace credgen {

// No look-alike characters (0/O, 1/l/I) so a password read off a label or the serial console is typed right.
static const char ALPHABET[] = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
static const size_t ALPHABET_LEN = sizeof(ALPHABET) - 1;

typedef void (*FillRandom)(uint8_t* buf, size_t len);

// `length` characters, each chosen uniformly: bytes that would bias the choice are thrown away.
inline std::string password(size_t length, FillRandom fill) {
    const unsigned limit = 256 - (256 % ALPHABET_LEN);
    std::string out;
    while (out.size() < length) {
        uint8_t buf[16];
        fill(buf, sizeof(buf));
        for (size_t i = 0; i < sizeof(buf) && out.size() < length; i++) {
            if (buf[i] < limit) out += ALPHABET[buf[i] % ALPHABET_LEN];
        }
    }
    return out;
}

} // namespace credgen

#endif // CRED_GEN_H
