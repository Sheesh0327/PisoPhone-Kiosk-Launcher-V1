#include "Security.h"
#include "ProtocolFraming.h"
#include "Config.h"
#include "esp_mac.h"
#include "mbedtls/md.h"
#include "mbedtls/sha1.h"
#include "mbedtls/base64.h"
#include "mbedtls/aes.h"

String calculateHMAC(String challenge, String secret) {
    mbedtls_md_context_t ctx;
    mbedtls_md_type_t md_type = MBEDTLS_MD_SHA256;
    mbedtls_md_init(&ctx);
    mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(md_type), 1);
    mbedtls_md_hmac_starts(&ctx, (const unsigned char*) secret.c_str(), secret.length());
    mbedtls_md_hmac_update(&ctx, (const unsigned char*) challenge.c_str(), challenge.length());
    unsigned char hmacResult[32];
    mbedtls_md_hmac_finish(&ctx, hmacResult);
    mbedtls_md_free(&ctx);
    
    String hex = "";
    for (int i = 0; i < 32; i++) {
        char buf[3];
        sprintf(buf, "%02x", hmacResult[i]);
        hex += buf;
    }
    return hex;
}

static String lengthPrefixedField(const String& field) {
    return String(field.length()) + ":" + field;
}

String calculateHttpReqSignature(
    const String& method,
    const String& endpoint,
    const String& recipient,
    const String& txId,
    const String& ts,
    const String& payload,
    const String& secret
) {
    std::string framed = PisoPhone::formatHttpReqData(
        method.c_str(), endpoint.c_str(), recipient.c_str(),
        txId.c_str(), ts.c_str(), payload.c_str()
    );
    return calculateHMAC(String(framed.c_str()), secret);
}

bool verifyHttpReqSignature(
    const String& method,
    const String& endpoint,
    const String& recipient,
    const String& txId,
    const String& ts,
    const String& payload,
    const String& sig,
    const String& secret
) {
    if (sig.length() == 0) return false;
    String expected = calculateHttpReqSignature(method, endpoint, recipient, txId, ts, payload, secret);
    return PisoPhone::constantTimeCompare(sig.c_str(), expected.c_str());
}

String calculateWsPaySignature(
    const String& event,
    const String& recipient,
    const String& txId,
    const String& ts,
    const String& payload,
    const String& secret
) {
    std::string framed = PisoPhone::formatWsPayData(
        event.c_str(), recipient.c_str(), txId.c_str(),
        ts.c_str(), payload.c_str()
    );
    return calculateHMAC(String(framed.c_str()), secret);
}

bool verifyWsPaySignature(
    const String& event,
    const String& recipient,
    const String& txId,
    const String& ts,
    const String& payload,
    const String& sig,
    const String& secret
) {
    if (sig.length() == 0) return false;
    String expected = calculateWsPaySignature(event, recipient, txId, ts, payload, secret);
    return PisoPhone::constantTimeCompare(sig.c_str(), expected.c_str());
}

String calculateAckSignature(
    const String& deviceId,
    const String& txId,
    int amount,
    int seconds,
    const String& ts,
    const String& status,
    const String& secret
) {
    std::string framed = PisoPhone::formatAckData(
        deviceId.c_str(), txId.c_str(), amount, seconds,
        ts.c_str(), status.c_str()
    );
    return calculateHMAC(String(framed.c_str()), secret);
}

bool verifyAckSignature(
    const String& deviceId,
    const String& txId,
    int amount,
    int seconds,
    const String& ts,
    const String& status,
    const String& sig,
    const String& secret
) {
    if (sig.length() == 0) return false;
    String expectedAck = calculateAckSignature(deviceId, txId, amount, seconds, ts, status, secret);
    return PisoPhone::constantTimeCompare(sig.c_str(), expectedAck.c_str());
}

