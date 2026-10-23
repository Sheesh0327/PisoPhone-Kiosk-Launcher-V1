#ifndef SHOP_BRANDING_STORE_H
#define SHOP_BRANDING_STORE_H

// The shop's branding on the box (the rules are in ShopBranding.h): four short settings in the settings memory, never a file.
// Only the phone products use it: the phones draw the look and the box just remembers and passes it on.

#include <Arduino.h>

#include "ShopBranding.h"

// Reads the settings from the settings memory (call while it is open for reading).
void loadShopBrandingFromPrefs();
// A factory reset forgets them (the phones fall back to the TechNet look on their next push).
void resetShopBranding();

shopbranding::Branding shopBrandingCurrent();
// Saves all four settings together (already checked by the caller). False: not saved.
bool shopBrandingSave(const shopbranding::Branding& next);

// {"palette":"..","name":"..","title":"..","density":"..","configured":true}
String shopBrandingJson();
// Tells one phone the branding now, in a /config push of its own (the existing push is left exactly as it is). Nothing is sent while
// nobody saved branding.
void pushBrandingToIp(const String& ip);
// Tells every paired phone.
void pushBrandingToPhones();

#endif
