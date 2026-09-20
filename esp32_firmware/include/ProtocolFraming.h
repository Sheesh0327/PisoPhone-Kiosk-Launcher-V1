#ifndef PROTOCOL_FRAMING_H
#define PROTOCOL_FRAMING_H

#include <string>
#include <sstream>
#include <cstdint>

#define PISOPHONE_PROTOCOL_VERSION "PISOPHONE-PROTOCOL-V1"

namespace PisoPhone {

/**
 * Pure UTF-8 byte-length prefix framing: "<byte_length>:<field>"
 * Guarantees exact parity with Kotlin UTF-8 byte counting.
 */
inline std::string utf8Frame(const std::string& field) {
    std::ostringstream oss;
    oss << field.size() << ":" << field;
    return oss.str();
}

inline std::string formatHttpReqData(
    const std::string& method,
    const std::string& endpoint,
    const std::string& recipient,
    const std::string& txId,
    const std::string& ts,
    const std::string& payload
) {
    return utf8Frame("HTTP_REQ") +
           utf8Frame(method) +
           utf8Frame(endpoint) +
           utf8Frame(recipient) +
           utf8Frame(txId) +
           utf8Frame(ts) +
           utf8Frame(payload);
}

inline std::string formatWsPayData(
    const std::string& event,
    const std::string& recipient,
    const std::string& txId,
    const std::string& ts,
    const std::string& payload
) {
    return utf8Frame("WS_PAY") +
           utf8Frame(event) +
           utf8Frame(recipient) +
           utf8Frame(txId) +
           utf8Frame(ts) +
           utf8Frame(payload);
}

inline std::string formatAckData(
    const std::string& deviceId,
    const std::string& txId,
    int amount,
    int seconds,
    const std::string& ts,
    const std::string& status
) {
    return utf8Frame("ACK") +
           utf8Frame(deviceId) +
           utf8Frame(txId) +
           utf8Frame(std::to_string(amount)) +
           utf8Frame(std::to_string(seconds)) +
           utf8Frame(ts) +
           utf8Frame(status);
}

inline bool constantTimeCompare(const std::string& a, const std::string& b) {
    if (a.size() != b.size()) return false;
    unsigned char result = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        char ca = (a[i] >= 'A' && a[i] <= 'Z') ? (a[i] + ('a' - 'A')) : a[i];
        char cb = (b[i] >= 'A' && b[i] <= 'Z') ? (b[i] + ('a' - 'A')) : b[i];
        result |= (ca ^ cb);
    }
    return result == 0;
}

} // namespace PisoPhone

#endif // PROTOCOL_FRAMING_H