bool applySlotToken(String token) {
    token.trim();
    token.toUpperCase();
    if (token.length() == 0) return false;

    if (macAddressStr.length() == 0) {
        uint8_t mac[6];
        esp_read_mac(mac, ESP_MAC_WIFI_STA);
        char macBuf[18];
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }

    String myMac = macAddressStr;
    myMac.trim(); myMac.toUpperCase();

    // Canonical Clean MAC (12 hex chars)
    String cleanMac = "";
    for (size_t i = 0; i < myMac.length(); i++) {
        if (myMac[i] != ':') cleanMac += myMac[i];
    }
    if (cleanMac.length() == 0) return false;

    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);

    // Canonical Single Verification Path: Match target slot count (1..MAX_SUPPORTED_SLOTS)
    for (int s = 1; s <= MAX_SUPPORTED_SLOTS; s++) {
        String payload = "PISOSLOT:" + cleanMac + ":" + String(s);
        String expectedSig = calculateHMAC(payload, secKey);
        expectedSig.toUpperCase();

        String shortSig = expectedSig.substring(0, 8);
        String fullToken = "PISOSLOT." + cleanMac + "." + String(s) + "." + shortSig;
        String fullTokenLong = "PISOSLOT." + cleanMac + "." + String(s) + "." + expectedSig;

        if (token.equalsIgnoreCase(shortSig) || token.equalsIgnoreCase(fullToken) || token.equalsIgnoreCase(fullTokenLong) || token.equalsIgnoreCase(expectedSig)) {
            maxLicensedSlots = min(max(maxLicensedSlots, s), MAX_SUPPORTED_SLOTS);
            for (int i = 0; i < maxLicensedSlots; i++) {
                licenseSlots[i].active = true;
            }

            is_licensed = true;
            saveSlotLicenses();
            Serial.printf("[+] Successfully applied Slot License Token: Capacity expanded to %d slots!\n", maxLicensedSlots);
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
        snprintf(macBuf, sizeof(macBuf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        macAddressStr = String(macBuf);
    }
    String myMac = macAddressStr;
    myMac.trim(); myMac.toUpperCase();
    String cleanMac = "";
    for (size_t i = 0; i < myMac.length(); i++) {
        if (myMac[i] != ':') cleanMac += myMac[i];
    }
    if (cleanMac.length() == 0) cleanMac = "000000000000";
    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
    String payload = "BOXREQ:" + cleanMac + ":" + String(maxLicensedSlots) + ":" + String(MAX_SUPPORTED_SLOTS);
    String sig = calculateHMAC(payload, secKey).substring(0, 4);
    sig.toUpperCase();
    return "PISO-" + cleanMac + "-" + String(maxLicensedSlots) + "-" + String(MAX_SUPPORTED_SLOTS) + "-" + sig;
}

String aes_encrypt(String plaintext, String secret) {
    uint8_t aes_key[32];
    mbedtls_md_context_t sha_ctx;
    mbedtls_md_init(&sha_ctx);
    mbedtls_md_setup(&sha_ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&sha_ctx);
    mbedtls_md_update(&sha_ctx, (const unsigned char*)secret.c_str(), secret.length());
    mbedtls_md_finish(&sha_ctx, aes_key);
    mbedtls_md_free(&sha_ctx);

    uint8_t iv[16];
    for (int i = 0; i < 16; i += 4) {
        uint32_t r = esp_random();
        memcpy(iv + i, &r, 4);
    }

    size_t plaintext_len = plaintext.length();
    size_t padding_len = 16 - (plaintext_len % 16);
    size_t padded_len = plaintext_len + padding_len;
    uint8_t* padded_input = (uint8_t*)malloc(padded_len);
    if (!padded_input) return "";
    memcpy(padded_input, plaintext.c_str(), plaintext_len);
    for (size_t i = plaintext_len; i < padded_len; i++) {
        padded_input[i] = (uint8_t)padding_len;
    }

    mbedtls_aes_context aes_ctx;
    mbedtls_aes_init(&aes_ctx);
    mbedtls_aes_setkey_enc(&aes_ctx, aes_key, 256);

    uint8_t* ciphertext = (uint8_t*)malloc(padded_len);
    if (!ciphertext) {
        free(padded_input);
        mbedtls_aes_free(&aes_ctx);
        return "";
    }
    uint8_t iv_tmp[16];
    memcpy(iv_tmp, iv, 16);

    mbedtls_aes_crypt_cbc(&aes_ctx, MBEDTLS_AES_ENCRYPT, padded_len, iv_tmp, padded_input, ciphertext);
    mbedtls_aes_free(&aes_ctx);
    free(padded_input);

    String hex_result = "";
    char hex_char[3];
    for (int i = 0; i < 16; i++) {
        sprintf(hex_char, "%02x", iv[i]);
        hex_result += hex_char;
    }
    for (size_t i = 0; i < padded_len; i++) {
        sprintf(hex_char, "%02x", ciphertext[i]);
        hex_result += hex_char;
    }
    free(ciphertext);
    return hex_result;
}

String aes_decrypt(String encryptedHex, String secret) {
    if (encryptedHex.length() < 32) return "";
    
    size_t total_bytes = encryptedHex.length() / 2;
    uint8_t* data = (uint8_t*)malloc(total_bytes);
    if (!data) return "";
    for (size_t i = 0; i < total_bytes; i++) {
        String part = encryptedHex.substring(i * 2, i * 2 + 2);
        data[i] = (uint8_t)strtol(part.c_str(), NULL, 16);
    }
    
    if (total_bytes < 17) {
        free(data);
        return "";
    }
    
    uint8_t iv[16];
    memcpy(iv, data, 16);
    
    size_t ciphertext_len = total_bytes - 16;
    uint8_t* ciphertext = data + 16;
    
    uint8_t aes_key[32];
    mbedtls_md_context_t sha_ctx;
    mbedtls_md_init(&sha_ctx);
    mbedtls_md_setup(&sha_ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
    mbedtls_md_starts(&sha_ctx);
    mbedtls_md_update(&sha_ctx, (const unsigned char*)secret.c_str(), secret.length());
    mbedtls_md_finish(&sha_ctx, aes_key);
    mbedtls_md_free(&sha_ctx);
    
    mbedtls_aes_context aes_ctx;
    mbedtls_aes_init(&aes_ctx);
    mbedtls_aes_setkey_dec(&aes_ctx, aes_key, 256);
    
    uint8_t* decrypted = (uint8_t*)malloc(ciphertext_len);
    if (!decrypted) {
        mbedtls_aes_free(&aes_ctx);
        free(data);
        return "";
    }
    uint8_t iv_tmp[16];
    memcpy(iv_tmp, iv, 16);
    
    mbedtls_aes_crypt_cbc(&aes_ctx, MBEDTLS_AES_DECRYPT, ciphertext_len, iv_tmp, ciphertext, decrypted);
    mbedtls_aes_free(&aes_ctx);
    free(data);
    
    uint8_t padding_len = decrypted[ciphertext_len - 1];
    if (padding_len > ciphertext_len || padding_len > 16 || padding_len == 0) {
        free(decrypted);
        return "";
    }
    
    size_t plaintext_len = ciphertext_len - padding_len;
    String plaintext = "";
    for (size_t i = 0; i < plaintext_len; i++) {
        plaintext += (char)decrypted[i];
    }
    
    free(decrypted);
    return plaintext;
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

String generateHighEntropySecret() {
    uint8_t randBytes[32];
    for (int i = 0; i < 32; i += 4) {
        uint32_t r = esp_random();
        memcpy(randBytes + i, &r, 4);
    }
    String hex = "";
    hex.reserve(64);
    char buf[3];
    for (int i = 0; i < 32; i++) {
        sprintf(buf, "%02x", randBytes[i]);
        hex += buf;
    }
    return hex;
}

bool verifyCoinSlotAuth(const String& sessionId, const String& tsStr, const String& sig) {
    if (sessionId.length() == 0 || tsStr.length() == 0 || sig.length() == 0) {
        return false;
    }

    String secKey = (sharedSecret.length() > 0) ? sharedSecret : String(MASTER_CRYPTO_SECRET);
    if (secKey.length() == 0) {
        return false;
    }

    // Expected signature computed over "sessionId:ts"
    String payload = sessionId + ":" + tsStr;
    String expectedSig = calculateHMAC(payload, secKey);

    if (!PisoPhone::constantTimeCompare(sig.c_str(), expectedSig.c_str())) {
        return false;
    }

    // Timestamp window verification (5 minutes / 300000ms window)
    unsigned long long ts = strtoull(tsStr.c_str(), NULL, 10);
    unsigned long long currentMasterTs = getCurrentMasterTimeMs();
    if (currentMasterTs > 300000ULL) {
        if (ts < (currentMasterTs - 300000ULL) || ts > (currentMasterTs + 300000ULL)) {
            return false;
        }
    }

    if (ts > 0) {
        updateMasterTime(ts);
    }
    return true;
}

