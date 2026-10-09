#ifndef ACCOUNT_STORAGE_H
#define ACCOUNT_STORAGE_H

// Keeps the account table (Accounts.h) in the encrypted "spiffs" flash partition, never in NVS: NVS is only 24 KB,
// shared with every other setting, and wears out if rewritten often. All changes go through the table; after
// changing it call accountsCommit().

#include "Accounts.h"

#include <Arduino.h>

void accountsBegin();                    // call once from setup(), after loadAllConfig()
void accountsLoop();                     // call from loop(): debounced saving and the hourly prune
bool accountsReady();                    // false if the filesystem or memory was not available
accounts::AccountTable& accountsTable(); // only valid while accountsReady()
uint32_t accountsNowS();                 // the box clock in seconds, 0 until a phone has set it
void accountsCommit(bool urgent);        // urgent: save now (sign-in/out, credit, create, delete); else within 30 s
accounts::Result accountsCreate(const String& username, const String& pin); // salts, creates, commits

#endif // ACCOUNT_STORAGE_H
