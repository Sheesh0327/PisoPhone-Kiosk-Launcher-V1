// Host test for PulseGuard.h, CoinSettle.h, CoinHwApply.h, PinRules.h, SetupGate.h,
// SerialProvision.h, and RestartGuard.h.
#include "../include/CoinHwApply.h"
#include "../include/CoinSettle.h"
#include "../include/PinRules.h"
#include "../include/PulseGuard.h"
#include "../include/RestartGuard.h"
#include "../include/SerialProvision.h"
#include "../include/SetupGate.h"
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

static std::string toHex(const std::string& s) {
    static const char* d = "0123456789abcdef";
    std::string out;
    for (unsigned char c : s) {
        out += d[c >> 4];
        out += d[c & 15];
    }
    return out;
}

int main() {
    // PulseGuard
    CHECK(pulseMinMsClamp(1) == PULSE_MIN_MS_LOWEST);
    CHECK(pulseMinMsClamp(99) == PULSE_MIN_MS_HIGHEST);
    CHECK(pulseMinMsClamp(15) == 15);
    CHECK(pulseIsReal(20000, 12000));
    CHECK(!pulseIsReal(3000, 12000));
    CHECK(!pulseIsReal(PULSE_MAX_US + 1, 12000));
    PulseStats ps{};
    pulseStatsAdd(ps, 1500, 12000);
    pulseStatsAdd(ps, 25000, 12000);
    pulseStatsAdd(ps, 200000, 12000);
    CHECK(ps.n == 3 && ps.shortN == 1 && ps.longN == 1 && ps.minUs == 1500 && ps.maxUs == 200000);

    // CoinSettle (500ms default)
    CHECK(PISO_COIN_SETTLE_MS == 500UL);
    CHECK(coinSettleActive(100, 600));
    CHECK(!coinSettleActive(600, 600));
    CHECK(coinSettleCovers(599, 600));
    CHECK(!coinSettleCovers(600, 600));
    CHECK(coinSettleRemainingMs(250, 600) == 350);
    CHECK(coinSettleExtend(600, 500, 280) == 780);

    // CoinHwApply
    CHECK(coinHwApplyDecision(false, false, false, false, true) == CoinHwApply::NOTHING);
    CHECK(coinHwApplyDecision(true, false, false, false, true) == CoinHwApply::NOW);
    CHECK(coinHwApplyDecision(true, false, false, false, false) == CoinHwApply::LATER);
    CHECK(coinHwApplyDecision(false, false, false, true, true) == CoinHwApply::NOW);

    // PinRules
    CHECK(pinrules::problem(pinrules::Chip::C3, 3, 4, 8, 2) == nullptr);
    CHECK(pinrules::problem(pinrules::Chip::C3, 18, 4, 8, 2) != nullptr);
    CHECK(pinrules::problem(pinrules::Chip::Classic, 3, 4, 2, 15) == nullptr);
    CHECK(pinrules::problem(pinrules::Chip::Classic, 3, 35, 2, 15) != nullptr); // input-only relay pin
    CHECK(pinrules::parsePin("12") == 12 && pinrules::parsePin("12a") == -1 && pinrules::parsePin("") == -1);

    // SetupGate
    CHECK(std::strcmp(setupgate::DEFAULT_WIFI_SSID, "PisoCoinBox") == 0);
    CHECK(std::strcmp(setupgate::DEFAULT_WIFI_PASSWORD, "PisoCoinBox@Setup") == 0);
    CHECK(!setupgate::usageAllowed(false) && setupgate::usageAllowed(true));
    CHECK(!setupgate::passwordAcceptable(setupgate::DEFAULT_PASSWORD));
    CHECK(!setupgate::passwordAcceptable("short"));
    CHECK(setupgate::passwordAcceptable("StrongPass123"));

    // SerialProvision
    provision::BoxFacts f;
    CHECK(provision::setupState(f) == provision::SetupState::Needed);
    CHECK(provision::acceptsSetup(provision::setupState(f)));
    f.adminPasswordChanged = true;
    f.wifiWait = true;
    CHECK(provision::setupState(f) == provision::SetupState::Joining);
    f.lifetimeCoins = 1;
    CHECK(provision::setupState(f) == provision::SetupState::Done);
    CHECK(!provision::acceptsSetup(provision::setupState(f)));

    provision::Request req;
    std::string cmd = toHex("PisoCoinBox") + " " + toHex("SecretPass8") + " " + toHex("AdminPass123");
    CHECK(provision::parseSetup(cmd, req) == provision::Parse::Ok);
    CHECK(req.wifiName == "PisoCoinBox" && req.wifiPassword == "SecretPass8" && req.adminPassword == "AdminPass123");

    // RestartGuard
    CHECK(mayRestart(false, true, 0));
    CHECK(!mayRestart(true, true, 0));
    CHECK(!mayRestart(false, false, 0));
    CHECK(!mayRestart(false, true, 1));
    CHECK(!resetPinMayRestart(true, 4999));
    CHECK(resetPinMayRestart(true, 5000));
    CHECK(!resetPinMayRestart(false, 5000));
    CHECK(resetPinMayRestart(false, 35000));

    printf("coin_pulse_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
