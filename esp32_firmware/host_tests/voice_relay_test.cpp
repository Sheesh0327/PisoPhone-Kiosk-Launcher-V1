// The box's streamed relay of a voice message (VoiceRelay.h): who may start, how the admin page's upload becomes frames for the phone
// without the box ever holding the clip, and what happens when something goes wrong. The vectors are
// protocol/fixtures/box_phone_voice_v1.json (protocol/gen_voice_vectors.py).
#include "voice_fixture.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <mbedtls/md.h>

#include "../include/VoiceRelay.h"

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

static const char* clipHex(const char* name) {
    for (const auto& v : VOICE_CLIPS)
        if (strcmp(v.name, name) == 0) return v.hex;
    return nullptr;
}

// A phone that takes frames: keeps a running hash of everything written, counts the frames, can be told to refuse the Nth.
struct FakePhone {
    mbedtls_md_context_t sha;
    size_t bytes = 0, frames = 0, refuseAt = (size_t)-1, largest = 0;
    FakePhone() {
        mbedtls_md_init(&sha);
        mbedtls_md_setup(&sha, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 0);
        mbedtls_md_starts(&sha);
    }
    ~FakePhone() { mbedtls_md_free(&sha); }
    bool operator()(const uint8_t* frame, size_t len) {
        if (frames == refuseAt) return false;
        mbedtls_md_update(&sha, frame, len);
        bytes += len;
        frames++;
        if (len > largest) largest = len;
        return true;
    }
    std::string digest() {
        uint8_t d[32];
        mbedtls_md_finish(&sha, d);
        return toHex(d, 32);
    }
};

// The iv rule of the vectors: iv[j] = (seq * 31 + j * 7 + 1) % 256, one frame after the other.
struct IvRule {
    unsigned seq = 0;
    void operator()(uint8_t iv[16]) {
        for (unsigned j = 0; j < 16; j++)
            iv[j] = (uint8_t)((seq * 31 + j * 7 + 1) % 256);
        seq++;
    }
};

