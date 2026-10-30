#!/usr/bin/env python3
"""
Makes a shop's brand pack: the signed file that puts the shop's logo (and one optional picture) on its guest Wi-Fi page.
Owner only: run it on your own computer with your private key (needs `pip install cryptography`).

A shop owner can change texts and colours in the router console, but a picture only ever comes from a pack YOU signed, so a shop cannot
put its own artwork on the page and nobody can pass a forged one to a router. The router program checks your signature (with the public
key built into it, tools/pisoportal/owner_key.b64) and that each picture is a whole PNG or JPEG of at most 64 KB and 512 by 512 pixels.

  python3 scripts/make_brand_pack.py --private ~/pisophone_license_key.pem --logo nena-logo.png
  python3 scripts/make_brand_pack.py --private KEY.pem --logo logo.png --picture banner.jpg --out nena.brand

Then send the .brand file to the shop owner. SVG is not accepted (it can carry scripts): export the logo as PNG first. The private key must
not be kept inside this repository, and it must be the key whose public half is built into the router program, or no router would take
the pack (this tool checks both). Signed text:
  pisophone-brand-v1|<logo sha256>|<logo size>|<picture sha256 or ->|<picture size>
The pack is six text lines (tools/pisoportal/src/brandpack.rs describes them); a pack never expires and is not tied to one router.
"""

import argparse
import base64
import hashlib
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import owner_key  # (shared key loading)

MAGIC = "pisophone-brand-v1"
MAX_IMAGE = 64 * 1024   # what a router accepts (tools/pisoportal/src/brandpack.rs)
MAX_SIDE = 512


class PackError(Exception):
    """Something the owner can fix: the message says what."""


def _sides(w, h):
    if not (1 <= w <= MAX_SIDE and 1 <= h <= MAX_SIDE):
        raise PackError(f"a picture may be at most {MAX_SIDE} by {MAX_SIDE} pixels (this one says {w} by {h})")


def check_image(data):
    """('png' | 'jpeg', width, height) for a whole PNG or JPEG the router will accept; PackError otherwise (the same rules as the router)."""
    if len(data) > MAX_IMAGE:
        raise PackError("a picture may be at most 64 KB")
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        at, w, h, first = 8, 0, 0, True
        while True:
            if at + 12 > len(data):
                raise PackError("the PNG is cut short")
            length, kind = struct.unpack(">I4s", data[at:at + 8])
            if first:
                if kind != b"IHDR" or length != 13 or at + 12 + length > len(data):
                    raise PackError("the PNG does not start with its header")
                w, h = struct.unpack(">II", data[at + 8:at + 16])
                first = False
            nxt = at + 12 + length
            if kind == b"IEND":
                if length != 0 or nxt != len(data):
                    raise PackError("the PNG has something after its end")
                break
            if nxt > len(data):
                raise PackError("the PNG is cut short")
            at = nxt
        _sides(w, h)
        return "png", w, h
    if data.startswith(b"\xff\xd8\xff"):
        if not data.endswith(b"\xff\xd9"):
            raise PackError("the JPEG is cut short")
        at = 2
        while True:
            if at + 4 > len(data) or data[at] != 0xFF:
                raise PackError("the JPEG is damaged")
            while at + 1 < len(data) and data[at + 1] == 0xFF:
                at += 1
            m = data[at + 1]
            if m == 0xD9:
                raise PackError("the JPEG has no frame header")
            if m == 0x01 or 0xD0 <= m <= 0xD8:
                at += 2
            elif m in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                if at + 9 > len(data):
                    raise PackError("the JPEG is cut short")
                h, w = struct.unpack(">HH", data[at + 5:at + 9])
                _sides(w, h)
                return "jpeg", w, h
            else:
                length = struct.unpack(">H", data[at + 2:at + 4])[0]
                if length < 2:
                    raise PackError("the JPEG is damaged")
                at += 2 + length
    raise PackError("only PNG or JPEG pictures are accepted (export an SVG or GIF as PNG first)")


