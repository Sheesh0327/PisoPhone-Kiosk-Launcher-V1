#include <iostream>
#include <fstream>
#include <string>
#include <cassert>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <iomanip>
#include <sstream>

#include "../include/ProtocolFraming.h"

static std::string calculateHostHMAC(const std::string& data, const std::string& secret) {
    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int result_len = 0;

    HMAC(EVP_sha256(),
         secret.c_str(), secret.length(),
         reinterpret_cast<const unsigned char*>(data.c_str()), data.length(),
         result, &result_len);

    std::ostringstream oss;
    for (unsigned int i = 0; i < result_len; ++i) {
        oss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(result[i]);
    }
    return oss.str();
}

int main() {
    std::cout << "[TEST] Starting PisoPhone C++ Protocol Contract Verification..." << std::endl;

    const std::string secret = "PISOPHONE_SHARED_SECRET_KEY_32B!";

    // 1. Framing Tests
    std::cout << "[TEST] 1. UTF-8 byte-length framing parity:" << std::endl;
    assert(PisoPhone::utf8Frame("hello") == "5:hello");
    assert(PisoPhone::utf8Frame("₱") == "3:₱");      // 3 bytes in UTF-8
    assert(PisoPhone::utf8Frame("Café") == "5:Café"); // 5 bytes in UTF-8
    assert(PisoPhone::utf8Frame("⚡") == "3:⚡");     // 3 bytes in UTF-8
    assert(PisoPhone::utf8Frame("") == "0:");
    assert(PisoPhone::utf8Frame("1800") == "4:1800");
    assert(PisoPhone::utf8Frame("-600") == "4:-600");
    std::cout << "  -> UTF-8 framing tests PASSED." << std::endl;

    // 2. HTTP_REQ Framing & HMAC parity
    std::cout << "[TEST] 2. HTTP_REQ Framing & HMAC tests:" << std::endl;
    std::string httpRaw = PisoPhone::formatHttpReqData("GET", "/add_time", "dev_slot_1", "tx-1700000000-0001", "1700000000000", "4a6f7365");
    std::string expectedHttpRaw = "8:HTTP_REQ3:GET9:/add_time10:dev_slot_118:tx-1700000000-000113:17000000000008:4a6f7365";
    assert(httpRaw == expectedHttpRaw);

    std::string httpHmac = calculateHostHMAC(httpRaw, secret);
    std::string expectedHttpHmac = "ffc8db2a5e62bc36a2d732f0845a592f2edb9d8d51a2c3cb610e8c0cd558c53d";
    assert(PisoPhone::constantTimeCompare(httpHmac, expectedHttpHmac));

    // Tampered endpoint
    std::string tamperedHttpRaw = PisoPhone::formatHttpReqData("GET", "/coin", "dev_slot_1", "tx-1700000000-0001", "1700000000000", "4a6f7365");
    std::string tamperedHttpHmac = calculateHostHMAC(tamperedHttpRaw, secret);
    assert(!PisoPhone::constantTimeCompare(tamperedHttpHmac, expectedHttpHmac));
    std::cout << "  -> HTTP_REQ framing and tampering rejection PASSED." << std::endl;

    // 3. WS_PAY Framing & HMAC parity
    std::cout << "[TEST] 3. WS_PAY Framing & HMAC tests:" << std::endl;
    std::string wsRaw = PisoPhone::formatWsPayData("COIN_DETECTED", "dev_slot_1", "tx-1700000000-0002", "1700000001000", "d3adb33f");
    std::string wsHmac = calculateHostHMAC(wsRaw, secret);
    std::string expectedWsHmac = calculateHostHMAC(
        PisoPhone::utf8Frame("WS_PAY") + PisoPhone::utf8Frame("COIN_DETECTED") +
        PisoPhone::utf8Frame("dev_slot_1") + PisoPhone::utf8Frame("tx-1700000000-0002") +
        PisoPhone::utf8Frame("1700000001000") + PisoPhone::utf8Frame("d3adb33f"),
        secret
    );
    assert(PisoPhone::constantTimeCompare(wsHmac, expectedWsHmac));

    // Tampered event
    std::string tamperedWsRaw = PisoPhone::formatWsPayData("ADMIN_COIN", "dev_slot_1", "tx-1700000000-0002", "1700000001000", "d3adb33f");
    std::string tamperedWsHmac = calculateHostHMAC(tamperedWsRaw, secret);
    assert(!PisoPhone::constantTimeCompare(tamperedWsHmac, expectedWsHmac));
    std::cout << "  -> WS_PAY framing and tampering rejection PASSED." << std::endl;

    // 4. ACK Framing & HMAC parity (including negative adjustment -300)
    std::cout << "[TEST] 4. ACK Framing & HMAC tests:" << std::endl;
    std::string ackRaw = PisoPhone::formatAckData("dev_slot_1", "tx-1700000000-0002", 5, 1800, "1700000001500", "OK");
    std::string expectedAckRaw = "3:ACK10:dev_slot_118:tx-1700000000-00021:54:180013:17000000015002:OK";
    assert(ackRaw == expectedAckRaw);

    std::string ackHmac = calculateHostHMAC(ackRaw, secret);
    std::string expectedAckHmac = "41f60c11dc9336664b600b20cb715e151e54b40abb5d2c7ccea8a0ec8235e20b";
    assert(PisoPhone::constantTimeCompare(ackHmac, expectedAckHmac));

    // Negative adjustment
    std::string negAckRaw = PisoPhone::formatAckData("dev_slot_1", "adj-deduct-001", 0, -300, "1700000003000", "OK");
    std::string negAckHmac = calculateHostHMAC(negAckRaw, secret);
    std::string expectedNegAckHmac = calculateHostHMAC(
        PisoPhone::utf8Frame("ACK") + PisoPhone::utf8Frame("dev_slot_1") +
        PisoPhone::utf8Frame("adj-deduct-001") + PisoPhone::utf8Frame("0") +
        PisoPhone::utf8Frame("-300") + PisoPhone::utf8Frame("1700000003000") +
        PisoPhone::utf8Frame("OK"),
        secret
    );
    assert(PisoPhone::constantTimeCompare(negAckHmac, expectedNegAckHmac));

    // Tampered seconds
    std::string tamperedSecAck = PisoPhone::formatAckData("dev_slot_1", "tx-1700000000-0002", 5, 1801, "1700000001500", "OK");
    std::string tamperedSecHmac = calculateHostHMAC(tamperedSecAck, secret);
    assert(!PisoPhone::constantTimeCompare(tamperedSecHmac, expectedAckHmac));

    // Tampered status
    std::string tamperedStatusAck = PisoPhone::formatAckData("dev_slot_1", "tx-1700000000-0002", 5, 1800, "1700000001500", "MUTATED");
    std::string tamperedStatusHmac = calculateHostHMAC(tamperedStatusAck, secret);
    assert(!PisoPhone::constantTimeCompare(tamperedStatusHmac, expectedAckHmac));

    std::cout << "  -> ACK framing, negative adjustments, and tampering rejection PASSED." << std::endl;

    std::cout << "ALL C++ PROTOCOL CONTRACT TESTS PASSED SUCCESSFULLY!" << std::endl;
    return 0;
}
