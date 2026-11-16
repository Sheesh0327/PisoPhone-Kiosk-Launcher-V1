#ifndef RESTART_GUARD_H
#define RESTART_GUARD_H

// Is it safe to restart the box (reboot, OTA, factory reset, reset pin)?
// Only when no coin can be in flight and no payment lives only in RAM.
inline bool mayRestart(bool hasUnpersistedPayments, bool slotIdle, int pendingPulses) {
    return !hasUnpersistedPayments && slotIdle && pendingPulses == 0;
}

// The hardware reset pin, held to GND. After HOLD_MS it waits for a safe moment so a coin in flight
// is drained first, with a hard ceiling of HOLD_MS + DRAIN_GRACE_MS.
inline bool resetPinMayRestart(bool safeNow, unsigned long heldMs) {
    const unsigned long HOLD_MS = 5000, DRAIN_GRACE_MS = 30000;
    if (heldMs < HOLD_MS) return false;
    return safeNow || heldMs >= HOLD_MS + DRAIN_GRACE_MS;
}

#endif // RESTART_GUARD_H
