#ifndef RESTART_GUARD_H
#define RESTART_GUARD_H

// Is it safe to restart the box (reboot, OTA, factory reset, reset pin)?
//
// Only when no coin can be in flight and no payment lives only in RAM: nothing unpersisted, the coin slot IDLE, and no
// pulse counted that has not yet been turned into a payment.
inline bool mayRestart(bool hasUnpersistedPayments, bool slotIdle, int pendingPulses) {
    return !hasUnpersistedPayments && slotIdle && pendingPulses == 0;
}

// The hardware reset pin, held to GND. After the 5 s hold it waits for a safe moment so a coin in flight is drained first,
// but a box that never gets one can still be reset after a further 30 s.
inline bool resetPinMayRestart(bool safeNow, unsigned long heldMs) {
    const unsigned long HOLD_MS = 5000, DRAIN_GRACE_MS = 30000;
    if (heldMs < HOLD_MS) return false;
    return safeNow || heldMs >= HOLD_MS + DRAIN_GRACE_MS;
}

#endif
