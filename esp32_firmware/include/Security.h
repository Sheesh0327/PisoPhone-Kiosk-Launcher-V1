#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
bool applySlotToken(String token);
String getBoxMachineCode();
String aes_encrypt(String plaintext, String secret);
String aes_decrypt(String encryptedHex, String secret);
String computeSecWebSocketAccept(String key);

#endif // SECURITY_H
