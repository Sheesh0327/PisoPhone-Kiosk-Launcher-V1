#ifndef COIN_RECORD_ORDER_H
#define COIN_RECORD_ORDER_H

// The order a coin is recorded in: queue the payment, then count the revenue, then blink the LED.
//
// The box records the payment before anything else. If power is cut after the revenue but before the queue, the box
// counts money the phone can never collect; the other way round, the phone is credited and only the revenue is lost.
// Returns whether the queue kept the payment. The revenue and LED steps run either way: the coin is real money.
template <typename Enqueue, typename Revenue, typename Led>
inline bool recordCoinInOrder(Enqueue enqueue, Revenue revenue, Led led) {
    bool retained = enqueue();
    revenue();
    led();
    return retained;
}

#endif
