#ifndef MONEY_H
#define MONEY_H

// Pure (no Arduino types) money helpers. Amounts are whole centavos in a uint32_t, so sums are exact
// and never drift the way float totals do (host_tests/money_test.cpp).

#include <cmath>
#include <cstdint>
#include <cstdio>

namespace money {

static const uint32_t CENTAVOS_PER_PESO = 100;

// Adds without wrapping: a counter that reaches the ceiling stays there instead of rolling to zero.
inline uint32_t saturatingAdd(uint32_t total, uint32_t add) {
    return (add > UINT32_MAX - total) ? UINT32_MAX : total + add;
}

// One coin pulse is one peso.
inline uint32_t centavosFromPulses(int pulses) {
    if (pulses <= 0) return 0;
    uint64_t c = (uint64_t)pulses * CENTAVOS_PER_PESO;
    return c > UINT32_MAX ? UINT32_MAX : (uint32_t)c;
}

// Converts the old float peso total stored by earlier firmware (one-time migration).
inline uint32_t centavosFromLegacyPesos(float pesos) {
    if (!(pesos > 0.0f)) return 0; // also catches NaN
    double c = std::round((double)pesos * CENTAVOS_PER_PESO);
    return c >= (double)UINT32_MAX ? UINT32_MAX : (uint32_t)c;
}

// Writes "1234.50" into buf (at least 16 bytes) and returns it.
inline const char* formatPesos(uint32_t centavos, char* buf, size_t len) {
    snprintf(buf, len, "%lu.%02u", (unsigned long)(centavos / CENTAVOS_PER_PESO),
             (unsigned)(centavos % CENTAVOS_PER_PESO));
    return buf;
}

} // namespace money

#endif // MONEY_H
