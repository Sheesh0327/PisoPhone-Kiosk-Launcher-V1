#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
String calculatePaymentSignature(const String& deviceId, const String& txId, int amount, unsigned long long ts, const String& secret);
String calculateAckSignature(const String& deviceId, const String& txId, int amount, unsigned long long ts, const String& secret);
bool verifyPaymentSignature(const String& deviceId, const String& txId, int amount, unsigned long long ts, const String& sig, const String& secret);
bool verifyAckSignature(const String& deviceId, const String& txId, int amount, unsigned long long ts, const String& sig, const String& secret);
String aes_encrypt(String plaintext, String secret);
String aes_decrypt(String encryptedHex, String secret);
String computeSecWebSocketAccept(String key);
bool applySlotToken(String token);
String getBoxMachineCode();

#endif // SECURITY_H
