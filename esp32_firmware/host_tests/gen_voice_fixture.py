#!/usr/bin/env python3
"""Turns protocol/fixtures/box_phone_voice_v1.json into a C++ header for voice_clip_test.cpp and voice_frames_test.cpp."""

import json
import os
import sys

src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "protocol", "fixtures", "box_phone_voice_v1.json")
with open(src) as f:
    d = json.load(f)


def q(s: str) -> str:
    return json.dumps(s)  # a JSON string is also a valid C++ string literal for plain ASCII


def flag(b: bool) -> str:
    return "true" if b else "false"


with open(sys.argv[1], "w") as out:
    c = d["constants"]
    out.write(
        "struct VoiceConsts { int version, codec, rate, headerBytes, blockBytes, blockSamples, maxSamples, maxClipBytes, "
        "framePlainMax, frameHead, ivBytes, macBytes; const char* macTag; };\n"
    )
    nums = ", ".join(
        str(c[k])
        for k in ("version", "codec", "rate", "header_bytes", "block_bytes", "block_samples", "max_samples", "max_clip_bytes",
                  "frame_plain_max", "frame_head", "iv_bytes", "mac_bytes")
    )
    out.write(f"static const VoiceConsts VOICE_CONSTS = {{{nums}, {q(c['mac_tag'])}}};\n")
    out.write("struct VoiceClipVec { const char* name; int samples; int bytes; const char* hex; };\n")
    out.write("struct VoiceRefusedVec { const char* name; const char* hex; bool boxRefuses; const char* reason; };\n")
    out.write(
        "struct VoiceFrameCase { const char* clip; int clipBytes; int frameCount; int wireBytes; const char* wireSha256; "
        "int framesHexCount; const char* const* framesHex; };\n"
    )
    out.write("static const VoiceClipVec VOICE_CLIPS[] = {\n")
    out.writelines(f"    {{{q(v['name'])}, {v['samples']}, {v['bytes']}, {q(v['hex'])}}},\n" for v in d["clips"])
    out.write("};\nstatic const VoiceRefusedVec VOICE_REFUSED[] = {\n")
    out.writelines(
        f"    {{{q(v['name'])}, {q(v['hex'])}, {flag(v['box_refuses'])}, {q(v['reason'])}}},\n" for v in d["refused_clips"]
    )
    out.write("};\n")
    fr = d["frames"]
    out.write(f"static const char* const VOICE_SECRET = {q(fr['secret'])};\n")
    out.write(f"static const char* const VOICE_ENVELOPE_HMAC = {q(fr['envelope_hmac'])};\n")
    rows = []
    for i, case in enumerate(fr["cases"]):
        hexes = case.get("frames_hex", [])
        listing = ", ".join(q(h) for h in hexes) or "nullptr"
        out.write(f"static const char* const VOICE_FRAMES_{i}[] = {{{listing}}};\n")
        rows.append(
            f"    {{{q(case['clip'])}, {case['clip_bytes']}, {case['frame_count']}, {case['wire_bytes']}, "
            f"{q(case['wire_sha256'])}, {len(hexes)}, VOICE_FRAMES_{i}}},"
        )
    out.write("static const VoiceFrameCase VOICE_FRAME_CASES[] = {\n" + "\n".join(rows) + "\n};\n")
