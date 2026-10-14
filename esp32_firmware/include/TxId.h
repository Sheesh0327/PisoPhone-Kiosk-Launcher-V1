#ifndef TX_ID_H
#define TX_ID_H

// Pure (no Arduino types) transaction ids for coin payments (host_tests/tx_id_test.cpp).
//
// A payment is identified by its tx_id on the box, on the phone and in the router listener, and the phone
// ignores an id it has already credited. So two different coins must never get the same id, even across
// reboots, with no clock synced, or after a factory reset. The id is built from:
//   boot  - a counter kept in flash that goes up by one on every start (never repeats between reboots)
//   seq   - a counter that goes up by one for every id made since this start
//   salt  - 32 random bits per id (covers a factory reset, which restarts the boot counter)

#include <cstdint>
#include <cstdio>
#include <string>

namespace txid {

inline std::string make(const std::string& prefix, uint32_t bootCount, uint32_t seq, uint32_t salt) {
    char buf[40];
    snprintf(buf, sizeof(buf), "b%lx-%lu-%08lx", (unsigned long)bootCount, (unsigned long)seq, (unsigned long)salt);
    return prefix + buf;
}

} // namespace txid

#endif // TX_ID_H
