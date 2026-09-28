#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
String calculateHttpReqSignature(const String& method, const String& endpoint, const String& recipient, const String& txId, const String& ts, const String& payload, const String& secret);
bool verifyHttpReqSignature(const String& method, const String& endpoint, const String& recipient, const String& txId, const String& ts, const String& payload, const String& sig, const String& secret);
String calculateWsPaySignature(const String& event, const String& recipient, const String& txId, const String& ts, const String& payload, const String& secret);
bool verifyWsPaySignature(const String& event, const String& recipient, const String& txId, const String& ts, const String& payload, const String& sig, const String& secret);
String calculateAckSignature(const String& deviceId, const String& txId, int amount, int seconds, const String& ts, const String& status, const String& secret);
bool verifyAckSignature(const String& deviceId, const String& txId, int amount, int seconds, const String& ts, const String& status, const String& sig, const String& secret);
String aes_encrypt(String plaintext, String secret);
String aes_decrypt(String encryptedHex, String secret);
String computeSecWebSocketAccept(String key);
bool applySlotToken(String token);
String getBoxMachineCode();

#endif // SECURITY_H
