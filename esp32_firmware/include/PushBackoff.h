#ifndef PUSH_BACKOFF_H
#define PUSH_BACKOFF_H

// Pure (no Arduino types) memory of which phone addresses did not answer (host_tests/push_backoff_test.cpp).
//
// One switched-off phone made the AuthWorker spend 3 attempts and 3 seconds of sleeping on every push to it, while pushes to the
// other phones waited behind it. After kFailuresToSkip failures in a row a target is skipped for kSkipMs (the payment stays
// queued on the box and is offered again later); a success clears it. A small fixed table: when it is full the oldest entry goes.
#include <cstdint>
#include <cstring>

namespace pushbackoff {

static const int kFailuresToSkip = 2;
static const unsigned long kSkipMs = 30000UL;
static const int kSlots = 8;

class Table {
public:
    bool shouldSkip(const char* target, unsigned long nowMs) const {
        const Entry* e = find(target);
        return e && e->failures >= kFailuresToSkip && (unsigned long)(nowMs - e->lastFailMs) < kSkipMs;
    }

    void failed(const char* target, unsigned long nowMs) {
        Entry* e = find(target);
        if (!e) {
            e = &entries_[next_];
            next_ = (next_ + 1) % kSlots;
            memset(e, 0, sizeof(*e));
            strncpy(e->target, target, sizeof(e->target) - 1);
        }
        // a failure after the skip time has run out starts a fresh count
        if (e->failures >= kFailuresToSkip && (unsigned long)(nowMs - e->lastFailMs) >= kSkipMs) e->failures = 0;
        e->failures++;
        e->lastFailMs = nowMs;
    }

    void succeeded(const char* target) {
        Entry* e = find(target);
        if (e) e->failures = 0;
    }

private:
    struct Entry {
        char target[24];
        int failures;
        unsigned long lastFailMs;
    };
    Entry entries_[kSlots] = {};
    int next_ = 0;

    Entry* find(const char* target) {
        for (int i = 0; i < kSlots; i++)
            if (entries_[i].target[0] && strncmp(entries_[i].target, target, sizeof(entries_[i].target) - 1) == 0)
                return &entries_[i];
        return nullptr;
    }
    const Entry* find(const char* target) const { return const_cast<Table*>(this)->find(target); }
};

} // namespace pushbackoff

#endif // PUSH_BACKOFF_H
