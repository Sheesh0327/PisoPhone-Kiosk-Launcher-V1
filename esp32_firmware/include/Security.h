#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
String aes_encrypt(String plaintext, String secret);
String computeSecWebSocketAccept(String key);
// Two hex signatures are the same (case ignored), compared without leaking where they first differ.
bool signatureMatches(const String& got, const String& expected);

#endif // SECURITY_H
