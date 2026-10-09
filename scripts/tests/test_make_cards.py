#!/usr/bin/env python3
"""Tests for the card format, key script and batch generator. Run: python3 scripts/tests/test_make_cards.py"""

import csv
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)
import card_format  # noqa: E402

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

failures = 0


def check(cond, what):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", what)


def run(*args, expect_ok=True):
    r = subprocess.run([sys.executable] + list(args), capture_output=True, text=True)
    if expect_ok and r.returncode != 0:
        print(r.stdout, r.stderr)
    return r


key = ec.generate_private_key(ec.SECP256R1())
pub = key.public_key()
BOX = "aabbccddeeff"

# ---- format ----
card = card_format.encode(key, BOX, 42, 10800)
check(card.startswith("PISO1.aabbccddeeff.42.10800."), "card text layout")
check(len(card) < 140, f"card short enough for a small QR ({len(card)} chars)")
check(card_format.verify(pub, card), "a good card verifies")
check(card_format.parse(card)[:3] == (BOX, 42, 10800), "fields parse back")
check(not card_format.verify(pub, card.replace(".42.", ".43.")), "a changed serial breaks the signature")
check(not card_format.verify(pub, card.replace(".10800.", ".99999.")), "changed seconds break the signature")
check(not card_format.verify(pub, card.replace(BOX, "112233445566")), "another box's id breaks the signature")
other = ec.generate_private_key(ec.SECP256R1())
check(not card_format.verify(other.public_key(), card), "a different key's public half rejects it")
for bad in ("", "PISO1", "PISO2." + card[6:], card + ".x", "PISO1.aabbccddeeff.0.10800.AA==", "PISO1.aabbccddeeff.42.5.AA=="):
    check(not card_format.verify(pub, bad), f"malformed text refused: {bad[:30]!r}")
check(card_format.normalize_box("AA:BB:CC:DD:EE:FF") == BOX and card_format.normalize_box("aa-bb-cc-dd-ee-ff") == BOX, "MAC forms")
for bad_box in ("", "AA:BB", "gg:bb:cc:dd:ee:ff"):
    try:
        card_format.normalize_box(bad_box)
        check(False, f"bad MAC accepted: {bad_box!r}")
    except ValueError:
        pass

