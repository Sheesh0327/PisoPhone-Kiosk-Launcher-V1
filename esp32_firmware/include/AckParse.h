#ifndef ACK_PARSE_H
#define ACK_PARSE_H

// Pure (no Arduino types) reader of a phone's answer to a push (host_tests/ack_parse_test.cpp).
//
// The phone answers "OK:tx_id=<id>:device_id=<id>:ts=<ms>:v_sig=<hex>" (or "ALREADY_PROCESSED:..."), colon-separated key=value pairs.
// It used to be read with indexOf("ts=") and friends, which finds a key inside another key or value ("...xts=" in an id) and
// reads from the wrong place. Here each ':'-separated part is split at its first '=' and only whole keys count.
#include <string>

namespace ack {

struct Fields {
    bool okPrefix = false; // starts with "OK" or "ALREADY_PROCESSED"
    bool hasTx = false, hasDev = false, hasTs = false, hasSig = false;
    std::string tx, dev, ts, sig;
};

inline Fields parseAckBody(const std::string& body) {
    Fields f;
    f.okPrefix = body.compare(0, 2, "OK") == 0 || body.compare(0, 17, "ALREADY_PROCESSED") == 0;
    size_t start = 0;
    while (start <= body.size()) {
        size_t end = body.find(':', start);
        if (end == std::string::npos) end = body.size();
        std::string part = body.substr(start, end - start);
        size_t eq = part.find('=');
        if (eq != std::string::npos) {
            std::string key = part.substr(0, eq), value = part.substr(eq + 1);
            if (key == "tx_id" && !f.hasTx) {
                f.hasTx = true;
                f.tx = value;
            } else if (key == "device_id" && !f.hasDev) {
                f.hasDev = true;
                f.dev = value;
            } else if (key == "ts" && !f.hasTs) {
                f.hasTs = true;
                f.ts = value;
            } else if (key == "v_sig" && !f.hasSig) {
                f.hasSig = true;
                f.sig = value;
            }
        }
        if (end >= body.size()) break;
        start = end + 1;
    }
    return f;
}

} // namespace ack

#endif // ACK_PARSE_H
