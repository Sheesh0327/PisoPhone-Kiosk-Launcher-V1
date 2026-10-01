#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
String aes_encrypt(String plaintext, String secret);
String computeSecWebSocketAccept(String key);
bool applySlotToken(String token);
String getBoxMachineCode();

#endif // SECURITY_H
