// What the box checks of a voice clip (VoiceClip.h): the 8 header bytes and that the total length fits the sample count. The box never
// decodes. The vectors are protocol/fixtures/box_phone_voice_v1.json (made by protocol/gen_voice_vectors.py, independent of this code);
// the phone's tests and the admin page's tests use the same file.
#include "voice_fixture.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../include/VoiceClip.h"

static int checks = 0, failures = 0;
#define CHECK(cond, ...)                                                                                               \
    do {                                                                                                               \
        checks++;                                                                                                      \
        if (!(cond)) {                                                                                                 \
            failures++;                                                                                                \
            printf("FAIL: ");                                                                                          \
            printf(__VA_ARGS__);                                                                                       \
            printf("\n");                                                                                              \
        }                                                                                                              \
    } while (0)

static std::vector<uint8_t> fromHex(const char* hex) {
    std::vector<uint8_t> out;
    size_t n = strlen(hex);
    for (size_t i = 0; i + 1 < n; i += 2) {
        unsigned v = 0;
        sscanf(hex + i, "%2x", &v);
        out.push_back((uint8_t)v);
    }
    return out;
}

int main() {
    // The numbers both sides agree on.
    CHECK(voiceclip::kHeaderBytes == (size_t)VOICE_CONSTS.headerBytes, "header bytes");
    CHECK(voiceclip::kBlockBytes == (size_t)VOICE_CONSTS.blockBytes, "block bytes");
    CHECK(voiceclip::kBlockSamples == (size_t)VOICE_CONSTS.blockSamples, "block samples");
    CHECK(voiceclip::kMaxSamples == (uint32_t)VOICE_CONSTS.maxSamples, "max samples");
    CHECK(voiceclip::kMaxClipBytes == (size_t)VOICE_CONSTS.maxClipBytes, "max clip bytes %zu",
          voiceclip::kMaxClipBytes);
    CHECK(voiceclip::kMaxClipBytes == 60841, "15.0 s is 60841 bytes");
    CHECK(voiceclip::clipBytesFor(voiceclip::kMaxSamples) == voiceclip::kMaxClipBytes,
          "kMaxClipBytes is the length of a 15.0 s clip");

    // Every good clip passes, and its length is the one the sample count fixes.
    for (const auto& v : VOICE_CLIPS) {
        std::vector<uint8_t> clip = fromHex(v.hex);
        CHECK((int)clip.size() == v.bytes, "%s: fixture size", v.name);
        CHECK((int)voiceclip::clipBytesFor((uint32_t)v.samples) == v.bytes, "%s: clipBytesFor(%d) = %zu, want %d",
              v.name, v.samples, voiceclip::clipBytesFor((uint32_t)v.samples), v.bytes);
        CHECK(voiceclip::checkHeader(clip.data(), clip.size(), clip.size()) == voiceclip::Check::Ok, "%s: accepted",
              v.name);
        // The box checks the header before the rest has arrived: the first 8 bytes and the length it was told.
        CHECK(voiceclip::checkHeader(clip.data(), voiceclip::kHeaderBytes, clip.size()) == voiceclip::Check::Ok,
              "%s: accepted from the first 8 bytes and the announced length", v.name);
    }

    // Every refused clip is refused for the recorded reason, unless it is a fault only the phone can see (inside the blocks).
    for (const auto& v : VOICE_REFUSED) {
        std::vector<uint8_t> clip = fromHex(v.hex);
        voiceclip::Check got = voiceclip::checkHeader(clip.data(), clip.size(), clip.size());
        if (v.boxRefuses) {
            CHECK(got != voiceclip::Check::Ok, "%s: refused", v.name);
            CHECK(strcmp(voiceclip::reasonName(got), v.reason) == 0, "%s: reason %s, want %s", v.name,
                  voiceclip::reasonName(got), v.reason);
        } else {
            CHECK(got == voiceclip::Check::Ok, "%s: the box does not look inside the blocks", v.name);
        }
    }

    // The length formula at the edges.
    CHECK(voiceclip::clipBytesFor(1) == 12, "one sample: 8 + 4");
    CHECK(voiceclip::clipBytesFor(505) == 8 + 256, "one full block");
    CHECK(voiceclip::clipBytesFor(506) == 8 + 256 + 4, "one sample into the second block");
    CHECK(voiceclip::clipBytesFor(0) == 0, "no samples: no length");
    CHECK(voiceclip::clipBytesFor(voiceclip::kMaxSamples + 1) == 0, "over 15 s: no length");

    // An announced length that is not the clip's: refused at once, with fewer bytes than a header, too.
    std::vector<uint8_t> good = fromHex(VOICE_CLIPS[2].hex);
    CHECK(voiceclip::checkHeader(good.data(), voiceclip::kHeaderBytes, good.size() + 1) == voiceclip::Check::Length,
          "announced one byte more");
    CHECK(voiceclip::checkHeader(good.data(), 7, good.size()) == voiceclip::Check::Short, "7 bytes is not a header");
    CHECK(voiceclip::checkHeader(nullptr, 0, 0) == voiceclip::Check::Short, "nothing is not a header");

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