def canonical(logo, picture):
    psha, plen = (hashlib.sha256(picture).hexdigest(), len(picture)) if picture else ("-", 0)
    return f"{MAGIC}|{hashlib.sha256(logo).hexdigest()}|{len(logo)}|{psha}|{plen}"


def build_pack(private_key, logo, picture=None):
    """The six-line pack text, signed with the owner's key. Both pictures are checked first."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    try:
        check_image(logo)
    except PackError as e:
        raise PackError(f"the logo: {e}") from None
    if picture is not None:
        try:
            check_image(picture)
        except PackError as e:
            raise PackError(f"the picture: {e}") from None
    sig = private_key.sign(canonical(logo, picture).encode(), ec.ECDSA(hashes.SHA256()))
    psha, plen = (hashlib.sha256(picture).hexdigest(), len(picture)) if picture else ("-", 0)
    return (f"{MAGIC}\nlogo {len(logo)} {hashlib.sha256(logo).hexdigest()}\npicture {plen} {psha}\n"
            f"sig {base64.b64encode(sig).decode()}\n{base64.b64encode(logo).decode()}\n"
            f"{base64.b64encode(picture).decode() if picture else ''}\n")


def public_b64(private_key):
    _, serialization, _ = owner_key._crypto()
    der = private_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


def _read(path, what):
    try:
        with open(path, "rb") as f:
            return f.read()
    except OSError as e:
        raise PackError(f"cannot read the {what} {path}: {e.strerror}") from None


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--private", required=True, help="the owner's private key (PEM), made by make_owner_keys.py; keep it outside the repository")
    p.add_argument("--logo", required=True, help="the shop's logo: PNG or JPEG, at most 64 KB and 512 by 512 pixels")
    p.add_argument("--picture", help="an optional second picture (same limits)")
    p.add_argument("--out", help="the pack to write (default: <logo name>.brand in the current folder)")
    p.add_argument("--force", action="store_true", help="overwrite an existing pack")
    p.add_argument("--router-key", default=owner_key.ROUTER_KEY_FILE, help="the public key the router program holds (default: tools/pisoportal/owner_key.b64)")
    p.add_argument("--no-key-check", action="store_true", help="do not compare the private key with the router program's key")
    args = p.parse_args(argv)
    try:
        private = os.path.realpath(args.private)
        if private == ROOT or private.startswith(ROOT + os.sep):
            raise PackError("the private key is inside this repository: move it out (it must never be committed), then run this again")
        key = owner_key.load_private(args.private)
        if key.curve.name != "secp256r1":
            raise PackError("the key is not an ECDSA P-256 key")
        if not args.no_key_check:
            built_in = _read(args.router_key, "router key file").decode("ascii", "replace").strip()
            if not built_in:
                raise PackError("no owner key is built into the router program (tools/pisoportal/owner_key.b64 is empty): no router would take a pack")
            if built_in != public_b64(key):
                raise PackError("this private key does not match the owner key built into the router program, so no router would take the pack "
                                "(use the key made by make_owner_keys.py; --no-key-check skips this test)")
        logo = _read(args.logo, "logo")
        picture = _read(args.picture, "picture") if args.picture else None
        text = build_pack(key, logo, picture)
        out = args.out or os.path.splitext(os.path.basename(args.logo))[0] + ".brand"
        if os.path.exists(out) and not args.force:
            raise PackError(f"{out} already exists (use --force to replace it)")
    except (PackError, OSError, ValueError) as e:
        sys.exit(f"Error: {e}")
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="ascii", newline="\n") as f:
        f.write(text)
    os.replace(tmp, out)
    kind, w, h = check_image(logo)
    print(f"Signed a brand pack: logo {len(logo)} bytes ({kind}, {w} by {h})" + (f", picture {len(picture)} bytes" if picture else ", no picture"))
    print(f"Written to {out} ({len(text)} bytes). Send this file to the shop owner.")


if __name__ == "__main__":
    main()
