"""
The QR card format shared by make_cards.py and its tests. The box (esp32_firmware/include/CardCodec.h) reads the same format.

A card is one line of text in the QR code:

    PISO1.<box>.<serial>.<seconds>.<signature>

  box        the 12 hex digits (lower case, no colons) of the MAC address of the ONE box the card works on
  serial     1 to 65535, never reused for that box; it is the player's account number
  seconds    the starter time the first scan adds (10800 = 3 hours)
  signature  base64 of the ECDSA P-256 / SHA-256 signature of  pisophone-card-v1|<box>|<serial>|<seconds>
             made with the card key (scripts/make_card_key.py), which is NOT the firmware owner key

The box checks the signature with the public key built into CardPubKey.h, that <box> is its own MAC, and that the serial has
not been used before. Nothing else is stored on the card: the card is the account key, so anyone holding it (or a photo of it)
can use its time.
"""

import base64
import re

PREFIX = "PISO1"
MAX_SERIAL = 65535
MIN_SECONDS = 60
MAX_SECONDS = 240 * 3600
BOX_RE = re.compile(r"^[0-9a-f]{12}$")


def normalize_box(text: str) -> str:
    """'AA:BB:CC:DD:EE:FF', 'aa-bb-cc-dd-ee-ff' or 'aabbccddeeff' -> 'aabbccddeeff'."""
    box = re.sub(r"[^0-9a-fA-F]", "", text).lower()
    if not BOX_RE.match(box):
        raise ValueError(f"not a MAC address: {text!r} (12 hex digits, as shown on the box dashboard)")
    return box


def message(box: str, serial: int, seconds: int) -> bytes:
    return f"pisophone-card-v1|{box}|{serial}|{seconds}".encode()


def check_fields(box: str, serial: int, seconds: int) -> None:
    if not BOX_RE.match(box):
        raise ValueError("box must be 12 lower-case hex digits")
    if not 1 <= serial <= MAX_SERIAL:
        raise ValueError(f"serial must be 1 to {MAX_SERIAL}")
    if not MIN_SECONDS <= seconds <= MAX_SECONDS:
        raise ValueError(f"seconds must be {MIN_SECONDS} to {MAX_SECONDS}")


def encode(private_key, box: str, serial: int, seconds: int) -> str:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    check_fields(box, serial, seconds)
    sig = private_key.sign(message(box, serial, seconds), ec.ECDSA(hashes.SHA256()))
    return f"{PREFIX}.{box}.{serial}.{seconds}.{base64.b64encode(sig).decode()}"


def parse(text: str):
    """Returns (box, serial, seconds, signature_bytes). Raises ValueError for anything that is not a well-formed card."""
    parts = text.strip().split(".")
    if len(parts) != 5 or parts[0] != PREFIX:
        raise ValueError("not a PisoPhone card")
    box, serial_s, seconds_s, sig_b64 = parts[1], parts[2], parts[3], parts[4]
    if not (serial_s.isdigit() and seconds_s.isdigit()):
        raise ValueError("serial and seconds must be whole numbers")
    serial, seconds = int(serial_s), int(seconds_s)
    check_fields(box, serial, seconds)
    return box, serial, seconds, base64.b64decode(sig_b64, validate=True)


def verify(public_key, text: str) -> bool:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    try:
        box, serial, seconds, sig = parse(text)
        public_key.verify(sig, message(box, serial, seconds), ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, InvalidSignature):
        return False
