#ifndef ORPHAN_PAYMENTS_H
#define ORPHAN_PAYMENTS_H

// Payments the box had to give up on (see OrphanLedger.h): kept in flash until the operator marks them handled, shown on the
// dashboard, and counted in the diagnostics. Nothing is dropped from the payment queue without being written here first.

#include <Arduino.h>
#include <stdint.h>

void orphansBegin(); // call once from setup(), after loadAllConfig()

// Writes down a payment that is about to be dropped. Returns false when it could not be written: the caller must then keep
// the payment where it is, so nothing is ever dropped without a trace. `owner` is PaymentRecord::ownerType, `reason` an
// orphans::Reason.
bool orphanRecord(const char* txId, const char* target, uint8_t owner, uint32_t pulses, uint32_t creditSeconds,
                  uint32_t ageS, uint8_t reason);

uint32_t orphansUnhandledCount();
uint32_t orphansUnhandledPulses();
String orphansJson();             // the whole ledger for the dashboard (GET /api/payments/orphans)
bool orphansHandle(uint32_t seq); // the operator has dealt with this payment
bool orphansHandleAll();
void orphansOnOwnerWipe(); // the owner's full wipe also starts a new ledger

#endif // ORPHAN_PAYMENTS_H
