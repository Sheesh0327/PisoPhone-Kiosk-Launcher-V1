#ifndef DIAG_RING_H
#define DIAG_RING_H

// Fixed-size ring of recent log lines. No heap and no Arduino dependency, so it is unit-tested on
// the host (host_tests). The caller provides locking when several tasks write.

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

template <size_t Lines, size_t LineLen>
class DiagRing {
public:
    // Stores "<seconds>.<millis> <message>", truncated to the line size on a UTF-8 boundary, with
    // leading and trailing line breaks removed.
    void push(unsigned long uptimeMs, const char* message) {
        const char* msg = message ? message : "";
        while (*msg == '\n' || *msg == '\r') msg++;
        char* slot = lines_[head_];
        int n = snprintf(slot, LineLen, "%lu.%03lu %s", uptimeMs / 1000UL, uptimeMs % 1000UL, msg);
        size_t len = n < 0 ? 0 : (size_t)n;
        if (len >= LineLen) {
            len = LineLen - 1;
            len = trimPartialUtf8(slot, len);
        }
        while (len > 0 && (slot[len - 1] == '\n' || slot[len - 1] == '\r')) len--;
        slot[len] = '\0';
        head_ = (head_ + 1) % Lines;
        total_++;
    }

    size_t size() const { return total_ < Lines ? (size_t)total_ : Lines; }
    uint32_t total() const { return total_; }

    // i = 0 is the oldest retained line.
    const char* at(size_t i) const {
        size_t start = total_ < Lines ? 0 : head_;
        return lines_[(start + i) % Lines];
    }

    void clear() {
        head_ = 0;
        total_ = 0;
        for (size_t i = 0; i < Lines; i++) lines_[i][0] = '\0';
    }

private:
    // If the last character was cut in half, drop the partial bytes so the text stays valid UTF-8
    // (a broken sequence would make the whole JSON response invalid).
    static size_t trimPartialUtf8(const char* s, size_t len) {
        size_t p = len;
        while (p > 0 && ((unsigned char)s[p - 1] & 0xC0) == 0x80) p--;  // continuation bytes
        if (p == 0) return len;
        unsigned char lead = (unsigned char)s[p - 1];
        size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        if (need > 1 && (p - 1) + need > len) return p - 1;
        return len;
    }

    char lines_[Lines][LineLen] = {};
    size_t head_ = 0;
    uint32_t total_ = 0;
};

#endif // DIAG_RING_H
