#ifndef SERIAL_PROVISION_H
#define SERIAL_PROVISION_H

// First-time setup over USB serial (`PISO-HELLO` / `PISO-SETUP`).
// Pure C++ (no Arduino types), tested on the host.

#include "SetupGate.h"

#include <cstdint>
#include <cstring>
#include <string>

namespace provision {

enum class SetupState { Needed, Joining, Done };

struct BoxFacts {
    bool adminPasswordChanged = false;
    bool wifiWait = false;
    uint32_t lifetimeCoins = 0;
    int pendingPayments = 0;
    bool unpersistedPayments = false;
};

inline SetupState setupState(const BoxFacts& f) {
    if (!f.adminPasswordChanged) return SetupState::Needed;
    bool anyPayment = f.lifetimeCoins > 0 || f.pendingPayments > 0 || f.unpersistedPayments;
    return f.wifiWait && !anyPayment ? SetupState::Joining : SetupState::Done;
}

inline bool acceptsSetup(SetupState s) {
    return s != SetupState::Done;
}

inline const char* stateName(SetupState s) {
    switch (s) {
    case SetupState::Needed:
        return "needed";
    case SetupState::Joining:
        return "joining";
    case SetupState::Done:
        return "done";
    }
    return "done";
}

enum class Parse { Ok, BadFormat, BadWifiName, BadWifiPassword, BadAdminPassword };

struct Request {
    std::string wifiName, wifiPassword, adminPassword;
};

inline int hexVal(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

inline bool hexDecode(const std::string& hex, std::string& out, size_t maxBytes) {
    out.clear();
    if (hex.empty() || hex.size() % 2 != 0 || hex.size() / 2 > maxBytes) return false;
    for (size_t i = 0; i < hex.size(); i += 2) {
        int hi = hexVal(hex[i]), lo = hexVal(hex[i + 1]);
        if (hi < 0 || lo < 0) return false;
        out += static_cast<char>(hi * 16 + lo);
    }
    return true;
}

inline bool printableAscii(const std::string& s) {
    for (unsigned char c : s)
        if (c < 0x20 || c > 0x7E) return false;
    return true;
}

inline Parse parseSetup(const std::string& rest, Request& out) {
    std::string word[3];
    size_t n = 0, pos = 0;
    while (pos < rest.size() && n < 4) {
        size_t sp = rest.find(' ', pos);
        if (sp == std::string::npos) sp = rest.size();
        if (sp > pos) {
            if (n == 3) return Parse::BadFormat;
            word[n++] = rest.substr(pos, sp - pos);
        }
        pos = sp + 1;
    }
    if (n != 3) return Parse::BadFormat;
    if (!hexDecode(word[0], out.wifiName, 32) || !hexDecode(word[1], out.wifiPassword, 63) ||
        !hexDecode(word[2], out.adminPassword, 64))
        return Parse::BadFormat;
    if (!setupgate::wifiSsidValid(out.wifiName.c_str()) || out.wifiName.size() != std::strlen(out.wifiName.c_str()))
        return Parse::BadWifiName;
    if (!setupgate::wifiPasswordValid(out.wifiPassword.c_str()) ||
        out.wifiPassword.size() != std::strlen(out.wifiPassword.c_str()))
        return Parse::BadWifiPassword;
    if (!printableAscii(out.adminPassword) || !setupgate::passwordAcceptable(out.adminPassword.c_str()))
        return Parse::BadAdminPassword;
    return Parse::Ok;
}

inline const char* reason(Parse p) {
    switch (p) {
    case Parse::Ok:
        return "ok";
    case Parse::BadFormat:
        return "bad-format";
    case Parse::BadWifiName:
        return "bad-wifi-name";
    case Parse::BadWifiPassword:
        return "bad-wifi-password";
    case Parse::BadAdminPassword:
        return "bad-admin-password";
    }
    return "bad-format";
}

} // namespace provision

#endif // SERIAL_PROVISION_H