int main() {
    using voicerelay::Fault;
    using voicerelay::Verdict;
    std::vector<uint8_t> env = fromHex(VOICE_ENVELOPE_HMAC);

    // ---- who may start ----
    const size_t enough = voicerelay::kReserveBytes + sizeof(voicerelay::Session);
    CHECK(voicerelay::admit(false, enough, 60841) == Verdict::Ok, "a free box with room starts");
    CHECK(voicerelay::admit(false, enough - 1, 60841) == Verdict::LowMemory, "one byte under the reserve is refused");
    CHECK(voicerelay::admit(true, enough, 60841) == Verdict::Busy, "a second clip while one is passing is refused");
    CHECK(voicerelay::admit(false, enough, 0) == Verdict::BadLength, "no clip");
    CHECK(voicerelay::admit(false, enough, 11) == Verdict::BadLength, "shorter than the shortest clip (12 bytes)");
    CHECK(voicerelay::admit(false, enough, 12) == Verdict::Ok, "the shortest clip");
    CHECK(voicerelay::admit(false, enough, 60842) == Verdict::BadLength, "longer than 15.0 s");
    CHECK(voicerelay::admit(true, 0, 0) == Verdict::BadLength, "a bad request is named before the box's state");
    CHECK(voicerelay::admit(true, 0, 60841) == Verdict::Busy, "busy is named before memory");
    CHECK(strcmp(voicerelay::verdictName(Verdict::LowMemory), "low_memory") == 0, "the words the page reads");
    CHECK(strcmp(voicerelay::verdictName(Verdict::Busy), "busy") == 0 &&
              strcmp(voicerelay::verdictName(Verdict::BadLength), "bad_length") == 0,
          "the words the page reads");
    CHECK(voicerelay::kReserveBytes == 24000, "the reserve is the firmware's own floor for the largest free block");

    // ---- the whole clip through the relay, in uploads of every size ----
    const size_t pieces[] = {1, 7, 1000, 1436, 5000, 70000};
    for (const auto& c : VOICE_FRAME_CASES) {
        std::vector<uint8_t> clip = fromHex(clipHex(c.clip));
        for (size_t piece : pieces) {
            voicerelay::Session s;
            CHECK(s.begin(VOICE_SECRET, env.data(), clip.size()), "%s: begin", c.clip);
            FakePhone phone;
            IvRule iv;
            bool ok = true;
            for (size_t off = 0; off < clip.size() && ok; off += piece) {
                size_t n = clip.size() - off < piece ? clip.size() - off : piece;
                ok = s.feed(clip.data() + off, n, phone, iv);
            }
            CHECK(ok, "%s/%zu: every piece taken", c.clip, piece);
            CHECK(s.finish(phone, iv), "%s/%zu: finish", c.clip, piece);
            CHECK(s.fault() == Fault::None, "%s/%zu: no fault", c.clip, piece);
            CHECK((int)phone.bytes == c.wireBytes, "%s/%zu: wrote %zu bytes, want %d", c.clip, piece, phone.bytes,
                  c.wireBytes);
            CHECK((int)phone.frames == c.frameCount && s.framesSent() == phone.frames, "%s/%zu: frames", c.clip, piece);
            CHECK(phone.digest() == c.wireSha256, "%s/%zu: the stream is not the vector's", c.clip, piece);
            CHECK(s.received() == clip.size(), "%s/%zu: received", c.clip, piece);
            // Never the whole clip: at most one frame's worth is held, whatever the length and the size of the pieces.
            CHECK(s.maxHeld() <= voiceframes::kPlainMax, "%s/%zu: held %zu clip bytes at once", c.clip, piece,
                  s.maxHeld());
            CHECK(phone.largest <= voiceframes::kFrameMax, "%s/%zu: a frame larger than the buffer", c.clip, piece);
        }
    }
    CHECK(sizeof(voicerelay::Session) < voiceframes::kFrameMax + 256,
          "the session is one frame buffer and a few counters: %zu bytes", sizeof(voicerelay::Session));
    CHECK(sizeof(voicerelay::Session) * 10 < voiceclip::kMaxClipBytes, "far less than the longest clip");

    // ---- things going wrong ----
    std::vector<uint8_t> clip = fromHex(clipHex("noise-1s")); // 4064 bytes, 4 frames
    {
        voicerelay::Session s;
        FakePhone phone;
        IvRule iv;
        CHECK(!s.begin(VOICE_SECRET, env.data(), 0), "begin refuses no clip");
        CHECK(!s.begin(VOICE_SECRET, env.data(), 11), "begin refuses a clip under 12 bytes");
        CHECK(!s.begin(VOICE_SECRET, env.data(), 60842), "begin refuses a clip over 15 s");
        CHECK(!s.begin("", env.data(), clip.size()), "begin refuses an empty key");
        CHECK(!s.feed(clip.data(), 10, phone, iv), "a session that did not begin takes nothing");
    }
    {
        // More bytes than the page announced.
        voicerelay::Session s;
        FakePhone phone;
        IvRule iv;
        CHECK(s.begin(VOICE_SECRET, env.data(), clip.size()), "begin");
        CHECK(s.feed(clip.data(), clip.size(), phone, iv), "the announced bytes");
        size_t before = phone.frames;
        CHECK(!s.feed(clip.data(), 1, phone, iv), "one byte more");
        CHECK(s.fault() == Fault::TooMuch, "named too much");
        CHECK(phone.frames == before, "nothing more is written");
        CHECK(!s.finish(phone, iv), "a faulted session does not finish");
    }
    {
        // Fewer bytes than announced.
        voicerelay::Session s;
        FakePhone phone;
        IvRule iv;
        CHECK(s.begin(VOICE_SECRET, env.data(), clip.size()), "begin");
        CHECK(s.feed(clip.data(), clip.size() - 1, phone, iv), "all but one byte");
        CHECK(!s.finish(phone, iv), "finish with a byte missing");
        CHECK(s.fault() == Fault::TooLittle, "named too little");
        CHECK(phone.frames == 3, "the last frame was never made: %zu frames", phone.frames);
    }
    {
        // A phone that stops taking frames.
        voicerelay::Session s;
        FakePhone phone;
        phone.refuseAt = 2;
        IvRule iv;
        CHECK(s.begin(VOICE_SECRET, env.data(), clip.size()), "begin");
        bool ok = s.feed(clip.data(), clip.size(), phone, iv);
        CHECK(!ok, "the upload is refused once the phone stops");
        CHECK(s.fault() == Fault::Phone, "named phone");
        CHECK(phone.frames == 2, "nothing is written after the refusal");
        CHECK(!s.feed(clip.data(), 1, phone, iv), "and nothing more is taken");
    }
    for (const auto& r : VOICE_REFUSED) {
        // A header the box can see is wrong: refused before one frame has gone to the phone.
        std::vector<uint8_t> bad = fromHex(r.hex);
        if (!r.boxRefuses || bad.size() < 12 || bad.size() > voiceclip::kMaxClipBytes) continue;
        voicerelay::Session s;
        FakePhone phone;
        IvRule iv;
        CHECK(s.begin(VOICE_SECRET, env.data(), bad.size()), "%s: begin", r.name);
        bool ok = s.feed(bad.data(), bad.size(), phone, iv);
        if (ok) ok = s.finish(phone, iv);
        CHECK(!ok, "%s: refused", r.name);
        CHECK(s.fault() == Fault::Header, "%s: named header", r.name);
        CHECK(phone.frames == 0, "%s: %zu frames reached the phone", r.name, phone.frames);
    }

    // ---- the request to the phone, and its answer ----
    std::string head = voicerelay::requestHead("192.168.1.50:8080", "AB12", "cd34", 64972);
    CHECK(
        head ==
            "POST /voice?payload=AB12&hmac=cd34 HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nContent-Type: application/octet-stream\r\n"
            "Content-Length: 64972\r\nConnection: close\r\n\r\n",
        "request head: %s", head.c_str());
    CHECK(voicerelay::statusOf("HTTP/1.1 200 OK") == 200, "200");
    CHECK(voicerelay::statusOf("HTTP/1.0 403 Forbidden\r\n") == 403, "403");
    CHECK(voicerelay::statusOf("") == 0 && voicerelay::statusOf("garbage") == 0 &&
              voicerelay::statusOf("HTTP/1.1 2") == 0,
          "no status");
    CHECK(strcmp(voicerelay::errorWord(Fault::Header), "bad_clip") == 0, "words: header");
    CHECK(strcmp(voicerelay::errorWord(Fault::TooMuch), "bad_clip") == 0, "words: too much");
    CHECK(strcmp(voicerelay::errorWord(Fault::TooLittle), "cut_off") == 0, "words: too little");
    CHECK(strcmp(voicerelay::errorWord(Fault::Phone), "cut_off") == 0, "words: phone");
    CHECK(strcmp(voicerelay::errorWord(Fault::Crypto), "box_error") == 0, "words: crypto");
    CHECK(
        voicerelay::httpStatusForWord("busy") == 429 && voicerelay::httpStatusForWord("low_memory") == 503 &&
            voicerelay::httpStatusForWord("unknown_phone") == 404 && voicerelay::httpStatusForWord("old_app") == 409 &&
            voicerelay::httpStatusForWord("no_answer") == 502 && voicerelay::httpStatusForWord("cut_off") == 502 &&
            voicerelay::httpStatusForWord("bad_clip") == 400 && voicerelay::httpStatusForWord("bad_length") == 400 &&
            voicerelay::httpStatusForWord("phone_refused") == 502 && voicerelay::httpStatusForWord("box_error") == 500,
        "the status codes the page gets");

    printf("%d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
