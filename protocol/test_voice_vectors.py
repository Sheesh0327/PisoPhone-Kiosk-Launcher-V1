"""Checks the voice message reference (gen_voice_vectors.py) and its committed fixture. Run: python3 -m pytest -x protocol/test_voice_vectors.py"""

import hashlib
import json
import math
import os
import struct
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_voice_vectors as g

FIXTURE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures", "box_phone_voice_v1.json")


def fixture() -> dict:
    with open(FIXTURE) as f:
        return json.load(f)


def test_the_committed_fixture_is_what_the_generator_makes() -> None:
    assert fixture() == json.loads(json.dumps(g.build()))


@pytest.mark.parametrize("samples", [1, 2, 504, 505, 506, 1010, 1011, 4000, 120000])
def test_the_length_is_fixed_by_the_sample_count(samples: int) -> None:
    assert len(g.encode(g.pcm_sawtooth(samples))) == g.clip_bytes_for(samples)


def test_the_longest_clip_and_its_frames() -> None:
    assert g.clip_bytes_for(g.MAX_SAMPLES) == 60841
    assert g.frame_count_for(60841) == 60
    assert g.wire_bytes_for(60841) == 59 * 1093 + 485
    assert g.frame_wire_bytes(1024) == 1093 and g.frame_wire_bytes(16) == 5 + 16 + 32 + 32 and g.frame_wire_bytes(1) == 69


def test_every_clip_decodes_to_its_recorded_hash() -> None:
    for c in fixture()["clips"]:
        clip = bytes.fromhex(c["hex"])
        assert len(clip) == c["bytes"]
        assert g.check_header(clip) is None
        assert g.pcm_sha(g.decode(clip)) == c["decoded_sha256"], c["name"]
        assert g.encode(g.make_pcm(c["generator"])) == clip, c["name"]


def test_silence_is_silent_and_a_sine_survives_the_codec() -> None:
    assert set(g.decode(g.encode(g.pcm_silence(1500)))) == {0}
    pcm = [round(8000 * math.sin(2 * math.pi * 440 * i / 8000)) for i in range(4000)]
    out = g.decode(g.encode(pcm))
    assert len(out) == len(pcm)
    signal = sum(v * v for v in pcm)
    noise = sum((a - b) ** 2 for a, b in zip(pcm, out, strict=True))
    assert 10 * math.log10(signal / noise) > 25


def test_refused_clips_are_refused_for_the_named_reason() -> None:
    rows = fixture()["refused_clips"]
    assert len(rows) >= 10
    for r in rows:
        clip = bytes.fromhex(r["hex"])
        box = g.check_header(clip)
        assert (box == r["reason"]) if r["box_refuses"] else (box is None), r["name"]
        with pytest.raises(g.ClipError) as err:
            g.decode(clip)
        assert str(err.value) == r["reason"], r["name"]


def frames_for(name: str) -> tuple[bytes, list[bytes], int]:
    c = next(x for x in fixture()["clips"] if x["name"] == name)
    clip = bytes.fromhex(c["hex"])
    return clip, g.make_frames(clip, g.SECRET, g.ENVELOPE_HMAC), len(clip)


def test_the_recorded_frames_are_the_ones_made_now() -> None:
    for case in fixture()["frames"]["cases"]:
        clip, frames, size = frames_for(case["clip"])
        wire = b"".join(frames)
        assert (size, len(frames), len(wire)) == (case["clip_bytes"], case["frame_count"], case["wire_bytes"])
        assert hashlib.sha256(wire).hexdigest() == case["wire_sha256"]
        if "frames_hex" in case:
            assert [f.hex() for f in frames] == case["frames_hex"]
        assert g.verify_frames(wire, g.SECRET, g.ENVELOPE_HMAC, size) == clip


def refused(wire: bytes, size: int, reason: str, secret: str = g.SECRET, env: str = g.ENVELOPE_HMAC) -> None:
    with pytest.raises(g.FrameError) as err:
        g.verify_frames(wire, secret, env, size)
    assert str(err.value) == reason


def test_a_damaged_set_of_frames_is_refused() -> None:
    _, frames, size = frames_for("sawtooth-0.5s")
    wire = b"".join(frames)
    first = len(frames[0])
    flip = lambda data, at: data[:at] + bytes([data[at] ^ 1]) + data[at + 1 :]
    refused(wire[:-1], size, "length")                                 # one byte short
    refused(wire + b"\x00", size, "length")                            # one byte long
    refused(wire[:first], size, "length")                              # the last frame is missing
    refused(frames[0] + frames[0] + frames[1], size, "length")         # a repeated frame, so too long
    refused(frames[1] + frames[0], size, "seq")                        # reordered
    refused(flip(wire, 0), size, "last")                               # the first frame says it is the last
    refused(flip(wire, 1), size, "seq")                                # sequence number
    refused(flip(wire, 4), size, "length")                             # the plain length
    refused(flip(wire, 5), size, "mac")                                # the iv
    refused(flip(wire, 25), size, "mac")                               # the ciphertext
    refused(flip(wire, first - 1), size, "mac")                        # the mac itself
    refused(flip(wire, first), size, "last")                           # the second frame loses its last mark
    refused(wire, size + 1, "length")                                  # the signed request said a different size
    refused(wire, size, "mac", secret="another-secret-1234")           # another key
    refused(wire, size, "mac", env=hashlib.sha256(b"x").hexdigest())   # frames of another signed request
    last = len(wire) - len(frames[1])
    refused(wire[:last] + flip(wire[last:], 0), size, "last")          # the last frame says it is not the last


def test_a_frame_from_another_position_in_the_chain_is_refused() -> None:
    _, frames, size = frames_for("sawtooth-0.5s")
    _, other, _ = frames_for("sawtooth-0.5s")
    # Frames made for another envelope fit by length but not by the chain.
    swapped = g.make_frames(b"".join([bytes.fromhex(c["hex"]) for c in fixture()["clips"] if c["name"] == "sawtooth-0.5s"]),
                            g.SECRET, hashlib.sha256(b"y").hexdigest())
    assert len(swapped) == len(frames) == len(other)
    refused(b"".join([frames[0], swapped[1]]), size, "mac")


def test_the_header_bytes_are_big_endian() -> None:
    clip = g.encode(g.pcm_sawtooth(4000))
    assert clip[:8] == struct.pack(">BBHI", 1, 1, 8000, 4000)
