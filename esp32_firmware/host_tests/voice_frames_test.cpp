// How the box turns a voice clip into frames for the phone (VoiceFrames.h), one frame at a time. The vectors are
// protocol/fixtures/box_phone_voice_v1.json (made by protocol/gen_voice_vectors.py); the phone's tests check the same file.
#include "voice_fixture.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <mbedtls/md.h>

#include "../include/VoiceFrames.h"

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

static std::string toHex(const uint8_t* d, size_t n) {
    static const char* h = "0123456789abcdef";
    std::string s;
    for (size_t i = 0; i < n; i++) {
        s += h[d[i] >> 4];
        s += h[d[i] & 15];
    }
    return s;
}

static void ivFor(unsigned seq, uint8_t iv[16]) {
    for (unsigned j = 0; j < 16; j++)
        iv[j] = (uint8_t)((seq * 31 + j * 7 + 1) % 256);
}

static const char* clipHex(const char* name) {
    for (const auto& v : VOICE_CLIPS)
        if (strcmp(v.name, name) == 0) return v.hex;
    return nullptr;
}

int main() {
    CHECK(voiceframes::kPlainMax == (size_t)VOICE_CONSTS.framePlainMax, "plain max");
    CHECK(voiceframes::kHeadBytes == (size_t)VOICE_CONSTS.frameHead, "head bytes");
    CHECK(voiceframes::kIvBytes == (size_t)VOICE_CONSTS.ivBytes, "iv bytes");
    CHECK(voiceframes::kMacBytes == (size_t)VOICE_CONSTS.macBytes, "mac bytes");
    CHECK(strcmp(voiceframes::kMacTag, VOICE_CONSTS.macTag) == 0, "mac tag");
    CHECK(voiceframes::kFrameMax == 1093, "the largest frame is 1093 bytes, not %zu", voiceframes::kFrameMax);

    // The sizes the box announces to the phone before sending anything.
    CHECK(voiceframes::frameWireBytes(1) == 69 && voiceframes::frameWireBytes(15) == 69,
          "1..15 plain bytes: one cipher block");
    CHECK(voiceframes::frameWireBytes(16) == 85, "16 plain bytes: a whole padding block is added");
    CHECK(voiceframes::frameWireBytes(1024) == 1093, "a full frame");
    CHECK(voiceframes::frameCountFor(60841) == 60, "the longest clip is 60 frames");
    CHECK(voiceframes::wireBytesFor(60841) == 64972, "the longest clip: 64972 bytes on the wire, not %zu",
          voiceframes::wireBytesFor(60841));
    CHECK(voiceframes::frameCountFor(0) == 0 && voiceframes::wireBytesFor(0) == 0, "no clip, no frames");
    CHECK(voiceframes::frameCountFor(1024) == 1 && voiceframes::frameCountFor(1025) == 2, "frame count at the edge");

    std::vector<uint8_t> env = fromHex(VOICE_ENVELOPE_HMAC);
    CHECK(env.size() == 32, "the signed request's hmac is 32 bytes");

    for (const auto& c : VOICE_FRAME_CASES) {
        std::vector<uint8_t> clip = fromHex(clipHex(c.clip));
        CHECK((int)clip.size() == c.clipBytes, "%s: clip size", c.clip);
        CHECK((int)voiceframes::wireBytesFor(clip.size()) == c.wireBytes, "%s: announced wire size", c.clip);
        CHECK((int)voiceframes::frameCountFor(clip.size()) == c.frameCount, "%s: frame count", c.clip);

        voiceframes::Framer framer;
        CHECK(framer.begin(VOICE_SECRET, env.data()), "%s: begin", c.clip);
        mbedtls_md_context_t sha;
        mbedtls_md_init(&sha);
        mbedtls_md_setup(&sha, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
        mbedtls_md_starts(&sha);

        // One frame buffer, reused: the box holds nothing else of the clip.
        uint8_t frame[voiceframes::kFrameMax];
        size_t total = 0, count = 0;
        for (size_t off = 0; off < clip.size(); off += voiceframes::kPlainMax) {
            size_t take = clip.size() - off < voiceframes::kPlainMax ? clip.size() - off : voiceframes::kPlainMax;
            bool last = off + take == clip.size();
            uint8_t iv[16];
            ivFor((unsigned)count, iv);
            size_t n = framer.next(clip.data() + off, take, last, iv, frame);
            CHECK(n == voiceframes::frameWireBytes(take), "%s: frame %zu is %zu bytes, want %zu", c.clip, count, n,
                  voiceframes::frameWireBytes(take));
            if ((int)count < c.framesHexCount)
                CHECK(toHex(frame, n) == c.framesHex[count], "%s: frame %zu differs from the vector", c.clip, count);
            mbedtls_md_update(&sha, frame, n);
            total += n;
            count++;
        }
        uint8_t digest[32];
        mbedtls_md_finish(&sha, digest);
        mbedtls_md_free(&sha);
        CHECK((int)total == c.wireBytes, "%s: wrote %zu bytes, want %d", c.clip, total, c.wireBytes);
        CHECK((int)count == c.frameCount, "%s: wrote %zu frames, want %d", c.clip, count, c.frameCount);
        CHECK(toHex(digest, 32) == c.wireSha256, "%s: the whole stream differs from the vector", c.clip);
        CHECK(framer.next(clip.data(), 1, true, nullptr, frame) == 0, "%s: nothing is made after the last frame",
              c.clip);
    }

    // Refusals: the framer never writes a frame it should not.
    {
        uint8_t iv[16];
        ivFor(0, iv);
        uint8_t frame[voiceframes::kFrameMax];
        uint8_t data[2048] = {0};
        voiceframes::Framer fresh;
        CHECK(fresh.next(data, 10, true, iv, frame) == 0, "a frame before begin is refused");
        voiceframes::Framer f;
        CHECK(!f.begin("", env.data()), "an empty secret is refused");
        CHECK(f.begin(VOICE_SECRET, env.data()), "begin");
        CHECK(f.next(data, 0, false, iv, frame) == 0, "an empty piece is refused");
        CHECK(f.next(data, voiceframes::kPlainMax + 1, false, iv, frame) == 0, "a piece over 1024 bytes is refused");
        CHECK(f.next(nullptr, 10, false, iv, frame) == 0, "no data is refused");
        CHECK(f.next(data, 10, false, nullptr, frame) == 0, "no iv is refused");
        CHECK(f.next(data, 10, false, iv, nullptr) == 0, "no room is refused");
        CHECK(f.seq() == 0, "refused pieces do not use up a sequence number");
        CHECK(f.next(data, 10, false, iv, frame) == voiceframes::frameWireBytes(10),
              "a good piece after the refused ones");
        CHECK(f.seq() == 1, "the sequence number moved on");
    }

    // What the box keeps while it works: keys, the last mac and a counter. Nothing of the clip.
    CHECK(sizeof(voiceframes::Framer) < 256, "the framer's own state is %zu bytes", sizeof(voiceframes::Framer));

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
