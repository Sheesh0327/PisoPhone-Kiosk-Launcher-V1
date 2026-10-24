#ifndef WORKER_LIVENESS_H
#define WORKER_LIVENESS_H

// Pure (no Arduino types) check that a background task is still making progress (host_tests/worker_liveness_test.cpp).
//
// The AuthWorker task sends the signed pushes and acks to phones. If it hangs (a stuck socket, a lock that is never released),
// payments stay safely queued but customers wait and nothing says why. The task bumps a counter every time it takes a request and
// every attempt it makes; loop() asks this every 10 s. "Stalled" means: work has been waiting and the counter did not move for
// `stallMs`. An idle worker (nothing waiting) is never stalled.
#include <cstdint>

namespace liveness {

class Watch {
public:
    explicit Watch(unsigned long stallMs = 60000UL) : stallMs_(stallMs) {}

    // Call every few seconds. `passes` is the worker's counter; `waiting` says whether requests are queued for it.
    bool stalled(unsigned long nowMs, uint32_t passes, bool waiting) {
        if (!started_ || passes != lastPasses_ || !waiting) {
            started_ = true;
            lastPasses_ = passes;
            lastProgressMs_ = nowMs;
            return false;
        }
        return (unsigned long)(nowMs - lastProgressMs_) >= stallMs_;
    }

private:
    unsigned long stallMs_;
    bool started_ = false;
    uint32_t lastPasses_ = 0;
    unsigned long lastProgressMs_ = 0;
};

} // namespace liveness

#endif // WORKER_LIVENESS_H
