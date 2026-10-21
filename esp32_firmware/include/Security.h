#ifndef SECURITY_H
#define SECURITY_H

#include <Arduino.h>
#include "Product.h"

String calculateHMAC(String challenge, String secret);
String aes_encrypt(String plaintext, String secret);
#if PISO_HAS_PHONES
String computeSecWebSocketAccept(String key);
#endif
// Two hex signatures are the same (case ignored), compared without leaking where they first differ.
bool signatureMatches(const String& got, const String& expected);

#endif // SECURITY_H
