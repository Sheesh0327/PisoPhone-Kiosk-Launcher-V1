#include "../include/PushParams.h"

#include <cstdio>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(c)                                                                                                       \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(c)) {                                                                                                    \
            failures++;                                                                                                \
            printf("FAIL line %d: %s\n", __LINE__, #c);                                                                \
        }                                                                                                              \
    } while (0)

int main() {
    // FW-12: what fits the 256-byte buffer (255 characters and the zero)
    CHECK(pushparams::paramsFit(0));
    CHECK(pushparams::paramsFit(255));
    CHECK(!pushparams::paramsFit(256));
    CHECK(!pushparams::paramsFit(1000));

    // FW-13: the largest legal ACK frame the phone can send needs more than 256 bytes of JSON document, and fits 512
    std::string dev(96, 'd'), tx(63, 't'), sig(64, 'a');
    std::string frame = "{\"event\":\"ACK\",\"device_id\":\"" + dev + "\",\"tx_id\":\"" + tx + "\",\"v_sig\":\"" + sig +
                        "\",\"ts\":\"17900000000000000000\",\"amount\":\"1000000000\"}";
    size_t need = pushparams::jsonDocNeed(frame.size(), 6);
    CHECK(need > 256);                       // the old 256-byte document could not hold it (the ACK was dropped)
    CHECK(need <= pushparams::kAckDocBytes); // the new one can
    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
