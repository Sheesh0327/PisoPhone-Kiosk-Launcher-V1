#ifndef SERIAL_PROVISION_H
#define SERIAL_PROVISION_H

// First-time setup over the USB cable (the one-file setup program, pisophone_setup.py, speaks this; the rules are tested in
// host_tests/serial_provision_test.cpp). The box has no screen and no access point, so this is how it learns the shop's Wi-Fi.
//
//   host -> box   PISO-HELLO
//   box  -> host  PISO-BOX fw=<version> mac=<box code> setup=<needed|done> wifi=<up|down> ip=<address or ->
//   host -> box   PISO-SETUP <Wi-Fi name> <Wi-Fi password> <admin password>      (each one hex-encoded: no spaces or quoting trouble)
//   box  -> host  PISO-SETUP-OK            (then the box restarts and joins the Wi-Fi)
//              or PISO-SETUP-ERR <reason>
//
// Only a box that has not been set up yet (the admin password is still the published default) accepts PISO-SETUP: a box in use
// ignores it, so someone plugging a cable into a working box cannot change its Wi-Fi or take it over. (A factory reset, or
// writing the software again, makes a box "needed" again; both need the owner at the box.) The same Wi-Fi is given to the rental
// phones (the "Set up a phone" link carries it): in the PisoPhone-only setup (no router) box and phones share the shop's Wi-Fi.
// Pure C++ (no Arduino), tested on the host.

#include "SetupGate.h"

#include <cstring>
#include <string>

namespace provision {

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

// Hex text -> the bytes it stands for; false for odd length, a non-hex character or more than `maxBytes` bytes.
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

// The text after "PISO-SETUP ": three hex words. Every value is checked here, before anything is saved.
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
