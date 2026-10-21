#ifndef REVENUE_VAULT_H
#define REVENUE_VAULT_H

// The coin box's revenue "vault": what has been counted since the vendor last collected. The lifetime counters in Config.h only
// go up; collecting is an event written to a log (RevenueLedger.h). Every way of "resetting the revenue" goes through
// vaultCollect(), so each one is recorded and none can erase the lifetime totals.

#include <Arduino.h>
#include <stdint.h>

void vaultBegin();                  // call once from setup(), after loadAllConfig()
uint32_t vaultCoins();              // coins counted since the last collection
uint32_t vaultCentavos();           // earnings counted since the last collection
uint32_t vaultCollections();        // how many collections have been recorded in all
bool vaultCollect(uint32_t reason); // takes the vault's contents out (a revenue::Reason); false = nothing was changed
String vaultHistoryJson(int max);   // the newest collections, newest first, as a JSON array
void vaultOnOwnerWipe();            // the owner's full wipe also starts a new log

// Period Ledger Integration
void vaultRecordCoin(int pulses, bool isPhone, uint32_t seq = 0);
void vaultAnchorTime(uint32_t unixSec);
uint32_t vaultTodayCoins(uint32_t* outWifi = nullptr, uint32_t* outPhone = nullptr, uint16_t* outTx = nullptr);
uint32_t vaultYesterdayCoins(uint32_t* outWifi = nullptr, uint32_t* outPhone = nullptr);
uint32_t vaultLast7DaysCoins(uint32_t outDaily7[7] = nullptr);
uint32_t vaultCurrentWeekCoins();
uint32_t vaultLast30DaysCoins();
uint32_t vaultCurrentMonthCoins();
uint32_t vaultLastCountedSeq();
String vaultPeriodDaysJson(int maxDays = 30);

#endif // REVENUE_VAULT_H
