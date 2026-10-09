#ifndef WEB_SERVER_ACCOUNTS_H
#define WEB_SERVER_ACCOUNTS_H

// Player-account calls from the phone app (see AccountProtocol.h and docs/api/gateway-coinslot.md, "Accounts"):
//   /api/account/scan     sign in by scanning a QR card (the first scan of a card also gives its starter time)
//   /api/account/name     give the signed-in account a display name
//   /api/account/signout  bank the time left and sign out
//   /api/account/info     read the balance (signed-in phone only)
// All are signed by a paired phone. The card text is the only credential.

#include <Arduino.h>

void handleApiAccountScan();
void handleApiAccountName();
void handleApiAccountSignout();
void handleApiAccountInfo();

// Admin dashboard (box admin login): list the accounts and change them.
//   GET  /api/accounts                          every account: number, name, minutes left, slot, last use
//   POST /api/accounts/adjust  id, minutes      add (positive) or remove (negative) minutes; not while signed in
//   POST /api/accounts/delete  id               delete the account and its time (the card stays used); not while signed in
void handleApiAccountsList();
void handleApiAccountsAdjust();
void handleApiAccountsDelete();

// Heartbeat hook. Applies the signed time report the phone may have attached (acct, atime, asig) and appends the box's
// view of who is signed in on this slot (`,"acct":"...","acct_bal":N`) to the reply.
void accountsOnHeartbeat(int slotNum, const String& deviceId, const String& tsStr, String& jsonReply);

// A coin the phone confirmed: when a player is signed in on that phone, the account gets the same seconds.
void accountsOnPhonePaymentAcked(const String& deviceId, const String& txId, int creditSeconds);

// From loop(): a phone that stopped reporting for 90 s is signed out, balance as last reported.
void accountsWatchdogLoop();

#endif // WEB_SERVER_ACCOUNTS_H
