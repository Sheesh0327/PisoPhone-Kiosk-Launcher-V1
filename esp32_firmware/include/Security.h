#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>

String calculateHMAC(String challenge, String secret);
String aes_encrypt(String plaintext, String secret);
String computeSecWebSocketAccept(String key);

#endif // SECURITY_H