# ---- scripts, end to end ----
with tempfile.TemporaryDirectory() as tmp:
    pem = os.path.join(tmp, "k.pem")
    with open(pem, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    out = os.path.join(tmp, "cards")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", "AA:BB:CC:DD:EE:FF", "--count", "14", "--key", pem, "--out", out)
    check(r.returncode == 0, "first batch runs")
    rows = list(csv.DictReader(open(os.path.join(out, f"ledger_{BOX}.csv"))))
    check([int(x["serial"]) for x in rows] == list(range(1, 15)), "serials 1..14 in the ledger")
    check(all(x["seconds"] == "10800" for x in rows), "3 hours by default")
    batches = [d for d in os.listdir(out) if d.startswith("batch_")]
    check(len(batches) == 1, "one batch folder")
    files = sorted(os.listdir(os.path.join(out, batches[0])))
    check(files == ["back_01.png", "back_02.png", "backs.pdf", "duplex_both_sides.pdf", "front_01.png", "front_02.png", "fronts.pdf"],
          f"14 cards = 2 sheets, each with a front and a back, plus three PDFs; got {files}")

    def pdf_pages(path):
        data = open(path, "rb").read()
        return data.count(b"/Type /Page") - data.count(b"/Type /Pages")
    folder = os.path.join(out, batches[0])
    check(pdf_pages(os.path.join(folder, "fronts.pdf")) == 2 and pdf_pages(os.path.join(folder, "backs.pdf")) == 2, "fronts.pdf and backs.pdf have 2 pages")
    check(pdf_pages(os.path.join(folder, "duplex_both_sides.pdf")) == 4, "duplex_both_sides.pdf alternates front and back: 4 pages")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "3", "--hours", "5", "--key", pem, "--out", out)
    rows = list(csv.DictReader(open(os.path.join(out, f"ledger_{BOX}.csv"))))
    check([int(x["serial"]) for x in rows][-3:] == [15, 16, 17], "the next batch continues the numbering")
    check(rows[-1]["seconds"] == "18000", "5 hours = 18000 s")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", "bad", "--count", "1", "--key", pem, "--out", out, expect_ok=False)
    check(r.returncode != 0, "a bad MAC is refused")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "1", "--key", pem, "--out", os.path.join(os.path.dirname(SCRIPTS), "cards_leak"), expect_ok=False)
    check(r.returncode != 0 and not os.path.exists(os.path.join(os.path.dirname(SCRIPTS), "cards_leak")), "output inside the repo is refused")

    # Back-to-back: the logo must land exactly behind its QR once the paper is turned over.
    from PIL import Image
    import make_cards as mc

    def dark(img, rect, inset=40):  # a back: the logo's dark background near the card's corner
        x, y, w, h = rect
        r, g, b = img.getpixel((x + inset, y + 40))[:3]
        return r + g + b < 200

    def white(img, rect, inset=40):  # nothing printed near the corner
        x, y, w, h = rect
        return img.getpixel((x + inset, y + 40))[:3] == (255, 255, 255)

    def has_front(img, rect):  # a front: its grey cut line on the left edge
        x, y, w, h = rect
        return img.getpixel((x + 1, y + h // 2))[:3] != (255, 255, 255)

    f2 = Image.open(os.path.join(folder, "front_02.png")).convert("RGB")
    b2 = Image.open(os.path.join(folder, "back_02.png")).convert("RGB")
    # sheet 2 holds cards 13 and 14: fronts at (0,0) and (1,0); the backs belong at the mirrored (2,0) and (1,0), nothing at (0,0)
    check(has_front(f2, mc.card_rect(0, 0)) and has_front(f2, mc.card_rect(1, 0)) and not has_front(f2, mc.card_rect(2, 0)), "fronts: cards 13, 14 at the first two places")
    check(dark(b2, mc.card_rect(2, 0)) and dark(b2, mc.card_rect(1, 0)) and white(b2, mc.card_rect(0, 0)), "backs (turned over the long edge): mirrored left to right")
    check(white(b2, mc.card_rect(0, 1)) and white(b2, mc.card_rect(2, 3)), "backs: no logo where there is no card")
    b1 = Image.open(os.path.join(folder, "back_01.png")).convert("RGB")
    check(all(dark(b1, mc.card_rect(c, r)) for c in range(3) for r in range(4)), "a full sheet has 12 backs")
    check(mc.back_position(0, 0, "long") == (2, 0) and mc.back_position(2, 3, "long") == (0, 3), "long-edge mapping")
    check(mc.back_position(0, 0, "short") == (0, 3) and mc.back_position(2, 3, "short") == (2, 0), "short-edge mapping")
    # the card edge of a front and the colour of its back share one rectangle: a back reaches a little past the front's edge
    x, y, w, h = mc.card_rect(2, 0)
    check(b2.getpixel((x - mc.BLEED + 2, y + h // 2))[:3] != (255, 255, 255) and b2.getpixel((x - mc.BLEED - 6, y + h // 2))[:3] == (255, 255, 255), "the back has a small bleed past the card edge")

    # short-edge flip and a printer shift
    out2 = os.path.join(tmp, "cards_short")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "2", "--key", pem, "--out", out2, "--flip", "short", "--back-shift-x-mm", "5")
    check(r.returncode == 0, "short-edge batch runs")
    fb = os.path.join(out2, [d for d in os.listdir(out2) if d.startswith("batch_")][0])
    bs = Image.open(os.path.join(fb, "back_01.png")).convert("RGB")
    check(dark(bs, mc.card_rect(0, 3), 120) and dark(bs, mc.card_rect(1, 3), 120) and white(bs, mc.card_rect(0, 0), 120), "short-edge: mirrored top to bottom")
    shift_px = int(round(5 / 25.4 * mc.DPI))
    sx, sy, sw, sh = mc.card_rect(0, 3)
    check(bs.getpixel((sx - mc.BLEED + 2, sy + sh // 2))[:3] == (255, 255, 255) and bs.getpixel((sx - mc.BLEED + shift_px + 2, sy + sh // 2))[:3] != (255, 255, 255), "the 5 mm shift moves the backs right")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "1", "--key", pem, "--out", os.path.join(tmp, "x"), "--logo", os.path.join(tmp, "nope.png"), expect_ok=False)
    check(r.returncode != 0, "a missing logo is refused before any serial is used")
    check(not os.path.exists(os.path.join(tmp, "x", f"ledger_{BOX}.csv")), "and no serial was reserved")

    # key script: writes a header, refuses to overwrite, refuses the repo
    kp, hp = os.path.join(tmp, "card.pem"), os.path.join(tmp, "CardPubKey.h")
    r = run(os.path.join(SCRIPTS, "make_card_key.py"), kp, "--header", hp)
    check(r.returncode == 0 and os.path.exists(kp) and "CARD_PUBKEY_DER" in open(hp).read(), "key script writes key and header")
    check(oct(os.stat(kp).st_mode & 0o777) == "0o600", "private key is owner-only")
    r = run(os.path.join(SCRIPTS, "make_card_key.py"), kp, "--header", hp, expect_ok=False)
    check(r.returncode != 0, "key script refuses to overwrite")
    r = run(os.path.join(SCRIPTS, "make_card_key.py"), os.path.join(SCRIPTS, "oops.pem"), "--header", hp, expect_ok=False)
    check(r.returncode != 0 and not os.path.exists(os.path.join(SCRIPTS, "oops.pem")), "key script refuses a path inside the repo")

print("test_make_cards:", "all passed" if failures == 0 else f"{failures} FAILED")
sys.exit(1 if failures else 0)
