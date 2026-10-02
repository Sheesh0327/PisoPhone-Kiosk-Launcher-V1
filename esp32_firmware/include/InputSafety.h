#ifndef INPUT_SAFETY_H
#define INPUT_SAFETY_H

// Portable (no Arduino types) so it is covered by host_tests/input_safety_test.cpp.
#include <stddef.h>
#include <stdint.h>
#include <string>

namespace inputsafety {

// Escapes a value for use inside a JSON string literal (RFC 8259). Control characters become \u00XX.
inline std::string jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    static const char* hex = "0123456789abcdef";
    for (size_t i = 0; i < in.size(); i++) {
        unsigned char c = (unsigned char)in[i];
        switch (c) {
        case '"':
            out += "\\\"";
            break;
        case '\\':
            out += "\\\\";
            break;
        case '\n':
            out += "\\n";
            break;
        case '\r':
            out += "\\r";
            break;
        case '\t':
            out += "\\t";
            break;
        default:
            if (c < 0x20) {
                out += "\\u00";
                out += hex[c >> 4];
                out += hex[c & 0xF];
            } else {
                out += (char)c;
            }
        }
    }
    return out;
}

// Display names come from the network. Keep letters, digits, space and a few safe separators
// (UTF-8 bytes are kept so non-English names survive); drop markup/quoting characters and control
// characters, and cap the length without cutting a UTF-8 sequence in half.
inline std::string sanitizeName(const std::string& in, size_t maxLen = 32) {
    std::string out;
    for (size_t i = 0; i < in.size(); i++) {
        unsigned char c = (unsigned char)in[i];
        if (c < 0x20 || c == 0x7F) continue;
        if (c == '<' || c == '>' || c == '"' || c == '\'' || c == '&' || c == '\\' || c == '`') continue;
        out += (char)c;
    }
    size_t b = 0, e = out.size();
    while (b < e && out[b] == ' ')
        b++;
    while (e > b && out[e - 1] == ' ')
        e--;
    out = out.substr(b, e - b);
    if (out.size() > maxLen) {
        size_t cut = maxLen;
        while (cut > 0 && ((unsigned char)out[cut] & 0xC0) == 0x80)
            cut--;
        out.resize(cut);
    }
    return out;
}

// Per-client failed-login counter. Five wrong passwords lock that client out for a minute.
class LoginThrottle {
public:
    static const int MAX_FAILS = 5;
    static const unsigned long LOCK_MS = 60000UL;
    static const unsigned long WINDOW_MS = 120000UL; // failures older than this are forgotten

    // Milliseconds left on the lockout, 0 when the client may try.
    unsigned long lockedForMs(uint32_t client, unsigned long now) const {
        for (int i = 0; i < SLOTS; i++) {
            if (e_[i].used && e_[i].client == client && e_[i].lockUntil != 0) {
                long left = (long)(e_[i].lockUntil - now);
                if (left > 0) return (unsigned long)left;
            }
        }
        return 0;
    }

    void recordFailure(uint32_t client, unsigned long now) {
        Entry& e = find(client, now);
        if (e.fails > 0 && now - e.lastFail > WINDOW_MS) e.fails = 0;
        if (e.lockUntil != 0 && (long)(e.lockUntil - now) <= 0) {
            e.lockUntil = 0;
            e.fails = 0;
        }
        e.fails++;
        e.lastFail = now;
        if (e.fails >= MAX_FAILS) {
            e.lockUntil = now + LOCK_MS;
            if (e.lockUntil == 0) e.lockUntil = 1;
            e.fails = 0;
        }
    }

    void recordSuccess(uint32_t client) {
        for (int i = 0; i < SLOTS; i++) {
            if (e_[i].used && e_[i].client == client) e_[i] = Entry();
        }
    }

private:
    struct Entry {
        bool used = false;
        uint32_t client = 0;
        int fails = 0;
        unsigned long lastFail = 0;
        unsigned long lockUntil = 0;
    };
    static const int SLOTS = 4;
    Entry e_[SLOTS];

    Entry& find(uint32_t client, unsigned long now) {
        int oldest = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (e_[i].used && e_[i].client == client) return e_[i];
            if (!e_[i].used) {
                oldest = i;
                break;
            }
            if (e_[i].lastFail < e_[oldest].lastFail) oldest = i;
        }
        e_[oldest] = Entry();
        e_[oldest].used = true;
        e_[oldest].client = client;
        e_[oldest].lastFail = now;
        return e_[oldest];
    }
};

} // namespace inputsafety

#ifdef ARDUINO
#include <Arduino.h>
// Arduino String wrappers used by the web handlers.
inline String jsonEsc(const String& s) {
    return String(inputsafety::jsonEscape(std::string(s.c_str())).c_str());
}
inline String cleanName(const String& s) {
    return String(inputsafety::sanitizeName(std::string(s.c_str())).c_str());
}
#endif

#endif // INPUT_SAFETY_H
