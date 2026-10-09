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
    check(files == ["all_sheets.pdf", "sheet_01.png", "sheet_02.png"], f"14 cards = 2 sheets + pdf, got {files}")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "3", "--hours", "5", "--key", pem, "--out", out)
    rows = list(csv.DictReader(open(os.path.join(out, f"ledger_{BOX}.csv"))))
    check([int(x["serial"]) for x in rows][-3:] == [15, 16, 17], "the next batch continues the numbering")
    check(rows[-1]["seconds"] == "18000", "5 hours = 18000 s")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", "bad", "--count", "1", "--key", pem, "--out", out, expect_ok=False)
    check(r.returncode != 0, "a bad MAC is refused")
    r = run(os.path.join(SCRIPTS, "make_cards.py"), "--box", BOX, "--count", "1", "--key", pem, "--out", os.path.join(os.path.dirname(SCRIPTS), "cards_leak"), expect_ok=False)
    check(r.returncode != 0 and not os.path.exists(os.path.join(os.path.dirname(SCRIPTS), "cards_leak")), "output inside the repo is refused")

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
