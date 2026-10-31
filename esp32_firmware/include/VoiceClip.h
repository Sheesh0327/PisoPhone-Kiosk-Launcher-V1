#ifndef VOICE_CLIP_H
#define VOICE_CLIP_H

// Pure (no Arduino types) check of a voice clip, for the box (host_tests/voice_clip_test.cpp checks it against
// protocol/fixtures/box_phone_voice_v1.json, which the phone's and the admin page's tests check too). Feature 056.
//
// The admin page records up to 15 s and sends the box a clip; the box passes it to the phone and never decodes it:
//   bytes 0..7   version 1, codec 1 (IMA ADPCM, mono), sample rate 8000 (u16), sample count (u32), all big-endian
//   then         blocks of 256 bytes (505 samples each, the last one shorter), each with a 4-byte header and 4-bit codes
// The length of the whole clip is fixed by the sample count, so a header that lies about it is found from the first 8 bytes
// and the length the page announced, before the rest has arrived. Faults inside the blocks are for the phone to find.
#include <cstddef>
#include <cstdint>

namespace voiceclip {

static const size_t kHeaderBytes = 8;
static const size_t kBlockBytes = 256;
static const size_t kBlockSamples = 505;    // one sample in the block header, two per byte after its 4 bytes
static const uint32_t kMaxSamples = 120000; // 15.0 s at 8 kHz
static const size_t kMaxClipBytes = 60841;  // clipBytesFor(kMaxSamples)
static const uint8_t kVersion = 1;
static const uint8_t kCodec = 1;
static const uint16_t kRate = 8000;

enum class Check : uint8_t { Ok, Short, Version, Codec, Rate, Empty, TooLong, Length };

inline const char* reasonName(Check c) {
    switch (c) {
    case Check::Ok:
        return "ok";
    case Check::Short:
        return "short";
    case Check::Version:
        return "version";
    case Check::Codec:
        return "codec";
    case Check::Rate:
        return "rate";
    case Check::Empty:
        return "empty";
    case Check::TooLong:
        return "too_long";
    case Check::Length:
        return "length";
    }
    return "?";
}

// The length of the whole clip (header and blocks) for a sample count; 0 when there are no samples or more than 15 s.
inline size_t clipBytesFor(uint32_t samples) {
    if (samples == 0 || samples > kMaxSamples) return 0;
    size_t blocks = (samples + kBlockSamples - 1) / kBlockSamples;
    size_t last = samples - kBlockSamples * (blocks - 1);
    return kHeaderBytes + kBlockBytes * (blocks - 1) + 4 + last / 2;
}

// head: the first bytes of the clip (at least 8); totalBytes: how many bytes the whole clip is, as announced or as received.
inline Check checkHeader(const uint8_t* head, size_t headLen, size_t totalBytes) {
    if (!head || headLen < kHeaderBytes) return Check::Short;
    if (head[0] != kVersion) return Check::Version;
    if (head[1] != kCodec) return Check::Codec;
    if ((uint16_t)((head[2] << 8) | head[3]) != kRate) return Check::Rate;
    uint32_t samples = ((uint32_t)head[4] << 24) | ((uint32_t)head[5] << 16) | ((uint32_t)head[6] << 8) | head[7];
    if (samples == 0) return Check::Empty;
    if (samples > kMaxSamples) return Check::TooLong;
    if (totalBytes != clipBytesFor(samples)) return Check::Length;
    return Check::Ok;
}

} // namespace voiceclip

#endif // VOICE_CLIP_H
