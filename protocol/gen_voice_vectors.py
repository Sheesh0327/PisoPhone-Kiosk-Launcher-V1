#!/usr/bin/env python3
"""
Writes protocol/fixtures/box_phone_voice_v1.json: known-answer vectors for the voice message (feature 056).

Both ends must reproduce them: the box (esp32_firmware/host_tests/voice_clip_test.cpp and voice_frames_test.cpp), the phone
(app/src/test/.../voice/) and the admin page's encoder (scripts/tests/test_portal_voice_ui.py). They are made here, independent of all
three, and committed. The existing box_phone_v1.json (encryption and signing of ordinary messages) is not changed.

THE CLIP (what the admin page makes and the phone plays)
  bytes 0..7   version 1, codec 1 (IMA ADPCM, mono), sample rate 8000 (u16), sample count (u32), all big-endian
  then         blocks of 256 bytes (505 samples each, the last block shorter): 4-byte header (predictor i16 little-endian,
               step index 0..88, reserved 0), then 4-bit codes, low nibble first. The first sample of a block is the predictor.
  at most 120000 samples (15.0 s) = 60841 bytes. The length is fixed by the sample count, so a header that lies is found.

THE FRAMES (what the box sends the phone, one at a time: the box never holds the clip)
  The signed request (payload + hmac in the URL, as every box message) says how many clip bytes follow; its hmac seeds the chain.
  frame = flags (bit 0 = last) | seq u16 | plain length u16 | iv 16 | AES-256-CBC(PKCS#7) | mac 32       (big-endian)
  mac   = HMAC-SHA256(secret, "pisovoice1" | previous mac | the 5 header bytes | iv | ciphertext)
  the first "previous mac" is the 32 raw bytes of the signed request's hmac. key = SHA-256(secret), as for every box message.
  at most 1024 clip bytes per frame (60841 bytes = 60 frames). The phone plays only after the last frame has checked out.

Run again only when the format changes on purpose (and bump the version in the file name).
"""

import hashlib
import hmac
import json
import os
import struct
from collections.abc import Sequence

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

STEP = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
    1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
    8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
]
INDEX = [-1, -1, -1, -1, 2, 4, 6, 8] * 2
assert len(STEP) == 89

VERSION, CODEC, RATE = 1, 1, 8000
BLOCK_BYTES, BLOCK_SAMPLES, MAX_SAMPLES = 256, 505, 120000
HEADER_BYTES = 8
FRAME_PLAIN_MAX = 1024
FRAME_HEAD, IV_BYTES, MAC_BYTES = 5, 16, 32
MAC_TAG = b"pisovoice1"

SECRET = "Abcd2345Efgh6789Jkmn"  # (the first secret of box_phone_v1.json)
ENVELOPE_HMAC = hmac.new(SECRET.encode(), b"voice-envelope-payload", hashlib.sha256).hexdigest()


class ClipError(Exception):
    pass


class FrameError(Exception):
    pass


