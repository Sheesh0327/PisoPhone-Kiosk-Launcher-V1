// Cryptographic helpers: HMAC signing, AES payload encryption, slot token verification,
// the box machine code and the WebSocket handshake accept key.

#include "Security.h"
#include "Config.h"
#include "LicenseCrypto.h"
#include "ProtocolCrypto.h"
#include "LicensePubKey.h"
#include "esp_mac.h"
#include "mbedtls/md.h"
#include "mbedtls/sha1.h"
#include "mbedtls/base64.h"
#include "mbedtls/aes.h"

static String sha256Hex(const String& in) {
    unsigned char out[32];
    mbedtls_md(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), (const unsigned char*)in.c_str(), in.length(), out);
    String hex;
    for (int i = 0; i < 32; i++) {
        char b[3];
        snprintf(b, sizeof(b), "%02x", out[i]);
        hex += b;
    }
    return hex;
}

String calculateHMAC(String challenge, String secret) {
    return String(protocol::hmacHex(challenge.c_str(), secret.c_str()).c_str());
}

static void grantSlots(int s) {
    maxLicensedSlots = min(max(maxLicensedSlots, s), MAX_SUPPORTED_SLOTS);
    for (int i = 0; i < maxLicensedSlots; i++) {
        licenseSlots[i].active = true;
    }
    saveSlotLicenses();
    Serial.printf("[+] Slot license applied: capacity is now %d slots\n", maxLicensedSlots);
}

bool applySlotToken(String token) {
    token.trim();
    if (token.length() == 0) return false;

    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4],
                 mac[5]);
        macAddressStr = String(macBuf);
    }

    // Signed license (checked against the owner's public key). Once a key is built in, this is the only way in.
    if (LICENSE_PUBKEY_LEN > 0) {
        uint32_t slots = 0;
        auto r = licensecrypto::checkToken(token.c_str(), macAddressStr.c_str(), LICENSE_PUBKEY_DER, LICENSE_PUBKEY_LEN,
                                           MAX_SUPPORTED_SLOTS, slots);
        if (r == licensecrypto::LicenseCheck::Ok) {
            grantSlots((int)slots);
            return true;
        }
        Serial.printf("[-] License rejected (code %d)\n", (int)r);
        return false;
    }

    // DEPRECATED: no public key built in yet, so the old shared-secret keys still work.
    // Remove once every box has been flashed with a LicensePubKey.h from `generate_license.py keygen`.
    Serial.println("[!] No license public key in this firmware: accepting the deprecated shared-secret key.");
    token.toUpperCase();

    String myMac = macAddressStr;
    myMac.trim();
    myMac.toUpperCase();

    // Canonical Clean MAC (12 hex chars)
    String cleanMac = "";
    for (size_t i = 0; i < myMac.length(); i++) {
        if (myMac[i] != ':') cleanMac += myMac[i];
    }
    if (cleanMac.length() == 0) return false;

    String secKey = getLegacyLicenseSecret();

    // Canonical Single Verification Path: Match target slot count (1..MAX_SUPPORTED_SLOTS)
    for (int s = 1; s <= MAX_SUPPORTED_SLOTS; s++) {
        String payload = "PISOSLOT:" + cleanMac + ":" + String(s);
        String expectedSig = calculateHMAC(payload, secKey);
        expectedSig.toUpperCase();

        String shortSig = expectedSig.substring(0, 8);
        String fullToken = "PISOSLOT." + cleanMac + "." + String(s) + "." + shortSig;
        String fullTokenLong = "PISOSLOT." + cleanMac + "." + String(s) + "." + expectedSig;

        if (token.equalsIgnoreCase(shortSig) || token.equalsIgnoreCase(fullToken) ||
            token.equalsIgnoreCase(fullTokenLong) || token.equalsIgnoreCase(expectedSig)) {
            grantSlots(s);
            return true;
        }
    }

    Serial.println("[-] Invalid slot token signature mismatch!");
    return false;
}

String getBoxMachineCode() {
    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4],
                 mac[5]);
        macAddressStr = String(macBuf);
    }
    String myMac = macAddressStr;
    myMac.trim();
    myMac.toUpperCase();
    String cleanMac = "";
    for (size_t i = 0; i < myMac.length(); i++) {
        if (myMac[i] != ':') cleanMac += myMac[i];
    }
    if (cleanMac.length() == 0) cleanMac = "000000000000";
    // Typing-mistake check only; it is not a secret.
    String payload = "BOXREQ:" + cleanMac + ":" + String(maxLicensedSlots) + ":" + String(MAX_SUPPORTED_SLOTS);
    String sig = sha256Hex(payload).substring(0, 4);
    sig.toUpperCase();
    return "PISO-" + cleanMac + "-" + String(maxLicensedSlots) + "-" + String(MAX_SUPPORTED_SLOTS) + "-" + sig;
}

String aes_encrypt(String plaintext, String secret) {
    uint8_t iv[16];
    esp_fill_random(iv, sizeof(iv));
    return String(protocol::encryptHex(plaintext.c_str(), secret.c_str(), iv).c_str());
}

String computeSecWebSocketAccept(String key) {
    key.trim();
    String concat = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    unsigned char sha1Result[20];
    mbedtls_sha1((const unsigned char*)concat.c_str(), concat.length(), sha1Result);

    unsigned char base64Result[36];
    size_t outLen = 0;
    mbedtls_base64_encode(base64Result, sizeof(base64Result), &outLen, sha1Result, 20);
    base64Result[outLen] = 0;
    return String((char*)base64Result);
}
