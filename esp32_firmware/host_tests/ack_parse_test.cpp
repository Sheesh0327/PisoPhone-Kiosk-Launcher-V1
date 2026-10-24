#include "../include/AckParse.h"

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
    ack::Fields f = ack::parseAckBody("OK:tx_id=tx-b1-1-abcdef01:device_id=phone-1:ts=1790000000000:v_sig=0123abcd");
    CHECK(f.okPrefix && f.hasTx && f.hasDev && f.hasTs && f.hasSig);
    CHECK(f.tx == "tx-b1-1-abcdef01" && f.dev == "phone-1" && f.ts == "1790000000000" && f.sig == "0123abcd");

    // ALREADY_PROCESSED is an acknowledgement too
    f = ack::parseAckBody("ALREADY_PROCESSED:tx_id=a:device_id=b:ts=5:v_sig=ff");
    CHECK(f.okPrefix && f.tx == "a" && f.dev == "b" && f.ts == "5" && f.sig == "ff");

    // plain answers without fields
    f = ack::parseAckBody("OK");
    CHECK(f.okPrefix && !f.hasTx && !f.hasDev && !f.hasSig && !f.hasTs);
    f = ack::parseAckBody("ERROR:bad");
    CHECK(!f.okPrefix && !f.hasTx);
    f = ack::parseAckBody("");
    CHECK(!f.okPrefix && !f.hasTx && !f.hasDev);

    // a key hidden inside another key or a value is NOT that key (the indexOf reading found it)
    f = ack::parseAckBody("OK:xts=999:tx_id=t1:device_id=d1:ts=123:v_sig=aa");
    CHECK(f.hasTs && f.ts == "123"); // "xts=" is not "ts="
    f = ack::parseAckBody("OK:tx_id=t1:device_id=ctx_id=zzz:ts=1:v_sig=bb");
    CHECK(f.tx == "t1"); // the first whole tx_id wins
    f = ack::parseAckBody("OK:device_id=d:mytx_id=evil:ts=1:v_sig=cc");
    CHECK(!f.hasTx); // "mytx_id=" is not "tx_id="

    // the first of a repeated key wins (a later one cannot replace the signed id)
    f = ack::parseAckBody("OK:tx_id=good:device_id=d:tx_id=evil:ts=1:v_sig=dd");
    CHECK(f.tx == "good");

    // empty values and a missing signature
    f = ack::parseAckBody("OK:tx_id=t:device_id=d:ts=1");
    CHECK(f.hasTx && f.hasDev && f.hasTs && !f.hasSig);
    f = ack::parseAckBody("OK:tx_id=:device_id=d:ts=1:v_sig=");
    CHECK(f.hasTx && f.tx.empty() && f.hasSig && f.sig.empty());

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
