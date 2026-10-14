// Cryptographic helpers: HMAC signing, AES payload encryption and the WebSocket handshake accept key.

#include "Security.h"
#include "Config.h"
#include "ProtocolCrypto.h"
#include <esp_random.h>
#include "mbedtls/md.h"
#include "mbedtls/sha1.h"
#include "mbedtls/base64.h"
#include "mbedtls/aes.h"

String calculateHMAC(String challenge, String secret) {
    return String(protocol::hmacHex(challenge.c_str(), secret.c_str()).c_str());
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
