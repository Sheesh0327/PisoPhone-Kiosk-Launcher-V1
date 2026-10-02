#ifndef SUPER_ADMIN_CREDS_H
#define SUPER_ADMIN_CREDS_H

#include <Arduino.h>

// Remotely managed super-admin password. The website publishes a signed PBKDF2 hash
// (website/update/credentials.json); once one has been accepted, the plaintext password stored
// in flash is deleted and logins are checked against the hash.
#ifndef PISO_CRED_URL
#define PISO_CRED_URL "https://pisophone.pages.dev/update/credentials.json"
#endif

void superAdminCredsLoad();    // call once at boot, after prefs are usable
bool superAdminCredsManaged(); // true once a signed hash has been accepted
bool superAdminPasswordOk(const String& candidate);
bool superAdminBasicAuthOk(); // HTTP Basic "superadmin:<password>" header
void superAdminSyncLoop();    // call from loop(); downloads/applies updates

#endif
