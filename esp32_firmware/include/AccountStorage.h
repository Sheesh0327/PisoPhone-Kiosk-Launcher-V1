#ifndef ACCOUNT_STORAGE_H
#define ACCOUNT_STORAGE_H

// Keeps the account table (Accounts.h) in the encrypted "spiffs" flash partition, never in NVS: NVS is only 24 KB,
// shared with every other setting, and wears out if rewritten often. All changes go through the table; after
// changing it call accountsCommit().

#include "Accounts.h"

#include <Arduino.h>

// What the diagnostics show about the account store, so a failing flash is seen before a player loses anything.
struct AccountStorageHealth {
    bool ready;     // the table is loaded and in use
    bool fsMounted; // the flash filesystem mounted
    size_t accounts;
    bool dirty;                   // changes not yet on flash
    uint32_t saveFailures;        // since boot
    uint32_t consecutiveFailures; // 0 when the last save worked
    uint32_t savesOk;
};
AccountStorageHealth accountsHealth();

void accountsBegin();                    // call once from setup(), after loadAllConfig()
void accountsLoop();                     // call from loop(): debounced saving and the hourly prune
bool accountsReady();                    // false if the filesystem or memory was not available
accounts::AccountTable& accountsTable(); // only valid while accountsReady()
uint32_t accountsNowS();                 // the box clock in seconds, 0 until a phone has set it
void accountsCommit(bool urgent);        // urgent: save now (sign-in/out, credit, create, delete); else within 30 s
accounts::Result accountsCreate(const String& username, const String& pin); // salts, creates, commits

#endif // ACCOUNT_STORAGE_H
