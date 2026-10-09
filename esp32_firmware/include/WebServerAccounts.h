#ifndef WEB_SERVER_ACCOUNTS_H
#define WEB_SERVER_ACCOUNTS_H

// Player-account calls from the phone app (see AccountProtocol.h and docs/api/gateway-coinslot.md, "Accounts"):
//   /api/account/create   make an account              /api/account/signin   open it on this phone
//   /api/account/signout  bank the time left and close  /api/account/info     read the balance (signed-in phone only)
// All are signed by a paired phone, and the PIN travels only encrypted.

#include <Arduino.h>

void handleApiAccountCreate();
void handleApiAccountSignin();
void handleApiAccountSignout();
void handleApiAccountInfo();

// Heartbeat hook. Applies the signed time report the phone may have attached (acct, atime, asig) and appends the box's
// view of who is signed in on this slot (`,"acct":"...","acct_bal":N`) to the reply.
void accountsOnHeartbeat(int slotNum, const String& deviceId, const String& tsStr, String& jsonReply);

// A coin the phone confirmed: when a player is signed in on that phone, the account gets the same seconds.
void accountsOnPhonePaymentAcked(const String& deviceId, const String& txId, int creditSeconds);

// From loop(): a phone that stopped reporting for 90 s is signed out, balance as last reported.
void accountsWatchdogLoop();

#endif // WEB_SERVER_ACCOUNTS_H
