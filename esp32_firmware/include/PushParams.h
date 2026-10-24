#ifndef PUSH_PARAMS_H
#define PUSH_PARAMS_H

// Pure (no Arduino types) size rules for what the box sends to and reads from a phone (host_tests/push_params_test.cpp).
//
// A push to a phone keeps its parameters in a fixed buffer (AuthRequest::params, 256 bytes). A longer text used to be cut by
// strncpy without a word, so the phone received a different message than the box meant (a cut signature is refused, a cut amount
// is wrong). Now a text that does not fit is refused and logged. A phone's ACK frame is read into a 512-byte JSON document; the
// largest legal one (96-char device id, 63-char tx id, 64-hex signature, a 20-digit time, an amount) needs more than the 256 bytes it
// used to have.
#include <cstddef>

namespace pushparams {

static const size_t kParamsBytes = 256; // sizeof(AuthRequest::params), including the terminating zero
static const size_t kAckDocBytes = 512;

inline bool paramsFit(size_t length) {
    return length < kParamsBytes;
}

// A conservative need for a JSON document holding a flat object: the text itself (strings are copied) plus a slot per member.
inline size_t jsonDocNeed(size_t textLength, size_t members) {
    return textLength + members * 16 + 16;
}

} // namespace pushparams

#endif // PUSH_PARAMS_H
