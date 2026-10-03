#ifndef COIN_TX_VISIBILITY_H
#define COIN_TX_VISIBILITY_H

// Who may see a coin transaction in the slot-status answer.
//
// Only the phone the coin was meant for, only on a request signed with the shared secret, and never once it was
// acknowledged. This used to also show every leftover transaction to whoever currently held the slot, so the next
// customer to press Insert Coin on another phone credited the previous customer's coins (free time).
// Works with Arduino String and std::string alike.
template <class Str>
inline bool coinTxVisibleTo(const Str& txDeviceId, const Str& requesterDeviceId, bool requestSignedAndValid,
                            bool acknowledged) {
    return requestSignedAndValid && !acknowledged && requesterDeviceId.length() > 0 && txDeviceId == requesterDeviceId;
}

#endif // COIN_TX_VISIBILITY_H
