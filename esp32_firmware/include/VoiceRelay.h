#ifndef VOICE_RELAY_H
#define VOICE_RELAY_H

// Pure (no Arduino types) rules of the box's voice message relay (host_tests/voice_relay_test.cpp). Feature 056.
//
// The admin page uploads a recorded clip to the box; the box passes it on to the phone and keeps nothing. This file is the part that
// does not need the network: who may start, how the upload becomes frames, and what every fault is called. A Session holds ONE frame
// buffer (the clip is collected in it, encrypted and signed in place, written to the phone, and the buffer is used again), so what the
// box holds for a clip is the same few KB whether the clip is a second or 15 s long, and it stores nothing.
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#include "VoiceClip.h"
#include "VoiceFrames.h"

namespace voicerelay {

// A relay starts only if the largest free block stays above this after the session's own buffer: the firmware's own floor
// (Diagnostics.cpp refuses its report below it). Group 0's RS66 numbers say how much room a real box has.
static const size_t kReserveBytes = 24000;
static const unsigned long kFrameWriteMs = 4000; // the phone may take this long over one frame
static const unsigned long kTotalMs =
    12000; // and the whole message this long, from the first byte to the phone's answer
static const unsigned long kStaleMs =
    30000; // a relay older than this is dead: its buffer is freed and the box is free again

enum class Verdict : uint8_t { Ok, BadLength, Busy, LowMemory };

inline const char* verdictName(Verdict v) {
    switch (v) {
    case Verdict::Ok:
        return "ok";
    case Verdict::BadLength:
        return "bad_length";
    case Verdict::Busy:
        return "busy";
    case Verdict::LowMemory:
        return "low_memory";
    }
    return "?";
}

enum class Fault : uint8_t { None, Header, TooMuch, TooLittle, Phone, Crypto };

class Session {
public:
    // secret: the phone's box key; envelopeMac: the 32 raw bytes of the signed request's hmac; clipBytes: how long the clip is.
    bool begin(const std::string& secret, const uint8_t envelopeMac[32], size_t clipBytes) {
        begun_ = false;
        fault_ = Fault::None;
        received_ = held_ = maxHeld_ = frames_ = 0;
        headerChecked_ = false;
        if (clipBytes < voiceclip::clipBytesFor(1) || clipBytes > voiceclip::kMaxClipBytes) return false;
        if (!framer_.begin(secret, envelopeMac)) return false;
        clipBytes_ = clipBytes;
        begun_ = true;
        return true;
    }

    // A piece of the upload, of any size. Whenever a frame is full (or the clip is complete) it is encrypted, signed and handed to
    // sink(frame, length), which says whether the phone took it; iv(iv16) fills 16 fresh random bytes. False once anything is wrong.
    template <class Sink, class Iv> bool feed(const uint8_t* data, size_t len, Sink& sink, Iv& iv) {
        if (!begun_ || fault_ != Fault::None) return false;
        if (len > clipBytes_ - received_) {
            fault_ = Fault::TooMuch;
            return false;
        }
        uint8_t* plain = buf_ + voiceframes::kHeadBytes + voiceframes::kIvBytes;
        while (len > 0) {
            size_t room = voiceframes::kPlainMax - held_;
            size_t take = len < room ? len : room;
            memcpy(plain + held_, data, take);
            held_ += take;
            received_ += take;
            data += take;
            len -= take;
            if (held_ > maxHeld_) maxHeld_ = held_;
            // The first 8 bytes are all the box looks at: nothing reaches the phone from a clip whose header is wrong.
            if (!headerChecked_ && received_ >= voiceclip::kHeaderBytes) {
                headerChecked_ = true;
                if (voiceclip::checkHeader(plain, voiceclip::kHeaderBytes, clipBytes_) != voiceclip::Check::Ok) {
                    fault_ = Fault::Header;
                    return false;
                }
            }
            bool last = received_ == clipBytes_;
            if ((held_ == voiceframes::kPlainMax || last) && !emit(last, sink, iv)) return false;
        }
        return true;
    }