def audio_bytes_for(samples: int) -> int:
    blocks = -(-samples // BLOCK_SAMPLES)
    last = samples - BLOCK_SAMPLES * (blocks - 1)
    return BLOCK_BYTES * (blocks - 1) + 4 + (last - 1 + 1) // 2


def clip_bytes_for(samples: int) -> int:
    return HEADER_BYTES + audio_bytes_for(samples)


def _clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def _encode_sample(sample: int, pred: int, index: int) -> tuple[int, int, int]:
    step = STEP[index]
    diff = sample - pred
    code = 0
    if diff < 0:
        code = 8
        diff = -diff
    vpdiff = step >> 3
    if diff >= step:
        code |= 4
        diff -= step
        vpdiff += step
    step >>= 1
    if diff >= step:
        code |= 2
        diff -= step
        vpdiff += step
    step >>= 1
    if diff >= step:
        code |= 1
        vpdiff += step
    pred = _clamp(pred - vpdiff if code & 8 else pred + vpdiff, -32768, 32767)
    return code, pred, _clamp(index + INDEX[code], 0, 88)


def _decode_sample(code: int, pred: int, index: int) -> tuple[int, int]:
    step = STEP[index]
    vpdiff = step >> 3
    if code & 4:
        vpdiff += step
    if code & 2:
        vpdiff += step >> 1
    if code & 1:
        vpdiff += step >> 2
    pred = _clamp(pred - vpdiff if code & 8 else pred + vpdiff, -32768, 32767)
    return pred, _clamp(index + INDEX[code], 0, 88)


def encode(pcm: Sequence[int], *, unchecked: bool = False) -> bytes:
    n = len(pcm)
    if n == 0 or (n > MAX_SAMPLES and not unchecked):
        raise ValueError("a clip has 1 to 120000 samples")
    out = bytearray(struct.pack(">BBHI", VERSION, CODEC, RATE, n))
    index = 0
    for start in range(0, n, BLOCK_SAMPLES):
        block = pcm[start : start + BLOCK_SAMPLES]
        pred = block[0]
        out += struct.pack("<hBB", pred, index, 0)
        codes = []
        for s in block[1:]:
            code, pred, index = _encode_sample(s, pred, index)
            codes.append(code)
        if len(codes) % 2:
            codes.append(0)
        for i in range(0, len(codes), 2):
            out.append(codes[i] | (codes[i + 1] << 4))
    return bytes(out)


def check_header(clip: bytes) -> str | None:
    """What the BOX checks (it never decodes): the 8 header bytes and that the total length fits the sample count."""
    if len(clip) < HEADER_BYTES:
        return "short"
    version, codec, rate, n = struct.unpack(">BBHI", clip[:HEADER_BYTES])
    if version != VERSION:
        return "version"
    if codec != CODEC:
        return "codec"
    if rate != RATE:
        return "rate"
    if n == 0:
        return "empty"
    if n > MAX_SAMPLES:
        return "too_long"
    if len(clip) != clip_bytes_for(n):
        return "length"
    return None


def decode(clip: bytes) -> list[int]:
    """What the PHONE does: the box's checks, then the block headers, then the samples."""
    reason = check_header(clip)
    if reason:
        raise ClipError(reason)
    n = struct.unpack(">I", clip[4:8])[0]
    pos, remaining, out = HEADER_BYTES, n, []
    while remaining > 0:
        take = min(BLOCK_SAMPLES, remaining)
        size = 4 + take // 2
        block = clip[pos : pos + size]
        pred, index, reserved = struct.unpack("<hBB", block[:4])
        if index > 88 or reserved != 0:
            raise ClipError("block_header")
        out.append(pred)
        for i in range(take - 1):
            byte = block[4 + i // 2]
            pred, index = _decode_sample((byte >> 4) if i % 2 else (byte & 15), pred, index)
            out.append(pred)
        pos += size
        remaining -= take
    return out


# ---- reference sound (integer maths only, so every language makes the same samples) ----

def pcm_silence(n: int) -> list[int]:
    return [0] * n


def pcm_sawtooth(n: int) -> list[int]:
    return [(i * 1117) % 20001 - 10000 for i in range(n)]


def pcm_noise(n: int, seed: int) -> list[int]:
    x, out = seed, []
    for _ in range(n):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out.append(((x >> 8) % 65536) - 32768)
    return out


def make_pcm(gen: dict) -> list[int]:
    kind, n = gen["kind"], gen["samples"]
    if kind == "silence":
        return pcm_silence(n)
    if kind == "sawtooth":
        return pcm_sawtooth(n)
    if kind == "noise":
        return pcm_noise(n, gen["seed"])
    raise ValueError(kind)


# ---- frames ----

def frame_count_for(clip_bytes: int) -> int:
    return -(-clip_bytes // FRAME_PLAIN_MAX)


def frame_wire_bytes(plain_len: int) -> int:
    return FRAME_HEAD + IV_BYTES + 16 * (plain_len // 16 + 1) + MAC_BYTES


def wire_bytes_for(clip_bytes: int) -> int:
    full, rest = divmod(clip_bytes, FRAME_PLAIN_MAX)
    return full * frame_wire_bytes(FRAME_PLAIN_MAX) + (frame_wire_bytes(rest) if rest else 0)


def iv_rule(seq: int) -> bytes:
    return bytes((seq * 31 + j * 7 + 1) % 256 for j in range(IV_BYTES))


def _mac(secret: str, prev: bytes, head: bytes, iv: bytes, cipher: bytes) -> bytes:
    return hmac.new(secret.encode(), MAC_TAG + prev + head + iv + cipher, hashlib.sha256).digest()


def make_frames(clip: bytes, secret: str, envelope_hmac_hex: str) -> list[bytes]:
    key = hashlib.sha256(secret.encode()).digest()
    prev = bytes.fromhex(envelope_hmac_hex)
    count = frame_count_for(len(clip))
    frames = []
    for seq in range(count):
        plain = clip[seq * FRAME_PLAIN_MAX : (seq + 1) * FRAME_PLAIN_MAX]
        iv = iv_rule(seq)
        padder = padding.PKCS7(128).padder()
        enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        cipher = enc.update(padder.update(plain) + padder.finalize()) + enc.finalize()
        head = struct.pack(">BHH", 1 if seq == count - 1 else 0, seq, len(plain))
        mac = _mac(secret, prev, head, iv, cipher)
        frames.append(head + iv + cipher + mac)
        prev = mac
    return frames


def verify_frames(wire: bytes, secret: str, envelope_hmac_hex: str, clip_bytes: int) -> bytes:
    """What the PHONE does with the body: the exact length is known from the signed request, then frame by frame."""
    if clip_bytes < 1 or wire_bytes_for(clip_bytes) != len(wire):
        raise FrameError("length")
    key = hashlib.sha256(secret.encode()).digest()
    prev = bytes.fromhex(envelope_hmac_hex)
    count, pos, clip = frame_count_for(clip_bytes), 0, bytearray()
    for seq in range(count):
        head = wire[pos : pos + FRAME_HEAD]
        flags, got_seq, plain_len = struct.unpack(">BHH", head)
        if flags & ~1:
            raise FrameError("flags")
        if got_seq != seq:
            raise FrameError("seq")
        if bool(flags & 1) != (seq == count - 1):
            raise FrameError("last")
        if plain_len != min(FRAME_PLAIN_MAX, clip_bytes - FRAME_PLAIN_MAX * seq):
            raise FrameError("length")
        size = frame_wire_bytes(plain_len)
        iv = wire[pos + FRAME_HEAD : pos + FRAME_HEAD + IV_BYTES]
        cipher = wire[pos + FRAME_HEAD + IV_BYTES : pos + size - MAC_BYTES]
        mac = wire[pos + size - MAC_BYTES : pos + size]
        if not hmac.compare_digest(mac, _mac(secret, prev, head, iv, cipher)):
            raise FrameError("mac")
        dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        unpadder = padding.PKCS7(128).unpadder()
        try:
            plain = unpadder.update(dec.update(cipher) + dec.finalize()) + unpadder.finalize()
        except ValueError as e:
            raise FrameError("padding") from e
        if len(plain) != plain_len:
            raise FrameError("padding")
        clip += plain
        prev = mac
        pos += size
    return bytes(clip)


# ---- the fixture ----

CLIPS = [
    ("one-sample", {"kind": "noise", "samples": 1, "seed": 3}),
    ("silence-0.1s", {"kind": "silence", "samples": 800}),
    ("sawtooth-0.5s", {"kind": "sawtooth", "samples": 4000}),
    ("one-full-block", {"kind": "sawtooth", "samples": 505}),
    ("one-block-and-one-sample", {"kind": "sawtooth", "samples": 506}),
    ("noise-1s", {"kind": "noise", "samples": 8000, "seed": 1}),
    ("max-15s", {"kind": "noise", "samples": MAX_SAMPLES, "seed": 7}),
]
FRAME_CASES = ["one-sample", "sawtooth-0.5s", "max-15s"]


def pcm_sha(samples: Sequence[int]) -> str:
    return hashlib.sha256(struct.pack(f"<{len(samples)}h", *samples)).hexdigest()


def refused_clips() -> list[dict]:
    good = encode(pcm_sawtooth(800))

    def row(name: str, clip: bytes, box: bool, phone: bool, reason: str) -> dict:
        return {"name": name, "hex": clip.hex(), "box_refuses": box, "phone_refuses": phone, "reason": reason}

    bad_index = bytearray(good)
    bad_index[HEADER_BYTES + 2] = 89
    bad_reserved = bytearray(good)
    bad_reserved[HEADER_BYTES + 3] = 1
    lies = bytearray(good)
    lies[4:8] = struct.pack(">I", 1000)
    return [
        row("input-shorter-than-a-header", good[:5], True, True, "short"),
        row("version-2", bytes([2]) + good[1:], True, True, "version"),
        row("codec-2", good[:1] + bytes([2]) + good[2:], True, True, "codec"),
        row("rate-16000", good[:2] + struct.pack(">H", 16000) + good[4:], True, True, "rate"),
        row("zero-samples", good[:4] + struct.pack(">I", 0), True, True, "empty"),
        row("over-15-seconds", encode(pcm_silence(MAX_SAMPLES + 1), unchecked=True), True, True, "too_long"),
        row("one-byte-short", good[:-1], True, True, "length"),
        row("one-byte-long", good + b"\x00", True, True, "length"),
        row("header-claims-more-samples", bytes(lies), True, True, "length"),
        row("step-index-89", bytes(bad_index), False, True, "block_header"),
        row("reserved-byte-set", bytes(bad_reserved), False, True, "block_header"),
    ]


def build() -> dict:
    clips = []
    by_name = {}
    for name, gen in CLIPS:
        clip = encode(make_pcm(gen))
        assert check_header(clip) is None
        by_name[name] = clip
        clips.append({
            "name": name, "generator": gen, "samples": gen["samples"], "bytes": len(clip), "hex": clip.hex(),
            "decoded_sha256": pcm_sha(decode(clip)),
        })
    cases = []
    for name in FRAME_CASES:
        clip = by_name[name]
        frames = make_frames(clip, SECRET, ENVELOPE_HMAC)
        wire = b"".join(frames)
        assert verify_frames(wire, SECRET, ENVELOPE_HMAC, len(clip)) == clip
        case = {"clip": name, "clip_bytes": len(clip), "frame_count": len(frames), "wire_bytes": len(wire),
                "wire_sha256": hashlib.sha256(wire).hexdigest()}
        if len(frames) <= 4:
            case["frames_hex"] = [f.hex() for f in frames]
        cases.append(case)
    return {
        "version": 1,
        "constants": {
            "version": VERSION, "codec": CODEC, "rate": RATE, "header_bytes": HEADER_BYTES, "block_bytes": BLOCK_BYTES,
            "block_samples": BLOCK_SAMPLES, "max_samples": MAX_SAMPLES, "max_clip_bytes": clip_bytes_for(MAX_SAMPLES),
            "frame_plain_max": FRAME_PLAIN_MAX, "frame_head": FRAME_HEAD, "iv_bytes": IV_BYTES, "mac_bytes": MAC_BYTES,
            "mac_tag": MAC_TAG.decode(),
        },
        "pcm_generators": {
            "silence": "0",
            "sawtooth": "(i * 1117) % 20001 - 10000",
            "noise": "x = (x * 1103515245 + 12345) & 0x7FFFFFFF; sample = ((x >> 8) % 65536) - 32768   (x starts at the seed)",
        },
        "clips": clips,
        "refused_clips": refused_clips(),
        "frames": {
            "secret": SECRET, "envelope_hmac": ENVELOPE_HMAC,
            "iv_rule": "iv[j] = (seq * 31 + j * 7 + 1) % 256, j = 0..15", "cases": cases,
        },
    }


def main() -> None:
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "box_phone_voice_v1.json")
    with open(path, "w") as f:
        json.dump(build(), f, indent=1)
        f.write("\n")
    print("wrote", path)


if __name__ == "__main__":
    main()