    // After the last byte of the upload: true only if exactly the announced clip came and went.
    template <class Sink, class Iv> bool finish(Sink&, Iv&) {
        if (!begun_ || fault_ != Fault::None) return false;
        if (received_ != clipBytes_) {
            fault_ = Fault::TooLittle;
            return false;
        }
        return true;
    }

    Fault fault() const { return fault_; }
    size_t received() const { return received_; }
    size_t framesSent() const { return frames_; }
    size_t maxHeld() const { // the most clip bytes this session ever held at once
        return maxHeld_;
    }

private:
    template <class Sink, class Iv> bool emit(bool last, Sink& sink, Iv& iv) {
        uint8_t ivBytes[voiceframes::kIvBytes];
        iv(ivBytes);
        size_t n = framer_.next(buf_ + voiceframes::kHeadBytes + voiceframes::kIvBytes, held_, last, ivBytes, buf_);
        if (n == 0) {
            fault_ = Fault::Crypto;
            return false;
        }
        if (!sink(buf_, n)) {
            fault_ = Fault::Phone;
            return false;
        }
        frames_++;
        held_ = 0;
        return true;
    }

    uint8_t buf_[voiceframes::kFrameMax];
    voiceframes::Framer framer_;
    size_t clipBytes_ = 0;
    size_t received_ = 0;
    size_t held_ = 0;
    size_t maxHeld_ = 0;
    size_t frames_ = 0;
    Fault fault_ = Fault::None;
    bool begun_ = false;
    bool headerChecked_ = false;
};

// Whether a relay may start: a bad request is named first, then a box that is already passing a clip, then a box short of memory.
inline Verdict admit(bool busy, size_t largestFreeBlock, size_t clipBytes) {
    if (clipBytes < voiceclip::clipBytesFor(1) || clipBytes > voiceclip::kMaxClipBytes) return Verdict::BadLength;
    if (busy) return Verdict::Busy;
    if (largestFreeBlock < kReserveBytes + sizeof(Session)) return Verdict::LowMemory;
    return Verdict::Ok;
}

// The request the box makes of the phone. The signed payload and hmac are in the URL as for every box message; the frames are the body.
inline std::string requestHead(const std::string& hostPort, const std::string& payload, const std::string& hmac,
                               size_t contentLength) {
    return "POST /voice?payload=" + payload + "&hmac=" + hmac + " HTTP/1.1\r\nHost: " + hostPort +
           "\r\nContent-Type: application/octet-stream\r\nContent-Length: " + std::to_string(contentLength) +
           "\r\nConnection: close\r\n\r\n";
}

// The status of "HTTP/1.1 200 OK", or 0.
inline int statusOf(const std::string& line) {
    if (line.size() < 12 || line.compare(0, 7, "HTTP/1.") != 0 || line[8] != ' ') return 0;
    int code = 0;
    for (size_t i = 9; i < 12; i++) {
        if (line[i] < '0' || line[i] > '9') return 0;
        code = code * 10 + (line[i] - '0');
    }
    return code;
}

// The words the admin page reads (the "error" of its JSON answer).
inline const char* errorWord(Fault f) {
    switch (f) {
    case Fault::Header:
    case Fault::TooMuch:
        return "bad_clip";
    case Fault::TooLittle:
    case Fault::Phone:
        return "cut_off";
    case Fault::Crypto:
        return "box_error";
    case Fault::None:
        break;
    }
    return "";
}

inline int httpStatusForWord(const std::string& word) {
    if (word == "busy") return 429;
    if (word == "low_memory") return 503;
    if (word == "unknown_phone") return 404;
    if (word == "old_app") return 409;
    if (word == "bad_clip" || word == "bad_length") return 400;
    if (word == "no_answer" || word == "cut_off" || word == "phone_refused") return 502;
    if (word == "timeout") return 504;
    return 500;
}

} // namespace voicerelay

#endif // VOICE_RELAY_H
