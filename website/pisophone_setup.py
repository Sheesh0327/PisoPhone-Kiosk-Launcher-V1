#!/usr/bin/env python3
"""TechNet PisoHub Setup: the one program that sets up a whole PisoHub system, from a bare coin box and a factory-reset router
to working rental phones. It needs no screen: it serves its setup as a web page, so a small headless computer (Armbian, Parrot,
a Raspberry Pi, a laptop) can run it and you open the page on your phone.

    python3 pisophone_setup.py                    the setup page: open the address it prints, type the PIN
    python3 pisophone_setup.py --branch beta      the same with the beta branch's files (testing)
    sudo python3 pisophone_setup.py --install-service   start the setup page by itself whenever the computer boots (Linux)
    python3 pisophone_setup.py --cli              the same in the terminal (no web page)
    python3 pisophone_setup.py --phone-only       PisoPhone only in the terminal: the coin box on the shop's normal router, set up over USB
                                                  (no OpenWrt, no SSH); the setup page offers the same choice at its start
    python3 pisophone_setup.py --check-box        look at the coin box on USB (set up? online? its address)
    python3 pisophone_setup.py --box AA:BB:CC:DD:EE:FF --count 2    make slot license keys (each opens one more phone slot)
    python3 pisophone_setup.py --update           new software only, on a router that is set up already (terminal)
    python3 pisophone_setup.py --cli --no-flash   the terminal mode without writing the coin box (it already has the software)

On start it detects the system (Windows, macOS, Linux; Debian, Armbian, Parrot, Ubuntu, Raspberry Pi OS, Fedora, Arch...) and installs
what is missing: ssh from the system's packages (needs root or sudo) and esptool with pip into its own folder
(~/.pisophone, no root needed). --no-install turns that off.

The setup page's steps, in order:
  1. Start        checks this computer (ssh, the website, esptool) and installs what is missing
  2. Router       finds the factory-reset router by itself (192.168.1.1 or 10.0.0.1)
  3. Settings     the shop's name and public Wi-Fi name (asked every time), the country and the passwords (new ones every time)
  4. Install      writes the PisoPhone software to the coin box over USB (esptool, checked against the website's checksums), runs
                  the one-line installer on the router unattended (it checks the setup file's sha256), waits for the router to
                  move to 10.0.0.1, runs the setup, pairs the coin box, saves the summary and the setup sheet
  5. Phones       the link to the phone setup page (Set up a phone), already filled in: open it on a phone or laptop with
                  internet and scan its QR code with the rental phone
  6. Finish       tests a real coin, checks the customer Wi-Fi, offers Telegram alerts; "Set up the next unit" starts over
                  for the next router and coin box
The summary (every password), the printable setup sheet and a printable Wi-Fi join card (a QR code that joins the hidden PisoKiosk
network from a phone, and one that opens the coin box's page) are saved on this computer, readable only by you, and can be opened
from the page.

The page is served over plain HTTP to the local network and protected by a PIN (printed when it starts, kept in
~/.pisophone/pin; five wrong PINs lock it for a minute). Use --local-only to serve this computer only. It needs Python 3.8+
and the ssh command (built into Windows 10/11, macOS and most Linux systems). The passwords travel over the SSH connection's
input, never on a command line; the router's SSH key is pinned for the run (the same key must answer at 192.168.1.1 and at
10.0.0.1). Running it again is safe: a router that is set up already keeps its names and passwords (it is then only repaired
or finished).

Build a standalone program with Nuitka (no Python needed on the computer that runs it):
    python -m nuitka --onefile --include-package=esptool --include-package=serial setup/pisophone_setup.py"""
import argparse
import base64
import contextlib
import csv
import datetime
import getpass
import glob
import gzip
import hashlib
import http.client
import http.server
import importlib
import io
import json
import os
import pathlib
import platform
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import zlib

APP_NAME = "TechNet PisoHub Setup"
USER_AGENT = "TechNetPisoHubSetup/1.0 (+https://pisophone.pages.dev)"   # a name a website can recognise (Python's own is often refused)
SITE_HOST = "pisophone.pages.dev"   # the website (Cloudflare Pages): the coin box software, the phone setup page, the installer and the setup file
DEFAULT_BRANCH = "main"
FACTORY_IP = "192.168.1.1"      # OpenWrt's address after a factory reset
TARGET_IP = "10.0.0.1"          # where the PisoPhone setup puts it
BOX_IP = "10.0.0.10"            # the coin box, always <LAN network>.10
KIOSK_SSID = "PisoKiosk"        # fixed names (setup/piso-setup.sh.in)
BOX_SSID = "PisoCoinBox"
MOVE_WAIT = 240                 # seconds to wait for the router at 10.0.0.1 after the move
FORBIDDEN = "'\"\\$`"           # characters the router's shell quoting cannot take
# (a generated password avoids characters that are easily misread: 0/O, 1/l/I)
ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PASSWORDS = [  # (environment name read by the setup, what it is, min, max, generated length)
    ("ROOT_PASSWORD", "router password (SSH and LuCI login)", 8, 63, 14),
    ("KIOSK_PASSWORD", f"{KIOSK_SSID} Wi-Fi password (for the rental phones' setup)", 8, 63, 12),
    ("BOX_NEW_ADMIN_PASSWORD", "coin box admin password (the box's page; also the phones' admin PIN)", 8, 32, 16),
]
ANSWER_ORDER = [env for env, *_ in PASSWORDS] + ["GUEST_SSID", "SITE_NAME", "COUNTRY", "SHOP_PRODUCT"]
PRODUCTS = [("wifi", "PisoWiFi", "customer Wi-Fi sold by the coin (an OpenWrt router and the coin box; no rental phones)"),
            ("phone", "PisoPhone", "rental phones with the coin box on your shop's normal router (nothing is installed on the router)"),
            ("both", "both", "PisoWiFi and PisoPhone (an OpenWrt router, the coin box and the rental phones)")]
COUNTRIES = [  # the Wi-Fi regulatory country (which channels and power the router may use)
    ("PH", "Philippines"), ("ID", "Indonesia"), ("MY", "Malaysia"), ("SG", "Singapore"), ("TH", "Thailand"),
    ("VN", "Vietnam"), ("KH", "Cambodia"), ("MM", "Myanmar"), ("JP", "Japan"), ("KR", "South Korea"), ("TW", "Taiwan"),
    ("HK", "Hong Kong"), ("CN", "China"), ("IN", "India"), ("AE", "United Arab Emirates"), ("SA", "Saudi Arabia"),
    ("AU", "Australia"), ("NZ", "New Zealand"), ("US", "United States"), ("CA", "Canada"), ("GB", "United Kingdom"),
    ("DE", "Germany"),
]
TELEGRAM_TOKEN = re.compile(r"\d{5,12}:[A-Za-z0-9_-]{30,50}")


class SetupError(Exception):
    pass


def say(text=""):
    print(text, flush=True)


def site_url(branch):
    """The website of a branch: main's is the production site, any other branch has its preview site (Cloudflare's branch
    alias, as in setup/install.sh: lowercase, other characters become '-', 28 at most)."""
    if branch == "main":
        return f"https://{SITE_HOST}"
    alias = re.sub(r"[^a-z0-9]", "-", branch.lower())[:28].strip("-")
    return f"https://{alias}.{SITE_HOST}"


# ---- what is typed -------------------------------------------------------------------------------------------------------

def password_problem(value, lo, hi):
    """Why the router setup would refuse this password, or None."""
    if not lo <= len(value) <= hi:
        return f"use {lo} to {hi} characters"
    if any(c.isspace() for c in value) or any(c in FORBIDDEN for c in value):
        return "no spaces, quotes, backslashes, $ or backticks"
    if any(ord(c) < 33 or ord(c) > 126 for c in value):
        return "use plain letters, digits and punctuation"
    return None


def name_problem(value, what):
    """Why the setup would refuse this Wi-Fi / site name, or None."""
    if not 1 <= len(value) <= 32:
        return "use 1 to 32 characters"
    if any(c in FORBIDDEN for c in value) or any(ord(c) < 32 for c in value):
        return "no quotes, backslashes, $ or backticks"
    if what == "wifi" and value == KIOSK_SSID:
        return f"{KIOSK_SSID} is the rental phones' network"
    if what == "wifi" and BOX_SSID in value:
        return f"it may not contain {BOX_SSID} (the coin box's network)"
    return None


def country_problem(value):
    return None if re.fullmatch(r"[A-Z]{2}", value or "") else "a two-letter country code, like PH"


def generate(length):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def ask_text(prompt, default, what, read=input):
    while True:
        value = read(f"{prompt} [{default}]: ").strip() or default
        problem = name_problem(value, what)
        if not problem:
            return value
        say(f"  Not usable: {problem}.")


def ask_password(label, lo, hi, length, read=getpass.getpass):
    say()
    say(f"Choose the {label}: {lo} to {hi} characters, no spaces or quotes. Press Enter to have one generated.")
    while True:
        value = read("  Password (not shown): ")
        if not value:
            value = generate(length)
            say(f"  Generated: {value}")
            return value
        problem = password_problem(value, lo, hi)
        if problem:
            say(f"  Not usable: {problem}.")
            continue
        if read("  Type it again: ") != value:
            say("  The two entries differ. Try again.")
            continue
        return value


def check_answers(answers):
    """Raises SetupError when an answer would be refused by the router setup."""
    for env, label, lo, hi, _length in PASSWORDS:
        if env == "KIOSK_PASSWORD" and answers.get("SHOP_PRODUCT") == "wifi":
            continue   # (a PisoWiFi-only router has no rental-phone Wi-Fi)
        problem = password_problem(answers[env], lo, hi)
        if problem:
            raise SetupError(f"the {label}: {problem}")
    for key, what in (("GUEST_SSID", "wifi"), ("SITE_NAME", "site")):
        problem = name_problem(answers[key], what)
        if problem:
            raise SetupError(f"{key}: {problem}")
    problem = country_problem(answers.get("COUNTRY", "PH"))
    if problem:
        raise SetupError(f"country: {problem}")


def product_choice(args, read=input):
    """The product of this run: "wifi", "phone" or "both". From --product (or --phone-only, which means phone); with --yes there is
    no one to ask, so a missing choice stops the setup before anything is changed; otherwise asked, with nothing preselected."""
    chosen = getattr(args, "product", None)
    if getattr(args, "phone_only", False):
        if chosen not in (None, "phone"):
            raise SetupError("--phone-only means --product phone: it cannot be combined with --product " + chosen)
        return "phone"
    if chosen:
        return chosen
    if getattr(args, "yes", False):
        raise SetupError("choose the product: --product wifi, phone or both")
    say()
    say("What are you setting up?")
    for n, (_key, name, what) in enumerate(PRODUCTS, 1):
        say(f"  {n}) {name}: {what}")
    while True:
        answer = read("Type 1, 2 or 3 (or wifi, phone, both): ").strip().lower()
        for n, (key, name, _what) in enumerate(PRODUCTS, 1):
            if answer in (str(n), key, name.lower()):
                return key
        say("  Please choose one of the three.")


def collect_answers(args, read=input, read_secret=getpass.getpass):
    """The answers the setup needs (environment name -> value), asked here or taken from the options."""
    answers = {"COUNTRY": (getattr(args, "country", None) or "PH").upper(), "SHOP_PRODUCT": getattr(args, "product", None) or "both"}
    wifi_only = answers["SHOP_PRODUCT"] == "wifi"
    if args.yes:
        answers["GUEST_SSID"] = args.guest_ssid or "PisoWiFi"
        answers["SITE_NAME"] = args.site_name or answers["GUEST_SSID"]
        for env, _label, _lo, _hi, length in PASSWORDS:
            answers[env] = "" if wifi_only and env == "KIOSK_PASSWORD" else generate(length)
    else:
        say()
        say("A few questions first. Nothing on the router is changed until you have reviewed your answers.")
        say()
        answers["GUEST_SSID"] = ask_text("Name of the public customer Wi-Fi", args.guest_ssid or "PisoWiFi", "wifi", read)
        answers["SITE_NAME"] = ask_text("Name of the shop / site (on the setup sheet and in alerts)",
                                        args.site_name or answers["GUEST_SSID"], "site", read)
        for env, label, lo, hi, length in PASSWORDS:
            answers[env] = "" if wifi_only and env == "KIOSK_PASSWORD" else ask_password(label, lo, hi, length, read_secret)
    check_answers(answers)
    return answers


def kept_answers(country="PH", product=""):
    """The answers sent to a router that was set up before: none (it keeps its own), except the Wi-Fi country and the product
    when one was chosen (the router asks before it removes the rental phones' Wi-Fi)."""
    answers = {key: "" for key in ANSWER_ORDER}
    answers["COUNTRY"] = country
    answers["SHOP_PRODUCT"] = product
    return answers


def review(answers):
    say()
    say("================ PLEASE REVIEW (write the passwords down now) ================")
    say(f"  Public Wi-Fi (customers):  {answers['GUEST_SSID']}  (open, behind the coin payment page)")
    if answers.get("SHOP_PRODUCT") != "wifi":
        say(f"  Rental-phone Wi-Fi:        {KIOSK_SSID}-xxxx  (hidden; xxxx is this router's own ending, shown at the end)")
    say(f"  Site name:                 {answers['SITE_NAME']}")
    say(f"  Router address:            {TARGET_IP}   Country: {answers.get('COUNTRY', 'PH')}")
    say(f"  Router password:           {answers['ROOT_PASSWORD']}")
    if answers.get("SHOP_PRODUCT") != "wifi":
        say(f"  {KIOSK_SSID} Wi-Fi password:  {answers['KIOSK_PASSWORD']}")
    say(f"  Coin box admin password:   {answers['BOX_NEW_ADMIN_PASSWORD']}")
    say("  The router's Wi-Fi networks are replaced and the coin box is paired and set up.")
    say("  (A router that was set up before keeps the names and passwords it already has.)")
    say("==============================================================================")


def parse_summary(text):
    """The passwords and names in the router's summary file (piso-setup's write_summary), as far as they are there."""
    found = {}
    for key, pattern in (("ROOT_PASSWORD", r"^Router \(SSH / LuCI\):.*?password: (\S+)"),
                         ("KIOSK_PASSWORD", r"^Kiosk Wi-Fi:.*?password: (\S+)"),
                         ("KIOSK_SSID_NAME", r"^Kiosk Wi-Fi:\s+(\S+)\s+\(HIDDEN\)"),   # this router's own name (PisoKiosk-a1b2; PisoKiosk on an older router)
                         ("GUEST_SSID", r"^PisoWiFi \(customers\):\s+(.+?)\s{2,}\(open"),
                         ("BOX_NEW_ADMIN_PASSWORD", r"^Coin box:.*?admin password: (\S+)")):
        m = re.search(pattern, text or "", re.M)
        if m and not m.group(1).startswith("NOT"):
            found[key] = m.group(1)
    return found


# ---- QR codes and the printable Wi-Fi card ------------------------------------------------------------------------------------
# A small QR encoder (byte mode, error correction M, versions 1 to 10: up to 213 bytes), so the setup program needs nothing
# installed to draw a QR code. It is checked against two independent QR readers in router/tests/test_pisophone_setup.py.

_QR_M = {   # version: (data codewords per block list, EC codewords per block)
    1: ([16], 10), 2: ([28], 16), 3: ([44], 26), 4: ([32] * 2, 18), 5: ([43] * 2, 24), 6: ([27] * 4, 16), 7: ([31] * 4, 18),
    8: ([38] * 2 + [39] * 2, 22), 9: ([36] * 3 + [37] * 2, 22), 10: ([43] * 4 + [44], 26)}
_QR_ALIGN = {2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46],
             10: [6, 28, 50]}
_GF_EXP, _GF_LOG = [0] * 512, [0] * 256
_x = 1
for _i in range(255):
    _GF_EXP[_i], _GF_LOG[_x] = _x, _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _GF_EXP[_i] = _GF_EXP[_i - 255]


def _gf_mul(a, b):
    return 0 if a == 0 or b == 0 else _GF_EXP[_GF_LOG[a] + _GF_LOG[b]]


def _rs_remainder(data, n):
    """The n Reed-Solomon error correction codewords of `data`."""
    gen = [1]
    for i in range(n):
        nxt = [0] * (len(gen) + 1)
        for j, c in enumerate(gen):
            nxt[j] ^= _gf_mul(c, 1)
            nxt[j + 1] ^= _gf_mul(c, _GF_EXP[i])
        gen = nxt
    rem = [0] * n
    for byte in data:
        factor = byte ^ rem[0]
        rem = rem[1:] + [0]
        for i in range(n):
            rem[i] ^= _gf_mul(gen[i + 1], factor)
    return rem


def _bch_remainder(value, poly, degree):
    """The BCH check bits for the format (degree 10, poly 0x537) and version (degree 12, poly 0x1F25) information."""
    rem = value
    for _ in range(degree):
        rem = (rem << 1) ^ ((rem >> (degree - 1)) * poly)
    return rem


def qr_matrix(text):
    """The QR code for a text (UTF-8, byte mode, level M) as a list of rows of booleans (True = dark), without a quiet zone."""
    raw = text.encode("utf-8")
    version = next((v for v in range(1, 11) if len(raw) <= sum(_QR_M[v][0]) - (2 if v < 10 else 3)), None)
    if version is None:
        raise ValueError("the text is too long for this QR encoder")
    blocks, ec_len = _QR_M[version]
    capacity = sum(blocks)
    bits = [0, 1, 0, 0]
    count_bits = 8 if version < 10 else 16
    bits += [(len(raw) >> i) & 1 for i in range(count_bits - 1, -1, -1)]
    for byte in raw:
        bits += [(byte >> i) & 1 for i in range(7, -1, -1)]
    bits += [0] * min(4, capacity * 8 - len(bits))
    bits += [0] * (-len(bits) % 8)
    data = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    for pad in range(capacity - len(data)):
        data.append(0xEC if pad % 2 == 0 else 0x11)
    # split into blocks, add the error correction, interleave
    parts, at = [], 0
    for size in blocks:
        chunk = data[at:at + size]
        at += size
        parts.append((chunk, _rs_remainder(chunk, ec_len)))
    out = []
    for i in range(max(blocks)):
        out += [c[i] for c, _e in parts if i < len(c)]
    for i in range(ec_len):
        out += [e[i] for _c, e in parts]
    stream = [(byte >> i) & 1 for byte in out for i in range(7, -1, -1)]
    size = 17 + 4 * version

    def blank():
        return [[None] * size for _ in range(size)]

    def finder(m, r, c):
        for dr in range(-1, 8):
            for dc in range(-1, 8):
                rr, cc = r + dr, c + dc
                if 0 <= rr < size and 0 <= cc < size:
                    m[rr][cc] = (0 <= dr <= 6 and 0 <= dc <= 6 and (dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4)))

    base = blank()
    finder(base, 0, 0)
    finder(base, 0, size - 7)
    finder(base, size - 7, 0)
    pos = _QR_ALIGN.get(version, [])
    for r in pos:
        for c in pos:
            if base[r][c] is not None:
                continue   # (the three corners hold finders)
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    base[r + dr][c + dc] = max(abs(dr), abs(dc)) != 1
    for i in range(8, size - 8):
        base[6][i] = base[i][6] = (i % 2 == 0)
    base[size - 8][8] = True   # the always-dark module
    reserved = [[cell is not None for cell in row] for row in base]
    for i in range(9):
        reserved[8][i] = reserved[i][8] = True
    for i in range(8):
        reserved[8][size - 1 - i] = reserved[size - 1 - i][8] = True
    if version >= 7:
        for i in range(6):
            for j in range(3):
                reserved[i][size - 11 + j] = reserved[size - 11 + j][i] = True

    def place(mask):
        m = [row[:] for row in base]
        bit = 0
        col = size - 1
        up = True
        while col > 0:
            if col == 6:
                col -= 1
            for step in range(size):
                r = size - 1 - step if up else step
                for c in (col, col - 1):
                    if reserved[r][c]:
                        continue
                    v = bool(stream[bit]) if bit < len(stream) else False
                    bit += 1
                    if (mask == 0 and (r + c) % 2 == 0) or (mask == 1 and r % 2 == 0) or (mask == 2 and c % 3 == 0) or \
                       (mask == 3 and (r + c) % 3 == 0) or (mask == 4 and (r // 2 + c // 3) % 2 == 0) or \
                       (mask == 5 and (r * c) % 2 + (r * c) % 3 == 0) or (mask == 6 and ((r * c) % 2 + (r * c) % 3) % 2 == 0) or \
                       (mask == 7 and ((r + c) % 2 + (r * c) % 3) % 2 == 0):
                        v = not v
                    m[r][c] = v
            col -= 2
            up = not up
        data_bits = 0b00 << 3 | mask   # (error correction level M is 00)
        fmt = ((data_bits << 10) | _bch_remainder(data_bits, 0x537, 10)) ^ 0x5412
        for i in range(15):
            bit_i = bool((fmt >> i) & 1)
            if i < 6:
                m[i][8] = bit_i
            elif i < 8:
                m[i + 1][8] = bit_i
            elif i == 8:
                m[8][7] = bit_i
            else:
                m[8][14 - i] = bit_i
            if i < 8:
                m[8][size - 1 - i] = bit_i
            else:
                m[size - 15 + i][8] = bit_i
        if version >= 7:
            info = (version << 12) | _bch_remainder(version, 0x1F25, 12)
            for i in range(18):
                v = bool((info >> i) & 1)
                m[i // 3][size - 11 + i % 3] = v
                m[size - 11 + i % 3][i // 3] = v
        return m

    def penalty(m):
        score = 0
        for grid in (m, [list(col) for col in zip(*m)]):
            for row in grid:
                run = 1
                for i in range(1, size):
                    if row[i] == row[i - 1]:
                        run += 1
                        if run == 5:
                            score += 3
                        elif run > 5:
                            score += 1
                    else:
                        run = 1
                pattern = "".join("1" if v else "0" for v in row)
                score += 40 * (pattern.count("10111010000") + pattern.count("00001011101"))
        for r in range(size - 1):
            for c in range(size - 1):
                if m[r][c] == m[r][c + 1] == m[r + 1][c] == m[r + 1][c + 1]:
                    score += 3
        dark = sum(v for row in m for v in row)
        score += 10 * (abs(dark * 100 // (size * size) - 50) // 5)
        return score
    return min((place(mask) for mask in range(8)), key=penalty)


def qr_svg(text, module=8, quiet=4):
    """The QR code as an SVG picture (black modules on white, with the quiet zone the readers need)."""
    m = qr_matrix(text)
    n = len(m) + 2 * quiet
    cells = "".join(f'<rect x="{c + quiet}" y="{r + quiet}" width="1" height="1"/>' for r, row in enumerate(m) for c, v in enumerate(row) if v)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n} {n}" width="{n * module}" height="{n * module}" '
            f'shape-rendering="crispEdges"><rect width="{n}" height="{n}" fill="#fff"/><g fill="#000">{cells}</g></svg>')


def wifi_qr_text(ssid, password, hidden=True):
    """The text phones read as "join this Wi-Fi": WIFI:T:WPA;S:<name>;P:<password>;H:true;; (backslash, semicolon, comma, colon and double quote are escaped;
    a name or password made only of hex digits is quoted so the phone does not take it for hex)."""
    def esc(value):
        value = re.sub(r'([\\;,:"])', r"\\\1", value)
        return f'"{value}"' if re.fullmatch(r"[0-9A-Fa-f]+", value) else value
    return f"WIFI:T:WPA;S:{esc(ssid)};P:{esc(password)};{'H:true;' if hidden else ''};"


# ---- TechNet brand pictures (small PNGs, embedded so the page and the printouts are one file) ---------------------------------
LOGO_B64 = (
    "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"
)
LOGO_LARGE_B64 = (
    "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"
)
MASCOT_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAM0AAADSCAMAAADe4rQVAAABCGlDQ1BJQ0MgUHJvZmlsZQAAeJxjYGA8wQAELAYMDLl5JUVB7k4KEZFRCuwPGB"
    "iBEAwSk4sLGHADoKpv1yBqL+viUYcLcKakFicD6Q9ArFIEtBxopAiQLZIOYWuA2EkQtg2IXV5SUAJkB4DYRSFBzkB2CpCtkY7ETkJiJxcUgdT3"
    "ANk2uTmlyQh3M/Ck5oUGA2kOIJZhKGYIYnBncAL5H6IkfxEDg8VXBgbmCQixpJkMDNtbGRgkbiHEVBYwMPC3MDBsO48QQ4RJQWJRIliIBYiZ0t"
    "IYGD4tZ2DgjWRgEL7AwMAVDQsIHG5TALvNnSEfCNMZchhSgSKeDHkMyQx6QJYRgwGDIYMZAKbWPz9HbOBQAAABgFBMVEX6/P4JDR3y8fIHIVjw"
    "nCgLYO3301kJKp3nqVP35J6blqgWm+sXWmYbWJ+n4PX5yDQEMdracB5dWmGgqtijYRsVnp0MDhJfoORSJw9ba5Ve3PIKDhUh1edXWV7Vq6EOz7"
    "CbnqRnTh6Zb18fIiNmlKWmkWDkZkqWIw/itJTvzq3JuNMzTFSQeZdKNk+nrMvYn2itizJUctpqcJChcVAOFVchSzcMh3i3ydIuNUxmi20XG1ep"
    "i2lZNBF3hYmgWiNaW1iYmaClyari3+yjXyHYkFTjpWVTNRhU0LmukFvg5ucjaKBRNyhnRSdwUyTObzT5348pRTxNUGrQt57t1a0NRTVlTTNoa4"
    "+dZSuYdUifqejWhTTpuo4KbGMlpJRUYa54g5yZZVS61u7JPCPVczXYhjT/xHAcQDcORFNwgHl9i+evmHGhlYupqsXAYzHVe0jDvcQQS5IaiX0Z"
    "rZtDNFdAOIZmd9VThsR5hsZWwbJj8uiUMQyGfd+vyLu5x+LXf1HCfVZGp1aAAAAAgHRSTlMA+/7+/v7+/vz5+P77/f7//vr7+/v9Vv78/P2n/j"
    "D9/ib6/TL9+/z9KST+OP/9JjX9/SgqU/z3Fzn1pyldJFarUvmop1inpvqnVzonNaikrDhLVEuqV086rKles0+nqVpZXP9TpFBHsCKuULBQLj0l"
    "pK9nTv+vOUGvsZL/E6ZIpBf1eYwAACL8SURBVHja7Z2Hd9vGlvANDAsAApAIgCiERLFBlmQpltydOMXp2bSX5NV9Zd/b3vvu17/91/feOzPADA"
    "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"
    "lFTkSuQmCC"
)
CARD_EMBLEM_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAgAAAAIACAMAAADDpiTIAAADAFBMVEVa2t1jmaUOFx4i3OgSGiNc"
    "p+ad19oaWGOXbibf16MFESCqmFUbLVTPrVkFESAKH1dYYF8hnJtqVSYlp+7hx3AIn3kCNJUOXdUR"
    "K0vk6eVRNxvdaQHq59kkao+VrsibqKnz+Pj/4i0QKkrYnipR0rYC2KYbVmMR2KgAKO21jCv/5xuN"
    "cVPfoBH0+PUE+d4hRDr4qBb16F6fpK7zpRPFusXKs6AB0qizxLhdZ2yxztP/1ysCkmwCrJLw8KIF"
    "GIOzih2wusknWGMCyaQbbYpnbI6wdgv74lcAtYpYX2OsnFEvVFtZYmZpjlsGrYpncI9Odstsl5eE"
    "e4SYoaxXRyGdo52tzd7/0ST/4ln//KIJ+tcW3815gImfOwGwzMzXuFXVq0wqc4cB5H1MPUZfRyZh"
    "//eut8GzwbvhZADQuFzp57UAR9QAwH9GJwBhTzBue4tfcYdqm6Rjs/98yP+jo26+r14AAAD8/f3+"
    "qhABFSoAZf4ABA4NJUQSJDsAw5cAyLEAd/4ACSUB2NH+tBkHExoAh/7rmwoZM1AAGkcAWfYAuo8G"
    "1LGibBkmOE8kJyvU5uzr6u37uCUIpPr8xywDl/zT+fr9+q0yRVVkTCXN1tWYpq3+xhZOWWgF5tH7"
    "5m21xswCFS2rtLgASPP+1S9seIb++G361k+mq7L46I+vusQqJBmv5+38/NAC+O51hpFVRylVZHEE"
    "u6aQmqaFiZFDS1T99pL3yksDy8ex+fn+51RsVykKppCNlZkVRVQG6OUuV2grR2cuNDEpGRDGys/N"
    "2ubz2W6z2ehMU1hQdodJKQ1laXOtdBZSOBOZZhVtdHkQOWxr+/cr2smO+fYJJksFVtMrZ2/qqioI"
    "tvv8/fxU+/cAO/Hotk9rRhUqhesADCNy2c/Ohw0cc3SOWQ8mJyUQmIwBBhAmBQVQ6Oayhi3alBII"
    "ZtID5bIDJ22Q2erW49UNR43migEBBw//9lZQaIYG9dJ1yuuS5esLWLCy1tUmc3cxya3Mlirn3uAG"
    "SK3u2YyEvu//0xcNLsvpAAABAHRSTlP+/hv+6v7+/f7+V/73/pwO/v7+/v7///9cFv//9/7+/Wb6"
    "o/r/DAj2/v4N/wajBv+pDwtO//9b/w0DYgIHCgICBaCoAhICqKykBWNm/1L//wX/cAm1aZJYl1O1"
    "av+mE1dWAv9hCl4DArpiA/8Cp3C8qmVlRboA/v7+/v7+/v7+/v7//v7//v7+/v7+//7+/v7+//7+"
    "/v7+/v7+/v7+/v4E/v7+/v7//v/+//7///7+/v7+/v7+/v/+/v7+/v7//v7+//7+/v7+/v/+//7+"
    "/v7///4H//7//wb//v7//wL+//7/CP9v//////////7+//6y/v7//v///v7///7//v//G5wgpAAA"
    "wSBJREFUeNrt/Ql8FFeWJwqndoEkELvBLLZx2eV2Ld3VVV3V87p6me7p6ZmeN2/2fd77vre/b98y"
    "MxQRkkKZytRCKjNJLWiX0IYECAmQkNi0sIvVNosx2IUBY/BuwGVsF25/99x7I+JGZERkRCoF2O37"
    "M1iAlsy4/3PO/+wu93f/LHxNOXY+Xf3shX8PHo7rO373cLSXu3DhU6Zn4UItSPRf/j0Avi333pGe"
    "3rFQvXW48+fgCDYO/kRAg4qFDs33+x4AT/jt/+lCRX6femrBggVC0mfBAoSEFxaq3+9PF34PgCda"
    "7jvSqcyDxOcLQgXHicKsTz5oBKoPXgNt8D0Anly5R1K/YIGIjiCIHCcEg+h/s7r9Ck6kZ8GC+QsX"
    "ftd0wXcCAB1UOBc+BVIvyDLP8f6hwQtBTuKE1J385xA5IChAvOB7ADzm8/rrLyhyn4mlXlRVvsQH"
    "1z04LkopBQD8ADET6YLXyM/9tmuCbzMAfv3rP8DyjwSfkXtVd+fnrxkbuyBJFcKcHFkVpHd0fA+A"
    "R3/+7PdWwsNPf2cBFsv4++H4iuVL6sINvCTM0QFaQFjBwte+B8CjPD/6R38G/1v5f/wNb3q9FcgA"
    "rMioO1TDS0FhTg/SBORlpX8PgEdx0v8AFP8f/Pu/npdXaG7eRU7KXFObsXVdJs/NMQCQJkDMEG7/"
    "Rz/6v30PgDmW/T/7Efr99/7BX/f0jC7mLOid6K9YXrQxI/tlbu4BQBXBfMDAn/33P/oeAHMm+7/3"
    "e3D7f/j7vvG2I1dO8zxvTu858Wxlee3GtEI/zwmP6FQsmP+v4QX+Xvr3AEj9+YM/+zMs++u9Xl9X"
    "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"
)
CARD_EMBLEM_URI = "data:image/png;base64," + CARD_EMBLEM_B64   # the circle emblem alone, 512 px, for the card backs
LOGO_URI = "data:image/png;base64," + LOGO_B64
LOGO_LARGE_URI = "data:image/png;base64," + LOGO_LARGE_B64
MASCOT_URI = "data:image/png;base64," + MASCOT_B64


def html_escape(text):
    return (str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;"))


def wifi_card_html(site, kiosk_ssid, kiosk_password, box_url=f"http://{BOX_IP}/", stamp=""):
    """A print-ready page (one A4 or Letter sheet) for the owner's manual: scan to join the hidden rental-phone Wi-Fi, scan to open
    the coin box's page, and the same steps by hand. It holds the Wi-Fi password: keep the printout private."""
    join, page = qr_svg(wifi_qr_text(kiosk_ssid, kiosk_password), 7), qr_svg(box_url, 7)
    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TechNet PisoHub: join the {html_escape(kiosk_ssid)} Wi-Fi - {html_escape(site)}</title>
<style>
@page {{ size: A4; margin: 14mm; }}
* {{ box-sizing: border-box; }}
body {{ font: 15px/1.45 system-ui, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; color: #111; margin: 0; padding: 16px; background: #fff; }}
main {{ max-width: 780px; margin: 0 auto; }}
.top {{ display: flex; align-items: center; gap: 14px; border-bottom: 3px solid #1f6bff; padding-bottom: 10px; margin-bottom: 6px; }} .top > div {{ flex: 1; }} .mascot {{ height: auto; }}
h1 {{ font-size: 26px; margin: 0 0 2px; color: #0b1630; }} h2 {{ font-size: 17px; margin: 0 0 6px; }}
.sub {{ color: #444; margin: 0 0 14px; }}
.row {{ display: flex; gap: 20px; margin: 14px 0; }}
.card {{ flex: 1; border: 2px solid #111; border-radius: 10px; padding: 14px; text-align: center; break-inside: avoid; }}
.card svg {{ width: 100%; max-width: 270px; height: auto; display: block; margin: 8px auto; }}
.num {{ display: inline-block; width: 26px; height: 26px; line-height: 26px; border-radius: 50%; background: linear-gradient(135deg, #1f6bff, #12b894); color: #fff; font-weight: 700; -webkit-print-color-adjust: exact; print-color-adjust: exact; }}
.kv {{ text-align: left; margin: 10px 0 0; }} .kv div {{ display: flex; gap: 8px; padding: 2px 0; }}
.kv span:first-child {{ color: #555; min-width: 92px; }} .mono {{ font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; word-break: break-all; }}
ol {{ margin: 6px 0 0 20px; padding: 0; }} li {{ margin: 3px 0; }}
.steps {{ border: 1px solid #999; border-radius: 10px; padding: 12px 16px; margin: 14px 0; break-inside: avoid; }}
.warn {{ border: 2px dashed #b00; color: #800; border-radius: 10px; padding: 8px 12px; margin-top: 14px; font-weight: 600; }}
.foot {{ color: #666; font-size: 12px; margin-top: 12px; }}
@media (max-width: 640px) {{ .row {{ flex-direction: column; }} }}
@media print {{ body {{ padding: 0; }} }}
</style></head><body><main>
<div class="top"><img src="{LOGO_LARGE_URI}" alt="" width="64" height="64"><div><h1>TechNet PisoHub: reach the coin box from your phone</h1>
<p class="sub">{html_escape(site)}{(' &middot; ' + html_escape(stamp)) if stamp else ''}</p></div><img class="mascot" src="{MASCOT_URI}" alt="" width="96"></div>
<div class="row">
 <div class="card"><h2><span class="num">1</span> Join the Wi-Fi</h2>
  <p>Scan with your phone's camera. It joins the hidden network by itself.</p>
  {join}
  <div class="kv"><div><span>Network</span><span class="mono">{html_escape(kiosk_ssid)}</span></div>
  <div><span>Password</span><span class="mono">{html_escape(kiosk_password)}</span></div>
  <div><span>Security</span><span>WPA2-Personal, hidden network</span></div></div></div>
 <div class="card"><h2><span class="num">2</span> Open the coin box</h2>
  <p>Once joined, scan to open its page (or type the address).</p>
  {page}
  <div class="kv"><div><span>Address</span><span class="mono">{html_escape(box_url)}</span></div>
  <div><span>Sign in</span><span>admin password, or <b>Scan admin QR</b> (your own code)</span></div></div></div>
</div>
<div class="steps"><h2>No QR scanner? Join by hand</h2>
<ol><li>Wi-Fi settings &rarr; <b>Add network</b> (a hidden network does not show in the list).</li>
<li>Name <b>{html_escape(kiosk_ssid)}</b>, security <b>WPA2-Personal</b>, turn on <b>Hidden network</b>, type the password above.</li>
<li>Open <b>{html_escape(box_url)}</b> in the phone's browser.</li></ol></div>
<div class="warn">Keep this page private: whoever has the password can join the rental-phone network. Store the printout with your admin QR code, not at the shop.</div>
<p class="foot">Made by the TechNet PisoHub setup program. The rental phones join this network by themselves; this page is for you.</p>
</main></body></html>
"""

# ---- reaching the router -------------------------------------------------------------------------------------------------

def port_open(host, port=22, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def parse_gateways(text):
    """The IPv4 default gateways in the output of `ip -4 route` (Linux), `netstat -rn -f inet` (macOS) or `route print -4` (Windows)."""
    found = []
    for line in text.splitlines():
        m = (re.match(r"\s*default\s+via\s+(\d+\.\d+\.\d+\.\d+)\s", line + " ")
             or re.match(r"\s*default\s+(\d+\.\d+\.\d+\.\d+)\s", line + " ")
             or re.match(r"\s*0\.0\.0\.0\s+0\.0\.0\.0\s+(\d+\.\d+\.\d+\.\d+)\s", line + " "))
        if m and m.group(1) not in found:
            found.append(m.group(1))
    return found


def default_gateways(system=None, run=subprocess.run):
    """The gateways this computer's networks name (the router's address when it hands out addresses on the cable); [] when unknown."""
    kind = (system or {}).get("kind") or ("windows" if os.name == "nt" else "linux")
    command = (["route", "print", "-4"] if kind == "windows" else ["netstat", "-rn", "-f", "inet"] if kind == "mac" else ["ip", "-4", "route"])
    try:
        done = run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=False)
        return parse_gateways(done.stdout.decode("utf-8", "replace"))
    except (OSError, subprocess.SubprocessError):
        return []


def parse_interface_addresses(text):
    """(interface, IPv4 address) pairs from `ip -4 -o address` (Linux), `ifconfig` (macOS) or `ipconfig` (Windows)."""
    found, current = [], ""
    for line in text.splitlines():
        m = re.match(r"\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/", line)
        if m:
            found.append((m.group(1), m.group(2)))
            continue
        head = re.match(r"(\S+?):\s+flags=", line) or re.match(r"(\S.*?(?:adapter|Adapter).*?):\s*$", line)
        if head:
            current = head.group(1)
            continue
        m = re.match(r"\s*inet\s+(\d+\.\d+\.\d+\.\d+)\s", line + " ") or re.match(r"\s*IPv4 Address[ .]*:\s*(\d+\.\d+\.\d+\.\d+)", line)
        if m and current:
            found.append((current, m.group(1)))
    return found


def local_interface_addresses(system=None, run=subprocess.run):
    """The addresses of this computer's network connections, as (interface, address) pairs; [] when they cannot be read."""
    kind = (system or {}).get("kind") or ("windows" if os.name == "nt" else "linux")
    command = ["ipconfig"] if kind == "windows" else ["ifconfig"] if kind == "mac" else ["ip", "-4", "-o", "address"]
    try:
        done = run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=False)
        return parse_interface_addresses(done.stdout.decode("utf-8", "replace"))
    except (OSError, subprocess.SubprocessError):
        return []


def network_clash_advice(addresses):
    """Advice text when two of this computer's connections are in the same range (the factory router's cable and the shop modem's Wi-Fi
    are both 192.168.1.x: the computer then cannot tell which one the router is on). None when there is no such pair."""
    ranges = {}
    for name, ip in addresses:
        if ip.startswith(("127.", "169.254.", "0.")):
            continue
        ranges.setdefault(ip.rsplit(".", 1)[0], []).append(name)
    for prefix, names in ranges.items():
        names = list(dict.fromkeys(names))
        if len(names) >= 2:
            return (f"This computer is on two networks that use the same addresses ({prefix}.x): {' and '.join(names[:3])}. It cannot tell which "
                    "one the router is on. Put this computer's Wi-Fi on your phone's hotspot (a different address range), or turn its Wi-Fi off, "
                    "then press Search again. (A modem from your internet company is usually at 192.168.1.1, like a new router.)")
    return None


def find_router(probe=port_open, gateways=default_gateways):
    """The router's address: 10.0.0.1 (moved already) or 192.168.1.1 (factory), else the gateway this computer's cable was given
    (vendor firmware on OpenWrt starts at 192.168.8.1, 192.168.10.1 ...: the OpenWrt check comes after, in probe_router), or None."""
    tried = [TARGET_IP, FACTORY_IP]
    for host in tried + [g for g in gateways() if g not in tried]:
        if probe(host):
            return host
    return None


def self_command():
    """How to start this program again (the password helper of the window mode runs it)."""
    if "__compiled__" in globals():   # a Nuitka build
        return [os.path.abspath(sys.argv[0])]
    exe = sys.executable
    if os.name == "nt" and os.path.basename(exe).lower() == "pythonw.exe":
        console = os.path.join(os.path.dirname(exe), "python.exe")
        exe = console if os.path.exists(console) else exe
    return [exe, os.path.abspath(__file__)]


class Ssh:
    """The system's ssh client. The router's host key is trusted the first time and then pinned for this run (a file of our
    own, so a router that was reset since the last setup gives no "host key changed" error). PISO_SSH replaces the ssh
    command (tests). In the window there is no terminal for ssh to ask a password on: set_password() makes ssh take it from
    a small helper (SSH_ASKPASS) instead, and `hidden` keeps ssh from opening a console window on Windows."""

    def __init__(self, workdir, hidden=False):
        self.workdir = workdir
        self.hidden = hidden
        self.env = None
        raw_cmd = os.environ.get("PISO_SSH", "")
        self.command = [a.strip("'\"") for a in shlex.split(raw_cmd, posix=(os.name != "nt"))] if raw_cmd else ["ssh"]
        known = os.path.join(workdir, "known_hosts").replace(os.sep, "/")
        self.options = [
            "-o", f'UserKnownHostsFile="{known}"', "-o", "StrictHostKeyChecking=accept-new",
            "-o", "HostKeyAlias=pisophone-router",      # the same key at 192.168.1.1 and at 10.0.0.1
            "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=12",
            "-o", "IdentitiesOnly=yes", "-o", "LogLevel=ERROR",
        ]

    def set_password(self, password):
        """From now on ssh answers its password question with this (an empty one fails at once instead of waiting)."""
        if os.name == "nt":
            helper = os.path.join(self.workdir, "askpass.cmd")
            with open(helper, "w") as f:
                f.write("@" + " ".join(f'"{p}"' for p in self_command()) + "\r\n")
        else:
            helper = os.path.join(self.workdir, "askpass.sh")
            with open(helper, "w") as f:
                f.write("#!/bin/sh\nprintf '%s\\n' \"$PISO_ASKPASS_PW\"\n")
            os.chmod(helper, 0o700)
        self.env = dict(os.environ, SSH_ASKPASS=helper, SSH_ASKPASS_REQUIRE="force", PISO_ASKPASS_PW=password,
                        PISO_ASKPASS_MODE="1", DISPLAY=os.environ.get("DISPLAY") or ":0")

    def argv(self, host, remote, *extra):
        if self.env is not None:
            extra = ("-o", "NumberOfPasswordPrompts=1", *extra)
        return [*self.command, *self.options, *extra, f"root@{host}", remote]

    def _kw(self, kw):
        if self.env is not None:
            kw.setdefault("env", self.env)
        if self.hidden and os.name == "nt":
            kw.setdefault("creationflags", getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return kw

    def run(self, host, remote, *extra, **kw):
        return subprocess.run(self.argv(host, remote, *extra), **self._kw(kw))

    def popen(self, host, remote, *extra, **kw):
        return subprocess.Popen(self.argv(host, remote, *extra), **self._kw(kw))


def probe_router(ssh, host):
    """Logs in without asking for a password (a factory-reset OpenWrt router has none): 'factory', 'configured' (it was set
    up before), 'password' (it has a password: probably set up before), or raises when it is not an OpenWrt router."""
    remote = "[ -f /etc/openwrt_release ] && echo @@OPENWRT; [ -s /etc/piso-setup.conf ] && echo @@CONFIGURED; true"
    try:
        r = ssh.run(host, remote, "-T", "-o", "BatchMode=yes", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        raise SetupError(f"the router at {host} does not answer SSH logins")
    if r.returncode == 0:
        if "@@OPENWRT" not in r.stdout:
            raise SetupError(f"the device at {host} is not an OpenWrt router (is this computer's Wi-Fi still on?)")
        return "configured" if "@@CONFIGURED" in r.stdout else "factory"
    if "permission denied" in r.stderr.lower():
        return "password"
    raise SetupError(f"could not log in to {host}: {r.stderr.strip() or 'ssh failed'}")


def check_login(ssh, host):
    """True when ssh logs in (with the password given to set_password, if any)."""
    try:
        r = ssh.run(host, "echo @@LOGIN-OK", "-T", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return False
    return "@@LOGIN-OK" in r.stdout


def login_refused(ssh, host):
    """True when the router answers but refuses the login (ssh says 'Permission denied'); a router that does not answer is not that."""
    try:
        r = ssh.run(host, "echo @@LOGIN-OK", "-T", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return False
    return "@@LOGIN-OK" not in r.stdout and "permission denied" in r.stderr.lower()


def login_refused_message(ssh):
    """Which password the router needs, for the person to type on the Router step."""
    given = (ssh.env or {}).get("PISO_ASKPASS_PW", "")
    what = ("did not accept the router password this computer used (was it changed since?)" if given
            else "has a password, and this computer was not given it")
    return (f"the router {what}. Go back to the Router step and type the router password (it is in your saved summary, "
            "in the file pisophone-router-password.txt).")


# The router password is saved on this computer: in the summary of a finished setup, and (written before the setup changes it,
# so an interrupted run can be repeated) in a small file of its own. A re-run logs in with it by itself.
SAVED_PASSWORD_FILES = ("pisophone-summary-*.txt", "pisophone-router-password.txt")


def saved_router_password(*folders):
    """(password, path) from the newest file with a saved router password in these folders, or (None, None)."""
    best = None
    for folder in dict.fromkeys(os.path.abspath(f) for f in folders if f):
        for pattern in SAVED_PASSWORD_FILES:
            for path in glob.glob(os.path.join(glob.escape(folder), pattern)):
                try:
                    with open(path, encoding="utf-8", errors="replace") as f:
                        password = parse_summary(f.read()).get("ROOT_PASSWORD")
                    stamp = os.path.getmtime(path)
                except OSError:
                    continue
                if password and (best is None or stamp > best[0]):
                    best = (stamp, password, path)
    return (best[1], best[2]) if best else (None, None)


def save_router_password(out_dir, password, host=None):
    """Keeps the router password the setup is about to set (in the summary's format), before the router changes."""
    if not password:
        return None
    path = os.path.join(out_dir, "pisophone-router-password.txt")
    os.makedirs(out_dir, exist_ok=True)
    save_private(path, f"Router (SSH / LuCI):   root@{host or TARGET_IP}        password: {password}\n")
    return path


def use_saved_password(ssh, host, folders, notify=say):
    """Logs in to a router that has a password with the one saved on this computer. Returns the password when it worked (ssh
    keeps using it); otherwise None, and ssh is as it was (a terminal ssh asks the person)."""
    password, path = saved_router_password(*folders)
    if not password:
        return None
    before = ssh.env
    ssh.set_password(password)
    if check_login(ssh, host):
        notify(f"Logged in to the router with the password saved in {path}.")
        return password
    ssh.env = before
    notify(f"The router password saved in {path} was not accepted (was it changed since?).")
    return None


def installer_command(branch, *args):
    """The remote command: download the one-line installer of this branch and run it. Downloaded to a file first, so a
    failed download is reported (a pipe into sh would run nothing and report success)."""
    url = f"{site_url(branch)}/install.sh"
    run = " ".join(shlex.quote(a) for a in ("--branch", branch, "--yes", *args))
    return (f"rm -f /tmp/piso-install.sh; if ! wget -q -T 60 -O /tmp/piso-install.sh {shlex.quote(url)}; then "
            f"echo 'ERROR: the router could not download the installer. Is the modem plugged into its WAN port, with internet?'; "
            f"exit 3; fi; sh /tmp/piso-install.sh {run}")


def local_installer_command(branch):
    """The remote command that runs the installer copied to the router (no download): it uses the setup file in the same folder."""
    q = shlex.quote(OFFLINE_DIR)
    return f"sh {q}/install.sh --branch {shlex.quote(branch)} --yes --local {q}"


def setup_command(branch, local=False):
    """The remote command for the setup itself: the answers are read from the connection's input (never on a command line,
    where any process on the router could read them), then the installer runs the setup, then the results are sent back
    between markers. With `local` the packages and the setup file were copied to the router's memory first (PISO_LOCAL_PKGS):
    nothing is downloaded by the router."""
    reads = "; ".join(f"IFS= read -r {n}" for n in ANSWER_ORDER)
    if local:
        installer = f"export PISO_LOCAL_PKGS={shlex.quote(OFFLINE_DIR + '/pkgs')}; {local_installer_command(branch)}"
    else:
        installer = installer_command(branch)
    return (f"{reads}; export {' '.join(ANSWER_ORDER)}; {installer}; rc=$?; echo; echo \"@@PISO-RC $rc\"; "
            f"echo \"@@PISO-STATE $(cat /tmp/piso-setup.state 2>/dev/null)\"; "
            f"if [ -r /root/piso-setup-summary.txt ]; then echo @@PISO-SUMMARY; cat /root/piso-setup-summary.txt; fi; "
            f"if [ -r /root/piso-handout.html ]; then echo @@PISO-HANDOUT; cat /root/piso-handout.html; fi; echo @@PISO-END")


# ---- the first setup without the router's internet (feature 011, part A) -------------------------------------------------------
# The computer has internet and the router may not. The computer reads what the router knows, downloads the installer and the
# OpenWrt packages the router is missing from the router's own package sources, copies them to the router's memory, and the router
# checks them itself before it installs anything (setup/src/21-local-packages.sh).

OFFLINE_DIR = "/tmp/piso-offline"           # where the bundle lives on the router (memory only, deleted after the setup)
OFFLINE_WANTED = ["opennds", "curl", "ca-bundle", "jsonfilter"]   # what the setup installs (setup/src/20-preflight.sh install_packages)


class OfflineNotPossible(SetupError):
    """The first setup cannot run without the router's internet. The message says why; the caller then uses today's way."""

    def __init__(self, reason):
        super().__init__(f"offline setup not possible: {reason}")
        self.reason = reason


class ApkRouter(OfflineNotPossible):
    """A router that installs packages with apk (OpenWrt 25.x or a snapshot) or has no opkg: neither the offline way nor the online way
    works on it yet, so the setup stops before anything is changed (it is not a reason to fall back to the online way)."""

    def __init__(self, release):
        SetupError.__init__(self, f"this router runs OpenWrt {release or '(release unknown)'}, which has no opkg (OpenWrt 25.x and snapshots install "
                                  "packages with apk), and this setup only supports routers with opkg (OpenWrt 23.05 and 24.10; the list is in "
                                  "docs/ROUTERS.md). Nothing was changed on the router.")
        self.reason = str(self)


# one ssh command that prints what offline mode needs to know, each part after its marker line
READ_FEEDS_COMMAND = (
    "echo @@RELEASE; cat /etc/openwrt_release 2>/dev/null; "
    "echo @@FEEDS; cat /etc/opkg/distfeeds.conf 2>/dev/null; "
    "echo @@INSTALLED; opkg list-installed 2>/dev/null; "
    "echo @@CONF; cat /etc/opkg.conf 2>/dev/null; "
    "echo @@USIGN; if command -v usign >/dev/null 2>&1; then echo yes; else echo no; fi; "
    "echo @@KEYS; for k in /etc/opkg/keys/*; do [ -f \"$k\" ] && echo \"$k\"; done; "
    "echo @@APK; if command -v apk >/dev/null 2>&1; then echo yes; else echo no; fi; "
    "echo @@OPKG; if command -v opkg >/dev/null 2>&1; then echo yes; else echo no; fi; "
    "echo @@MEM; sed -n 's/^MemAvailable: *\\([0-9]*\\) kB.*/\\1/p' /proc/meminfo 2>/dev/null | head -n 1")


def parse_router_feeds(text):
    """What READ_FEEDS_COMMAND printed, as a dict; raises OfflineNotPossible when the router cannot take part (an apk router, no
    signature checking, no usign, no keys)."""
    parts, cur = {}, None
    for line in text.splitlines():
        if re.fullmatch(r"@@[A-Z]+", line):
            cur = line[2:]
            parts[cur] = []
        elif cur:
            parts[cur].append(line)
    release = {}
    for line in parts.get("RELEASE", []):
        m = re.match(r"\s*(DISTRIB_[A-Z]+)=['\"]?([^'\"]*)['\"]?\s*$", line)
        if m:
            release[m.group(1)] = m.group(2)
    feeds = []
    for line in parts.get("FEEDS", []):
        m = re.match(r"\s*src(?:/gz)?\s+(\S+)\s+(\S+)", line)
        if m:
            feeds.append((m.group(1), m.group(2)))
    installed = {}
    for line in parts.get("INSTALLED", []):
        m = re.match(r"(\S+) - (\S+)", line)
        if m:
            installed[m.group(1)] = m.group(2)
    signed = False
    for line in parts.get("CONF", []):
        m = re.match(r"\s*option\s+check_signature(?:\s+(\S+))?\s*$", line)
        if m and (m.group(1) or "1").lower() not in ("0", "false", "no"):
            signed = True
    word = lambda name: (parts.get(name) or [""])[0].strip() 
    keys = len([k for k in parts.get("KEYS", []) if k.strip()])
    mem = word("MEM")
    if not feeds:
        if word("APK") == "yes" or word("OPKG") == "no":
            raise ApkRouter(release.get("DISTRIB_RELEASE", ""))
        raise OfflineNotPossible("the router lists no package sources (/etc/opkg/distfeeds.conf)")
    if not signed:
        raise OfflineNotPossible("this router does not check package signatures (no 'option check_signature' in /etc/opkg.conf), so copied packages could not be checked")
    if word("USIGN") != "yes":
        raise OfflineNotPossible("this router has no usign program to check package signatures")
    if not keys:
        raise OfflineNotPossible("this router has no package signing keys in /etc/opkg/keys")
    return {"release": release.get("DISTRIB_RELEASE", ""), "arch": release.get("DISTRIB_ARCH", ""), "target": release.get("DISTRIB_TARGET", ""),
            "feeds": feeds, "installed": installed, "mem_kb": int(mem) if mem.isdigit() else 0, "keys": keys}


def read_router_feeds(ssh, host):
    """Asks the router (one ssh command, with the session's login) for its release, package sources, installed packages, signature
    settings and free memory. Raises OfflineNotPossible with the reason when offline mode cannot be used with this router."""
    try:
        r = ssh.run(host, READ_FEEDS_COMMAND, "-T", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError) as e:
        raise OfflineNotPossible(f"could not ask the router for its package sources ({e})")
    if r.returncode != 0:
        raise OfflineNotPossible("could not log in to the router to read its package sources")
    return parse_router_feeds(r.stdout)


def parse_depends(text):
    """'a, b (>= 1) | c' -> [[('a', '', '')], [('b', '>=', '1'), ('c', '', '')]]: a list of clauses, each a list of alternatives."""
    clauses = []
    for clause in text.split(","):
        alts = []
        for alt in clause.split("|"):
            m = re.match(r"\s*([^\s(]+)\s*(?:\(\s*([<>=]+)\s*([^)\s]+)\s*\))?", alt)
            if m:
                alts.append((m.group(1), m.group(2) or "", m.group(3) or ""))
        if alts:
            clauses.append(alts)
    return clauses


def parse_packages_index(text):
    """The packages in an OpenWrt 'Packages' file: name, version, depends (see parse_depends), provides, filename, size, sha256."""
    out = []
    for block in re.split(r"\n\s*\n", text):
        fields = {}
        for line in block.splitlines():
            if line[:1] in (" ", "\t") or ":" not in line:
                continue   # a continuation of the description
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
        if "Package" not in fields:
            continue
        size = fields.get("Size", "")
        out.append({"name": fields["Package"], "version": fields.get("Version", ""), "depends": parse_depends(fields.get("Depends", "")),
                    "provides": [m.group(1) for m in (re.match(r"\s*([^\s(]+)", x) for x in fields.get("Provides", "").split(",")) if m],
                    "filename": fields.get("Filename", ""), "size": int(size) if size.isdigit() else 0, "sha256": fields.get("SHA256sum", "")})
    return out


def resolve_packages(info, indexes, wanted=None):
    """The packages the router is missing, and the ones they need, in install order (dependencies first), from the router's own
    package sources. `info` is read_router_feeds' answer, `indexes` the text of each source's Packages file by source name.
    An alternative (a | b) that is already installed (itself, or through a package that Provides it) is used, else the first one
    that exists; version constraints are not compared, except that a package built for another kernel is refused. Raises
    OfflineNotPossible when a package or a kernel does not fit."""
    installed = info["installed"]
    urls = dict(info["feeds"])
    by_name, providers = {}, {}
    for source, text in indexes.items():
        for rec in parse_packages_index(text):
            rec["source"], rec["url"] = source, urls.get(source, "")
            by_name.setdefault(rec["name"], rec)
            for virtual in rec["provides"]:
                providers.setdefault(virtual, []).append(rec)
    have = set(installed)   # names that are there: installed packages and what they Provide
    for name in installed:
        if name in by_name:
            have.update(by_name[name]["provides"])
    chosen, order, visiting = {}, [], set()

    def present(name):
        return name in have or name in chosen or any(rec["name"] in chosen for rec in providers.get(name, []))

    def known(name):
        return name in by_name or bool(providers.get(name))

    def need(name):
        if present(name):
            return
        rec = by_name.get(name) or providers[name][0]
        if rec["name"] in visiting:
            return
        visiting.add(rec["name"])
        for clause in rec["depends"]:
            if any(present(a[0]) and not (a[0] == "kernel" and a[1] == "=" and installed.get("kernel") != a[2]) for a in clause):
                continue
            for dep, op, version in clause:
                if dep == "kernel" and op == "=":
                    raise OfflineNotPossible(f"{rec['name']} is built for kernel {version}, but this router runs {installed.get('kernel') or 'an unknown kernel'}")
            usable = [a[0] for a in clause if known(a[0])]
            if not usable:
                raise OfflineNotPossible(f"{rec['name']} needs {clause[0][0]}, which is not in any of the router's package sources")
            need(usable[0])
        chosen[rec["name"]] = rec
        order.append(rec)

    if wanted is None:
        wanted = ([] if any(n.startswith("libmicrohttpd") for n in installed) else ["libmicrohttpd-no-ssl"]) + OFFLINE_WANTED
    for name in wanted:
        if present(name):
            continue
        if not known(name):
            raise OfflineNotPossible(f"{name} is not in any of the router's package sources")
        need(name)
    return [{"name": r["name"], "version": r["version"], "source": r["source"], "url": r["url"], "filename": r["filename"],
             "size": r["size"], "sha256": r["sha256"]} for r in order]


def installer_file_for(arch):
    """The setup file for the router's CPU family: the same choice as setup/install.sh makes (the file carries the portal program)."""
    if not arch or arch.startswith("mipsel_"):
        return "piso-setup.sh"
    if arch.startswith("mips_"):
        return "piso-setup-mips.sh"
    if arch.startswith("aarch64_"):
        return "piso-setup-aarch64.sh"
    if arch in ("arm_cortex-a7_neon-vfpv4", "arm_cortex-a15_neon-vfpv4", "arm_cortex-a9_neon", "arm_cortex-a9_vfpv3-d16"):
        return "piso-setup-armv7.sh"
    if arch == "x86_64":
        return "piso-setup-x86_64.sh"
    raise OfflineNotPossible(f"this router's CPU ({arch}) has no PisoPhone portal program built yet")


def download_bytes(url, name, opener=urllib.request.urlopen, timeout=120):
    """A file from the web (http or https), or OfflineNotPossible naming it."""
    try:
        with opener(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=timeout) as r:
            return r.read()
    except (OSError, ValueError, http.client.HTTPException) as e:
        raise OfflineNotPossible(f"{name} could not be downloaded ({e})")


def build_offline_bundle(info, branch, out_dir, opener=urllib.request.urlopen, site=None, emit=None):
    """Prepares, on this computer, everything the router needs for a first setup without its internet, in `out_dir`:
    install.sh, the setup file for the router's CPU with its .sha256, and pkgs/ with the packages the router is missing (downloaded
    from the router's own package sources and checked against their lists here too), each used source's Packages and Packages.sig,
    and order.txt. The router checks signatures itself; this computer cannot (usign). Nothing on the router changes.
    Returns the bundle: its files with sizes and sha256, the total size, a sha256 list and the package names."""
    emit = emit or (lambda line: None)
    site = site or site_url(branch)
    get = lambda url, name: download_bytes(url, name, opener)
    raw, skipped = {}, []
    for name, url in info["feeds"]:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", name):
            raise OfflineNotPossible(f"the router's package source name \"{name}\" is not one this setup can use")
        url = url.rstrip("/")
        try:
            raw[name] = get(f"{url}/Packages", f"{name}/Packages")
        except OfflineNotPossible:
            try:
                raw[name] = gzip.decompress(get(f"{url}/Packages.gz", f"{name}/Packages.gz"))
            except (OfflineNotPossible, OSError, EOFError, zlib.error):
                skipped.append(name)   # (fine unless a package that is needed is only there)
    try:
        wanted = resolve_packages(info, {n: d.decode("utf-8", "replace") for n, d in raw.items()})
    except OfflineNotPossible as e:
        if skipped:
            raise OfflineNotPossible(f"{e.reason} (package sources this computer could not read: {', '.join(skipped)})")
        raise
    urls = {n: u.rstrip("/") for n, u in info["feeds"]}
    os.makedirs(os.path.join(out_dir, "pkgs"), exist_ok=True)

    def put(rel, data):
        path = os.path.join(out_dir, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)

    names = []
    for rec in wanted:
        base = os.path.basename(rec["filename"])
        if not re.fullmatch(r"[A-Za-z0-9._+-]+\.ipk", base):
            raise OfflineNotPossible(f"{rec['filename']} is not a package file name this setup can use")
        emit(f"Downloading {base}")
        data = get(f"{rec['url'].rstrip('/')}/{rec['filename']}", base)
        if len(data) != rec["size"]:
            raise OfflineNotPossible(f"{base} has the wrong size ({len(data)} bytes instead of {rec['size']})")
        if hashlib.sha256(data).hexdigest() != rec["sha256"]:
            raise OfflineNotPossible(f"{base} does not match the sha256 in the package list")
        put(f"pkgs/{base}", data)
        names.append(base)
    for source in dict.fromkeys(rec["source"] for rec in wanted):
        put(f"pkgs/{source}/Packages", raw[source])
        put(f"pkgs/{source}/Packages.sig", get(f"{urls[source]}/Packages.sig", f"{source}/Packages.sig"))
    put("pkgs/order.txt", "".join(n + "\n" for n in names).encode())
    setup_file = installer_file_for(info["arch"])
    emit(f"Downloading {setup_file}")
    data = get(f"{site}/setup/{setup_file}", setup_file)
    want = get(f"{site}/setup/{setup_file}.sha256", f"{setup_file}.sha256")
    if hashlib.sha256(data).hexdigest() != want.decode("utf-8", "replace").strip()[:64]:
        raise OfflineNotPossible(f"{setup_file} does not match its sha256 (a damaged download: try again)")
    put(setup_file, data)
    put(f"{setup_file}.sha256", want)
    put("install.sh", get(f"{site}/install.sh", "install.sh"))
    files = []
    for root, _dirs, found in os.walk(out_dir):
        for f in found:
            path = os.path.join(root, f)
            rel = os.path.relpath(path, out_dir).replace(os.sep, "/")
            with open(path, "rb") as fh:
                body = fh.read()
            files.append({"path": rel, "size": len(body), "sha256": hashlib.sha256(body).hexdigest()})
    files.sort(key=lambda f: f["path"])
    return {"dir": out_dir, "files": files, "total": sum(f["size"] for f in files), "names": [r["name"] for r in wanted], "order": names,
            "sums": "".join(f"{f['sha256']}  {f['path']}\n" for f in files), "setup_file": setup_file}


def check_offline_memory(mem_kb, bundle):
    """OfflineNotPossible unless the router has 24000 KB free after the bundle is in its memory (the rule updates use)."""
    need = 24000 + -(-bundle["total"] // 1024)
    if not mem_kb:
        raise OfflineNotPossible("could not tell how much free memory the router has")
    if mem_kb < need:
        raise OfflineNotPossible(f"the router has {mem_kb} KB of free memory and copying the bundle needs {need} KB (24000 KB must stay free)")


def sub_bundle(bundle, paths):
    """The same bundle with only these files (what goes to a factory router before it is moved)."""
    files = [f for f in bundle["files"] if f["path"] in paths]
    return dict(bundle, files=files, total=sum(f["size"] for f in files), sums="".join(f"{f['sha256']}  {f['path']}\n" for f in files))


def router_has_internet(ssh, host):
    """Decision A8: the router answers a ping to 1.1.1.1 or 8.8.8.8 (the test the setup itself uses)."""
    r = run_remote(ssh, host, "ping -c 1 -W 3 1.1.1.1 > /dev/null 2>&1 || ping -c 1 -W 3 8.8.8.8 > /dev/null 2>&1", lambda line: None)
    return r["exit"] == 0


def push_offline_bundle(ssh, host, bundle, mem_kb, emit=None, offline_dir=OFFLINE_DIR):
    """Copies the bundle into the router's memory, one ssh command per file, and has the router check every sha256. Returns
    "already there" (the same bundle is in place: the second run after the address move) or "copied". Raises OfflineNotPossible
    when the router has too little free memory (24000 KB must stay free) or a file did not arrive intact."""
    emit = emit or (lambda line: None)
    check_offline_memory(mem_kb, bundle)
    q = shlex.quote(offline_dir)
    sums = hashlib.sha256(bundle["sums"].encode()).hexdigest()

    def run(command, data=None):
        lines = []
        result = run_remote(ssh, host, command, lines.append, data=data)
        return result["exit"], lines

    code, lines = run(f"cd {q} 2>/dev/null && [ \"$(sha256sum < SHA256SUMS | cut -c1-64)\" = {sums} ] && sha256sum -c SHA256SUMS >/dev/null 2>&1 && echo @@SAME")
    if any(line.strip() == "@@SAME" for line in lines):
        emit("The files are on the router already.")
        return "already there"
    code, lines = run(f"rm -rf {q}")
    if code != 0:
        raise OfflineNotPossible("the router did not let this computer clear its old copy in memory")
    for f in bundle["files"] + [{"path": "SHA256SUMS", "data": bundle["sums"].encode()}]:
        if "data" in f:
            data = f["data"]
        else:
            with open(os.path.join(bundle["dir"], *f["path"].split("/")), "rb") as fh:
                data = fh.read()
        rel = f["path"]
        full = f"{offline_dir}/{rel}"
        emit(f"Copying {rel} to the router")
        code, lines = run(f"mkdir -p {shlex.quote(os.path.dirname(full))} && cat > {shlex.quote(full)}.part && mv {shlex.quote(full)}.part {shlex.quote(full)}", data)
        if code != 0:
            raise OfflineNotPossible(f"{rel} could not be copied to the router's memory")
    code, lines = run(f"cd {q} && sha256sum -c SHA256SUMS")
    if code != 0:
        bad = [line.split(":")[0] for line in lines if line.rstrip().endswith("FAILED")]
        raise OfflineNotPossible(f"{bad[0] if bad else 'a file'} did not arrive intact on the router")
    return "copied"


def tip_text(line):
    """The advice in a "TIP:" line of the router's output, or None."""
    m = re.match(r"\s*TIP: (.+)$", line or "")
    return m.group(1).strip() if m else None


def stream(proc, emit):
    """Passes the router's output on line by line as it comes (emit) and returns the sections after the markers (rc, state,
    summary, handout)."""
    result = {"rc": None, "state": "", "summary": [], "handout": []}
    section = None
    for raw in iter(proc.stdout.readline, b""):
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("@@PISO-RC "):
            word = line.split()[1] if len(line.split()) > 1 else ""
            result["rc"] = int(word) if word.isdigit() else None
            section = "tail"
            continue
        if line.startswith("@@PISO-STATE"):
            result["state"] = line[len("@@PISO-STATE"):].strip()
            continue
        if line in ("@@PISO-SUMMARY", "@@PISO-HANDOUT"):
            section = line[7:].lower()
            continue
        if line == "@@PISO-END":
            section = "end"
            continue
        if section in ("summary", "handout"):
            result[section].append(line)
        elif section is None:
            emit(line)
    proc.wait()
    result["exit"] = proc.returncode
    return result


def run_remote(ssh, host, remote, emit, data=None):
    """Runs a remote command, its output (ssh's own messages included) passed to emit line by line."""
    proc = ssh.popen(host, remote, "-T", stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if data is not None:
        try:
            proc.stdin.write(data if isinstance(data, bytes) else data.encode())
            proc.stdin.close()
        except OSError:
            pass   # ssh failed before reading: its message and exit status say why
    return stream(proc, emit)


def save_private(path, text):
    """Writes a file only this user can read (it holds passwords)."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def wait_for_move(probe=port_open, wait=MOVE_WAIT, sleep=time.sleep, now=time.monotonic, renew=None, notify=say):
    """After the move: waits until the router answers at 10.0.0.1 (this computer needs a new address in 10.0.0.x)."""
    notify(f"The router is moving to {TARGET_IP}. Waiting for it (this computer needs a new address on the router's network)...")
    start, hinted, renewed = now(), False, False
    sleep(5)
    while now() - start < wait:
        if probe(TARGET_IP):
            notify(f"The router answers at {TARGET_IP}.")
            return True
        elapsed = now() - start
        if elapsed > 20 and renew and not renewed:
            renewed = True
            renew()
        if elapsed > 30 and not hinted:
            hinted = True
            notify("Still waiting. Unplug this computer's network cable from the router, wait 5 seconds and plug it back in.")
        sleep(3)
    return False


def renew_address():
    """Windows: ask for a new address at once (other systems do it when the cable is replugged)."""
    if os.name == "nt":
        try:
            subprocess.run(["ipconfig", "/renew"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90, check=False,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            pass


def install(ssh, host, answers, branch, out_dir, emit, wait_move, stage=lambda n: None, online=False, offline_site=None,
            opener=urllib.request.urlopen):
    """The installation: on a factory router the installer first moves it to 10.0.0.1 (wait_move() waits for that), then
    the setup runs with the answers. Returns the result (ok, state, saved files, summary); raises SetupError when it could
    not run to its end.

    Offline first (unless `online`): this computer reads the router, builds the packages bundle and checks that it fits in the
    router's memory before the router is changed at all; the router then installs from its memory and never goes online. When the
    bundle cannot be prepared (or copied) the router's internet is tested (decision A8): with internet today's way runs and the
    reason is told; without, the setup stops and says both reasons. The bundle is removed from the router afterwards."""
    state = {"local": False, "pushed": False, "moved": False}
    work = None
    info = bundle = None
    if ssh.env is not None and login_refused(ssh, host):   # (a terminal ssh asks for the password itself)
        raise SetupError(login_refused_message(ssh))

    def fall_back(reason):
        state["local"] = False
        emit(f"NOTE: the setup without the router's internet is not possible: {reason}")
        clean_router()
        if router_has_internet(ssh, host_now[0]):
            emit("The router has internet: it will download what it needs, as the setup always did.")
            return
        raise SetupError(f"{'Nothing more was changed' if state['moved'] else 'Nothing was changed'} on the router. The setup without the router's internet is not possible ({reason}), "
                         "and the router has no internet either: plug the modem into its WAN port (and wait a minute), or fix the reason "
                         "above, then try again.")

    def clean_router():
        if state["pushed"]:
            state["pushed"] = False
            try:
                run_remote(ssh, host_now[0], f"rm -rf {shlex.quote(OFFLINE_DIR)}", lambda line: None)
            except (OSError, subprocess.SubprocessError):
                pass   # (a restart of the router clears its memory too)

    host_now = [host]
    try:
        if not online:
            try:
                info = read_router_feeds(ssh, host)
                emit("Preparing the router's packages on this computer, so that the router does not need the internet...")
                work = tempfile.mkdtemp(prefix="piso-offline-")
                bundle = build_offline_bundle(info, branch, work, opener=opener, site=offline_site, emit=emit)
                check_offline_memory(info["mem_kb"], bundle)
                state["local"] = True
            except ApkRouter:
                raise
            except OfflineNotPossible as e:
                fall_back(e.reason)
        if host != TARGET_IP:   # (the factory address, or the one this router started at: the installer moves it)
            stage(0)
            if state["local"]:
                try:
                    first = sub_bundle(bundle, {"install.sh", bundle["setup_file"], bundle["setup_file"] + ".sha256"})
                    state["pushed"] = True
                    push_offline_bundle(ssh, host, first, info["mem_kb"], emit)
                except OfflineNotPossible as e:
                    fall_back(e.reason)
            if state["local"]:
                emit(f"Moving the router to {TARGET_IP} (it downloads nothing)...")
                r = run_remote(ssh, host, local_installer_command(branch), emit)
            else:
                emit(f"Downloading the setup to the router and moving it to {TARGET_IP}...")
                r = run_remote(ssh, host, installer_command(branch), emit)
            if r["exit"] != 0:
                raise SetupError("the router could not be prepared (see the messages above). Nothing else was changed; try again.")
            stage(1)
            if not wait_move():
                raise SetupError(
                    f"the router does not answer at {TARGET_IP}. Unplug and replug this computer's network cable (or restart the "
                    "computer's network), then try again: it continues where it stopped.")
            host = host_now[0] = TARGET_IP
            state["moved"] = True
        stage(2)
        if state["local"]:
            try:
                state["pushed"] = True
                push_offline_bundle(ssh, host, bundle, info["mem_kb"], emit)
            except OfflineNotPossible as e:
                fall_back(e.reason)
        values = "".join(answers.get(key, "") + "\n" for key in ANSWER_ORDER)
        save_router_password(out_dir, answers.get("ROOT_PASSWORD"), host)   # before the router changes it: a repeat can log in
        result = run_remote(ssh, host, setup_command(branch, state["local"]), emit, data=values)
    finally:
        clean_router()
        if work:
            shutil.rmtree(work, ignore_errors=True)

    saved = []
    stamp = time.strftime("%Y%m%d-%H%M")
    os.makedirs(out_dir, exist_ok=True)
    if result["summary"]:
        path = os.path.join(out_dir, f"pisophone-summary-{stamp}.txt")
        save_private(path, "\n".join(result["summary"]) + "\n")
        saved.append(("Summary (every password)", os.path.abspath(path)))
    if result["handout"]:
        path = os.path.join(out_dir, f"pisophone-setup-sheet-{stamp}.html")
        save_private(path, "\n".join(result["handout"]) + "\n")
        saved.append(("Printable setup sheet", os.path.abspath(path)))
    found = parse_summary("\n".join(result["summary"]))
    if found.get("KIOSK_PASSWORD"):   # the card for the owner's manual: scan to join the hidden network, scan to open the box
        site = answers.get("SITE_NAME") or found.get("GUEST_SSID") or KIOSK_SSID
        path = os.path.join(out_dir, f"pisophone-wifi-card-{stamp}.html")
        save_private(path, wifi_card_html(site, found.get("KIOSK_SSID_NAME") or KIOSK_SSID, found["KIOSK_PASSWORD"], stamp=time.strftime("%Y-%m-%d")))
        saved.append(("Wi-Fi join card (print it for your manual)", os.path.abspath(path)))
    if result["rc"] is None:
        raise SetupError("the connection to the router was lost before the setup finished. Try again: it continues where it "
                         "stopped (the router keeps what is done).")
    stage(3)
    result.update(host=host, saved=saved, ok=result["rc"] == 0 and result["state"].startswith("DONE all checks passed"))
    result["summary_text"] = "\n".join(result["summary"])
    return result


def failure_message(result):
    """What went wrong, for a result that is not ok."""
    state = result["state"]
    if state.startswith("DONE"):
        return (f"the setup finished, but some checks failed ({state}). Read the messages above; after fixing the cause run "
                "it again (it continues where it stopped).")
    reason = state.replace("FAILED ", "", 1) if state else "see the messages above"
    more = ""
    if "box" in reason.lower():
        more = (" The report above (ending in a line that starts with \"verdict:\") says where the link to the coin box breaks; "
                f"you can get it again any time with: ssh root@{TARGET_IP} piso-setup box-diag.")
    return f"the setup stopped: {reason}.{more} Fix that and run it again: it continues where it stopped."


def update_router(ssh, host, branch, emit):
    r = run_remote(ssh, host, installer_command(branch, "update"), emit)
    if r["exit"] != 0:
        raise SetupError("the update did not finish (see the messages above). It is safe to run it again.")


def connect_telegram(ssh, host, token, site, emit, confirm):
    """Connects the Telegram bot (piso-setup telegram): the router waits for the first message sent to the bot, then
    confirm(<that line>) decides whether that chat is the owner's. True when connected."""
    proc = ssh.popen(host, "piso-setup telegram", "-T", stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        proc.stdin.write(f"{token}\n{site}\n".encode())
        proc.stdin.flush()
    except OSError:
        pass
    buf, chat, answered, done = "", "", False, False
    fd = proc.stdout.fileno()
    while True:
        chunk = os.read(fd, 4096)
        if not chunk:
            break
        buf += chunk.decode("utf-8", "replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            line = line.rstrip("\r")
            if line.startswith("Got a message from chat"):
                chat = line
            if line.startswith("Done."):
                done = True
            emit(line)
        if not answered and "Is that you" in buf:
            answered = True
            ok = confirm(chat or "a chat")
            emit(buf.strip() + (" yes" if ok else " no"))
            buf = ""
            try:
                proc.stdin.write(b"y\n" if ok else b"n\n")
                proc.stdin.close()
            except OSError:
                pass
    if buf.strip():
        emit(buf.strip())
    proc.wait()
    return proc.returncode == 0 and done


# ---- the browser, the coin box's page, the checks ------------------------------------------------------------------------

def chromium_path():
    """Chrome, Edge or Chromium on this computer (the phone setup page needs WebUSB to set up a phone over a cable: Chromium only)."""
    candidates = []
    if os.name == "nt":
        for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), os.environ.get("LOCALAPPDATA")):
            if base:
                candidates += [os.path.join(base, "Google", "Chrome", "Application", "chrome.exe"),
                               os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe")]
    elif sys.platform == "darwin":
        candidates = ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                      "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                      "/Applications/Chromium.app/Contents/MacOS/Chromium"]
    for path in candidates:
        if os.path.isfile(path):
            return path
    for name in ("google-chrome", "google-chrome-stable", "chrome", "microsoft-edge", "microsoft-edge-stable", "msedge",
                 "chromium", "chromium-browser"):
        found = shutil.which(name)
        if found:
            return found
    return None


def open_url(url, chromium=True, find=chromium_path, launch=subprocess.Popen, fallback=webbrowser.open):
    """Opens a page in Chrome or Edge when this computer has one (a new tab of the running browser), else in the default
    browser. Returns "chromium" or "default"."""
    exe = find() if chromium else None
    if exe:
        try:
            launch([exe, url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return "chromium"
        except OSError:
            pass
    fallback(url)
    return "default"


def fetch_box_link(host, password, opener=urllib.request.urlopen, timeout=8):
    """The coin box's MAC and secret, read from its admin page the way its Set up a phone button does (the page holds
    them as window.PISO_CFG). Logs in as admin with the coin box admin password."""
    req = urllib.request.Request(f"http://{host}/")
    req.add_header("Authorization", "Basic " + base64.b64encode(f"admin:{password}".encode()).decode())
    try:
        with opener(req, timeout=timeout) as r:
            page = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise SetupError("the coin box did not accept the admin password")
        raise SetupError(f"the coin box answered with an error (HTTP {e.code})")
    except (urllib.error.URLError, OSError) as e:
        raise SetupError(f"the coin box at {host} does not answer ({getattr(e, 'reason', e)})")
    m = re.search(r'PISO_CFG\s*=\s*\{\s*mac:\s*"([^"]*)"\s*,\s*secret:\s*"([^"]*)"', page)
    if not m:
        raise SetupError(f"{host} did not show the coin box's admin page")
    return {"mac": m.group(1), "secret": m.group(2)}


def provisioning_url(branch, link, slot, wifi_pass, box_ip=BOX_IP, wifi_ssid=""):
    """The website's phone setup page for this box, filled in. Everything goes after the # (a fragment is never sent to a web
    server, its logs or a Referer); the page reads it, shows the QR code and removes it from the address bar."""
    values = {"mac": link["mac"], "secret": link["secret"], "slot": slot, "name": f"PisoPhone {slot}", "ip": box_ip,
              "wifi_ssid": wifi_ssid if wifi_ssid != KIOSK_SSID else "",   # (empty: the shared old name, which the page assumes)
              "wifi_pass": wifi_pass}
    return f"{site_url(branch)}/#" + urllib.parse.urlencode({k: v for k, v in values.items() if v not in ("", None)})


def provisioning_target(branch, passwords, slot=1, fetch=fetch_box_link):
    """Where the phone setup page is: ("setup", the website's page, filled in, "") when the coin box's admin page can be read,
    else ("box", the coin box's own page, why not)."""
    box_password = passwords.get("BOX_NEW_ADMIN_PASSWORD") or ""
    note = "the coin box's admin password is not known here"
    if box_password:
        try:
            link = fetch(BOX_IP, box_password)
            if link["mac"] and link["mac"] != "00:00:00:00:00:00":
                return "setup", provisioning_url(branch, link, slot, passwords.get("KIOSK_PASSWORD", ""),
                                                 wifi_ssid=passwords.get("KIOSK_SSID_NAME", "")), ""
            note = "the coin box has no address yet"
        except SetupError as e:
            note = str(e)
    return "box", f"http://{BOX_IP}/", note


def open_provisioning(branch, passwords, slot=1, fetch=fetch_box_link, opener=open_url):
    """Opens the coin box's phone setup page (Set up a phone) in the browser: filled in when the box's admin page can be
    read, else the box's own page (log in, click Set up a phone). Returns ("setup" or "box", a note for the user)."""
    kind, url, note = provisioning_target(branch, passwords, slot, fetch)
    if kind == "setup":
        opener(url)
    else:
        opener(url, chromium=False)
    return kind, note


def box_online(host=BOX_IP, probe=port_open):
    return probe(host, 80)


PRINT_HOST = "10.0.0.95"   # where the router looks for the print server unless SEND_HOST says otherwise


def read_box_network(ssh, host):
    """The name of the router's hidden coin box network, read-only, over the login the session already holds (it never asks for
    a password); None when the router cannot be asked."""
    try:
        r = ssh.run(host, "uci -q get wireless.box_ap.ssid", "-T", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return None
    return r.stdout.strip() or None if r.returncode == 0 else None


def read_router_alerts(ssh, host):
    """The last lines of the router's alerts.txt (what its monitor noticed: a portal that was restarted, little free flash, the coin box
    off), read-only over the login the session already holds. [] when there are none, None when the router cannot be asked."""
    try:
        r = ssh.run(host, "tail -n 5 /etc/coinslot.d/alerts.txt 2>/dev/null; true", "-T", capture_output=True, text=True, timeout=60,
                    stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        return None
    return [ln.strip() for ln in r.stdout.splitlines() if ln.strip()] if r.returncode == 0 else None


def run_checkup(branch, probe=port_open, opener=urllib.request.urlopen, box_net=None, alerts=None):
    """The Check-up page: a read-only look at the router, the coin box, the update feed and the print server. Each item has
    a state (ok, bad, warn for a notice, or off for the optional print server), one line saying what was found and, when it is
    not ok, the one-line fix. `box_net` (optional) tells the name of the router's coin box network; `alerts` (optional) the last
    lines of the router's alerts.txt (the way a shop without Telegram is told something is wrong)."""
    items = []

    def add(name, state, text, fix=""):
        items.append({"name": name, "state": state, "text": text, "fix": fix})

    if probe(TARGET_IP, 22):
        add("Router", "ok", f"Answers at {TARGET_IP}.")
    elif probe(FACTORY_IP, 22):
        add("Router", "bad", f"Answers at {FACTORY_IP}: it has not been set up yet.", "Run the installation from the setup page.")
    else:
        add("Router", "bad", "Does not answer.", "Is it powered on, and is this computer plugged into one of its LAN ports (not WAN)?")
    if box_online(BOX_IP, probe):
        add("Coin box", "ok", f"Answers at {BOX_IP}.")
    else:
        add("Coin box", "bad", f"Does not answer at {BOX_IP}.", "Is it powered (its USB cable) and near the router? If it was never paired, run the installation again.")
    if box_net is not None and probe(TARGET_IP, 22):
        try:
            name = box_net()
        except (OSError, subprocess.SubprocessError):
            name = None
        if name == "PisoCoinBox":
            add("Coin box network", "warn", "Called PisoCoinBox, the shared setup name: a second router being set up nearby can take this box's place.",
                "Log in to the router and run: piso-setup box-wifi-name")
    if alerts is not None and probe(TARGET_IP, 22):
        try:
            lines = alerts()
        except (OSError, subprocess.SubprocessError):
            lines = None
        if lines:
            add("Router alerts", "warn", "The router's monitor noted: " + " | ".join(lines[-3:]),
                "Read them; they stay in /etc/coinslot.d/alerts.txt and on the owner console's front page.")
        elif lines is not None:
            add("Router alerts", "ok", "Nothing noted by the router's monitor.")
    try:
        request = urllib.request.Request(site_url(branch) + "/update/router.json", headers={"User-Agent": USER_AGENT})
        with opener(request, timeout=8) as r:
            feed = json.loads(r.read(20000).decode("utf-8"))
        add("Update feed", "ok", f"Router release {feed['version']} is published ({feed.get('rollout', 100)}% of routers).")
    except (OSError, ValueError, KeyError, AttributeError):
        add("Update feed", "bad", "The website's update list cannot be read from this computer.",
            "Check this computer's internet. The routers update from the same list, by themselves.")
    if probe(PRINT_HOST, 80):
        add("Print server", "ok", f"Answers at {PRINT_HOST}.")
    else:
        add("Print server", "off", f"Nothing at {PRINT_HOST}. This is optional.", "Only if you use one: see the Add a print server page.")
    return items


def terminal_qr(text, tty=None):
    """The QR code for a text drawn with half-block characters and colours (black on white, so a phone reads it on any terminal),
    or an empty string when the output is not a terminal that shows colours."""
    if tty is None:
        tty = sys.stdout.isatty() and not os.environ.get("NO_COLOR") and (os.name != "nt" or bool(os.environ.get("WT_SESSION")))
    if not tty:
        return ""
    try:
        rows = qr_matrix(text)
    except ValueError:
        return ""
    quiet = 2
    width = len(rows) + 2 * quiet
    grid = [[False] * width for _ in range(quiet)] + [[False] * quiet + list(r) + [False] * quiet for r in rows] + [[False] * width for _ in range(quiet)]
    if len(grid) % 2:
        grid.append([False] * width)
    lines = []
    for top, bottom in zip(grid[0::2], grid[1::2]):
        # "upper half block": its colour is the top module, the background is the bottom module (30 black / 97 white; 40 / 107)
        lines.append("".join(f"\x1b[{30 if t else 97};{40 if b else 107}m\u2580" for t, b in zip(top, bottom)) + "\x1b[0m")
    return "\n".join(lines)


# ---- the coin box: its software goes in over USB ----------------------------------------------------------------------------

FLASH_BAUD = 460800   # fast, and still reliable on the cheap USB-serial chips of most boards
# the USB-serial chips on ESP32 boards: Espressif's own (C3 and S3 boards), CH340, CP210x, FTDI, Prolific
USB_BOARD_IDS = {0x303A: "Espressif", 0x1A86: "CH340", 0x10C4: "CP210x", 0x0403: "FTDI", 0x067B: "Prolific"}
CHIP_RE = re.compile(r"(?:Chip is|Chip type:|Connected to)\s*(ESP32(?:-[CSHP]\d)?)", re.I)
PROGRESS_RE = re.compile(r"\((\d+) ?%\)")


def esptool_module():
    """Espressif's flashing tool (pip install esptool; the standalone program carries it), or None."""
    try:
        import esptool
        return esptool
    except ImportError:
        return None


def esptool_major(tool):
    try:
        return int(str(tool.__version__).split(".")[0])
    except (AttributeError, ValueError):
        return 4


def usb_boards(comports=None):
    """[(port, description)] of the USB serial adapters coin box boards use, sorted. Empty when none is plugged in (or
    pyserial, which esptool brings, is missing)."""
    if comports is None:
        use_private_libs()
        try:
            from serial.tools.list_ports import comports
        except ImportError:
            return []
    found = []
    for port in comports():
        if getattr(port, "vid", None) in USB_BOARD_IDS:
            found.append((port.device, port.description or USB_BOARD_IDS[port.vid]))
    return sorted(found)


class _Lines(io.TextIOBase):
    """Collects what esptool prints and hands it on line by line (its progress lines end in a carriage return)."""

    def __init__(self, emit):
        self.emit, self.buf = emit, ""

    def writable(self):
        return True

    def write(self, text):
        self.buf += text
        parts = re.split(r"[\r\n]", self.buf)
        self.buf = parts.pop()
        for part in parts:
            if part.strip():
                self.emit(part.rstrip())
        return len(text)

    def flush(self):
        if self.buf.strip():
            self.emit(self.buf.rstrip())
        self.buf = ""


def run_esptool(tool, argv, emit):
    """Runs one esptool command in this process. Raises SetupError (with the tool's last words) when it fails."""
    seen = []
    real_out, real_err = sys.stdout, sys.stderr

    def take(line):
        seen.append(line)
        # (esptool's output is collected by redirecting stdout: a function that prints must print to the real one, or it feeds itself)
        with contextlib.redirect_stdout(real_out), contextlib.redirect_stderr(real_err):
            emit(line)
    out = _Lines(take)
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            tool.main(argv)
    except SystemExit as e:
        if e.code not in (0, None):
            out.flush()
            raise SetupError("the flashing tool stopped: " + (seen[-1] if seen else str(e.code)))
    except Exception as e:   # esptool's own errors (FatalError, serial problems)
        out.flush()
        raise SetupError("the flashing tool failed: " + re.sub(r"\s+", " ", str(e)).strip())
    out.flush()
    return seen


def board_advice(message):
    """What to try when the board did not answer (esptool says 'Failed to connect', 'Permission denied' ...)."""
    low = message.lower()
    if "permission" in low or "access is denied" in low:
        return ("This computer may not use the USB port. Linux: add your user to the 'dialout' group and log in again. "
                "Another program (a serial monitor) may also be using the port.")
    if "failed to connect" in low or "timed out" in low or "no serial data" in low:
        return ("The board did not answer. Use a USB DATA cable, hold the board's BOOT button, tap RESET, release BOOT, and try "
                "again. Some boards need their USB driver (CH340 or CP210x) installed once.")
    return ""


def with_advice(error, evidence):
    """An error's text, one sentence, with what to try when the board's trouble is a known one."""
    text = str(error).rstrip(". ")
    advice = board_advice(evidence)
    return f"{text}. {advice}" if advice else text + "."


def flash_manifest(branch, cache_dir, opener=urllib.request.urlopen, emit=say):
    """The list of firmware images of this branch (flash/manifest.json on the website). Offline, the copy kept on this
    computer from the last time is used."""
    path = os.path.join(cache_dir, "manifest.json")
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/manifest.json", headers={"User-Agent": USER_AGENT}), timeout=15) as r:
            text = r.read().decode("utf-8")
        manifest = json.loads(text)
        os.makedirs(cache_dir, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    except (urllib.error.URLError, OSError, ValueError):
        try:
            with open(path, encoding="utf-8") as f:
                manifest = json.load(f)
            emit("The website could not be reached: using the coin box software kept from the last time.")
        except (OSError, ValueError):
            raise SetupError("the coin box software list could not be downloaded. This computer needs internet for this step.")
    if not isinstance(manifest.get("builds"), list) or not manifest["builds"]:
        raise SetupError("the website has no coin box software for this branch.")
    return manifest


def choose_build(manifest, chip, product="full"):
    """The image of a product ("full", "phone" or "wifi") for a chip ("ESP32-C3", "ESP32"), or None. A list entry without a
    product is the full one; the image of another product is never taken instead."""
    name = (chip or "").upper()
    return next((b for b in manifest["builds"]
                 if str(b.get("chip", "")).upper() == name and (b.get("product") or "full") == product), None)


def fetch_image(branch, build, cache_dir, opener=urllib.request.urlopen):
    """The path of the image, downloaded and checked against the manifest's size and sha256 (kept for next time)."""
    path = os.path.join(cache_dir, f"{build['sha256'][:16]}-{build['file']}")
    if re.search(r"[/\\]", build["file"]):
        raise SetupError("the website's software list is not valid.")

    def good(data):
        return len(data) == build["size"] and hashlib.sha256(data).hexdigest() == build["sha256"]
    try:
        with open(path, "rb") as f:
            if good(f.read()):
                return path
    except OSError:
        pass
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/{build['file']}", headers={"User-Agent": USER_AGENT}), timeout=60) as r:
            data = r.read()
    except (urllib.error.URLError, OSError) as e:
        raise SetupError(f"the coin box software could not be downloaded ({e}).")
    if not good(data):
        raise SetupError("the downloaded coin box software does not match its checksum. Try again in a minute.")
    os.makedirs(cache_dir, exist_ok=True)
    with open(path + ".part", "wb") as f:
        f.write(data)
    os.replace(path + ".part", path)
    return path


def flash_box(branch, port, emit, cache_dir, opener=urllib.request.urlopen, tool=None, image=None, product="full"):
    """Writes the PisoPhone software to the board on `port`: detects the chip, takes the image for it (checked), erases the
    whole flash (a used box then forgets its old Wi-Fi), writes and verifies it, restarts the board. Returns (chip, version)."""
    tool = tool or esptool_module()
    if tool is None:
        raise SetupError("the flashing tool is missing: run  pip install esptool  and start this again.")
    major = esptool_major(tool)

    def name(text):   # esptool 5 spells its commands and options with hyphens (4 with underscores)
        return text.replace("_", "-") if major >= 5 else text
    base = ["--port", port, "--baud", str(FLASH_BAUD)]
    emit(f"== Looking at the board on {port}")
    seen = []
    try:
        run_esptool(tool, base + [name("chip_id")], seen.append)
    except SetupError as e:
        raise SetupError(with_advice(e, ' '.join(seen) + ' ' + str(e)))
    match = CHIP_RE.search("\n".join(seen))
    if not match:
        raise SetupError("the board did not say which chip it has. " + board_advice("failed to connect"))
    chip = match.group(1).upper()
    if image:   # a merged image of your own build (terminal mode), written from the start of the flash
        build, manifest = {"offset": 0}, {"version": "your own build"}
        emit(f"{chip} found. Writing {image}.")
    else:
        manifest = flash_manifest(branch, cache_dir, opener, emit)
        build = choose_build(manifest, chip, product)
        if not build:
            chips = list(dict.fromkeys(b["chip"] for b in manifest["builds"] if (b.get("product") or "full") == product))
            raise SetupError(f"this board is an {chip}; PisoPhone software exists for {' and '.join(chips) or 'no board'}.")
        emit(f"{chip} found. Software version {manifest.get('version', '?')} ({build['size'] / 1048576:.2f} MB).")
        image = fetch_image(branch, build, cache_dir, opener)
    emit("== Writing the coin box software (erasing the board first; about a minute)")
    last = [-10]

    def progress(line):
        found = PROGRESS_RE.search(line)
        if found:
            pct = int(found.group(1))
            if pct // 10 == last[0] // 10 and pct < 100:
                return
            last[0] = pct
        emit(line)
    args = base + [name("write_flash"), "-e", "-z", name("--flash_mode"), "keep", name("--flash_freq"), "keep",
                   name("--flash_size"), "keep", hex(build["offset"]), image]
    try:
        run_esptool(tool, args, progress)
    except SetupError as e:
        raise SetupError(with_advice(e, str(e)))
    emit("Written and verified. The board restarts by itself; keep it powered (the USB cable does that) while it is set up.")
    return chip, manifest.get("version", "?")


def preflight(branch, opener=urllib.request.urlopen, tool=esptool_module, which=shutil.which):
    """The checks of the first page: [(what, ok, what to do when not)]. Nothing here changes anything."""
    checks = []
    have_ssh = bool(os.environ.get("PISO_SSH") or which("ssh"))
    checks.append(("ssh program", have_ssh, "Install it with the button below (Linux), or: Windows: Settings > Apps > Optional "
                                            "features > add \"OpenSSH Client\"."))
    online, why = False, ""
    for path in ("/update/app.json", "/update/router.json"):   # (either answering proves the website is reachable)
        try:
            with opener(urllib.request.Request(f"{site_url(branch)}{path}", headers={"User-Agent": USER_AGENT}), timeout=15) as r:
                online = r.status == 200
        except urllib.error.HTTPError as e:
            why = f"the website answered HTTP {e.code}"
        except urllib.error.URLError as e:
            why = f"{e.reason}"
        except (OSError, ValueError) as e:
            why = f"{type(e).__name__}: {e}"
        if online:
            break
    advice = "This computer needs internet now (the router gets its own from the modem later)."
    if why:
        advice += f" The error was: {why}."
        if "CERTIFICATE" in why.upper():
            advice += " A security program or a proxy on this computer may be inspecting HTTPS traffic: pause it for the setup, or try another network."
    checks.append((f"internet and the {urllib.parse.urlsplit(site_url(branch)).netloc} website", online, advice))
    checks.append(("USB flashing tool (esptool)", tool() is not None,
                   "Install it with the button below, or run  python3 -m pip install esptool."))
    return checks


# ---- the terminal mode ---------------------------------------------------------------------------------------------------

def flash_in_terminal(args, read, board_finder, flasher):
    """Writes the coin box's software when a board is on USB. Without one, goes on (the box may have it already)."""
    boards = board_finder()
    if not boards:
        say("No coin box board found on USB: going on without writing it (it must run PisoPhone software already; plug it in and")
        say("run this again to write it, or add --no-flash to skip this check).")
        return
    if len(boards) > 1:
        raise SetupError("several USB boards are connected (" + ", ".join(p for p, _d in boards) + "): unplug all but the coin box.")
    port, description = boards[0]
    say(f"Coin box board found: {description} on {port}.")
    if not args.yes and read("Write the PisoPhone software to it? This erases the board. [Y/n] ").strip().lower() in ("n", "no"):
        say("Skipped writing the coin box.")
        return
    flasher(port, say)


def run_setup(args, ssh, read=input, read_secret=getpass.getpass, probe=port_open, wait=wait_for_move, open_page=open_url,
              board_finder=usb_boards, flasher=None, offline_site=None):
    if flasher is None:
        def flasher(port, emit):
            return flash_box(args.branch, port, emit, flash_cache_dir(), product="wifi" if getattr(args, "product", None) == "wifi" else "full")
    host = args.router or find_router(probe)
    if not host:
        raise SetupError(
            f"no router found at {TARGET_IP} or {FACTORY_IP}. Check that this computer is plugged into one of the router's LAN "
            "ports (not WAN), that the router has finished starting (about 2 minutes after power-on) and was factory reset, and "
            "turn this computer's Wi-Fi off.")
    say(f"Router found at {host}.")
    state = probe_router(ssh, host)
    auto_login = use_saved_password(ssh, host, [args.out, default_out_dir()]) if state == "password" else None
    if args.update:
        if state == "factory":
            raise SetupError("this router has not been set up yet: run this script without --update")
        say("Installing the current PisoPhone software (you are asked for the router password)...")
        update_router(ssh, host, args.branch, say)
        say()
        say("UPDATE DONE.")
        return 0

    if state in ("configured", "password"):
        # (the router's own settings win over new answers, so none are asked: they would not be used)
        say("This router was set up before (or has a password): it keeps the names and passwords it has, and the setup")
        say("finishes or repairs it. Anything it does not have yet is generated and shown in the summary.")
        if state == "password" and not auto_login:
            say("Type the router password when ssh asks for it (it is in your saved summary; a router that was just")
            say("factory reset has none: press Enter).")
        if not args.yes and read("Continue? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1
        answers = kept_answers((getattr(args, "country", None) or "PH").upper(), getattr(args, "product", None) or "")
    else:
        answers = collect_answers(args, read, read_secret)
        review(answers)
        if not args.yes and read("Apply these settings to the router? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1

    if not getattr(args, "no_flash", False):
        flash_in_terminal(args, read, board_finder, flasher)
    say()
    if host != TARGET_IP:
        say(f"Step 1 of 2: downloading the setup to the router and moving it to {TARGET_IP}...")
    else:
        say("Step 1 of 2: the router is at its address already.")

    def stage(n):
        if n == 2:
            say()
            say("Step 2 of 2: the setup runs on the router (about 3 to 8 minutes; it waits for the coin box to join, so keep the")
            say("box powered on). Do not close this window.")
            say()

    result = install(ssh, host, answers, args.branch, args.out, say, lambda: wait(probe=probe, renew=renew_address), stage,
                     online=getattr(args, "router_online", False), offline_site=offline_site)
    host = result["host"]
    say()
    for label, path in result["saved"]:
        say(f"{label} saved to: {path}  (keep it private)")
    if not result["ok"]:
        raise SetupError(failure_message(result))
    say()
    say("SETUP COMPLETE: all checks passed.")
    found = dict(answers, **parse_summary(result.get("summary_text", "")))
    if not args.no_browser:
        try:
            kind, note = open_provisioning(args.branch, found, opener=open_page)
            say("Opened the phone setup page in your browser: scan its QR code with a factory-reset phone (6 taps on the welcome"
                " screen)." if kind == "setup" else
                f"Opened the coin box's page ({note}): log in as admin and click Set up a phone.")
        except Exception:
            say(f"Next: set up the rental phones from the coin box's page (http://{BOX_IP}/), Set up a phone.")
    else:
        say(f"Next: set up the rental phones from the coin box's page (http://{BOX_IP}/), Set up a phone.")
    if not args.yes and read("Connect Telegram alerts now? You need a bot token from @BotFather. [y/N] ").strip().lower() in ("y", "yes"):
        if found.get("ROOT_PASSWORD"):
            ssh.set_password(found["ROOT_PASSWORD"])   # the setup has set it: ssh needs no typing
        else:
            say("Type the router password when ssh asks for it (it is in the summary above).")
        ssh.run(host, "piso-setup telegram", "-t")
    return 0


# ---- this computer: what it is, what it needs --------------------------------------------------------------------------------

def pisophone_home():
    """The folder for what this program keeps (the PIN, downloaded libraries): ~/.pisophone, or $PISOPHONE_HOME (the service)."""
    return os.environ.get("PISOPHONE_HOME") or os.path.join(os.path.expanduser("~"), ".pisophone")


def private_lib_dir():
    """Where esptool is installed when the system's Python does not have it (pip --target: no root, no venv, works on
    Debian-family systems whose Python is 'externally managed')."""
    return os.path.join(pisophone_home(), f"pylib-{sys.version_info[0]}.{sys.version_info[1]}")


def use_private_libs():
    lib = private_lib_dir()
    if os.path.isdir(lib) and lib not in sys.path:
        sys.path.insert(0, lib)


def read_os_release(path="/etc/os-release"):
    values = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                key, sep, value = line.strip().partition("=")
                if sep:
                    values[key] = value.strip().strip('"')
    except OSError:
        pass
    return values


def package_family(os_release):
    """The package manager family of a Linux system, from /etc/os-release (ID and ID_LIKE): Armbian, Parrot, Kali, Ubuntu,
    Raspberry Pi OS and Debian are all 'debian'."""
    ids = set((os_release.get("ID", "") + " " + os_release.get("ID_LIKE", "")).lower().split())
    for family, names in (("debian", {"debian", "ubuntu", "armbian", "parrot", "kali", "raspbian", "linuxmint", "pop"}),
                          ("redhat", {"fedora", "rhel", "centos", "rocky", "almalinux"}), ("arch", {"arch", "manjaro"}),
                          ("alpine", {"alpine"}), ("suse", {"suse", "opensuse", "opensuse-leap"})):
        if ids & names:
            return family
    return ""


def detect_system(os_release=None, system=None, machine=None, is_root=None, which=shutil.which):
    """What this computer is: {kind: linux|windows|macos, name, family, arch, root, sudo}. 'sudo' means sudo works without a
    password (so packages can be installed unattended)."""
    system = system or platform.system()
    kind = {"Windows": "windows", "Darwin": "macos"}.get(system, "linux")
    info = {"kind": kind, "family": "", "arch": machine or platform.machine(), "root": False, "sudo": False}
    if kind == "linux":
        release = os_release if os_release is not None else read_os_release()
        info["family"] = package_family(release)
        info["name"] = release.get("PRETTY_NAME") or release.get("NAME") or "Linux"
        info["root"] = (os.geteuid() == 0) if is_root is None and hasattr(os, "geteuid") else bool(is_root)
        if not info["root"] and which("sudo"):
            try:
                info["sudo"] = subprocess.run(["sudo", "-n", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                              timeout=10).returncode == 0
            except (OSError, subprocess.SubprocessError):
                pass
    else:
        info["name"] = "Windows" if kind == "windows" else "macOS " + platform.mac_ver()[0]
    return info


SYSTEM_PACKAGES = {   # what to install per package family
    "ssh": {"debian": "openssh-client", "redhat": "openssh-clients", "arch": "openssh", "alpine": "openssh-client", "suse": "openssh"},
    "pip": {"debian": "python3-pip", "redhat": "python3-pip", "arch": "python-pip", "alpine": "py3-pip", "suse": "python3-pip"},
}
INSTALLERS = {"debian": ["apt-get", "install", "-y", "-q"], "redhat": ["dnf", "install", "-y"], "arch": ["pacman", "-S", "--noconfirm"],
              "alpine": ["apk", "add"], "suse": ["zypper", "--non-interactive", "install"]}


def run_command(argv, emit, timeout=900, env=None):
    """Runs a command, hands its output on line by line. Returns its exit status (127 when it cannot be started)."""
    try:
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, universal_newlines=True,
                                errors="replace", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except OSError as e:
        emit(f"{argv[0]}: {e}")
        return 127
    timer = threading.Timer(timeout, proc.kill)
    timer.start()
    try:
        for line in proc.stdout:
            if line.strip():
                emit(line.rstrip())
        return proc.wait()
    finally:
        timer.cancel()


def install_system_package(what, system, emit, run=run_command):
    """Installs a system package ('ssh', 'pip') with the system's package manager, when this program may (root, or sudo
    without a password). Returns True when the command succeeded."""
    family = system.get("family", "")
    package = SYSTEM_PACKAGES.get(what, {}).get(family)
    if not package or family not in INSTALLERS:
        emit(f"This system's packages are not known here: install {what} yourself.")
        return False
    if not (system.get("root") or system.get("sudo")):
        emit(f"Installing {package} needs administrator rights: run  sudo {' '.join(INSTALLERS[family])} {package}  and start this again.")
        return False
    argv = ([] if system.get("root") else ["sudo", "-n"]) + INSTALLERS[family] + [package]
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    if family == "debian":
        run((["sudo", "-n"] if not system.get("root") else []) + ["apt-get", "update", "-q"], emit, 600, env)
    emit(f"Installing {package}...")
    return run(argv, emit, 900, env) == 0


def pip_available(run=run_command):
    return run([sys.executable, "-m", "pip", "--version"], lambda line: None, 60) == 0


def ensure_esptool(emit, system, run=run_command):
    """Makes `import esptool` work: already there, or installed into this program's own folder with pip (and pip itself from
    the system's packages when it is missing). Returns True when esptool can be used."""
    use_private_libs()
    if esptool_module():
        return True
    if not pip_available(run):
        emit("pip is missing.")
        if system["kind"] == "linux":
            install_system_package("pip", system, emit, run)
        if not pip_available(run):
            emit("Without pip esptool cannot be installed here. Debian family: sudo apt install python3-pip (or: sudo apt install esptool).")
            return False
    lib = private_lib_dir()
    os.makedirs(lib, exist_ok=True)
    emit("Installing esptool (the tool that writes the coin box)...")
    lines = []

    def keep(line):
        lines.append(line)
        emit(line)
    argv = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--quiet", "--upgrade", "--target", lib, "esptool"]
    code = run(argv, keep, 900)
    if code != 0 and any("externally-managed" in line for line in lines):
        code = run(argv + ["--break-system-packages"], keep, 900)   # (only into the folder above)
    if code != 0:
        emit("esptool could not be installed (is the internet on? Armbian / Debian: sudo apt install esptool is the alternative).")
        return False
    importlib.invalidate_caches()
    use_private_libs()
    return esptool_module() is not None


def ensure_ssh(emit, system, run=run_command, which=shutil.which):
    """The ssh command: present, or installed from the system's packages on Linux. Returns True when it can be used."""
    if os.environ.get("PISO_SSH") or which("ssh"):
        return True
    if system["kind"] == "linux":
        install_system_package("ssh", system, emit, run)
        return bool(which("ssh"))
    if system["kind"] == "windows":
        emit("Windows: Settings > Apps > Optional features > add 'OpenSSH Client'.")
    else:
        emit("ssh is missing: it is part of macOS; check the Command Line Tools.")
    return False


def ensure_dependencies(emit, system=None, run=run_command, which=shutil.which):
    """Installs what this program needs and the system lacks (ssh, esptool). Returns {name: ok}."""
    system = system or detect_system()
    emit(f"This computer: {system['name']} ({system['arch']}){', running as administrator' if system['root'] else ''}.")
    result = {"ssh": ensure_ssh(emit, system, run, which), "esptool": ensure_esptool(emit, system, run)}
    for name, ok in result.items():
        emit(f"{name}: {'ready' if ok else 'MISSING'}")
    return result


def serial_access_fix(port, system, emit, run=run_command):
    """Linux: a USB serial port is usually owned by root and group 'dialout'. Makes the port usable for this run when the
    user may (sudo without a password); otherwise says what to do. Returns True when the port can be opened."""
    if system["kind"] != "linux" or os.access(port, os.R_OK | os.W_OK):
        return True
    if system.get("sudo") and run(["sudo", "-n", "chmod", "a+rw", port], emit, 30) == 0:
        emit(f"Allowed this program to use {port}.")
        return True
    user = os.environ.get("USER") or "your-user"
    emit(f"This account may not use {port}: run  sudo usermod -aG dialout {user}  and log in again (or start this as root).")
    return False


def flash_cache_dir():
    """Where the downloaded coin box software is kept, so the next unit does not download it again."""
    return os.path.join(default_out_dir(), "firmware-cache")


def default_out_dir():
    """Where the summary and the setup sheet are saved: Documents/PisoPhone (or PisoPhone in the home folder), or the
    service's own folder."""
    if os.environ.get("PISOPHONE_HOME"):
        return os.path.join(os.environ["PISOPHONE_HOME"], "saved")
    home = pathlib.Path.home()
    docs = home / "Documents"
    return str((docs if docs.is_dir() else home) / "PisoPhone")


# ---- owner tools: slot licenses, time cards, the admin QR code, the phone recovery code ------------------------------------------------------
# What used to be separate owner scripts (and the owner console) lives here, in the one program: the QR codes are drawn by this
# program's own QR encoder and printed from the browser, so only the signing needs a package (`cryptography`, installed on request).
# The key files are the same as the scripts' (the home folder), so keys and ledgers made earlier keep working.
CARD_PREFIX = "PISO1"
CARD_MAX_SERIAL, CARD_MIN_SECONDS, CARD_MAX_SECONDS, CARD_MAX_BATCH = 65535, 60, 240 * 3600, 600
CARD_COLS, CARD_ROWS = 3, 4
CARD_LEDGER_FIELDS = ["serial", "seconds", "box", "printed", "batch", "sold", "note"]
SLOT_PREFIX, FREE_SLOTS, MAX_SLOTS, SLOT_PRICE_PHP = "PSLOT1", 2, 12, 500   # SlotLicense.h
ADMIN_PREFIX, RECOVERY_PREFIX, RECOVERY_MAX_MINUTES = "PISOADMIN1", "PISOREC1", 24 * 60
KEY_FILES = {   # kind: (file in the home folder, what it is for)
    "card": ("pisophone_card_key.pem", "Time cards"),
    "admin": ("pisophone-superadmin.key", "Admin QR code"),
    "owner": ("pisophone_license_key.pem", "Slot licenses and phone recovery code"),
}


class ToolError(Exception):
    """A problem the owner can fix (shown on the Owner tools page)."""


def keys_home():
    return os.environ.get("PISOPHONE_KEYS") or os.path.expanduser("~")


def key_path(kind):
    return os.path.join(keys_home(), KEY_FILES[kind][0])


def cards_dir():
    return os.path.join(keys_home(), "pisophone_cards")


def tool_crypto():
    use_private_libs()
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError:
        raise ToolError("The key tools need one more package. Tap 'Install what is missing' on this page.")
    return hashes, serialization, ec


def ensure_cryptography(emit, system, run=run_command):
    """Makes `import cryptography` work (it signs the QR codes): already there, or installed into this program's own folder."""
    use_private_libs()
    try:
        import cryptography  # noqa: F401
        return True
    except ImportError:
        pass
    if not pip_available(run):
        emit("pip is missing.")
        if system["kind"] == "linux":
            install_system_package("pip", system, emit, run)
        if not pip_available(run):
            emit("Without pip it cannot be installed here. Debian family: sudo apt install python3-cryptography")
            return False
    lib = private_lib_dir()
    os.makedirs(lib, exist_ok=True)
    emit("Installing cryptography (it signs the QR codes)...")
    lines = []

    def keep(line):
        lines.append(line)
        emit(line)
    argv = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--quiet", "--upgrade", "--target", lib, "cryptography"]
    code = run(argv, keep, 900)
    if code != 0 and any("externally-managed" in line for line in lines):
        code = run(argv + ["--break-system-packages"], keep, 900)
    importlib.invalidate_caches()
    use_private_libs()
    try:
        import cryptography  # noqa: F401,F811
        return True
    except ImportError:
        emit("cryptography could not be installed (is the internet on? Debian family: sudo apt install python3-cryptography).")
        return False


# Fingerprints (first 16 hex chars of SHA-256 over SubjectPublicKeyInfo DER) of the public keys
# compiled into the coin box firmware (LicensePubKey.h, CardPubKey.h, SuperAdminPubKey.h)
# and router (owner_key.b64) and phone app (RecoveryCode.kt).
EXPECTED_FINGERPRINTS = {
    "owner": os.environ.get("PISOPHONE_OWNER_FINGERPRINT") or "b95cb2b953650047",
    "card": os.environ.get("PISOPHONE_CARD_FINGERPRINT") or "721fe54f8ef52b2e",
    "admin": os.environ.get("PISOPHONE_ADMIN_FINGERPRINT") or "1c87e0e2e0790cdb",
}


def b64u(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def load_key(kind, path=None, check_match=True):
    """The private key in the owner's key file (a ToolError when there is none or it is not a P-256 key)."""
    _hashes, serialization, ec = tool_crypto()
    path = path or key_path(kind)
    if not os.path.isfile(path):
        raise ToolError(f"There is no {KEY_FILES[kind][1].lower()} key yet. Import your key file (or paste it) in 'Your keys' above.")
    try:
        with open(path, "rb") as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
    except Exception:
        raise ToolError(f"The key file {path} could not be read (it must be an unprotected PEM key).")
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ToolError(f"The key file {path} is not a P-256 key.")
    if check_match:
        expected_fp = os.environ.get(f"PISOPHONE_{kind.upper()}_FINGERPRINT")
        if expected_fp is None and kind == "owner":
            expected_fp = EXPECTED_FINGERPRINTS.get("owner")
        if expected_fp and expected_fp != "*":
            fp = key_fingerprint(key)
            if fp != expected_fp:
                label = KEY_FILES[kind][1].lower()
                raise ToolError(
                    f"The {label} key ({fp}) does not match the public key built into the boxes and routers ({expected_fp}). "
                    "Codes signed with this key will be refused by the boxes. Import your true key (or secret backup) in 'Your keys' below."
                )
    return key


def key_fingerprint(key):
    _hashes, serialization, _ec = tool_crypto()
    der = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return hashlib.sha256(der).hexdigest()[:16]


def write_private_key(kind, pem, replace=False):
    """Saves a key file (mode 600); an existing key is only replaced on request and then kept as <file>.old."""
    path = key_path(kind)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.exists(path):
        if not replace:
            raise ToolError(f"There already is a key at {path}. Replacing it makes everything printed with the old one stop working.")
        os.replace(path, path + ".old")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem if isinstance(pem, bytes) else pem.encode())
    return path


def import_key(kind, text=None, path=None, replace=False):
    """Saves a key the owner already has (its PEM text, base64 text, or the path of its file on this computer) after checking it."""
    _hashes, serialization, ec = tool_crypto()
    if path:
        path = os.path.expanduser(path.strip())
        if not os.path.isfile(path):
            raise ToolError(f"No file at {path}.")
        with open(path, "rb") as f:
            raw = f.read(20000)
    else:
        raw = (text or "").strip().encode()
    if not raw:
        raise ToolError("Paste the key (the text between -----BEGIN and -----END, or base64) or type the path of its file.")
    key = None
    pem_bytes = None
    # 1. Try directly as PEM
    try:
        key = serialization.load_pem_private_key(raw, password=None)
        pem_bytes = raw if raw.endswith(b"\n") else raw + b"\n"
    except Exception:
        pass

    # 2. If not raw PEM, try base64 decode (e.g. GitHub secret OWNER_SIGNING_KEY_B64 or DER base64)
    if key is None:
        try:
            cleaned = re.sub(rb"\s+", b"", raw)
            decoded = base64.b64decode(cleaned, validate=True)
            try:
                key = serialization.load_pem_private_key(decoded, password=None)
                pem_bytes = decoded if decoded.endswith(b"\n") else decoded + b"\n"
            except Exception:
                key = serialization.load_der_private_key(decoded, password=None)
                pem_bytes = key.private_bytes(
                    serialization.Encoding.PEM,
                    serialization.PrivateFormat.PKCS8,
                    serialization.NoEncryption(),
                )
        except Exception:
            pass

    if key is None:
        raise ToolError("That is not a usable key: it must be an unprotected PEM private key or base64-encoded key.")

    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ToolError("That is not a P-256 key.")

    if kind == "owner":
        expected_fp = os.environ.get("PISOPHONE_OWNER_FINGERPRINT")
        if expected_fp is None:
            expected_fp = EXPECTED_FINGERPRINTS.get("owner")
        if expected_fp and expected_fp != "*":
            fp = key_fingerprint(key)
            if fp != expected_fp:
                raise ToolError(
                    f"The imported owner key ({fp}) does not match the public key built into the boxes and routers ({expected_fp}). "
                    "Codes signed with this key will be refused by the boxes. Please paste the secret key from GitHub (OWNER_SIGNING_KEY_B64) or your secret backup."
                )

    write_private_key(kind, pem_bytes, replace)
    return key


def create_key(kind, replace=False):
    """Makes a new key. Boxes only accept what their firmware was built to trust, so a new card or admin key works only after the
    firmware is rebuilt with its public half (a developer step); the page says so."""
    _hashes, serialization, ec = tool_crypto()
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    write_private_key(kind, pem, replace)
    return key


# -- time cards --------------------------------------------------------------------------------------------------------------------
def card_box(text):
    """'AA:BB:CC:DD:EE:FF', 'aa-bb-...' or 'aabbccddeeff' -> 'aabbccddeeff'."""
    box = re.sub(r"[^0-9a-fA-F]", "", text or "").lower()
    if not re.fullmatch(r"[0-9a-f]{12}", box):
        raise ToolError("That is not a box address: type the 12 characters shown on the coin box's page (like AA:BB:CC:DD:EE:FF).")
    return box


def card_text(key, box, serial, seconds):
    hashes, _serialization, ec = tool_crypto()
    sig = key.sign(f"pisophone-card-v1|{box}|{serial}|{seconds}".encode(), ec.ECDSA(hashes.SHA256()))
    return f"{CARD_PREFIX}.{box}.{serial}.{seconds}.{base64.b64encode(sig).decode()}"


def card_verify(public_key, text):
    hashes, _serialization, ec = tool_crypto()
    from cryptography.exceptions import InvalidSignature
    try:
        prefix, box, serial, seconds, sig = text.split(".")
        public_key.verify(base64.b64decode(sig, validate=True), f"pisophone-card-v1|{box}|{serial}|{seconds}".encode(), ec.ECDSA(hashes.SHA256()))
        return prefix == CARD_PREFIX
    except (ValueError, InvalidSignature):
        return False


def read_card_ledger(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def next_serials(rows, count):
    used = [int(r["serial"]) for r in rows]
    if len(used) != len(set(used)):
        raise ToolError("The ledger lists a serial twice; fix the ledger file by hand before printing more cards.")
    start = (max(used) if used else 0) + 1
    if start + count - 1 > CARD_MAX_SERIAL:
        raise ToolError(f"Not enough card numbers are left for this box: {CARD_MAX_SERIAL - start + 1} remain, {count} asked for.")
    return list(range(start, start + count))


def known_boxes(directory=None):
    """The boxes cards were printed for before (from their ledger files): [{box, printed, sold, last}]."""
    directory = directory or cards_dir()
    found = []
    try:
        names = sorted(os.listdir(directory))
    except OSError:
        return found
    for name in names:
        m = re.fullmatch(r"ledger_([0-9a-f]{12})\.csv", name)
        if not m:
            continue
        try:
            rows = read_card_ledger(os.path.join(directory, name))
        except (OSError, csv.Error):
            continue
        found.append({"box": m.group(1), "printed": len(rows), "sold": sum(1 for r in rows if (r.get("sold") or "").strip()),
                      "last": max((int(r["serial"]) for r in rows), default=0)})
    return found


def card_back_position(col, row, flip):
    """Where the back of the card at (col, row) goes once the paper is turned over (long edge: left and right swap)."""
    return (CARD_COLS - 1 - col, row) if flip == "long" else (col, CARD_ROWS - 1 - row)


_CARD_CSS = """@page{size:A4;margin:0}*{box-sizing:border-box}body{margin:0;background:#fff;font-family:system-ui,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;-webkit-print-color-adjust:exact;print-color-adjust:exact}
.sheet{position:relative;width:210mm;height:297mm;page-break-after:always;overflow:hidden;background:#fff}.sheet:last-child{page-break-after:auto}
.card{position:absolute;width:61mm;height:66.6mm;border:.3mm solid #787878;text-align:center;color:#000}
.card b{display:block;margin-top:4.5mm;font-size:5.6mm;line-height:6mm}.card .h{margin-top:.8mm;font-size:3.7mm;line-height:4.4mm;color:#3c3c3c}
.card svg{position:absolute;left:50%;top:17mm;width:38mm;height:38mm;margin-left:-19mm}
.card .s{position:absolute;left:0;right:0;top:56.5mm;font-size:2.9mm}.card .n{position:absolute;left:0;right:0;bottom:2.4mm;font-size:2.7mm;color:#505050}
.back{position:absolute;width:61mm;height:66.6mm;border:.3mm solid #787878;display:flex;align-items:center;justify-content:center}.back img{width:42mm;height:42mm}
.note{position:absolute;left:8mm;right:8mm;bottom:3mm;font-size:3mm;color:#666;text-align:center}
@media screen{body{background:#dde3ee;padding:10px}.sheet{margin:0 auto 12px;box-shadow:0 1px 6px #0006}.note{position:static;margin:4px 0}}
@media print{.noprint{display:none}}"""


def _card_pos(col, row):
    cell_w, cell_h = (2480 - 200) / 3, (3508 - 200) / 4
    mm = 25.4 / 300
    return ((100 + col * cell_w + 20) * mm, (100 + row * cell_h + 20) * mm)


def card_front_html(cards, hours_label, per_page=CARD_COLS * CARD_ROWS):
    sheets = []
    for p in range(0, len(cards), per_page):
        body = []
        for i, (serial, text) in enumerate(cards[p:p + per_page]):
            x, y = _card_pos(i % CARD_COLS, i // CARD_COLS)
            body.append(f'<div class="card" style="left:{x:.2f}mm;top:{y:.2f}mm"><b>PisoPhone</b><div class="h">{html_escape(hours_label)} play card</div>'
                        f'{qr_svg(text, 4, 4)}<div class="s">Scan at the PisoPhone to start</div><div class="n">No. {serial:05d}</div></div>')
        sheets.append('<div class="sheet">' + "".join(body) + "</div>")
    return sheets


def card_back_html(count, flip, shift=(0.0, 0.0), per_page=CARD_COLS * CARD_ROWS):
    sheets = []
    for p in range(0, count, per_page):
        body = []
        for i in range(min(per_page, count - p)):
            bc, br = card_back_position(i % CARD_COLS, i // CARD_COLS, flip)
            x, y = _card_pos(bc, br)
            body.append(f'<div class="back" style="left:{x + shift[0]:.2f}mm;top:{y + shift[1]:.2f}mm"><img src="{CARD_EMBLEM_URI}" alt=""></div>')
        sheets.append('<div class="sheet">' + "".join(body) + "</div>")
    return sheets


def cards_page(title, sheets, hint):
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            f"<title>{html_escape(title)}</title><style>{_CARD_CSS}</style></head><body><p class=\"note noprint\">{html_escape(hint)}</p>"
            + "".join(sheets) + "</body></html>")


def make_cards(box, count, hours, note="", flip="long", shift=(0.0, 0.0), out=None, key=None):
    """Signs `count` new cards for one box, reserves their serial numbers in the box's ledger and writes the printable pages.
    Returns {folder, files: [(label, path)], first, last, ledger}."""
    box = card_box(box)
    try:
        count = int(count)
        hours = float(hours)
    except (TypeError, ValueError):
        raise ToolError("Type a whole number of cards and the hours as a number.")
    if not 1 <= count <= CARD_MAX_BATCH:
        raise ToolError(f"Make 1 to {CARD_MAX_BATCH} cards at a time.")
    seconds = int(round(hours * 3600))
    if not CARD_MIN_SECONDS <= seconds <= CARD_MAX_SECONDS:
        raise ToolError("The hours must be between 1 minute and 240 hours.")
    key = key or load_key("card")
    out = out or cards_dir()
    os.makedirs(out, exist_ok=True)
    ledger = os.path.join(out, f"ledger_{box}.csv")
    serials = next_serials(read_card_ledger(ledger), count)
    cards = [(s, card_text(key, box, s, seconds)) for s in serials]
    for s, text in cards:   # never print a card the box would refuse
        if not card_verify(key.public_key(), text):
            raise ToolError(f"Self-check failed for card {s}: nothing was printed.")
    batch = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    stamp = datetime.datetime.now().isoformat(timespec="seconds")
    # the serials are reserved before anything is drawn: if something fails below they are skipped, never reused
    new = not os.path.exists(ledger)
    with open(ledger, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CARD_LEDGER_FIELDS)
        if new:
            w.writeheader()
        for s in serials:
            w.writerow({"serial": s, "seconds": seconds, "box": box, "printed": stamp, "batch": batch, "sold": "", "note": note})
    folder = os.path.join(out, f"batch_{batch}")
    os.makedirs(folder, exist_ok=True)
    label = f"{hours:g}-hour"
    fronts = card_front_html(cards, label)
    backs = card_back_html(len(cards), flip, shift)
    pairs = [s for pair in zip(fronts, backs) for s in pair]
    howto = "Print at 100% / 'actual size' on A4, no margins and no headers or footers (Ctrl+P)."
    files = []
    for name, title, sheets, hint in (
            ("1-fronts.html", "Cards: QR sides", fronts, howto + " Print this first."),
            ("2-backs.html", "Cards: logo sides", backs, howto + " Put the printed sheets back so the backs print on the other side (try one sheet first)."),
            ("duplex-both-sides.html", "Cards: both sides", pairs, howto + " For a printer that prints on both sides: flip on the " + flip + " edge.")):
        path = os.path.join(folder, name)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(cards_page(title, sheets, hint))
        files.append((title, path))
    return {"folder": folder, "files": files, "first": serials[0], "last": serials[-1], "ledger": ledger, "box": box, "hours": label,
            "sheets": len(fronts), "cards": cards}


# -- the admin QR code and the phone recovery code ----------------------------------------------------------------------------
def admin_qr_text(key):
    """PISOADMIN1.<d>.<x>.<y>: the P-256 private number and the public point (32 bytes each, base64url)."""
    n = key.private_numbers()
    return ".".join([ADMIN_PREFIX, b64u(n.private_value.to_bytes(32, "big")), b64u(n.public_numbers.x.to_bytes(32, "big")),
                     b64u(n.public_numbers.y.to_bytes(32, "big"))])


def recovery_text(key, expiry):
    hashes, _serialization, ec = tool_crypto()
    sig = key.sign(f"pisophone-recover-v1|{expiry}".encode(), ec.ECDSA(hashes.SHA256()))
    return f"{RECOVERY_PREFIX}.{expiry}." + base64.urlsafe_b64encode(sig).decode().rstrip("=")


def qr_page(title, line, text, warning):
    """A printable page with one QR code (the admin code or a recovery code)."""
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            f"<title>{html_escape(title)}</title><style>@page{{size:A4;margin:14mm}}body{{font:16px/1.45 system-ui,Segoe UI,Roboto,sans-serif;"
            f"text-align:center;margin:0;padding:20px;color:#111}}svg{{width:100%;max-width:420px;height:auto}}.w{{border:2px dashed #b00;color:#800;"
            f"border-radius:10px;padding:8px 12px;margin:16px auto;max-width:520px;font-weight:600}}</style></head><body>"
            f'<img src="{LOGO_LARGE_URI}" alt="" width="64" height="64"><h1>{html_escape(title)}</h1><p>{html_escape(line)}</p>{qr_svg(text, 6, 4)}'
            f'<div class="w">{html_escape(warning)}</div></body></html>')


def save_tool_page(name, html):
    folder = default_out_dir()
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, name)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(html)
    return path


SALES_MONTH_RE = re.compile(r"revenue-\d{4}-\d{2}\.csv")


def move_sales_months(ssh, host, count, dest):
    """Sales history (spec 02, D1): the router closes its sales file each month (revenue-YYYY-MM.csv in /etc/coinslot.d). This copies
    the oldest `count` closed months to the folder `dest`, checks each copy against the router's own sha256, and only then removes
    that month from the router. The current month is never touched, and nothing is removed that was not copied and checked.
    Returns [(name, bytes)]. Raises ToolError when the router cannot be asked or a copy does not match."""
    def run(cmd, text=True):
        try:
            return ssh.run(host, cmd, "-T", capture_output=True, text=text, timeout=300, stdin=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError) as e:
            raise ToolError(f"The router did not answer ({e}). Is this computer plugged into it?") from e
    if count < 1:
        raise ToolError("Choose how many months to move (at least 1).")
    r = run("ls -1 /etc/coinslot.d 2>/dev/null")
    if r.returncode != 0:
        raise ToolError("The router did not answer. Is this computer plugged into it and the login saved?")
    months = sorted(n.strip() for n in r.stdout.splitlines() if SALES_MONTH_RE.fullmatch(n.strip()))
    if not months:
        raise ToolError("The router has no closed months of sales yet (the first one closes when a sale is made in a new month).")
    os.makedirs(dest, exist_ok=True)
    moved = []
    for name in months[:count]:
        remote = f"/etc/coinslot.d/{name}"
        data = run(f"cat {remote}", text=False)
        want = run(f"sha256sum {remote} | cut -d' ' -f1")
        if data.returncode != 0 or want.returncode != 0 or hashlib.sha256(data.stdout).hexdigest() != want.stdout.strip():
            raise ToolError(f"The copy of {name} did not match the router's file, so nothing was removed. Try again.")
        path = os.path.join(dest, name)
        with open(path, "wb") as f:
            f.write(data.stdout)
        with open(path, "rb") as f:
            if hashlib.sha256(f.read()).hexdigest() != want.stdout.strip():
                raise ToolError(f"{name} could not be saved on this computer, so nothing was removed.")
        if run(f"rm -f {remote}").returncode != 0:
            raise ToolError(f"{name} was saved to {path} but could not be removed from the router.")
        moved.append((name, len(data.stdout)))
    return moved


def make_admin_qr(key=None):
    key = key or load_key("admin")
    text = admin_qr_text(key)
    page = qr_page("PisoPhone owner admin QR code", "On a coin box's login page tap Scan admin QR and show this code to the camera.", text,
                   "Keep this private: it opens super admin on every box. Print it and store it away from the shop.")
    return {"svg": qr_svg(text, 6, 4), "path": save_tool_page("pisophone-admin-qr.html", page), "fingerprint": key_fingerprint(key)}


def make_recovery_qr(minutes, key=None):
    try:
        minutes = int(minutes)
    except (TypeError, ValueError):
        raise ToolError("Type the minutes as a number.")
    if not 1 <= minutes <= RECOVERY_MAX_MINUTES:
        raise ToolError(f"The code can work for 1 to {RECOVERY_MAX_MINUTES} minutes.")
    key = key or load_key("owner")
    expiry = int(time.time()) + minutes * 60
    text = recovery_text(key, expiry)
    until = time.strftime("%H:%M", time.localtime(expiry))
    page = qr_page("PisoPhone recovery code", f"Works until {until}. On the phone's lock screen: admin entry > SCAN A RECOVERY CODE.", text,
                   "Anyone holding this code can remove PisoPhone from a phone until it expires. Delete it afterwards.")
    return {"svg": qr_svg(text, 6, 4), "path": save_tool_page("pisophone-recovery-code.html", page), "until": until, "minutes": minutes}



# ---- slot license keys (SlotLicense.h) ---------------------------------------------------------------------------------------

def licenses_ledger():
    return os.path.join(keys_home(), "pisophone_licenses.csv")


LICENSE_LEDGER_FIELDS = ["made", "box", "key", "note"]


def read_license_ledger(path=None):
    path = path or licenses_ledger()
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def slot_license_text(key, box, key_id):
    """PSLOT1.<box code>.<key number>.<signature>: opens ONE more phone slot on that box, once (SlotLicense.h)."""
    hashes, _serialization, ec = tool_crypto()
    sig = key.sign(f"pisophone-slot-v1|{box}|{key_id}".encode(), ec.ECDSA(hashes.SHA256()))
    return f"{SLOT_PREFIX}.{box}.{key_id}.{base64.b64encode(sig).decode()}"


def slot_license_verify(public_key, text):
    hashes, _serialization, ec = tool_crypto()
    from cryptography.exceptions import InvalidSignature
    try:
        prefix, box, key_id, sig = text.split(".")
        public_key.verify(base64.b64decode(sig, validate=True), f"pisophone-slot-v1|{box}|{key_id}".encode(), ec.ECDSA(hashes.SHA256()))
        return prefix == SLOT_PREFIX
    except (ValueError, InvalidSignature):
        return False


def make_slot_licenses(box, count, note="", key=None, ledger=None):
    """Signs `count` new slot license keys for one box and records them in the ledger. A box holds at most MAX_SLOTS - FREE_SLOTS keys.
    Returns {'box', 'keys': [text], 'total', 'ledger'}."""
    box = card_box(box)
    try:
        count = int(count)
    except (TypeError, ValueError):
        raise ToolError("Type how many keys as a whole number.")
    room = MAX_SLOTS - FREE_SLOTS
    ledger = ledger or licenses_ledger()
    have = [r for r in read_license_ledger(ledger) if r.get("box") == box]
    if not 1 <= count <= room:
        raise ToolError(f"Make 1 to {room} keys at a time.")
    if len(have) + count > room:
        raise ToolError(f"A box holds at most {room} license keys ({FREE_SLOTS} slots are free, {MAX_SLOTS} is the most). This box already has "
                        f"{len(have)} on record, so {room - len(have)} more can be made.")
    key = key or load_key("owner")
    used = {r.get("key") for r in have}
    made = []
    for _ in range(count):
        key_id = secrets.token_hex(4)
        while key_id in used:
            key_id = secrets.token_hex(4)
        used.add(key_id)
        text = slot_license_text(key, box, key_id)
        if not slot_license_verify(key.public_key(), text):   # never hand out a key the box would refuse
            raise ToolError("Self-check failed: nothing was made.")
        made.append((key_id, text))
    stamp = datetime.datetime.now().isoformat(timespec="seconds")
    new = not os.path.exists(ledger)
    os.makedirs(os.path.dirname(ledger) or ".", exist_ok=True)
    with open(ledger, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=LICENSE_LEDGER_FIELDS)
        if new:
            w.writeheader()
        for key_id, _text in made:
            w.writerow({"made": stamp, "box": box, "key": key_id, "note": note})
    return {"box": box, "keys": [t for _i, t in made], "total": len(have) + count, "ledger": ledger}


def reissue_slot_licenses(box, key=None, ledger=None):
    """New signatures for the keys already sold to a box (after the box was erased and its keys were lost). The key numbers are the
    recorded ones, so the box can never end up with more slots than were sold. Returns the keys."""
    box = card_box(box)
    rows = [r for r in read_license_ledger(ledger or licenses_ledger()) if r.get("box") == box]
    if not rows:
        raise ToolError("No keys were made for that box on this computer.")
    key = key or load_key("owner")
    return [slot_license_text(key, box, r["key"]) for r in rows]


# -- CLI helpers for owner tools -----------------------------------------------------------------------------------------------

def make_wifi_poster_page(ssid, portal_url="http://10.0.30.1:2080/", shop_name=""):
    """Printable A4 HTML poster with 2 QR codes (Wi-Fi connect & portal). Works in any browser without external dependencies."""
    esc = re.sub(r'([\\;,:"])', r"\\\1", ssid)
    if re.fullmatch(r"[0-9A-Fa-f]+", ssid):
        esc = f'"{esc}"'
    wifi_data = f"WIFI:T:nopass;S:{esc};;"
    wifi_svg = qr_svg(wifi_data, 6, 4)
    portal_svg = qr_svg(portal_url, 6, 4)
    shop_line = f"<p style='color:#eef3ff;font-size:16px;margin:4px 0 0'>{html_escape(shop_name)}</p>" if shop_name else ""
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>TechNet PisoWiFi Poster</title>
<style>
@page {{ size: A4 portrait; margin: 0; }}
* {{ box-sizing: border-box; }}
body {{ margin: 0; background: #fff; font-family: system-ui, -apple-system, sans-serif; -webkit-print-color-adjust: exact; print-color-adjust: exact; text-align: center; color: #0b1530; }}
.sheet {{ max-width: 794px; min-height: 1123px; margin: 0 auto; background: #fff; }}
.band {{ background: #0a1b45; color: #fff; padding: 24px 20px; }}
.band h1 {{ margin: 0; font-size: 32px; font-weight: 700; }}
.band h1 span {{ color: #97a6c7; font-weight: 400; }}
.tagline {{ font-size: 26px; font-weight: 800; margin: 30px 0 10px; }}
.sub {{ color: #4a5878; font-size: 16px; margin: 0 0 25px; }}
.cards {{ display: flex; justify-content: center; gap: 20px; padding: 0 24px; }}
.card {{ border: 2px solid #d5ddec; border-radius: 14px; padding: 18px; width: 44%; text-align: center; background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }}
.badge {{ display: inline-block; background: #1d63d6; color: #fff; font-weight: 700; font-size: 14px; padding: 4px 14px; border-radius: 20px; margin-bottom: 8px; }}
.card h2 {{ font-size: 18px; margin: 6px 0 14px; color: #0b1530; }}
.qr-wrap {{ width: 220px; height: 220px; margin: 0 auto 12px; }}
.qr-wrap svg {{ width: 100%; height: 100%; }}
.card p {{ font-size: 13px; color: #4a5878; margin: 6px 0; line-height: 1.35; }}
.footer {{ margin-top: 30px; font-size: 14px; color: #4a5878; }}
@media screen {{ body {{ background: #eaedf2; padding: 20px; }} .sheet {{ box-shadow: 0 2px 12px rgba(0,0,0,0.15); }} }}
</style></head><body>
<div class="sheet">
<div class="band"><h1>TechNet <span>PisoWiFi</span></h1>{shop_line}</div>
<div class="tagline">Get online with a coin</div>
<div class="sub">Insert a coin at the coin box and you are online</div>
<div class="cards">
<div class="card"><div class="badge">Step 1</div><h2>Join the Wi-Fi</h2><div class="qr-wrap">{wifi_svg}</div>
<p>Scan to join <b>{html_escape(ssid)}</b>.<br>No password needed.<br>Portal opens automatically.</p></div>
<div class="card"><div class="badge">Step 2</div><h2>Already connected?</h2><div class="qr-wrap">{portal_svg}</div>
<p>Check time left or add a voucher.<br>Or visit:<br><b>{html_escape(portal_url)}</b></p></div>
</div>
<div class="footer"><p>Pay with coins, or get a voucher code to use later.<br>A voucher works once and is good for 30 days.</p></div>
</div></body></html>"""


def run_check_keys(say=print):
    if not ensure_cryptography(say, detect_system()):
        raise SetupError("the 'cryptography' package could not be installed.")
    say("PisoPhone Key Status:")
    say("---------------------")
    all_ok = True
    for kind, (_name, label) in KEY_FILES.items():
        path = key_path(kind)
        found = os.path.isfile(path)
        expected_fp = os.environ.get(f"PISOPHONE_{kind.upper()}_FINGERPRINT") or EXPECTED_FINGERPRINTS.get(kind)
        if not found:
            say(f"[-] {label:12}: MISSING ({path})")
            if kind == "owner":
                all_ok = False
            continue
        try:
            k = load_key(kind, path, check_match=False)
            fp = key_fingerprint(k)
            if expected_fp and expected_fp != "*" and fp != expected_fp:
                say(f"[!] {label:12}: MISMATCH ({path})")
                say(f"    Current fingerprint : {fp}")
                say(f"    Firmware expected   : {expected_fp}")
                if kind == "owner":
                    all_ok = False
            else:
                say(f"[+] {label:12}: OK (fingerprint {fp})")
        except Exception as e:
            say(f"[!] {label:12}: ERROR reading key: {e}")
            all_ok = False
    return 0 if all_ok else 1


def run_import_key(args, say=print):
    if not ensure_cryptography(say, detect_system()):
        raise SetupError("the 'cryptography' package could not be installed.")
    kind = (args.key_kind or "owner").lower()
    if kind not in KEY_FILES:
        raise ToolError(f"Unknown key kind '{kind}'. Must be one of: {', '.join(KEY_FILES.keys())}")
    key = import_key(kind, text=args.key_text, path=args.key_file, replace=args.replace)
    fp = key_fingerprint(key)
    path = key_path(kind)
    say(f"Successfully imported {KEY_FILES[kind][1]} key.")
    say(f"Location   : {path}")
    say(f"Fingerprint: {fp}")
    return 0


def run_make_cards_cli(args, say=print):
    if not ensure_cryptography(say, detect_system()):
        raise SetupError("the 'cryptography' package could not be installed.")
    if not args.box:
        raise ToolError("Specify --box AA:BB:CC:DD:EE:FF for the time cards.")
    count = int(args.count) if args.count else 12
    hours = float(args.hours) if getattr(args, "hours", None) else 1.0
    flip = getattr(args, "flip", "long") or "long"
    res = make_cards(args.box, count, hours, note=args.note, flip=flip, out=args.out if args.out != "." else None)
    say(f"Created {count} cards for box {res['box']} ({res['hours']})")
    say(f"Folder: {res['folder']}")
    for title, p in res["files"]:
        say(f"  - {title}: {p}")
    return 0


def run_make_admin_qr_cli(args, say=print):
    if not ensure_cryptography(say, detect_system()):
        raise SetupError("the 'cryptography' package could not be installed.")
    res = make_admin_qr()
    say("Admin QR code generated successfully.")
    say(f"HTML page   : {res['path']}")
    say(f"Fingerprint : {res['fingerprint']}")
    return 0


def run_make_recovery_cli(args, say=print):
    if not ensure_cryptography(say, detect_system()):
        raise SetupError("the 'cryptography' package could not be installed.")
    mins = int(args.minutes) if getattr(args, "minutes", None) else 60
    res = make_recovery_qr(mins)
    say(f"Phone recovery code generated (valid for {mins} minutes until {res['until']}).")
    say(f"HTML page: {res['path']}")
    return 0


def run_make_wifi_poster_cli(args, say=print):
    ssid = args.guest_ssid or args.wifi_name or "PisoWiFi"
    portal_url = getattr(args, "portal_url", None) or "http://10.0.30.1:2080/"
    shop_name = args.site_name or ""
    out_dir = default_out_dir() if args.out == "." else args.out
    os.makedirs(out_dir, exist_ok=True)

    html = make_wifi_poster_page(ssid, portal_url, shop_name)
    html_path = os.path.join(out_dir, "pisowifi_poster.html")
    with open(html_path, "w", encoding="utf-8") as f:
        f.write(html)
    say(f"Printable HTML poster created: {html_path}")

    # Also attempt PNG/PDF via scripts/make_wifi_poster.py if Pillow & qrcode are available
    try:
        repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        scripts_dir = os.path.join(repo_root, "scripts")
        if os.path.isdir(scripts_dir):
            sys.path.insert(0, scripts_dir)
            import make_wifi_poster
            logo = os.path.join(repo_root, "tools", "pisoportal", "src", "brand.png")
            logo_path = logo if os.path.isfile(logo) else None
            img = make_wifi_poster.build_poster(ssid, portal_url, shop_name, logo_path)
            png, pdf = os.path.join(out_dir, "pisowifi_poster.png"), os.path.join(out_dir, "pisowifi_poster.pdf")
            img.save(png, dpi=(make_wifi_poster.DPI, make_wifi_poster.DPI))
            img.save(pdf, resolution=make_wifi_poster.DPI)
            say(f"PNG poster: {png}")
            say(f"PDF poster: {pdf}")
    except Exception:
        pass
    return 0


# ---- PisoPhone only: the coin box on the shop's normal router, set up over USB (no router software, no SSH) ---------------------
SERIAL_BAUD = 115200


# -- talking to the board over USB (SerialProvision.h) ---------------------------------------------------------------------

def parse_status(line):
    """'PISO-BOX fw=1.0.0 mac=aa:bb:.. setup=needed wifi=down ip=-' -> {'fw': '1.0.0', 'mac': ..., 'setup': 'needed', ...}, or None."""
    if not line.startswith("PISO-BOX "):
        return None
    status = {}
    for part in line.split()[1:]:
        key, _, value = part.partition("=")
        if key:
            status[key] = value
    return status


def setup_line(wifi_name, wifi_password, admin_password):
    """The PISO-SETUP line: the three values hex-encoded, so no character in them can be mistaken for a separator."""
    return "PISO-SETUP " + " ".join(v.encode("utf-8").hex() for v in (wifi_name, wifi_password, admin_password))


class BoxLink:
    """One USB serial connection to the board. `port` is anything with write/read/close like pyserial's Serial."""

    def __init__(self, port, clock=time.monotonic, sleep=time.sleep):
        self.port, self.clock, self.sleep, self.buf = port, clock, sleep, b""

    @classmethod
    def open(cls, device):
        use_private_libs()
        try:
            import serial
        except ImportError:
            raise SetupError("the serial library (pyserial) is missing: it comes with esptool (use 'Install what is missing', or run  python -m pip install pyserial).")
        # (DTR and RTS are set low BEFORE the port opens: on a C3 board, raising them resets the chip or puts it in download mode)
        port = serial.Serial()
        port.port, port.baudrate, port.timeout = device, SERIAL_BAUD, 0.2
        port.dtr = False
        port.rts = False
        try:
            port.open()
        except (OSError, serial.SerialException) as e:
            raise SetupError(with_advice(f"the board's USB port {device} could not be opened ({e})", str(e)))
        return cls(port)

    def close(self):
        try:
            self.port.close()
        except Exception:
            pass

    def send(self, line, chunk=24, pause=0.02):
        """Writes a line a few bytes at a time: the board's USB buffer is small and it reads between loops."""
        data = (line + "\n").encode("ascii")
        for i in range(0, len(data), chunk):
            self.port.write(data[i:i + chunk])
            self.sleep(pause)

    def read_line(self, timeout):
        """The next complete line the board prints (None after `timeout` seconds)."""
        end = self.clock() + timeout
        while True:
            if b"\n" in self.buf:
                line, _, self.buf = self.buf.partition(b"\n")
                return line.decode("utf-8", "replace").strip()
            if self.clock() >= end:
                return None
            chunk = self.port.read(256)
            if chunk:
                self.buf += chunk
            else:
                self.sleep(0.02)

    def expect(self, prefixes, timeout):
        """Reads lines until one starts with one of `prefixes` (the board's boot messages are skipped). None on timeout."""
        end = self.clock() + timeout
        while self.clock() < end:
            line = self.read_line(max(0.05, end - self.clock()))
            if line and line.startswith(tuple(prefixes)):
                return line
        return None

    def hello(self, timeout=12):
        """Asks the board who it is (once a second); returns its status dict, or None if no PisoPhone software answers."""
        end = self.clock() + timeout
        while self.clock() < end:
            self.send("PISO-HELLO")
            line = self.expect(["PISO-BOX "], 1.2)
            if line:
                return parse_status(line)
        return None


def provision_box(link, wifi_name, wifi_password, admin_password, emit=say, tries=3):
    """Gives a new board the shop's Wi-Fi and the admin password. Raises SetupError with the board's reason when it refuses."""
    reasons = {
        "already-set-up": "this box was set up already (to set it up again, write the software to it again: that erases it)",
        "bad-wifi-name": "the board did not accept that Wi-Fi name (1 to 32 plain characters)",
        "bad-wifi-password": "the board did not accept that Wi-Fi password (8 to 63 plain characters)",
        "bad-admin-password": "the board did not accept that admin password (8 or more characters, not the factory one)",
        "busy": "the board is busy; try again in a moment",
        "could-not-save": "the board could not save the settings (its flash may be damaged)",
    }
    for attempt in range(1, tries + 1):
        link.send(setup_line(wifi_name, wifi_password, admin_password))
        answer = link.expect(["PISO-SETUP-OK", "PISO-SETUP-ERR"], 8)
        if answer == "PISO-SETUP-OK":
            return
        if answer and answer.startswith("PISO-SETUP-ERR"):
            why = answer.split(" ", 1)[1].strip() if " " in answer else ""
            if why != "bad-format":   # (bad-format is a damaged line: it is sent again)
                raise SetupError(reasons.get(why, f"the board refused ({why})") + ".")
        emit(f"  The board did not take that (try {attempt} of {tries}); sending it again.")
    raise SetupError("the board did not answer the setup. Unplug it, plug it in again and run this again.")


def wait_online(find_device, wifi_name, emit=say, timeout=120, opener=BoxLink.open, clock=time.monotonic, sleep=time.sleep):
    """Waits until the board says it is on the Wi-Fi (it restarts first, and its USB port may come back under another name).
    Returns its status dict with the address; raises SetupError with what is usually wrong when it never joins."""
    end = clock() + timeout
    last = None
    shown = False
    while clock() < end:
        device = find_device()
        link = None
        if device:
            try:
                link = opener(device)
            except SetupError:
                link = None
        if link:
            try:
                status = link.hello(6)
            finally:
                link.close()
            if status:
                last = status
                if status.get("wifi") == "up" and status.get("ip", "-") != "-":
                    return status
                if not shown:
                    emit(f"  The board is up and joining '{wifi_name}'...")
                    shown = True
        sleep(2)
    if last is None:
        raise SetupError("the board did not come back on USB after the setup. Unplug it, plug it in again and run  check.")
    raise SetupError(f"the board could not join the Wi-Fi '{wifi_name}'. Usual causes: a wrong Wi-Fi password; a 5 GHz-only network "
                     "(the ESP32-C3 needs 2.4 GHz: many routers share one name for both, some need the 2.4 GHz band switched on); a "
                     "router that only lets known devices join. Run the setup again with the right details.")




# -- the PisoPhone-only setup, start to finish -------------------------------------------------------------------------------------------

def wifi_name_problem(name):
    """Why the board would refuse this Wi-Fi name, or None."""
    if not 1 <= len(name) <= 32 or any(ord(c) < 32 or ord(c) > 126 for c in name):
        return "use 1 to 32 plain characters (letters, digits, punctuation)"
    return None


def wifi_password_problem(password):
    if not 8 <= len(password) <= 63 or any(ord(c) < 32 or ord(c) > 126 for c in password):
        return "use 8 to 63 plain characters (a network without a password cannot be used)"
    return None


def wifi_problem(name, password):
    p = wifi_name_problem(name)
    return ("the Wi-Fi name: " + p + ".") if p else (("the Wi-Fi password: " + wifi_password_problem(password) + ".") if wifi_password_problem(password) else None)


def admin_password_problem(value):
    if len(value) < 8 or len(value) > 64:
        return "use 8 to 64 characters"
    if any(ord(c) < 33 or ord(c) > 126 for c in value):
        return "use plain letters, digits and punctuation, no spaces"
    if value == "Coinslot@Setup":
        return "that is the factory password"
    return None


def choose_board(boards, read, emit=say):
    """The board to use: the only one, or the one the owner picks. SetupError when none."""
    if not boards:
        raise SetupError("no coin box board found on USB. Plug the ESP32-C3 into this computer with a USB data cable (some cables only "
                         "charge), wait a few seconds and run this again.")
    if len(boards) == 1:
        emit(f"Board found: {boards[0][1]} on {boards[0][0]}.")
        return boards[0][0]
    emit("Several USB boards are connected:")
    for i, (port, description) in enumerate(boards, 1):
        emit(f"  {i}. {description} on {port}")
    while True:
        pick = read("Which one is the coin box? (number): ").strip()
        if pick.isdigit() and 1 <= int(pick) <= len(boards):
            return boards[int(pick) - 1][0]


def current_device(finder, port):
    """The board's serial port now: the one it had, or the only board connected (the port may come back under another name)."""
    boards = finder()
    if any(p == port for p, _d in boards):
        return port
    return boards[0][0] if len(boards) == 1 else port


def box_summary(status, wifi_name, admin_password):
    ip = status.get("ip", "-")
    return "\n".join([
        f"PisoPhone coin box, set up {datetime.datetime.now().strftime('%Y-%m-%d %H:%M')}",
        "",
        f"Admin page : http://{ip}/",
        "Sign in    : the password below (the user name is admin)",
        f"Password   : {admin_password}",
        f"Box code   : {status.get('mac', '?')}    (give this to the vendor when you buy a slot license; it is also on the admin page, Phone slots)",
        f"Wi-Fi      : {wifi_name}    (the coin box and all rental phones use this network)",
        f"Software   : {status.get('fw', '?')}",
        "",
        "Keep this file private. Slots: 2 are free, each more needs a license key.",
        "",
    ])


def provision_new_box(find_port, wifi_name, wifi_password, admin_password, emit=say, opener=BoxLink.open, waiter=wait_online,
                      sleep=time.sleep, stage=lambda n: None):
    """After the software is written (or on a board that has it and is new): gives the board the shop's Wi-Fi and the admin password
    over USB, then waits until it is on the Wi-Fi. `find_port()` says which serial port the board has now. Returns its status
    (box code, address, ...). stage(1) and stage(2) tell the page where it is."""
    stage(1)
    emit("== Giving the board your Wi-Fi and a new admin password")
    status = None
    for _ in range(4):
        sleep(3)   # (the board restarts after it is written; its USB port goes away and comes back, sometimes under another name)
        try:
            link = opener(find_port())
        except SetupError:
            continue
        try:
            status = link.hello(15)
            if status:
                if status.get("setup") != "needed":
                    raise SetupError("the board is not in first-time setup mode after writing the software. Run this again.")
                provision_box(link, wifi_name, wifi_password, admin_password, emit)
                break
        finally:
            link.close()
    if not status:
        raise SetupError("no PisoPhone software answered on the board after writing it. Unplug it, plug it in again and run the check.")
    stage(2)
    emit("== Waiting for the board to join your Wi-Fi (up to two minutes)")
    return waiter(find_port, wifi_name, emit)


def run_phone_setup(args, read=input, read_secret=getpass.getpass, emit=say, finder=usb_boards, flasher=flash_box, opener=BoxLink.open,
              waiter=wait_online, sleep=time.sleep, out_dir=None):
    """The whole setup of one new coin box in the terminal (the same steps as the setup page). Returns the saved summary's path."""
    emit(f"== {APP_NAME}")
    emit("A new coin box needs: the board on USB, then the shop's Wi-Fi name and password. Nothing else.\n")
    port = args.serial or choose_board(finder(), read, emit)

    # 1. is a working box there already? Writing the software erases it, revenue totals and slot licenses included
    link = None
    try:
        link = opener(port)
        status = link.hello(4)
    except SetupError:
        status = None
    finally:
        if link:
            link.close()
    if status and status.get("setup") == "done":
        emit(f"This board already runs a set-up coin box (box code {status.get('mac', '?')}).")
        emit("Writing the software again ERASES everything on it: its settings, its sales totals and its slot license keys.")
        emit("To only update a working box, use its admin page (Tools > Install update) instead.")
        if read("Type ERASE to go on, or press Enter to stop: ").strip() != "ERASE":
            raise SetupError("stopped; nothing was changed.")

    # 2. what the owner types
    wifi_name = args.wifi_name or read("Shop Wi-Fi name (2.4 GHz): ").strip()
    wifi_password = args.wifi_password or read_secret("Shop Wi-Fi password: ")
    problem = wifi_problem(wifi_name, wifi_password)
    if problem:
        raise SetupError(problem)
    admin_password = args.admin_password or generate(12)
    problem = admin_password_problem(admin_password)
    if problem:
        raise SetupError("the admin password: " + problem + ".")

    # 3. the software, then the Wi-Fi and the admin password, then online
    flasher(args.branch, port, emit, flash_cache_dir(), image=args.image, product="phone")
    status = provision_new_box(lambda: current_device(finder, port), wifi_name, wifi_password, admin_password, emit, opener, waiter, sleep)
    folder = out_dir or default_out_dir()
    mac = status.get("mac", "box").replace(":", "")
    path = write_private_file(os.path.join(folder, f"pisophone-box-{mac}.txt"), box_summary(status, wifi_name, admin_password))
    ip = status["ip"]
    emit("")
    emit("==== Done: the coin box is online ====")
    emit(f"  Admin page : http://{ip}/        (open it from a phone or computer on the same Wi-Fi)")
    emit(f"  Sign in    : admin / {admin_password}")
    emit(f"  Box code   : {status.get('mac', '?')}")
    emit(f"  Saved to   : {path}  (readable only by you)")
    qr = terminal_qr(f"http://{ip}/")
    if qr:
        emit("\n  Scan to open the admin page:\n" + qr)
    emit("\nNext: open the admin page, then Phone slots > Set up a phone: it installs the PisoPhone app on a rental phone.")
    emit(f"The first {FREE_SLOTS} phone slots are free. For more, buy a slot license key (P{SLOT_PRICE_PHP} each) using the box code above.")
    return path


def check_items(finder, opener, probe, pick=None):
    """What a read-only look at the coin box on USB shows, as the Check-up page's list [{name, state, text, fix}]."""
    items = []

    def add(name, state, text, fix=""):
        items.append({"name": name, "state": state, "text": text, "fix": fix})
    boards = finder()
    if not boards:
        add("Board on USB", "bad", "no coin box board found.", "Plug it in with a USB data cable and try again.")
        return items
    if len(boards) > 1 and not pick:
        add("Board on USB", "bad", "several USB boards are connected.", "Unplug all but the coin box.")
        return items
    port = pick or boards[0][0]
    add("Board on USB", "ok", f"{boards[0][1]} on {port}." if len(boards) == 1 else port)
    try:
        link = opener(port)
    except SetupError as e:
        add("Coin box software", "bad", str(e))
        return items
    try:
        status = link.hello(12)
    finally:
        link.close()
    if not status:
        add("Coin box software", "bad", "no PisoPhone software answered.", "It may be empty or running other software: run the setup.")
        return items
    add("Coin box software", "ok", f"version {status.get('fw', '?')}, box code {status.get('mac', '?')}.")
    add("Set up", "ok" if status.get("setup") == "done" else "bad", "yes" if status.get("setup") == "done" else "no, it still has the factory settings.",
        "" if status.get("setup") == "done" else "Run the setup.")
    online = status.get("wifi") == "up" and status.get("ip", "-") != "-"
    add("Wi-Fi", "ok" if online else "bad", f"joined, address {status['ip']}." if online else "not joined.",
        "" if online else "Check the Wi-Fi name and password (2.4 GHz); run the setup again if they are wrong.")
    if online:
        reached = probe(f"http://{status['ip']}/")
        add("Admin page", "ok" if reached else "warn", f"answers at http://{status['ip']}/." if reached else
            f"does not answer from this computer at http://{status['ip']}/.", "" if reached else "Is this computer on the same Wi-Fi?")
    return items


def run_box_check(args, emit=say, finder=usb_boards, opener=BoxLink.open, read=input, probe=None):
    """Looks at the coin box on USB and, when it is online, whether its admin page answers. Changes nothing."""
    port = args.serial or choose_board(finder(), read, emit)
    items = check_items(finder, opener, probe or http_ok, pick=port)
    for i in items:
        mark = {"ok": "OK     ", "bad": "PROBLEM", "warn": "NOTE   "}[i["state"]]
        emit(f"{mark} {i['name']}: {i['text']}" + (f" {i['fix']}" if i["fix"] and i["state"] != "ok" else ""))
    return items


def http_ok(url, opener=urllib.request.urlopen):
    try:
        with opener(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=5) as r:
            return r.status < 500
    except (urllib.error.URLError, OSError, ValueError):
        return False



def write_private_file(path, text):
    """Writes a text file readable only by its owner (making its folder)."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    save_private(path, text)
    return path


def preflight_phone(branch, opener=urllib.request.urlopen, tool=esptool_module):
    """The checks of the first page in the PisoPhone-only mode: [(what, ok, what to do when not)]. Nothing here changes anything."""
    online, why = False, ""
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/manifest.json", headers={"User-Agent": USER_AGENT}), timeout=15) as r:
            online = r.status == 200
    except urllib.error.HTTPError as e:
        why = f"the website answered HTTP {e.code}"
    except urllib.error.URLError as e:
        why = f"{e.reason}"
    except (OSError, ValueError) as e:
        why = f"{type(e).__name__}: {e}"
    advice = "This computer needs internet now (to download the coin box software)."
    if why:
        advice += f" The error was: {why}."
        if "CERTIFICATE" in why.upper():
            advice += " A security program or a proxy may be inspecting HTTPS traffic: pause it for the setup, or try another network."
    return [(f"internet and the {urllib.parse.urlsplit(site_url(branch)).netloc} website", online, advice),
            ("USB flashing tool (esptool)", tool() is not None, "Install it with the button below, or run  python -m pip install esptool.")]


def serial_access_advice(port):
    user = os.environ.get("USER") or "your-user"
    return f"run  sudo usermod -aG dialout {user}  and log in again (or start this as root); another program may also be using {port}."



# ---- the setup page: a small web server on this computer ----------------------------------------------------------------------
# The setup is a web page served by this program (standard library only), so it needs no screen on the computer that runs it:
# open the address on any phone or laptop in the same network. A PIN keeps everyone else out.

STEPS = [("welcome", "Start"), ("router", "Router"), ("settings", "Settings"), ("install", "Install"), ("phones", "Phones"),
         ("finish", "Finish")]
INSTALL_STAGES = ["Write the coin box software (USB)", "Prepare the router", f"Move it to {TARGET_IP}", "Install and set up",
                  "Check everything"]
STAGE_ACTIVITY = ["Writing the coin box software", "Downloading the setup to the router", f"Waiting for the router at {TARGET_IP}",
                  "Installing on the router", "Checking"]
WIFI_STEPS = [step for step in STEPS if step[0] != "phones"]   # PisoWiFi only: the same router and coin box, no rental phones
PHONE_STEPS = [("welcome", "Start"), ("p_settings", "Settings"), ("p_install", "Install"), ("p_phones", "Phones"), ("p_finish", "Finish")]
PHONE_STAGES = ["Write the coin box software (USB)", "Give it your Wi-Fi and a new admin password", "Wait until it joins your Wi-Fi"]
PHONE_ACTIVITY = ["Writing the coin box software", "Giving the board your Wi-Fi and the admin password", "Waiting for the board to join your Wi-Fi"]
PIN_RE = re.compile(r"[A-Za-z0-9]{6,32}")
DEFAULT_PORT = 8765
LOG_KEEP = 3000


class Core:
    """What the setup does with the network, the router and the coin box; tests replace it with a fake."""

    def __init__(self, branch, workdir, router_online=False):
        self.branch = branch
        self.router_online = router_online   # --router-online: the router downloads its packages itself (it needs internet)
        self.ssh = Ssh(workdir, hidden=True)
        self.ssh.set_password("")   # never wait for a password on a terminal there is not

    def preflight(self):
        return preflight(self.branch)

    def find_router(self):
        return find_router()

    def probe(self, host):
        return probe_router(self.ssh, host)

    def network_clash(self):
        """Advice when two of this computer's networks are in the same range (None when not, or when it cannot be read)."""
        return network_clash_advice(local_interface_addresses(detect_system()))

    def saved_login(self, host, out_dir):
        """The router password saved on this computer, when the router accepts it (else None)."""
        return use_saved_password(self.ssh, host, [out_dir, default_out_dir()], notify=lambda text: None)

    def login(self, host, password):
        self.ssh.set_password(password)
        return check_login(self.ssh, host)

    def usb_boards(self):
        return usb_boards()

    def flash_box(self, port, emit, product="full"):
        system = detect_system()
        if not serial_access_fix(port, system, emit):
            raise SetupError("this account may not use the USB port (see above).")
        return flash_box(self.branch, port, emit, flash_cache_dir(), product=product)

    def install(self, host, answers, out_dir, emit, stage, notify):
        return install(self.ssh, host, answers, self.branch, out_dir, emit,
                       lambda: wait_for_move(renew=renew_address, notify=notify), stage, online=self.router_online)

    def run(self, host, password, command, emit):
        """A command on the router (the root password is known by now): its output line by line; returns its exit status."""
        self.ssh.set_password(password)
        return run_remote(self.ssh, host, command, emit)["exit"]

    def telegram(self, host, password, token, site, emit, confirm):
        self.ssh.set_password(password)
        return connect_telegram(self.ssh, host, token, site, emit, confirm)

    def provisioning_target(self, passwords, slot):
        return provisioning_target(self.branch, passwords, slot)

    def box_online(self):
        return box_online()

    def checkup(self):
        return run_checkup(self.branch, box_net=lambda: read_box_network(self.ssh, TARGET_IP),
                           alerts=lambda: read_router_alerts(self.ssh, TARGET_IP))

    # -- PisoPhone only (no router) ---------------------------------------------------------------------------------------------
    def preflight_phone(self):
        return preflight_phone(self.branch)

    def probe_box(self, port):
        """What the board on `port` says about itself (hello): a status dict, or None when no PisoPhone software answers."""
        if not serial_access_fix(port, detect_system(), lambda text: None):
            raise SetupError("this account may not use the USB port: " + serial_access_advice(port))
        link = BoxLink.open(port)
        try:
            return link.hello(4)
        finally:
            link.close()

    def provision(self, port, wifi_name, wifi_password, admin_password, emit, stage):
        return provision_new_box(lambda: current_device(usb_boards, port), wifi_name, wifi_password, admin_password, emit, stage=stage)

    def box_reachable(self, ip):
        return http_ok(f"http://{ip}/")

    def box_checkup(self):
        return check_items(usb_boards, BoxLink.open, http_ok)


def network_report(system=None, run=subprocess.run):
    """This computer's addresses and routes, as text lines: the first thing in the setup log (a real run that fails is then
    easier to understand: which networks the computer was on, and which way its traffic goes)."""
    kind = (system or {}).get("kind") or ("windows" if os.name == "nt" else "linux")
    commands = ([["ipconfig", "/all"], ["route", "print", "-4"]] if kind == "windows"
                else [["ifconfig"], ["netstat", "-rn", "-f", "inet"]] if kind == "mac"
                else [["ip", "-4", "address"], ["ip", "-4", "route"]])
    lines = []
    for command in commands:
        try:
            done = run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10, check=False)
            lines.append("$ " + " ".join(command))
            lines.extend(done.stdout.decode("utf-8", "replace").splitlines())
        except (OSError, subprocess.SubprocessError):
            lines.append(f"$ {' '.join(command)}  (could not be run)")
    if not lines:
        lines.append("addresses: " + ", ".join(local_addresses()))
    return lines


class SetupLog:
    """Every line the setup page shows, written to a private file in the saved folder (the page keeps only the last lines in
    memory). The unit's passwords are written as ***. A log that cannot be written never stops the setup."""

    def __init__(self, out_dir, secrets, report, stamp=None):
        self.out_dir, self.secrets, self.report = out_dir, secrets, report
        self.stamp = stamp or time.strftime("%Y%m%d-%H%M")
        self.path = None
        self.broken = False
        self.lock = threading.Lock()

    def file_name(self):
        return f"pisophone-setup-log-{self.stamp}.txt"

    @staticmethod
    def mask(line, secrets):
        for secret in secrets:
            if len(secret) >= 6:
                line = line.replace(secret, "***")
        return line

    def write(self, line):
        secrets = self.secrets()   # (before the file lock: the page's own lock is taken inside)
        with self.lock:
            if self.broken:
                return
            try:
                first = self.path is None
                if first:
                    os.makedirs(self.out_dir, exist_ok=True)
                    self.path = os.path.abspath(os.path.join(self.out_dir, self.file_name()))
                fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                with os.fdopen(fd, "a", encoding="utf-8", newline="\n") as f:
                    if first:
                        f.write(f"{APP_NAME} setup log, {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
                        for extra in self.report():
                            f.write(self.mask(extra, secrets) + "\n")
                        f.write("----\n")
                    f.write(self.mask(line, secrets) + "\n")
            except (OSError, ValueError):
                self.broken = True


class Session:
    """The state of the setup, for the web page: one unit after the other. Worker threads change it, the page reads it."""

    def __init__(self, core, branch=DEFAULT_BRANCH, out_dir=None, system=None, install_deps=None):
        self.core, self.branch = core, branch
        self.out_dir = out_dir or default_out_dir()
        self.system = system or detect_system()
        self.install_deps = install_deps or (lambda emit: ensure_dependencies(emit, self.system))
        self.lock = threading.RLock()
        self.network_report = lambda: network_report(self.system)   # (replaced in the tests)
        self.setup_log = SetupLog(self.out_dir, self.secrets, lambda: self.network_report())
        self.unit = 0
        self.mode = None           # None until the owner chooses at the start: "full" (the router, customer Wi-Fi and phones), "wifi" (PisoWiFi only) or "phone" (PisoPhone only)
        self.checks = None
        self.deps = {"running": False, "lines": []}
        self.country = "PH"
        # the owner tools do not belong to one unit: they stay when the next unit starts
        self.tools = {"error": None, "message": None, "batch": None, "licenses": None, "qr": None, "prep": {"running": False, "lines": []}}
        self.tool_files = []
        self.checkup = {"running": False, "done": False, "items": []}   # the Check-up page, also not tied to one unit
        self.reset_unit()
        self.run_checks()

    def steps(self):
        return PHONE_STEPS if self.mode == "phone" else WIFI_STEPS if self.mode == "wifi" else STEPS

    def key(self):
        return self.steps()[self.step][0]

    # -- state ---------------------------------------------------------------------------------------------------------------
    def reset_unit(self):
        with self.lock:
            self.unit += 1
            self.step = 0 if self.unit == 1 else 1
            self.values = {"GUEST_SSID": "", "SITE_NAME": ""}
            for env, _label, _lo, _hi, length in PASSWORDS:
                self.values[env] = generate(length)
            self.router = {"host": None, "state": None, "error": None, "login_ok": False, "login_failed": False, "searching": False}
            self.router_password = ""
            self.usb = None            # None: not looked yet, else [(port, description)]
            self.skip_flash = False
            self.flashed = False
            self.install = {"running": False, "stage": -1, "activity": "", "error": None, "banner": None, "ok": False, "lines": []}
            self.result = None
            self.final = {}
            self.saved = []
            self.prov = {"state": None, "note": "", "url": "", "slot": 1}
            self.prov_auto = False
            self.box_up = None
            # the PisoPhone-only mode keeps its own state (the full mode's is above)
            self.p = {"values": {"WIFI_NAME": "", "WIFI_PASSWORD": "", "ADMIN_PASSWORD": generate(12)}, "flashed": False,
                      "erase_needed": False, "erase_ok": False, "result": None, "final": {}, "box_up": None,
                      "install": {"running": False, "stage": -1, "activity": "", "error": None, "banner": None, "ok": False, "lines": []}}
            self.coin = {"running": False, "ok": None, "lines": []}
            self.telegram = {"running": False, "ok": False, "error": None, "lines": [], "confirm": None}
            self._tg_answer = None
            if self.step == 1:
                self._enter(1)

    def spawn(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def _log_saved(self):
        return [["Setup log (send it to the developer if something went wrong)", self.setup_log.path]] if self.setup_log.path else []

    def _with_log_note(self, error):
        """The error text names the setup log file, so the owner knows what to send."""
        if error and self.setup_log.path:
            return f"{error} The setup log is saved in {self.setup_log.path}."
        return error

    def secrets(self):
        """The unit's passwords: what the setup log must not hold."""
        with self.lock:
            found = [self.values.get(env) for env, *_ in PASSWORDS] + [self.router_password]
            found += [self.p["values"].get("WIFI_PASSWORD"), self.p["values"].get("ADMIN_PASSWORD"), (self.p["final"] or {}).get("admin")]
            found += [v for k, v in (self.final or {}).items() if "PASSWORD" in k]
        return [v for v in found if isinstance(v, str) and v]

    def log(self, line):
        self.setup_log.write(line)
        with self.lock:
            lines = self.install["lines"]
            lines.append(line)
            if len(lines) > LOG_KEEP:
                del lines[:len(lines) - LOG_KEEP]
            if line.startswith("== "):
                self.install["activity"] = line[3:]
            advice = tip_text(line)
            if advice and (self.install["banner"] is None or self.install["banner"][0] == "info"):
                self.install["banner"] = ("info", "Tip: " + advice)

    # -- the checks of this computer ---------------------------------------------------------------------------------------------
    def run_checks(self):
        def work():
            try:
                checks = self.core.preflight_phone() if self.mode == "phone" else self.core.preflight()
            except Exception as e:
                checks = [("checks", False, str(e))]
            with self.lock:
                self.checks = checks
        self.spawn(work)

    def do_recheck(self, a):
        with self.lock:
            self.checks = None
        self.run_checks()

    def do_install_deps(self, a):
        with self.lock:
            if self.deps["running"]:
                return
            self.deps = {"running": True, "lines": []}

        def emit(line):
            with self.lock:
                self.deps["lines"].append(line)

        def work():
            try:
                self.install_deps(emit)
            except Exception as e:
                emit(f"failed: {e}")
            with self.lock:
                self.deps["running"] = False
                self.checks = None
            self.run_checks()
        self.spawn(work)

    # -- the mode: the full system (router) or PisoPhone only -----------------------------------------------------------------------
    def do_set_mode(self, a):
        with self.lock:
            if a.get("mode") in ("full", "wifi", "phone") and self.step == 0 and self.mode != a.get("mode"):
                self.mode = a.get("mode")
                self.checks = None
        self.run_checks()

    # -- PisoPhone only: the Wi-Fi settings, the installation over USB, the phones ---------------------------------------------------
    def psettings_problems(self):
        v = self.p["values"]
        return {"WIFI_NAME": wifi_name_problem(v["WIFI_NAME"].strip()), "WIFI_PASSWORD": wifi_password_problem(v["WIFI_PASSWORD"]),
                "ADMIN_PASSWORD": admin_password_problem(v["ADMIN_PASSWORD"])}

    def do_pset(self, a):
        with self.lock:
            for key, value in (a.get("values") or {}).items():
                if key in self.p["values"] and isinstance(value, str) and len(value) < 200:
                    self.p["values"][key] = value

    def do_perase_ok(self, a):
        with self.lock:
            self.p["erase_ok"] = bool(a.get("value"))

    def plog(self, line):
        self.setup_log.write(line)
        with self.lock:
            lines = self.p["install"]["lines"]
            lines.append(line)
            if len(lines) > LOG_KEEP:
                del lines[:len(lines) - LOG_KEEP]
            if line.startswith("== "):
                self.p["install"]["activity"] = line[3:]
            advice = board_advice(line)
            if advice and (self.p["install"]["banner"] is None or self.p["install"]["banner"][0] == "info"):
                self.p["install"]["banner"] = ("info", "Tip: " + advice)

    def do_pstart_install(self, a):
        with self.lock:
            inst = self.p["install"]
            if inst["running"] or self.key() != "p_install":
                return
            inst.update(running=True, stage=0, activity="Starting...", error=None, banner=None, ok=False, lines=[])
            self.p["result"] = None
            wifi, wifi_pw = self.p["values"]["WIFI_NAME"].strip(), self.p["values"]["WIFI_PASSWORD"]
            admin = self.p["values"]["ADMIN_PASSWORD"]
            write_box = not (self.skip_flash or self.p["flashed"])
            erase_ok = self.p["erase_ok"]

        def stage(n):
            with self.lock:
                inst["stage"] = n
                inst["activity"] = PHONE_ACTIVITY[n]
                inst["banner"] = None

        def work():
            error, result = None, None
            try:
                boards = self.core.usb_boards()
                if not boards:
                    raise SetupError("no coin box board found on USB. Plug it in with a USB data cable (some cables only charge) and try again, "
                                     "or tick that it already has the PisoPhone software.")
                if len(boards) > 1:
                    raise SetupError("several USB boards are connected (" + ", ".join(p for p, _d in boards) + "): unplug all but the coin box.")
                port = boards[0][0]
                existing = self.core.probe_box(port)
                if existing and existing.get("setup") == "done" and not erase_ok:
                    with self.lock:
                        self.p["erase_needed"] = True
                    raise SetupError(f"this board already runs a set-up coin box (box code {existing.get('mac', '?')}). Writing it again ERASES "
                                     "everything on it: its settings, its sales totals and its slot license keys. To update a working box use its "
                                     "admin page (Tools > Install update). If you do want to erase it, tick the box below and try again.")
                if not write_box and not existing:
                    raise SetupError("no PisoPhone software answered on the board (it must be 3.11.0 or newer to be set up over USB). Untick "
                                     "'Do not write it' so the software is written first.")
                if write_box:
                    stage(0)
                    self.core.flash_box(port, self.plog, product=self.mode if self.mode in ("phone", "wifi") else "full")
                    with self.lock:
                        self.p["flashed"] = True
                try:
                    status = self.core.provision(port, wifi, wifi_pw, admin, self.plog, stage)
                except SetupError as e:
                    raise SetupError(str(e) + " (Setting up over USB needs coin box software 3.11.0 or newer.)" if "answer" in str(e) else str(e))
                path = write_private_file(os.path.join(self.out_dir, f"pisophone-box-{status.get('mac', 'box').replace(':', '')}.txt"),
                                          box_summary(status, wifi, admin))
                result = {"ok": True, "status": status, "wifi": wifi, "admin": admin, "saved": [("Coin box summary (every password)", path)]}
            except SetupError as e:
                error = str(e)
            except Exception as e:
                error = f"unexpected error: {e}"
            with self.lock:
                inst["running"] = False
                self.p["result"] = result
                inst["error"] = self._with_log_note(error)
                if result and result["ok"]:
                    inst.update(ok=True, stage=len(PHONE_STAGES), activity="Done.", banner=("ok", "The coin box is online."))
                    s = result["status"]
                    self.p["final"] = {"ip": s.get("ip", ""), "mac": s.get("mac", ""), "fw": s.get("fw", ""), "wifi": result["wifi"], "admin": result["admin"]}
                    self.saved = [[label, path] for label, path in result["saved"]] + self._log_saved()
                else:
                    inst["activity"] = "Stopped."
        self.spawn(work)

    def do_precheck_box(self, a):
        ip = (self.p["final"] or {}).get("ip")

        def work():
            try:
                up = bool(ip) and self.core.box_reachable(ip)
            except Exception:
                up = False
            with self.lock:
                self.p["box_up"] = up
        self.spawn(work)

    # -- the settings --------------------------------------------------------------------------------------------------------
    def kept(self):
        return self.router["state"] in ("configured", "password")

    def settings_problems(self):
        v = self.values
        if self.kept():
            return {"COUNTRY": country_problem(self.country)}
        problems = {"GUEST_SSID": name_problem(v["GUEST_SSID"], "wifi"), "SITE_NAME": name_problem(v["SITE_NAME"], "site"),
                    "COUNTRY": country_problem(self.country)}
        for env, _label, lo, hi, _length in PASSWORDS:
            if not (env == "KIOSK_PASSWORD" and self.mode == "wifi"):
                problems[env] = password_problem(v[env], lo, hi)
        return problems

    def product(self):
        return "wifi" if self.mode == "wifi" else "both"

    def answers(self):
        a = {k: v.strip() if k in ("GUEST_SSID", "SITE_NAME") else v for k, v in self.values.items()}
        a["COUNTRY"] = self.country
        a["SHOP_PRODUCT"] = self.product()
        if self.mode == "wifi":
            a["KIOSK_PASSWORD"] = ""   # (a PisoWiFi-only router has no rental-phone Wi-Fi)
        return a

    def install_answers(self):
        """What the router setup is sent: the new answers, or (a router set up before) only the country and the product."""
        return kept_answers(self.country, self.product()) if self.kept() else self.answers()

    def do_set(self, a):
        with self.lock:
            for key, value in (a.get("values") or {}).items():
                if key in self.values and isinstance(value, str) and len(value) < 200:
                    self.values[key] = value
            country = a.get("country")
            if isinstance(country, str) and re.fullmatch(r"[A-Za-z]{2}", country):
                self.country = country.upper()

    # -- moving between the steps -----------------------------------------------------------------------------------------------
    def can_next(self):
        key = self.key()
        if key == "welcome":
            return self.mode is not None
        if key == "p_settings":
            return not any(self.psettings_problems().values())
        if key == "p_install":
            return bool(self.p["result"] and self.p["result"]["ok"])
        if key == "p_finish":
            return False
        if key == "settings":
            return not any(self.settings_problems().values())
        if key == "router":
            if not self.router["host"] or self.router["error"]:
                return False
            return self.router["state"] != "password" or bool(self.router_password)
        if key == "install":
            return bool(self.result and self.result["ok"])
        if key == "finish":
            return False
        return True

    def do_next(self, a):
        with self.lock:
            key = self.key()
            if key == "router" and self.router["state"] == "password" and not self.router["login_ok"]:
                return self._check_password()
            if not self.can_next() or self.install["running"] or self.p["install"]["running"]:
                return
            self.step += 1
            self._enter(self.step)

    def do_back(self, a):
        with self.lock:
            if self.step > 0 and not self.install["running"] and not self.p["install"]["running"]:
                self.router["searching"] = False
                self.step -= 1
                self._enter(self.step)

    def _enter(self, n):
        key = self.steps()[n][0]
        if key == "router" and (not self.router["host"] or self.router["error"]):
            self.do_search({})
        elif key in ("install", "p_install") and self.usb is None:
            self.do_refresh_usb({})
        elif key == "p_phones":
            self.do_precheck_box({})
        elif key == "phones":
            self.do_recheck_box({})
            if not self.prov_auto and self.final:
                self.prov_auto = True
                self.do_open_phone({"slot": self.prov["slot"]})

    # -- the router ----------------------------------------------------------------------------------------------------------
    def do_search(self, a):
        with self.lock:
            if self.router["searching"]:
                return
            self.router.update(host=None, state=None, error=None, login_ok=False, login_failed=False, searching=True)

        def work():
            while True:
                with self.lock:
                    if not self.router["searching"]:
                        return
                try:
                    clash = self.core.network_clash()
                except Exception:
                    clash = None   # (a check that cannot run never stops the search)
                if clash:
                    with self.lock:
                        self.router.update(host=None, state=None, error=clash, searching=False)
                    return
                host = self.core.find_router()
                if host:
                    try:
                        state = self.core.probe(host)
                        saved = self.core.saved_login(host, self.out_dir) if state == "password" else None
                        error = None
                    except SetupError as e:
                        state, saved, error = None, None, str(e)
                    except Exception as e:
                        state, saved, error = None, None, f"unexpected error: {e}"
                    with self.lock:
                        if saved:
                            self.router_password = saved   # the password saved by the earlier setup worked: nothing to type
                        self.router.update(host=host, state=state, error=error, searching=False,
                                           login_ok=state != "password" or bool(saved), login_failed=False)
                    return
                time.sleep(3)
        self.spawn(work)

    def do_router_password(self, a):
        password = a.get("password")
        with self.lock:
            if isinstance(password, str) and len(password) < 200:
                self.router_password = password
                self.router["login_failed"] = False

    def _check_password(self):
        host, password = self.router["host"], self.router_password
        if not password:
            return

        def work():
            try:
                ok = self.core.login(host, password)
            except Exception:
                ok = False
            with self.lock:
                self.router.update(login_ok=ok, login_failed=not ok)
                if ok and self.key() == "router":
                    self.step += 1
                    self._enter(self.step)
        self.spawn(work)

    # -- the coin box on USB and the installation ---------------------------------------------------------------------------------
    def do_refresh_usb(self, a):
        with self.lock:
            self.usb = None

        def work():
            try:
                boards = self.core.usb_boards()
            except Exception:
                boards = []
            with self.lock:
                self.usb = boards
        self.spawn(work)

    def do_skip_flash(self, a):
        with self.lock:
            self.skip_flash = bool(a.get("value"))

    def do_start_install(self, a):
        with self.lock:
            if self.install["running"] or self.key() != "install":
                return
            self.install.update(running=True, stage=0, activity="Starting...", error=None, banner=None, ok=False, lines=[])
            self.result = None
            answers = self.install_answers()
            write_box = not (self.skip_flash or self.flashed)
            host_hint = self.router["host"]

        def stage(n):
            with self.lock:
                self.install["stage"] = n
                self.install["activity"] = STAGE_ACTIVITY[n]
                if n != 2:
                    self.install["banner"] = None

        def notify(msg):
            self.log(msg)
            if "Unplug" in msg:
                with self.lock:
                    self.install["banner"] = ("warn", "Unplug this computer's network cable from the router, wait 5 seconds and "
                                                      "plug it back in. The installation continues by itself.")

        def work():
            error, result = None, None
            try:
                host = self.core.find_router() or host_hint
                if not host:
                    raise SetupError("the router is not found any more. Check the network cable, then try again.")
                self._ensure_router_login(host)
                if write_box:
                    boards = self.core.usb_boards()
                    if not boards:
                        raise SetupError("no coin box board found on USB. Plug it in with a USB data cable and try again, or "
                                         "tick that it already has the PisoPhone software.")
                    if len(boards) > 1:
                        raise SetupError("several USB boards are connected: unplug all but the coin box.")
                    stage(0)
                    self.core.flash_box(boards[0][0], self.log)
                    with self.lock:
                        self.flashed = True
                result = self.core.install(host, answers, self.out_dir, self.log, lambda n: stage(n + 1), notify)
            except SetupError as e:
                error = str(e)
            except Exception as e:
                error = f"unexpected error: {e}"
            self._finish_install(result, error)
        self.spawn(work)

    def _ensure_router_login(self, host):
        """A repeat after the router password was set: this program's ssh login has no password yet. Log in with the one typed
        or saved; when there is none, send the owner to the Router step before anything on the box or router is changed."""
        if self.core.probe(host) != "password":
            return
        with self.lock:
            typed = self.router_password
        if typed and self.core.login(host, typed):
            return
        saved = self.core.saved_login(host, self.out_dir)
        if saved:
            with self.lock:
                self.router_password = saved
            return
        with self.lock:
            self.router.update(host=host, state="password", login_ok=False, login_failed=bool(typed))
        raise SetupError("the router has a password and this computer does not have the right one. Go back to the Router step "
                         "and type the router password (it is in your saved summary, in the file pisophone-router-password.txt).")

    def _finish_install(self, result, error):
        with self.lock:
            self.install["running"] = False
            self.result = result
            if result and result["host"]:
                self.router["host"] = result["host"]
            if result and not result["ok"]:
                error = failure_message(result)
            self.install["error"] = self._with_log_note(error)
            if result and result["ok"]:
                self.install.update(ok=True, stage=len(INSTALL_STAGES), activity="Done.", banner=("ok", "Installed and checked."))
                found = parse_summary(result.get("summary_text", ""))
                self.final = dict(self.answers(), **found) if not self.kept() else found
                self.saved = [[label, path] for label, path in result.get("saved", [])] + self._log_saved()
                if self.final.get("ROOT_PASSWORD"):
                    self.router_password = self.final["ROOT_PASSWORD"]
            else:
                self.install["activity"] = "Stopped."

    # -- the phones ------------------------------------------------------------------------------------------------------------
    def passwords(self):
        return dict(self.final or {})

    def do_checkup(self, a):
        with self.lock:
            if self.checkup["running"]:
                return
            self.checkup.update(running=True, done=False, items=[])

        def work():
            try:
                items = self.core.box_checkup() if self.mode == "phone" else self.core.checkup()
            except Exception as e:
                items = [{"name": "Check-up", "state": "bad", "text": f"Could not run: {e}", "fix": ""}]
            with self.lock:
                self.checkup.update(running=False, done=True, items=items)
        self.spawn(work)

    def do_recheck_box(self, a):
        def work():
            try:
                up = self.core.box_online()
            except Exception:
                up = False
            with self.lock:
                self.box_up = up
        self.spawn(work)

    def do_open_phone(self, a):
        try:
            slot = max(1, min(10, int(a.get("slot", 1))))
        except (TypeError, ValueError):
            slot = 1
        with self.lock:
            if self.prov["state"] == "opening":
                return
            self.prov = {"state": "opening", "note": "", "url": "", "slot": slot}
            passwords = self.passwords()

        def work():
            try:
                kind, url, note = self.core.provisioning_target(passwords, slot)
            except Exception as e:
                kind, url, note = "box", f"http://{BOX_IP}/", f"the page could not be prepared ({e})"
            with self.lock:
                self.prov = {"state": kind, "note": note, "url": url, "slot": slot}
        self.spawn(work)

    # -- the last checks ---------------------------------------------------------------------------------------------------------
    def do_coin_test(self, a):
        with self.lock:
            if self.coin["running"]:
                return
            self.coin = {"running": True, "ok": None, "lines": []}
            host, password = self.router["host"] or TARGET_IP, self.router_password

        def line(text):
            with self.lock:
                self.coin["lines"].append(text)
                if text.startswith("RESULT: the box counted"):
                    self.coin["ok"] = True
                elif text.startswith("RESULT:"):
                    self.coin["ok"] = False

        def work():
            try:
                self.core.run(host, password, "piso-setup test-coin", line)
            except Exception as e:
                line(f"RESULT: the test could not run ({e})")
            with self.lock:
                self.coin["running"] = False
                if self.coin["ok"] is None:
                    self.coin["ok"] = False
        self.spawn(work)

    def do_telegram(self, a):
        token = str(a.get("token", "")).strip()
        with self.lock:
            if self.telegram["running"]:
                return
            if not TELEGRAM_TOKEN.fullmatch(token):
                self.telegram["error"] = "That does not look like a bot token (numbers, a colon, then letters, like 123456789:AAH...)."
                return
            self.telegram.update(running=True, error=None, lines=[], confirm=None)
            host, password = self.router["host"] or TARGET_IP, self.router_password
            site = self.passwords().get("SITE_NAME") or self.answers()["SITE_NAME"]
            self._tg_answer = threading.Event()
            self._tg_yes = False

        def confirm(chat):
            with self.lock:
                self.telegram["confirm"] = chat
                event = self._tg_answer
            event.wait(600)
            with self.lock:
                self.telegram["confirm"] = None
                return self._tg_yes

        def line(text):
            with self.lock:
                self.telegram["lines"].append(text)

        def work():
            try:
                ok = self.core.telegram(host, password, token, site, line, confirm)
                error = None if ok else ("Telegram was not connected. Try again, or do it later with piso-setup telegram on the router.")
            except Exception as e:
                ok, error = False, f"Telegram was not connected: {e}"
            with self.lock:
                self.telegram.update(running=False, ok=ok, error=error, confirm=None)
        self.spawn(work)

    def do_telegram_answer(self, a):
        with self.lock:
            if self.telegram.get("confirm") is None or self._tg_answer is None:
                return
            self._tg_yes = bool(a.get("yes"))
            self._tg_answer.set()

    def do_next_unit(self, a):
        with self.lock:
            if self.install["running"] or self.p["install"]["running"] or self.coin["running"] or self.telegram["running"]:
                return
            self.reset_unit()


    # -- the owner tools (time cards, admin QR code, recovery code, keys) -------------------------------------------------------
    def _tool(self, work):
        """Runs one owner tool: its result or its problem is shown on the Owner tools page."""
        with self.lock:
            self.tools["error"] = self.tools["message"] = None
        try:
            return work()
        except ToolError as e:
            with self.lock:
                self.tools["error"] = str(e)
        except OSError as e:
            with self.lock:
                self.tools["error"] = f"Could not write the files: {e}"
        return None

    def _offer(self, path):
        with self.lock:
            if path not in [p for _l, p in self.tool_files]:
                self.tool_files.append(("", path))
            return [p for _l, p in self.tool_files].index(path)

    def do_tool_prepare(self, a):
        with self.lock:
            if self.tools["prep"]["running"]:
                return
            self.tools["prep"] = {"running": True, "lines": []}

        def emit(line):
            with self.lock:
                self.tools["prep"]["lines"].append(line)

        def work():
            try:
                ok = ensure_cryptography(emit, self.system)
                emit("Ready." if ok else "Not installed.")
            except Exception as e:
                emit(f"failed: {e}")
            with self.lock:
                self.tools["prep"]["running"] = False
        self.spawn(work)

    def do_tool_key(self, a):
        kind = a.get("kind")
        if kind not in KEY_FILES:
            raise ValueError("unknown key")
        mode = a.get("mode")

        def work():
            replace = bool(a.get("replace"))
            if mode == "import":
                key = import_key(kind, a.get("text"), a.get("path"), replace)
                what = "saved"
            elif mode == "create":
                key = create_key(kind, replace)
                what = "created"
            else:
                raise ValueError("unknown mode")
            with self.lock:
                self.tools["message"] = f"The {KEY_FILES[kind][1].lower()} key is {what} (fingerprint {key_fingerprint(key)}), in {key_path(kind)}."
                if mode == "create":
                    self.tools["message"] += " Boxes accept it only after their firmware is built with its public half (a developer step)."
        self._tool(work)

    def do_tool_licenses(self, a):
        def work():
            r = make_slot_licenses(a.get("box"), a.get("count"), str(a.get("note") or "")[:80])
            with self.lock:
                self.tools["licenses"] = {"box": r["box"], "keys": r["keys"], "total": r["total"], "ledger": self._offer(r["ledger"]), "reissued": False}
        self._tool(work)

    def do_tool_reissue(self, a):
        def work():
            box = card_box(a.get("box"))
            keys = reissue_slot_licenses(box)
            with self.lock:
                self.tools["licenses"] = {"box": box, "keys": keys, "total": len(keys), "ledger": None, "reissued": True}
        self._tool(work)

    def do_tool_cards(self, a):
        def work():
            shift = (float(a.get("shift_x") or 0), float(a.get("shift_y") or 0))
            r = make_cards(a.get("box"), a.get("count"), a.get("hours"), str(a.get("note") or "")[:80],
                           "short" if a.get("flip") == "short" else "long", shift)
            files = [[label, self._offer(path)] for label, path in r["files"]]
            files.append(["Ledger (which card is which)", self._offer(r["ledger"])])
            with self.lock:
                self.tools["batch"] = {"box": r["box"], "first": r["first"], "last": r["last"], "hours": r["hours"], "sheets": r["sheets"],
                                       "folder": r["folder"], "files": files}
        self._tool(work)

    def do_tool_adminqr(self, a):
        def work():
            r = make_admin_qr()
            with self.lock:
                self.tools["qr"] = {"kind": "admin", "svg": r["svg"], "title": "Owner admin QR code", "file": self._offer(r["path"]),
                                    "note": f"Key fingerprint {r['fingerprint']}. Keep it private: it opens super admin on every box."}
        self._tool(work)

    def do_tool_recovery(self, a):
        def work():
            r = make_recovery_qr(a.get("minutes"))
            with self.lock:
                self.tools["qr"] = {"kind": "recovery", "svg": r["svg"], "title": "Phone recovery code", "file": self._offer(r["path"]),
                                    "note": f"Works until {r['until']} ({r['minutes']} minutes). On the phone's lock screen: admin entry > SCAN A RECOVERY CODE."}
        self._tool(work)

    def do_tool_months(self, a):
        def work():
            try:
                count = int(a.get("count") or 1)
            except (TypeError, ValueError):
                count = 0
            dest = os.path.join(keys_home(), "pisophone_sales")
            moved = move_sales_months(self.ssh, TARGET_IP, count, dest)
            with self.lock:
                self.tools["message"] = (f"Moved {len(moved)} month(s) of sales from the router to {dest}: " + ", ".join(n for n, _b in moved)
                                         + ". Keep these files: they are your sales record. The router keeps checking the months it still has.")
        self._tool(work)

    def do_tool_hide(self, a):
        with self.lock:
            self.tools["qr"] = None

    def tools_snapshot(self):
        keys = []
        expected_owner = os.environ.get("PISOPHONE_OWNER_FINGERPRINT") or EXPECTED_FINGERPRINTS["owner"]
        for kind, (_name, label) in KEY_FILES.items():
            path = key_path(kind)
            found = os.path.isfile(path)
            status = "found" if found else "missing"
            note = ""
            if found:
                try:
                    k = load_key(kind, path, check_match=False)
                    fp = key_fingerprint(k)
                    if kind == "owner" and expected_owner != "*" and fp != expected_owner:
                        status = "mismatch"
                        note = f"Fingerprint {fp} does not match firmware ({expected_owner})"
                    else:
                        note = f"Fingerprint {fp}"
                except Exception as e:
                    status = "bad"
                    note = str(e)
            keys.append({"kind": kind, "label": label, "path": path, "found": found, "status": status, "note": note})
        try:
            import cryptography  # noqa: F401
            have = True
        except ImportError:
            use_private_libs()
            try:
                import cryptography  # noqa: F401,F811
                have = True
            except ImportError:
                have = False
        t = self.tools
        return {"crypto": have, "keys": keys, "boxes": known_boxes(), "error": t["error"], "message": t["message"], "batch": t["batch"], "licenses": t["licenses"],
                "qr": t["qr"], "prep": dict(t["prep"], lines=list(t["prep"]["lines"][-40:]))}

    def tool_file(self, index):
        """(name, path) of a page the tools made. Only those can be fetched."""
        with self.lock:
            if 0 <= index < len(self.tool_files):
                path = self.tool_files[index][1]
                return os.path.basename(path), path
        return None

    # -- what the page reads -------------------------------------------------------------------------------------------------------
    def act(self, name, args):
        if not re.fullmatch(r"[a-z_]{2,24}", name or "") or not hasattr(self, "do_" + name):
            raise ValueError("unknown action")
        getattr(self, "do_" + name)(args if isinstance(args, dict) else {})

    def snapshot(self):
        with self.lock:
            inst = dict(self.install, lines=list(self.install["lines"][-600:]), total=len(self.install["lines"]))
            problems = {k: v for k, v in self.settings_problems().items() if v}
            return {
                "unit": self.unit, "step": self.step, "steps": [label for _key, label in self.steps()], "mode": self.mode,
                "keys": [key for key, _label in self.steps()], "can_next": self.can_next(),
                "system": {"name": self.system["name"], "kind": self.system["kind"], "arch": self.system["arch"],
                           "root": self.system["root"]},
                "checks": None if self.checks is None else [list(c) for c in self.checks],
                "deps": dict(self.deps, lines=list(self.deps["lines"][-60:])),
                "router": dict(self.router, has_password=bool(self.router_password)),
                "settings": {"values": dict(self.values), "country": self.country, "problems": problems, "kept": self.kept(),
                             "countries": [[c, n] for c, n in COUNTRIES]},
                "usb": {"boards": self.usb, "skip": self.skip_flash, "flashed": self.flashed},
                "install": inst, "stages": INSTALL_STAGES, "final": self.passwords(), "saved": [[l, os.path.basename(p)] for l, p in self.saved],
                "review": {"public_wifi": self.answers()["GUEST_SSID"], "site": self.answers()["SITE_NAME"], "country": self.country,
                           "router": f"{self.router['host'] or '?'} -> {TARGET_IP}" if self.router["host"] not in (None, TARGET_IP) else TARGET_IP},
                "phones": dict(self.prov, box_up=self.box_up, box_ip=BOX_IP, kiosk_ssid=(self.final or {}).get("KIOSK_SSID_NAME") or KIOSK_SSID),
                "p": {"settings": {"values": dict(self.p["values"]), "problems": {k: v for k, v in self.psettings_problems().items() if v}},
                      "review": {"wifi": self.p["values"]["WIFI_NAME"].strip()}, "flashed": self.p["flashed"], "erase_needed": self.p["erase_needed"],
                      "erase_ok": self.p["erase_ok"], "stages": PHONE_STAGES, "final": dict(self.p["final"]),
                      "install": dict(self.p["install"], lines=list(self.p["install"]["lines"][-600:]), total=len(self.p["install"]["lines"])),
                      "phones": {"box_up": self.p["box_up"], "url": f"http://{self.p['final']['ip']}/" if self.p["final"].get("ip") else "",
                                 "qr": qr_svg(f"http://{self.p['final']['ip']}/", 6, 4) if self.p["final"].get("ip") else ""}},
                "coin": dict(self.coin), "telegram": dict(self.telegram), "tools": self.tools_snapshot(),
                "checkup": dict(self.checkup, items=[dict(i) for i in self.checkup["items"]]),
            }

    def saved_file(self, index):
        """(name, path) of a file the installation saved, or None. Only those files can be fetched by the page."""
        with self.lock:
            if 0 <= index < len(self.saved):
                path = self.saved[index][1]
                return os.path.basename(path), path
        return None


def local_addresses():
    """This computer's addresses on its networks (not loopback): what to type on a phone."""
    found = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("10.255.255.255", 1))   # (no packet is sent: it only picks the outgoing interface)
        found.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    return [ip for ip in dict.fromkeys(found) if not ip.startswith("127.") and not ip.startswith("0.")]


def load_pin(path=None, wanted=None):
    """The PIN of the setup page: --pin, else the one saved from the last run, else a new random one (saved, owner only)."""
    path = path or os.path.join(pisophone_home(), "pin")
    if wanted:
        if not PIN_RE.fullmatch(wanted):
            raise SetupError("the PIN must be 6 to 32 letters or digits")
        pin = wanted
    else:
        try:
            with open(path, encoding="utf-8") as f:
                pin = f.read().strip()
        except OSError:
            pin = ""
        if PIN_RE.fullmatch(pin):
            return pin
        pin = "".join(secrets.choice("0123456789") for _ in range(6))
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    save_private(path, pin + "\n")
    return pin


class Web:
    """The page's server state: the PIN, who has logged in, who guessed wrong."""

    def __init__(self, session, pin, allowed_hosts=()):
        self.session, self.pin = session, pin
        self.tokens = set()
        self.fails = []
        self.lock = threading.Lock()
        self.allowed_hosts = {h.lower() for h in allowed_hosts}

    def host_ok(self, header):
        """Rejects a Host header that is not this computer's address or name: a web page of another site cannot then reach the
        setup through the visitor's browser (DNS rebinding)."""
        host = (header or "").lower()
        if host.startswith("["):
            host = host[1:host.find("]")]
        elif host.count(":") == 1:
            host = host.split(":")[0]
        if host in ("localhost", "") or host in self.allowed_hosts or host.endswith(".local") or ":" in host:
            return True
        return bool(re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host))

    def login(self, pin):
        now = time.monotonic()
        with self.lock:
            self.fails = [t for t in self.fails if now - t < 60]
            if len(self.fails) >= 5:
                return None, "Too many wrong PINs: wait a minute."
            if secrets.compare_digest(str(pin), self.pin):
                token = secrets.token_urlsafe(24)
                self.tokens.add(token)
                return token, None
            self.fails.append(now)
            return None, "Wrong PIN."

    def valid(self, token):
        with self.lock:
            return token in self.tokens


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "PisoSetup"
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    @property
    def web(self):
        return self.server.web

    def _send(self, code, body, ctype="application/json", extra=()):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                                                    "connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'")
        for key, value in extra:
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def _json(self, code, value, extra=()):
        self._send(code, json.dumps(value), extra=extra)

    def _token(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            key, _sep, value = part.strip().partition("=")
            if key == "piso":
                return value
        return ""

    def _guard(self, post=False):
        if not self.web.host_ok(self.headers.get("Host")):
            self._json(403, {"error": "unknown host name: use this computer's address"})
            return False
        if post and self.headers.get("X-Piso") != "1":
            self._json(403, {"error": "forbidden"})
            return False
        return True

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path)
        if not self._guard():
            return
        if path.path == "/":
            return self._send(200, PAGE.replace("@@LOGO@@", LOGO_URI).replace("@@MASCOT@@", MASCOT_URI), "text/html; charset=utf-8")
        if path.path == "/favicon.ico":
            return self._send(204, b"")
        if not self.web.valid(self._token()):
            return self._json(401, {"error": "login"})
        if path.path == "/api/state":
            return self._json(200, self.web.session.snapshot())
        if path.path == "/api/saved":
            try:
                found = self.web.session.saved_file(int(urllib.parse.parse_qs(path.query).get("i", ["-1"])[0]))
            except ValueError:
                found = None
            if not found:
                return self._json(404, {"error": "no such file"})
            with open(found[1], "rb") as f:
                body = f.read()
            kind = "text/html; charset=utf-8" if found[0].endswith(".html") else "text/plain; charset=utf-8"
            return self._send(200, body, kind, [("Content-Disposition", f'inline; filename="{found[0]}"')])
        if path.path == "/api/toolfile":
            try:
                found = self.web.session.tool_file(int(urllib.parse.parse_qs(path.query).get("i", ["-1"])[0]))
            except ValueError:
                found = None
            if not found:
                return self._json(404, {"error": "no such file"})
            with open(found[1], "rb") as f:
                body = f.read()
            kind = "text/html; charset=utf-8" if found[0].endswith(".html") else "text/csv; charset=utf-8"
            return self._send(200, body, kind, [("Content-Disposition", f'inline; filename="{found[0]}"')])
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._guard(post=True):
            return
        try:
            size = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(min(size, 20000)).decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            return self._json(400, {"error": "bad request"})
        if not isinstance(body, dict):
            return self._json(400, {"error": "bad request"})
        if self.path == "/api/login":
            token, error = self.web.login(body.get("pin", ""))
            if error:
                return self._json(403, {"error": error})
            return self._json(200, {"ok": True}, [("Set-Cookie", f"piso={token}; HttpOnly; SameSite=Strict; Path=/")])
        if not self.web.valid(self._token()):
            return self._json(401, {"error": "login"})
        if self.path == "/api/do":
            try:
                self.web.session.act(body.get("action"), body)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            return self._json(200, self.web.session.snapshot())
        self._json(404, {"error": "not found"})


def make_server(session, pin, host="0.0.0.0", port=DEFAULT_PORT):
    """The setup page's server (port 0: any free one). Tries the next ports when the first is taken."""
    last = None
    for candidate in ([port] if port == 0 else range(port, port + 10)):
        try:
            server = http.server.ThreadingHTTPServer((host, candidate), Handler)
            break
        except OSError as e:
            last = e
    else:
        raise SetupError(f"no port is free from {port} on ({last})")
    server.daemon_threads = True
    server.web = Web(session, pin, {socket.gethostname()})
    return server


def has_display():
    return os.name == "nt" or sys.platform == "darwin" or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def service_unit(script, port):
    """The systemd unit that starts the setup page when the computer boots."""
    return (f"[Unit]\nDescription=PisoPhone setup page\nAfter=network-online.target\nWants=network-online.target\n\n"
            f"[Service]\nType=simple\nEnvironment=PISOPHONE_HOME=/var/lib/pisophone-setup\nEnvironment=PYTHONUNBUFFERED=1\n"
            f"ExecStart={sys.executable} {script} --port {port} --no-open\nRestart=on-failure\nRestartSec=5\n\n"
            f"[Install]\nWantedBy=multi-user.target\n")


def install_service(args, unit_path="/etc/systemd/system/pisophone-setup.service", state_dir="/var/lib/pisophone-setup",
                    run=run_command, supported=None, script=None):
    """Linux: makes the setup page start by itself at boot (as root: it writes to the USB port and installs packages). The service
    starts its own copy of this program in `state_dir`, so moving or deleting the downloaded file does not break it."""
    script = script or os.path.abspath(sys.argv[0])
    if supported is None:
        supported = sys.platform.startswith("linux") and bool(shutil.which("systemctl"))
    if not supported:
        raise SetupError("--install-service needs a Linux system with systemd")
    if hasattr(os, "geteuid") and os.geteuid() != 0:
        raise SetupError("run it as administrator:  sudo python3 " + script + " --install-service")
    os.environ["PISOPHONE_HOME"] = state_dir
    os.makedirs(state_dir, mode=0o700, exist_ok=True)
    pin = load_pin(wanted=args.pin)
    kept = os.path.join(state_dir, "pisophone_setup.py")
    if os.path.abspath(script) != os.path.abspath(kept):
        shutil.copyfile(script, kept)
    with open(unit_path, "w", encoding="utf-8") as f:
        f.write(service_unit(kept, args.port))
    for command in (["systemctl", "daemon-reload"], ["systemctl", "enable", "--now", "pisophone-setup"]):
        if run(command, say, 60) != 0:
            raise SetupError("systemctl failed (see above)")
    say("The setup page now starts by itself when this computer starts.")
    for ip in local_addresses() or ["<this computer's address>"]:
        say(f"  Open  http://{ip}:{args.port}  on your phone (same network), PIN {pin}")
    say("  Stop it with: sudo systemctl disable --now pisophone-setup")
    return 0


def run_web(args, core=None, system=None):
    """The default mode: the setup page. Installs what is missing, serves the page, prints where to open it."""
    use_private_libs()
    system = system or detect_system()
    say(APP_NAME)
    if not args.no_install:
        say("Checking what this computer needs (the first time it installs ssh and esptool; this can take a minute)...")
        ensure_dependencies(say, system)
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        session = Session(core or Core(args.branch, workdir, getattr(args, "router_online", False)), args.branch, None if args.out == "." else args.out, system)
        pin = load_pin(wanted=args.pin)
        server = make_server(session, pin, "127.0.0.1" if args.local_only else "0.0.0.0", args.port)
        port = server.server_address[1]
        say()
        say("The setup page is running. Open it in a browser:")
        say(f"  on this computer:  http://127.0.0.1:{port}")
        if not args.local_only:
            addresses = local_addresses()
            for ip in addresses:
                say(f"  on your phone:     http://{ip}:{port}   (same network as this computer)")
            code = terminal_qr(f"http://{addresses[0]}:{port}") if addresses else ""
            if code:
                say()
                say("Or scan this with your phone's camera (same network as this computer):")
                say(code)
                say()
        say(f"  PIN: {pin}")
        say("Leave this running until the last unit is done (Ctrl+C stops it).")
        if not args.no_open and has_display():
            try:
                webbrowser.open(f"http://127.0.0.1:{port}")
            except Exception:
                pass
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            say()
            say("Stopped. Running this again is safe: the router keeps what is done.")
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TechNet PisoHub Setup</title>
<style>
:root{--bg:#eef3fb;--card:#fff;--line:#d9e3f5;--ink:#0b1630;--mut:#5b6b8c;--acc:#1f6bff;--acc2:#12b894;--accd:#1550c4;--accs:#e3edff;--ok:#0f8f6b;--oks:#ddf5ee;--warn:#b45309;--warns:#fdf0d3;--bad:#b91c1c;--bads:#fee2e2;--log:#0b1220;--logi:#cbd5e1}
@media(prefers-color-scheme:dark){:root{--bg:#050d22;--card:#0c1a3a;--line:#1f3361;--ink:#eef3ff;--mut:#97a6c7;--acc:#4f93ff;--acc2:#16c9a4;--accd:#7fb0ff;--accs:#112a5c;--ok:#4be3b4;--oks:#0b2b2a;--warn:#ffb81c;--warns:#33280c;--bad:#ff8a8a;--bads:#341420;--log:#030816}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header{padding:12px 16px;border-bottom:1px solid var(--line);display:flex;gap:10px;align-items:center;flex-wrap:wrap}
header img{width:38px;height:38px}
header b{font-size:19px;font-style:italic;font-weight:800;background:linear-gradient(90deg,var(--acc),var(--acc2));-webkit-background-clip:text;background-clip:text;color:transparent}header span{color:var(--mut);font-size:14px}
.hero{display:flex;align-items:flex-end;gap:12px;margin:14px 0 4px}.hero img{width:110px;height:auto;flex:none}.hero h1{margin:0 0 6px}
nav{display:flex;gap:6px;padding:10px 16px;overflow-x:auto}
nav div{flex:1 0 auto;text-align:center;padding:6px 10px;border-radius:999px;font-size:13px;background:var(--card);border:1px solid var(--line);color:var(--mut);white-space:nowrap}
nav div.on{background:linear-gradient(90deg,var(--acc),var(--acc2));border-color:transparent;color:#fff;font-weight:600}nav div.done{color:var(--ok);border-color:var(--ok)}
main{max-width:760px;margin:0 auto;padding:6px 16px 110px}
h1{font-size:22px;margin:14px 0 4px}h2{font-size:16px;margin:0 0 8px}p{margin:6px 0}.mut{color:var(--mut)}.small{font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin:12px 0}
.note{border-left:4px solid var(--acc);background:var(--accs);padding:10px 12px;border-radius:6px;margin:12px 0}
.note.ok{border-color:var(--ok);background:var(--oks)}.note.warn{border-color:var(--warn);background:var(--warns)}.note.bad{border-color:var(--bad);background:var(--bads)}
button{font:inherit;padding:10px 16px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--ink);cursor:pointer;min-height:44px}
button.primary{background:linear-gradient(90deg,var(--acc),var(--acc2));border-color:transparent;color:#fff;font-weight:700}button:disabled{opacity:.45;cursor:default}
button.small{min-height:34px;padding:4px 10px;font-size:13px}
input,select{font:inherit;width:100%;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--ink);min-height:44px}
input.bad{border-color:var(--bad)}label{display:block;font-weight:600;margin-top:12px}.hint{font-weight:400;color:var(--mut);font-size:13px}.err{color:var(--bad);font-size:13px;min-height:1em}
ul.steps{padding-left:20px;margin:6px 0}ul.steps li{margin:4px 0}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.grow{flex:1}
.kv{display:flex;gap:10px;padding:3px 0}.kv span:first-child{color:var(--mut);min-width:110px}
pre{background:var(--log);color:var(--logi);padding:10px 12px;border-radius:8px;overflow:auto;max-height:280px;font:12px/1.4 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word;margin:8px 0}
.dot{display:inline-block;width:14px;height:14px;border-radius:50%;border:2px solid var(--line);margin-right:10px;vertical-align:-2px}.dot.on{background:var(--acc);border-color:var(--acc)}.dot.ok{background:var(--ok);border-color:var(--ok)}
.pw{font:15px ui-monospace,Menlo,Consolas,monospace;word-break:break-all}
footer{position:fixed;left:0;right:0;bottom:0;background:var(--bg);border-top:1px solid var(--line);padding:10px 16px;display:flex;gap:10px;justify-content:space-between;max-width:100%}
footer .in{display:flex;gap:10px;justify-content:space-between;width:100%;max-width:760px;margin:0 auto}
#login{position:fixed;inset:0;background:var(--bg);display:none;align-items:center;justify-content:center;padding:20px;z-index:9}#login .card{width:100%;max-width:360px}
a{color:var(--acc)}.hide{display:none!important}
.choice{display:flex;gap:10px;align-items:flex-start;font-weight:400;border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin:8px 0;cursor:pointer}.choice input{width:auto;min-height:0;margin-top:5px}
</style></head><body>
<div id="login"><div class="card"><div class="hero"><img src="@@MASCOT@@" alt=""><h1>TechNet PisoHub Setup</h1></div><p class="mut">Type the PIN shown on the computer that runs the setup (in its terminal, or <code>pisophone-setup.service</code>'s log).</p>
<label for="pin">PIN</label><input id="pin" type="password" inputmode="text" autocomplete="off"><div class="err" id="pinErr"></div>
<p><button class="primary" id="pinBtn" style="width:100%">Open the setup</button></p></div></div>
<header><img src="@@LOGO@@" alt=""><b>TechNet PisoHub Setup</b><span id="unit"></span><span id="sysname"></span><button class="small" id="btnTools" style="margin-left:auto">Owner tools</button></header>
<nav id="nav"></nav>
<main id="toolsMain" class="hide">
<div>
 <h1>Owner tools</h1>
 <p class="mut">Everything that is not a new installation, in one place: slot license keys, time cards, your admin QR code and the phone recovery code. Your keys stay on this computer and are never uploaded.</p>
 <div id="tBanner"></div>
 <div class="card"><h2>Check-up</h2><p class="mut">Is everything working? A read-only look at the router, the coin box, the update list and the print server. It changes nothing.</p>
  <button class="primary" id="btnCheckup">Check now</button><div id="cuList" style="margin-top:8px"></div></div>
 <div id="tPrep" class="card hide"><h2>One more package is needed</h2><p class="mut">The QR codes are signed with the <b>cryptography</b> package. It installs into this program's own folder (no administrator rights).</p>
  <button class="primary" id="btnPrep">Install what is missing</button><pre id="tPrepLog" class="hide"></pre></div>
 <div class="card"><h2>Slot licenses</h2>
  <p class="mut">Every coin box has 2 free phone slots; each more needs a license key (P500). A key opens ONE slot on ONE box, once. The customer reads you the <b>box code</b> from their admin page (Phone slots) and pastes the key there.</p>
  <label for="lBox">Box code <span class="hint">like AA:BB:CC:DD:EE:FF</span></label><input id="lBox" list="cBoxes" autocomplete="off" placeholder="AA:BB:CC:DD:EE:FF">
  <div class="row"><div><label for="lCount">How many keys</label><input id="lCount" type="number" min="1" max="10" value="1" style="width:100px"></div><div class="grow"><label for="lNote">Note for your records <span class="hint">customer, receipt</span></label><input id="lNote" autocomplete="off"></div></div>
  <p style="margin-top:12px"><button class="primary" id="btnLicenses">Make the keys</button> <button id="btnReissue">Re-issue the keys already sold to this box</button></p>
  <p class="mut small">Re-issue is for a box that was erased: it prints the keys sold before again (new signatures, the same numbers), never more. Every key you make is written to pisophone_licenses.csv in your home folder.</p>
  <div id="lResult"></div></div>
 <div class="card"><h2>Time cards (QR)</h2>
  <p class="mut">Printable cards that add play time to a rental phone: the first scan adds the time on the card. Each card works on one coin box only and its number is never printed twice.</p>
  <label for="cBox">Coin box <span class="hint">the 12 characters on the coin box's page, like AA:BB:CC:DD:EE:FF</span></label><input id="cBox" list="cBoxes" autocomplete="off" placeholder="AA:BB:CC:DD:EE:FF"><datalist id="cBoxes"></datalist>
  <div class="row"><div class="grow"><label for="cCount">How many cards</label><input id="cCount" type="number" min="1" max="600" value="24"></div>
   <div class="grow"><label for="cHours">Hours on each</label><input id="cHours" type="number" min="0.1" max="240" step="0.5" value="3"></div></div>
  <label for="cNote">Note <span class="hint">optional, kept in the ledger</span></label><input id="cNote" maxlength="80" autocomplete="off">
  <details style="margin-top:8px"><summary class="mut small">Printer settings</summary>
   <label for="cFlip">Turning the paper over</label><select id="cFlip"><option value="long">Like a page (long edge)</option><option value="short">Like a calendar (short edge)</option></select>
   <div class="row"><div class="grow"><label for="cSx">Move the backs right (mm)</label><input id="cSx" type="number" step="0.5" value="0"></div><div class="grow"><label for="cSy">Move the backs down (mm)</label><input id="cSy" type="number" step="0.5" value="0"></div></div></details>
  <p style="margin-top:12px"><button class="primary" id="btnCards">Make the cards</button></p>
  <div id="cResult"></div><div id="cBoxList"></div></div>
 <div class="card"><h2>Sales history on the router</h2><p class="mut">The router closes its sales file each month. When its flash is getting full (it tells you), copy the oldest months to this computer here: each copy is checked, and only then removed from the router. The current month is never touched.</p>
  <div class="row"><div><label for="mCount" style="margin-top:0">Oldest months to move</label><input id="mCount" type="number" min="1" max="24" value="3" style="width:120px"></div><button id="btnMonths" style="align-self:flex-end">Copy to this computer, then remove from the router</button></div></div>
 <div class="card"><h2>Admin QR code</h2><p class="mut">The code that logs you in as super admin on every coin box (the box's login page, Scan admin QR).</p>
  <button id="btnAdminQr">Show my admin QR code</button><div id="qrResult"></div></div>
 <div class="card"><h2>Phone recovery code</h2><p class="mut">Scanned on a phone's lock screen, it removes PisoPhone from that phone (no cable needed). It works for a short time only.</p>
  <div class="row"><div><label for="rMin" style="margin-top:0">Works for (minutes)</label><input id="rMin" type="number" min="1" max="1440" value="30" style="width:120px"></div><button id="btnRecovery" style="align-self:flex-end">Make a recovery code</button></div></div>
 <div class="card"><h2>Your keys</h2><p class="mut small">Found in your home folder. A key you already use is imported once; new keys are not needed for a shop that already runs.</p><div id="kList"></div>
  <details style="margin-top:8px"><summary class="mut small">Import a key you already have</summary>
   <label for="kKind">Which key</label><select id="kKind"></select>
   <label for="kPath">The file on this computer <span class="hint">or paste its text below</span></label><input id="kPath" autocomplete="off" placeholder="/home/you/pisophone_card_key.pem">
   <textarea id="kText" rows="4" style="width:100%;font:12px ui-monospace,Menlo,Consolas,monospace;margin-top:8px" placeholder="or paste the whole key text here"></textarea>
   <label style="font-weight:400;display:flex;gap:10px;align-items:center"><input type="checkbox" id="kReplace" style="width:auto;min-height:0"> Replace the key that is there (the old one is kept as .old)</label>
   <p><button id="btnKeyImport">Save this key</button></p></details>
  <details style="margin-top:8px"><summary class="mut small">Make a new key (only for your own firmware build)</summary>
   <p class="mut small">A coin box trusts only the keys its firmware was built with. A new card or admin key therefore works only after the firmware is rebuilt with its public half; the published boxes keep the old key.</p>
   <button id="btnKeyCreate">Make a new key for the selected kind</button></details></div>
</div>
</main>
<main>
<section id="s0" data-key="welcome">
 <div class="hero"><img src="@@MASCOT@@" alt=""><h1>Set up your PisoHub system</h1></div>
 <div class="card"><h2>What are you setting up?</h2>
  <label class="choice"><input type="radio" name="mode" value="full"><span><b>The full TechNet PisoHub</b><br><span class="mut small">PisoWiFi customer Wi-Fi and PisoPhone rental phones with an OpenWrt router. The router is set up over a network cable (SSH), and the coin box over USB.</span></span></label>
  <label class="choice"><input type="radio" name="mode" value="wifi"><span><b>PisoWiFi only</b><br><span class="mut small">Customer Wi-Fi sold by the coin, with the OpenWrt router and the coin box. No rental phones: the phone steps are left out.</span></span></label>
  <label class="choice"><input type="radio" name="mode" value="phone"><span><b>PisoPhone only</b><br><span class="mut small">Rental phones with the coin box on your shop's normal Wi-Fi router. Nothing is installed on the router and there is no SSH: only the ESP32 coin box is set up, over USB.</span></span></label></div>
 <p class="mut" id="introText">One unit at a time: the coin box gets its software over USB, the router is set up and paired with it, then the rental phones. About 15 minutes. Nothing on the router is changed until you start the installation.</p>
 <div class="card" id="needFull"><h2>What you need</h2><ul class="steps"><li>The ESP32 coin box board and a USB <b>data</b> cable, plugged into this computer</li><li>An OpenWrt router (for example a 360T6M), factory reset</li><li>The modem in the router's WAN port, and a network cable from this computer to a LAN port</li><li class="fullOnly">The rental phones, factory reset</li></ul></div>
 <div class="card hide" id="needPhone"><h2>What you need</h2><ul class="steps"><li>The ESP32 coin box board and a USB <b>data</b> cable, plugged into this computer</li><li>Your shop's Wi-Fi name and password: the <b>2.4 GHz</b> network (the ESP32-C3 cannot use 5 GHz)</li><li>The rental phones, factory reset (they are set up afterwards)</li></ul></div>
 <details class="card"><summary><b>My phone can't open this page</b></summary><p class="mut">The page runs on this computer, so the phone must be on a network that reaches it. A factory-reset router has no Wi-Fi yet, so the phone cannot use that one. Give this computer a second connection (its Wi-Fi or another network cable) to your own network and put the phone on that network; keep the cable to the router plugged in. Or use this computer's own browser at <b>http://127.0.0.1:8765</b>. A firewall may block the port (<b>--port</b> changes it).</p></details>
 <div class="card"><h2>This computer</h2><p class="mut small" id="sysline"></p><div id="checks"><p class="mut">Checking...</p></div>
  <div class="row" style="margin-top:8px"><button id="btnDeps" class="hide">Install what is missing</button><button id="btnRecheck">Check again</button></div>
  <pre id="depsLog" class="hide"></pre></div>
</section>
<section id="s1" data-key="router" class="hide">
 <h1>Connect the router</h1>
 <p class="mut">The router is set up over a network cable from this computer. It is found by itself as soon as it is connected.</p>
 <div class="card"><ul class="steps"><li>Factory reset the router (hold its reset button about 10 seconds) and wait 2 minutes.</li><li>Plug the modem into the router's <b>WAN</b> (internet) port.</li><li>Plug this computer into one of the router's <b>LAN</b> ports.</li><li>Turn this computer's Wi-Fi off if it is also on the router's network.</li></ul></div>
 <div id="unitNote" class="note hide"></div>
 <div id="routerBox" class="note">Looking for the router...</div>
 <div id="pwRow" class="card hide"><label for="routerPw">Router password <span class="hint">this router was set up before and has a password (it is in your saved summary)</span></label><input id="routerPw" type="password" autocomplete="off"><div class="err" id="pwErr"></div></div>
 <button id="btnSearch">Search again</button>
</section>
<section id="s2" data-key="settings" class="hide">
 <h1 id="setTitle">This customer's names</h1>
 <p class="mut" id="setSub"></p>
 <div class="card" id="namesCard">
  <label for="GUEST_SSID">Public Wi-Fi name <span class="hint">what customers see in their Wi-Fi list</span></label><input id="GUEST_SSID" data-k="1" autocomplete="off" autocapitalize="off"><div class="err" data-e="GUEST_SSID"></div>
  <label for="SITE_NAME">Shop / site name <span class="hint">on the setup sheet and in alerts</span></label><input id="SITE_NAME" data-k="1" autocomplete="off"><div class="err" data-e="SITE_NAME"></div>
 </div>
 <div class="card"><label for="COUNTRY" style="margin-top:0">Country <span class="hint">decides which Wi-Fi channels the router may use</span></label><select id="COUNTRY"></select><div class="err" data-e="COUNTRY"></div></div>
 <div class="card" id="pwCard"><h2>Passwords <span class="hint">made for you, new for every unit</span></h2>
  <p class="mut small" style="margin-top:0">They are saved on this computer and printed on the setup sheet at the end. Nothing to type here.</p>
  <details id="pwOwn"><summary class="mut small">Advanced: type my own passwords</summary>
  <label for="ROOT_PASSWORD">Router password <span class="hint">SSH and the router's web page</span></label><input id="ROOT_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="ROOT_PASSWORD"></div>
  <div id="kioskPw"><label for="KIOSK_PASSWORD">PisoKiosk Wi-Fi password <span class="hint">the hidden network of the rental phones</span></label><input id="KIOSK_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="KIOSK_PASSWORD"></div></div>
  <label for="BOX_NEW_ADMIN_PASSWORD">Coin box admin password <span class="hint">the box's admin page; also the phones' admin PIN</span></label><input id="BOX_NEW_ADMIN_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="BOX_NEW_ADMIN_PASSWORD"></div>
  </details></div>
</section>
<section id="s3" data-key="install" class="hide">
 <h1>Install</h1>
 <p class="mut" id="instSub">One click: the coin box gets its software over USB, then the router is set up and paired with it. About 10 minutes. Keep the USB cable plugged in (it powers the box) and the network cable too.</p>
 <div class="card"><h2>Settings</h2><div id="review"></div></div>
 <div class="card"><div class="row"><h2 class="grow" style="margin:0">Coin box (USB)</h2><button class="small" id="btnUsb">Look again</button></div><p id="usbText" class="mut"></p>
  <label style="font-weight:400;display:flex;gap:10px;align-items:center;margin-top:8px"><input type="checkbox" id="skipFlash" style="width:auto;min-height:0"> Do not write it: the coin box already has the PisoPhone software</label></div>
 <div class="card"><h2>Progress</h2><div id="stages"></div><p id="activity" class="mut small"></p></div>
 <div id="instBanner"></div>
 <p><button class="primary" id="btnStart">Start the installation</button></p>
 <details open><summary class="mut small">Details</summary><pre id="instLog"></pre></details>
</section>
<section id="s4" data-key="phones" class="hide">
 <h1>Set up the rental phones</h1>
 <p class="mut">Each phone is set up once with a QR code: Android installs PisoPhone, locks the phone as a kiosk and connects it to its coin box slot. Open the setup page on a phone or laptop that has internet, and scan its code with the rental phone.</p>
 <div class="card"><h2>For each phone</h2><ul class="steps"><li>Factory reset the phone. On its first welcome screen tap the same spot 6 times: a QR reader opens.</li><li>Open the setup page below, tap <b>Show the setup code</b>, and scan it with the rental phone.</li><li>Accept the screens until PisoPhone shows <b>One last step</b>: on most phones switch on <i>Allow display over other apps</i>. An Android Go phone asks for USB: plug it into a computer and use <b>Finish over USB</b> on the setup page (Chrome or Edge).</li><li>Pair the slot on the coin box's page. The kiosk is running.</li></ul>
  <div class="row" style="margin-top:10px"><label style="margin:0">Slot</label><select id="slot" style="width:90px"></select><button class="primary" id="btnPhone">Make the setup page link</button></div>
  <div id="provBox"></div></div>
 <div class="card"><h2>Coin box</h2><p id="boxUp" class="mut">Checking...</p></div>
 <div class="card"><h2>Your passwords</h2><div id="pwList"></div></div>
</section>
<section id="s5" data-key="finish" class="hide">
 <div class="hero"><img src="@@MASCOT@@" alt=""><h1>Check that it all works</h1></div>
 <div class="card"><h2>1. Test a coin</h2><p class="mut">Tap the button, then insert one coin when the box beeps (within 30 seconds).</p><button class="primary" id="btnCoin">Test a coin</button><div id="coinBox"></div></div>
 <div class="card"><h2>2. Try it as a customer</h2><p class="mut" id="custText"></p></div>
 <div class="card"><h2>Alerts on your phone (optional)</h2><p class="mut">Telegram messages when the coin box goes offline or the revenue does not add up, plus a daily report. You need a bot token from @BotFather.</p>
  <input id="tgToken" placeholder="123456789:AAH..." autocomplete="off"><div class="err" id="tgErr"></div><button id="btnTg">Connect Telegram</button><div id="tgBox"></div></div>
 <div class="card"><h2>Saved on this computer</h2><div id="savedList"></div><p class="mut small" id="savedDir"></p></div>
 <div class="card"><h2>Time cards</h2><p class="mut">Print QR time cards for this unit's coin box now, or any day later (the same page, any number of batches).</p><button id="btnToCards">Open Owner tools</button></div>
 <div class="card"><h2>Another unit?</h2><p class="mut">Starts over for the next router and coin box: the shop and Wi-Fi names are asked again and new passwords are made. This unit's summary stays saved.</p><button class="primary" id="btnNext">Set up the next unit</button></div>
</section>
<section id="sp1" data-key="p_settings" class="hide">
 <h1>Your shop's Wi-Fi</h1>
 <p class="mut">The coin box and all the rental phones use this one network (your normal router: nothing is installed on it). The coin box joins it by itself after the installation.</p>
 <div class="card">
  <label for="pm_WIFI_NAME">Wi-Fi name <span class="hint">the 2.4 GHz network of your shop, as it shows in a Wi-Fi list</span></label><input id="pm_WIFI_NAME" data-pk="1" autocomplete="off" autocapitalize="off"><div class="err" data-pe="WIFI_NAME"></div>
  <label for="pm_WIFI_PASSWORD">Wi-Fi password</label><input id="pm_WIFI_PASSWORD" data-pk="1" type="password" autocomplete="off"><div class="err" data-pe="WIFI_PASSWORD"></div>
  <label style="font-weight:400;display:flex;gap:10px;align-items:center"><input type="checkbox" id="pm_showPw" style="width:auto;min-height:0"> Show the password</label>
 </div>
 <div class="card"><h2>Admin password <span class="hint">made for you</span></h2>
  <p class="mut small" style="margin-top:0">It signs you in to the coin box's admin page (user <b>admin</b>) and is also the phones' admin PIN. It is saved on this computer and shown at the end. Nothing to type here.</p>
  <details id="pm_pwOwn"><summary class="mut small">Advanced: type my own admin password</summary>
  <label for="pm_ADMIN_PASSWORD">Coin box admin password <span class="hint">8 or more letters, digits or punctuation</span></label><input id="pm_ADMIN_PASSWORD" data-pk="1" autocomplete="off"><div class="err" data-pe="ADMIN_PASSWORD"></div>
  </details></div>
</section>
<section id="sp2" data-key="p_install" class="hide">
 <h1>Install</h1>
 <p class="mut">One click: the coin box gets its software over USB, then your Wi-Fi and the new admin password, and joins your Wi-Fi. About 5 minutes. Keep the USB cable plugged in (it powers the board). Nothing is done on the router.</p>
 <div class="card"><h2>Settings</h2><div id="pm_review"></div></div>
 <div class="card"><div class="row"><h2 class="grow" style="margin:0">Coin box (USB)</h2><button class="small" id="pm_btnUsb">Look again</button></div><p id="pm_usbText" class="mut"></p>
  <label style="font-weight:400;display:flex;gap:10px;align-items:center;margin-top:8px"><input type="checkbox" id="pm_skipFlash" style="width:auto;min-height:0"> Do not write it: the coin box already has the PisoPhone software (3.11.0 or newer) and was not set up yet</label>
  <div id="pm_eraseRow" class="note warn hide"><label style="font-weight:400;display:flex;gap:10px;align-items:center;margin:0"><input type="checkbox" id="pm_eraseOk" style="width:auto;min-height:0"> This board already runs a set-up coin box. Erase it: its settings, sales totals and slot license keys are lost.</label></div></div>
 <div class="card"><h2>Progress</h2><div id="pm_stages"></div><p id="pm_activity" class="mut small"></p></div>
 <div id="pm_instBanner"></div>
 <p><button class="primary" id="pm_btnStart">Start the installation</button></p>
 <details open><summary class="mut small">Details</summary><pre id="pm_instLog"></pre></details>
</section>
<section id="sp3" data-key="p_phones" class="hide">
 <h1>Set up the rental phones</h1>
 <p class="mut">The coin box's admin page installs PisoPhone on each rental phone. Open it on a phone or computer that is on your shop's Wi-Fi.</p>
 <div class="card"><h2>The coin box</h2><p id="pm_boxUp" class="mut">Checking...</p>
  <div class="kv"><span>Admin page</span><span id="pm_boxUrl"></span></div>
  <div class="kv"><span>Sign in</span><span>user <b>admin</b>, password below</span></div>
  <div id="pm_boxQr" style="max-width:240px;margin:10px 0;background:#fff;padding:4px;border-radius:8px"></div></div>
 <div class="card"><h2>For each phone</h2><ul class="steps"><li>Open the admin page and sign in.</li><li>In <b>Phone slots</b>, tap <b>Set up a phone</b>. Factory reset the phone, tap the same spot 6 times on its first welcome screen, and scan the code the page shows.</li><li>Accept the screens until PisoPhone shows <b>One last step</b>: switch on <i>Allow display over other apps</i>.</li><li>Pair the slot on the admin page. The kiosk is running.</li></ul>
  <p class="mut small">Two phone slots are free. Each more slot needs a license key (P500): you get it from your vendor with the <b>box code</b> below.</p></div>
 <div class="card"><h2>Your details</h2><div id="pm_pwList"></div></div>
</section>
<section id="sp4" data-key="p_finish" class="hide">
 <div class="hero"><img src="@@MASCOT@@" alt=""><h1>Check that it all works</h1></div>
 <div class="card"><h2>1. Test a coin</h2><p class="mut">On the admin page, arm a slot, insert one coin and watch the counter. The coin box records every coin before anything else.</p></div>
 <div class="card"><h2>2. Try a phone</h2><p class="mut">Pair a phone with slot 1, insert a coin: the phone unlocks for the minutes the coin buys and locks again when the time is over.</p></div>
 <div class="card"><h2>Saved on this computer</h2><div id="pm_savedList"></div><p class="mut small">Keep the summary private: it holds the admin password.</p></div>
 <div class="card"><h2>Time cards</h2><p class="mut">Print QR time cards for this coin box now, or any day later (the same page, any number of batches).</p><button id="pm_btnToCards">Open Owner tools</button></div>
 <div class="card"><h2>Another coin box?</h2><p class="mut">Starts over for the next board: the Wi-Fi name and password are asked again and a new admin password is made.</p><button class="primary" id="pm_btnNext">Set up the next coin box</button></div>
</section>
</main>
<footer><div class="in"><button id="btnBack">Back</button><button class="primary" id="btnNextStep">Next</button></div></footer>
<script>
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
let S=null,shown=-1,shownUnit=0,dirty=false,pwTimer=0,view=location.hash==='#tools'?'tools':'setup';
const h=(tag,attrs,...kids)=>{const e=document.createElement(tag);for(const k in(attrs||{})){if(k==='class')e.className=attrs[k];else if(k==='onclick')e.onclick=attrs[k];else e.setAttribute(k,attrs[k])}for(const c of kids.flat())if(c!=null)e.append(c.nodeType?c:document.createTextNode(String(c)));return e};
const clear=e=>{while(e.firstChild)e.removeChild(e.firstChild)};
async function api(path,body){const r=await fetch(path,{method:body?'POST':'GET',headers:{'X-Piso':'1','Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,credentials:'same-origin'});if(r.status===401){showLogin();throw new Error('login')}return r.json()}
async function act(action,extra){S=await api('/api/do',Object.assign({action},extra||{}));paint()}
function showLogin(){$('#login').style.display='flex'}
async function login(){const r=await fetch('/api/login',{method:'POST',headers:{'X-Piso':'1','Content-Type':'application/json'},body:JSON.stringify({pin:$('#pin').value}),credentials:'same-origin'});const j=await r.json();if(r.ok){$('#login').style.display='none';$('#pin').value='';poll(true)}else $('#pinErr').textContent=j.error||'Wrong PIN.'}
$('#pinBtn').onclick=login;$('#pin').onkeydown=e=>{if(e.key==='Enter')login()};
async function poll(once){try{S=await api('/api/state');paint()}catch(e){}if(once!==true)setTimeout(poll,document.hidden?4000:1000)}
function copyText(t,btn){const done=()=>{if(btn){const o=btn.textContent;btn.textContent='Copied';setTimeout(()=>btn.textContent=o,1200)}};if(navigator.clipboard&&window.isSecureContext)navigator.clipboard.writeText(t).then(done,()=>{});else{const a=document.createElement('textarea');a.value=t;document.body.append(a);a.select();try{document.execCommand('copy')}catch(e){}a.remove();done()}}
function note(kind,text){return h('div',{class:'note '+kind},text)}
function setPre(pre,lines){const near=pre.scrollTop+pre.clientHeight>=pre.scrollHeight-40;pre.textContent=lines.join('\n');if(near)pre.scrollTop=pre.scrollHeight}
function paint(){
 if(!S)return;
 const tv=view==='tools';$('#toolsMain').classList.toggle('hide',!tv);$$('main:not(#toolsMain)').forEach(m=>m.classList.toggle('hide',tv));$('#nav').classList.toggle('hide',tv);document.querySelector('footer').classList.toggle('hide',tv);$('#btnTools').textContent=tv?'Back to setup':'Owner tools';
 if(tv){paintTools();return}
 $('#unit').textContent='Unit '+S.unit+(S.mode==='phone'?' (PisoPhone only)':S.mode==='wifi'?' (PisoWiFi only)':S.mode==='full'?' (full system)':'');$('#sysname').textContent=S.system.name;
 if(S.unit!==shownUnit){shownUnit=S.unit;shown=-1;fillSettingsOnce();pFillOnce()}
 const nav=$('#nav');if(nav.children.length!==S.steps.length){clear(nav);S.steps.forEach(n=>nav.append(h('div',{},n)))}
 [...nav.children].forEach((d,i)=>d.className=i===S.step?'on':i<S.step?'done':'');
 if(shown!==S.step){shown=S.step;$$('main>section').forEach(s=>s.classList.toggle('hide',s.dataset.key!==S.keys[S.step]));window.scrollTo(0,0)}
 $('#btnBack').classList.toggle('hide',S.step===0||S.install.running);
 const last=S.step===S.steps.length-1;$('#btnNextStep').classList.toggle('hide',last);$('#btnNextStep').disabled=!S.can_next&&!(S.keys[S.step]==='router'&&S.router.state==='password'&&S.router.has_password);
 ({welcome:paint0,router:paint1,settings:paint2,install:paint3,phones:paint4,finish:paint5,p_settings:ppaint1,p_install:ppaint2,p_phones:ppaint3,p_finish:ppaint4})[S.keys[S.step]]();
}
function paint0(){
 $$('input[name=mode]').forEach(r=>r.checked=r.value===S.mode);$('#needFull').classList.toggle('hide',S.mode==='phone');$('#needPhone').classList.toggle('hide',S.mode!=='phone');$$('.fullOnly').forEach(e=>e.classList.toggle('hide',S.mode==='wifi'));
 $('#introText').textContent=S.mode==='wifi'?'One unit at a time: the coin box gets its software over USB, then the router is set up and paired with it. About 12 minutes. Nothing on the router is changed until you start the installation.':S.mode==='phone'?'The coin box gets its software over USB, then your shop\'s Wi-Fi and a new admin password. About 5 minutes. After that you set up the rental phones from the coin box\'s own admin page. Nothing is installed on your router.':'One unit at a time: the coin box gets its software over USB, the router is set up and paired with it, then the rental phones. About 15 minutes. Nothing on the router is changed until you start the installation.';
 $('#sysline').textContent=S.system.name+' ('+S.system.arch+')'+(S.system.root?', running as administrator':'');
 const c=$('#checks');clear(c);let miss=false;
 if(!S.checks)c.append(h('p',{class:'mut'},'Checking...'));
 else S.checks.forEach(([what,ok,fix])=>{if(!ok)miss=true;c.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+(ok?'ok':'bad')+')'},ok?'OK':'Missing'),h('span',{style:'color:var(--ink)'},what+(ok?'':': '+fix))))});
 $('#btnDeps').classList.toggle('hide',!miss||S.deps.running);$('#btnDeps').disabled=S.deps.running;
 const lg=$('#depsLog');lg.classList.toggle('hide',!S.deps.lines.length);if(S.deps.lines.length)setPre(lg,S.deps.lines);
 $('#btnRecheck').disabled=S.deps.running;
}
function paint1(){
 const r=S.router,b=$('#routerBox');
 $('#unitNote').classList.toggle('hide',S.unit<2);if(S.unit>1)$('#unitNote').textContent='Unit '+S.unit+': unplug the network cable from the last router, connect this computer to the next factory-reset router, and plug the next coin box board into USB.';
 let kind='',text='';
 if(r.error){kind='bad';text=r.error[0].toUpperCase()+r.error.slice(1)}
 else if(r.host){kind='ok';text='Router found at '+r.host+(r.state==='factory'?' (factory reset: ready to be set up).':r.state==='configured'?' (set up before: it keeps its names and passwords and is finished or repaired).':r.state==='password'?' (it has a password).':'.')}
 else{kind='';text='Looking for the router at 192.168.1.1 or 10.0.0.1... Check the cable is in a LAN port (not WAN) and the router has been on for 2 minutes.'}
 b.className='note '+kind;b.textContent=text;
 $('#pwRow').classList.toggle('hide',!(r.host&&r.state==='password'&&!r.login_ok));
 $('#pwErr').textContent=r.login_failed?'That password did not work.':'';
 $('#btnSearch').disabled=r.searching&&!r.host;
}
function fillSettingsOnce(){
 const v=S.settings.values;$$('input[data-k]').forEach(i=>i.value=v[i.id]||'');
 const sel=$('#COUNTRY');clear(sel);S.settings.countries.forEach(([c,n])=>sel.append(h('option',{value:c},n+' ('+c+')')));sel.value=S.settings.country;
}
function paint2(){
 const st=S.settings,kept=st.kept;
 $('#kioskPw').classList.toggle('hide',S.mode==='wifi');$('#namesCard').classList.toggle('hide',kept);$('#pwCard').classList.toggle('hide',kept);
 $('#setTitle').textContent=kept?'This router keeps its settings':'This customer\'s names and passwords';
 $('#setSub').textContent=kept?'It was set up before, so its Wi-Fi names and passwords stay as they are (they are in the summary the earlier setup saved). The installation finishes or repairs it.':'Type the shop name and public Wi-Fi name for this customer (asked every time). The passwords were made for you, new ones for every unit. They are saved on this computer at the end and printed on the setup sheet.';
 $$('.err[data-e]').forEach(e=>{const k=e.dataset.e,p=st.problems[k];const input=document.getElementById(k);const typed=input&&(input.value||k==='COUNTRY');e.textContent=p&&typed?p[0].toUpperCase()+p.slice(1):'';if(input)input.classList.toggle('bad',!!(p&&typed));if(p&&typed&&input&&input.closest('#pwOwn'))$('#pwOwn').open=true});
}
$$('input[data-k]').forEach(i=>i.addEventListener('input',()=>{clearTimeout(pwTimer);const values={};$$('input[data-k]').forEach(x=>values[x.id]=x.value);pwTimer=setTimeout(()=>act('set',{values}),250)}));
$('#COUNTRY').onchange=e=>act('set',{country:e.target.value});
$('#routerPw').addEventListener('input',e=>act('router_password',{password:e.target.value}));
function paint3(){
 const rv=S.review,i=S.install,u=S.usb,kept=S.settings.kept,box=$('#review');clear(box);
 [['Public Wi-Fi',kept?'kept as it is':rv.public_wifi],['Site name',kept?'kept as it is':rv.site],['Country',rv.country],['Router',rv.router],['Passwords',kept?'kept as they are':'made for you (on the setup sheet at the end)']].forEach(([k,v])=>box.append(h('div',{class:'kv'},h('span',{},k),h('span',{},v))));
 let t,cls='mut';
 if(u.flashed){t='The coin box software is written.';cls='ok'}else if(u.skip){t='Not written: the box is used as it is.'}else if(u.boards===null)t='Looking for the board...';
 else if(!u.boards.length){t='No board found. Plug the coin box board into this computer with a USB data cable (a charge-only cable does not work), then tap Look again.';cls='warn'}
 else if(u.boards.length>1){t='Several USB boards are connected ('+u.boards.map(b=>b[0]).join(', ')+'). Unplug all but the coin box.';cls='warn'}
 else{t='Found: '+u.boards[0][1]+' on '+u.boards[0][0]+'. It is erased and gets the PisoPhone software.';cls='ok'}
 const ut=$('#usbText');ut.textContent=t;ut.style.color=cls==='mut'?'':'var(--'+cls+')';
 $('#skipFlash').checked=u.skip;$('#skipFlash').disabled=i.running;$('#btnUsb').disabled=i.running;
 const st=$('#stages');clear(st);S.stages.forEach((n,k)=>{const done=i.ok||k<i.stage,on=k===i.stage&&i.running;st.append(h('div',{style:'margin:4px 0'},h('span',{class:'dot '+(done?'ok':on?'on':'')}),h('span',{style:on?'font-weight:600':''},n)))});
 $('#activity').textContent=i.activity;
 const bn=$('#instBanner');clear(bn);
 if(i.ok)bn.append(note('ok','Installation complete: all checks passed. Your passwords and the setup sheet are saved. Tap Next for the phones.'));
 else if(i.error)bn.append(note('bad',i.error[0].toUpperCase()+i.error.slice(1)));
 else if(i.banner)bn.append(note(i.banner[0],i.banner[1]));
 const sb=$('#btnStart');sb.classList.toggle('hide',i.ok);sb.disabled=i.running;sb.textContent=i.running?'Installing...':(i.error||S.install.total)?'Try again':'Start the installation';
 setPre($('#instLog'),i.lines);
}
$('#btnUsb').onclick=()=>act('refresh_usb');$('#skipFlash').onchange=e=>act('skip_flash',{value:e.target.checked});$('#btnStart').onclick=()=>act('start_install');
function paint4(){
 const sel=$('#slot');if(!sel.children.length){for(let n=1;n<=10;n++)sel.append(h('option',{value:n},String(n)))}
 const p=S.phones,box=$('#provBox');clear(box);$('#btnPhone').disabled=p.state==='opening';
 if(p.state==='opening')box.append(note('','Preparing the setup page...'));
 else if(p.state==='setup')box.append(note('ok','The setup page for slot '+p.slot+' is ready.'),h('p',{},h('a',{href:p.url,target:'_blank',rel:'noopener'},'Open the phone setup page'),' ',h('button',{class:'small',onclick:ev=>copyText(p.url,ev.target)},'Copy link')),h('p',{class:'mut small'},'The link holds this box\'s key: open it only on your own phone or laptop.'));
 else if(p.state==='box')box.append(note('warn','The phone setup page could not be filled in ('+p.note+'). Open the coin box\'s own page from a computer on the router\'s network, log in as admin with the admin password below, and tap Set up a phone: http://'+p.box_ip+'/'));
 const bu=$('#boxUp');bu.textContent=p.box_up===null?'Checking...':p.box_up?'Online at '+p.box_ip:'Does not answer at '+p.box_ip+'. Is it powered on, near the router?';bu.style.color=p.box_up===null?'':p.box_up?'var(--ok)':'var(--bad)';
 const list=$('#pwList'),f=S.final;clear(list);
 [['BOX_NEW_ADMIN_PASSWORD','Coin box admin (user admin)'],['KIOSK_PASSWORD',p.kiosk_ssid+' Wi-Fi'],['ROOT_PASSWORD','Router (user root)']].forEach(([k,l])=>{const v=f[k];list.append(h('div',{style:'margin:8px 0'},h('div',{class:'mut small'},l),h('div',{class:'row'},h('span',{class:'pw grow'},v||'in the saved summary'),v?h('button',{class:'small',onclick:ev=>copyText(v,ev.target)},'Copy'):null)))});
}
$('#btnPhone').onclick=()=>act('open_phone',{slot:+$('#slot').value});
function paint5(){
 const c=S.coin,cb=$('#coinBox');clear(cb);$('#btnCoin').disabled=c.running;
 if(c.lines.length)cb.append(h('pre',{},c.lines.join('\n')));
 if(c.ok===true)cb.append(note('ok','The box counted the coin: the box and the portal work.'));else if(c.ok===false&&!c.running)cb.append(note('bad','The coin test did not pass. Read the lines above, then try again.'));
 $('#custText').textContent='With any phone join the Wi-Fi "'+(S.final.GUEST_SSID||S.review.public_wifi)+'". The payment page opens by itself. Pick a plan, tap Insert Coin, insert a coin: it shows at once. Tap Done: you are online.';
 const t=S.telegram,tb=$('#tgBox');clear(tb);$('#btnTg').disabled=t.running;$('#tgErr').textContent=t.error||'';
 if(t.ok)tb.append(note('ok','Telegram is connected.'));
 if(t.lines.length)tb.append(h('pre',{},t.lines.join('\n')));
 if(t.confirm)tb.append(note('warn',t.confirm+' Is that you? Alerts and commands are then accepted only from this chat.'),h('div',{class:'row'},h('button',{class:'primary',onclick:()=>act('telegram_answer',{yes:true})},'Yes, that is me'),h('button',{onclick:()=>act('telegram_answer',{yes:false})},'No')));
 const sl=$('#savedList');clear(sl);S.saved.forEach(([l,n],i)=>sl.append(h('p',{},h('a',{href:'/api/saved?i='+i,target:'_blank'},l.split(' (')[0]),' ',h('span',{class:'mut small'},n))));
 if(!S.saved.length)sl.append(h('p',{class:'mut'},'Nothing saved yet.'));
 $('#savedDir').textContent='Keep the summary private: it holds every password. Day to day, the router has: piso-setup status.';
}

function paintCheckup(){
 const cu=S.checkup,cl=$('#cuList');clear(cl);$('#btnCheckup').disabled=cu.running;
 if(cu.running)cl.append(h('p',{class:'mut'},'Checking...'));
 cu.items.forEach(i=>cl.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+(i.state==='ok'?'ok':i.state==='bad'?'bad':'warn')+')'},i.state==='ok'?'OK':i.state==='bad'?'Problem':i.state==='warn'?'Notice':'Not used'),h('span',{style:'color:var(--ink)'},i.name+' '),h('span',{class:'mut small'},i.text+(i.fix&&i.state!=='ok'?' '+i.fix:'')))));
}
$('#btnCheckup').onclick=()=>act('checkup');
function paintTools(){
 paintCheckup();
 const t=S.tools,b=$('#tBanner');clear(b);
 if(t.error)b.append(note('bad',t.error));if(t.message)b.append(note('ok',t.message));
 $('#tPrep').classList.toggle('hide',t.crypto&&!t.prep.running&&!t.prep.lines.length);$('#btnPrep').disabled=t.prep.running||t.crypto;
 const pl=$('#tPrepLog');pl.classList.toggle('hide',!t.prep.lines.length);if(t.prep.lines.length)setPre(pl,t.prep.lines);
 const dl=$('#cBoxes');if(dl.children.length!==t.boxes.length){clear(dl);t.boxes.forEach(x=>dl.append(h('option',{value:x.box.replace(/(..)(?=.)/g,'$1:').toUpperCase()})))}
 const kl=$('#kList');clear(kl);t.keys.forEach(k=>{const col=k.status==='mismatch'||k.status==='bad'?'bad':k.found?'ok':'warn';const stTxt=k.status==='mismatch'?'Mismatch':k.status==='bad'?'Invalid':k.found?'Found':'Missing';kl.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+col+')'},stTxt),h('span',{style:'color:var(--ink)'},k.label+' '),h('span',{class:'mut small'},k.path+(k.note?' ('+k.note+')':''))))});
 const ks=$('#kKind');if(!ks.children.length)t.keys.forEach(k=>ks.append(h('option',{value:k.kind},k.label)));
 const lr=$('#lResult');clear(lr);
 if(t.licenses){const x=t.licenses;lr.append(note('ok',(x.reissued?'The ':'Made ')+x.keys.length+' key'+(x.keys.length>1?'s':'')+' for box '+x.box.replace(/(..)(?=.)/g,'$1:').toUpperCase()+(x.reissued?' (already sold)':'. This box now has '+x.total+' key'+(x.total>1?'s':'')+' on record, '+(2+x.total)+' slots')+'. Give the customer one line per slot, after they pay.'));
  x.keys.forEach(k=>lr.append(h('div',{class:'kv'},h('code',{style:'word-break:break-all;font-size:12px;flex:1'},k),h('button',{class:'small',onclick:ev=>copyText(k,ev.target)},'Copy'))));
  if(x.ledger!=null)lr.append(h('p',{class:'mut small'},h('a',{href:'/api/toolfile?i='+x.ledger,target:'_blank',rel:'noopener'},'Open the ledger of license keys')))}
 const cr=$('#cResult');clear(cr);
 if(t.batch){const x=t.batch;cr.append(note('ok','Made '+(x.last-x.first+1)+' cards, numbers '+x.first+' to '+x.last+', '+x.hours+' each, for box '+x.box+' ('+x.sheets+' sheet'+(x.sheets>1?'s':'')+').'),
  h('ul',{class:'steps'},h('li',{},'Open ',h('b',{},'Cards: QR sides'),', print it at 100% on A4 (Ctrl+P, no margins, no headers).'),h('li',{},'Turn the paper over and print ',h('b',{},'Cards: logo sides'),' on the back (try one sheet first). A printer that prints both sides: use ',h('b',{},'Cards: both sides'),'.'),h('li',{},'Cut along the grey lines.')),
  h('div',{},x.files.map(([l,i])=>h('p',{},h('a',{href:'/api/toolfile?i='+i,target:'_blank',rel:'noopener'},l)))),h('p',{class:'mut small'},'The QR codes are the accounts: keep the printed cards and the ledger private. Fill in the "sold" column of the ledger as you sell.'))}
 const bl=$('#cBoxList');clear(bl);if(t.boxes.length){bl.append(h('p',{class:'mut small'},'Printed before: '+t.boxes.map(x=>x.box.replace(/(..)(?=.)/g,'$1:').toUpperCase()+' ('+x.printed+' cards, '+x.sold+' sold, last number '+x.last+')').join('; ')))}
 const q=$('#qrResult');const sig=t.qr?t.qr.title+t.qr.note:'';if(q.dataset.sig!==sig){q.dataset.sig=sig;clear(q);
  if(t.qr){const sv=h('div',{style:'max-width:360px;margin:10px 0;background:#fff;padding:4px;border-radius:8px'});sv.innerHTML=t.qr.svg;
   q.append(note('warn',t.qr.note),sv,h('p',{},h('a',{href:'/api/toolfile?i='+t.qr.file,target:'_blank',rel:'noopener'},'Open the printable page'),' ',h('button',{class:'small',onclick:()=>act('tool_hide')},'Hide it')))}}
 ['btnLicenses','btnReissue','btnCards','btnAdminQr','btnRecovery','btnKeyImport','btnKeyCreate'].forEach(id=>$('#'+id).disabled=!t.crypto);
}
$('#btnToCards').onclick=()=>{view='tools';paint()};
$('#btnTools').onclick=()=>{view=view==='tools'?'setup':'tools';paint()};
$('#btnPrep').onclick=()=>act('tool_prepare');
$('#btnLicenses').onclick=()=>act('tool_licenses',{box:$('#lBox').value,count:$('#lCount').value,note:$('#lNote').value});
$('#btnReissue').onclick=()=>act('tool_reissue',{box:$('#lBox').value});
$('#btnCards').onclick=()=>act('tool_cards',{box:$('#cBox').value,count:$('#cCount').value,hours:$('#cHours').value,note:$('#cNote').value,flip:$('#cFlip').value,shift_x:$('#cSx').value,shift_y:$('#cSy').value});
$('#btnAdminQr').onclick=()=>act('tool_adminqr');
$('#btnMonths').onclick=()=>act('tool_months',{count:$('#mCount').value});
$('#btnRecovery').onclick=()=>act('tool_recovery',{minutes:$('#rMin').value});
$('#btnKeyImport').onclick=()=>{act('tool_key',{kind:$('#kKind').value,mode:'import',path:$('#kPath').value,text:$('#kText').value,replace:$('#kReplace').checked});$('#kText').value=''};
$('#btnKeyCreate').onclick=()=>{if(confirm('Make a NEW '+$('#kKind').selectedOptions[0].textContent.toLowerCase()+' key? Boxes only trust the key their firmware was built with.'))act('tool_key',{kind:$('#kKind').value,mode:'create',replace:$('#kReplace').checked})};
$('#btnCoin').onclick=()=>act('coin_test');$('#btnTg').onclick=()=>act('telegram',{token:$('#tgToken').value});$('#btnNext').onclick=()=>act('next_unit');
$('#btnBack').onclick=()=>act('back');$('#btnNextStep').onclick=()=>act('next');
$('#btnDeps').onclick=()=>act('install_deps');$('#btnRecheck').onclick=()=>act('recheck');$('#btnSearch').onclick=()=>act('search');
$$('input[name=mode]').forEach(r=>r.onchange=()=>act('set_mode',{mode:r.value}));
function ppaint1(){
 const st=S.p.settings;
 $$('.err[data-pe]').forEach(e=>{const k=e.dataset.pe,p=st.problems[k];const input=document.getElementById('pm_'+k);const typed=input&&input.value;e.textContent=p&&typed?p[0].toUpperCase()+p.slice(1):'';if(input)input.classList.toggle('bad',!!(p&&typed));if(p&&typed&&input&&input.closest('#pm_pwOwn'))$('#pm_pwOwn').open=true});
}
function pFillOnce(){const v=S.p.settings.values;$$('input[data-pk]').forEach(i=>i.value=v[i.id.slice(3)]||'')}
$$('input[data-pk]').forEach(i=>i.addEventListener('input',()=>{clearTimeout(pwTimer);const values={};$$('input[data-pk]').forEach(x=>values[x.id.slice(3)]=x.value);pwTimer=setTimeout(()=>act('pset',{values}),250)}));
$('#pm_showPw').onchange=e=>{$('#pm_WIFI_PASSWORD').type=e.target.checked?'text':'password'};
function ppaint2(){
 const rv=S.p.review,i=S.p.install,u=S.usb,box=$('#pm_review');clear(box);
 [['Shop Wi-Fi',rv.wifi],['Wi-Fi password','as typed'],['Admin password','made for you (shown at the end)']].forEach(([k,v])=>box.append(h('div',{class:'kv'},h('span',{},k),h('span',{},v))));
 let t,cls='mut';
 if(S.p.flashed){t='The coin box software is written.';cls='ok'}else if(u.skip){t='Not written: the board is used as it is.'}else if(u.boards===null)t='Looking for the board...';
 else if(!u.boards.length){t='No board found. Plug the coin box board into this computer with a USB data cable (a charge-only cable does not work), then tap Look again.';cls='warn'}
 else if(u.boards.length>1){t='Several USB boards are connected ('+u.boards.map(b=>b[0]).join(', ')+'). Unplug all but the coin box.';cls='warn'}
 else{t='Found: '+u.boards[0][1]+' on '+u.boards[0][0]+'. It is erased and gets the PisoPhone software.';cls='ok'}
 const ut=$('#pm_usbText');ut.textContent=t;ut.style.color=cls==='mut'?'':'var(--'+cls+')';
 $('#pm_skipFlash').checked=u.skip;$('#pm_skipFlash').disabled=i.running;$('#pm_btnUsb').disabled=i.running;
 $('#pm_eraseRow').classList.toggle('hide',!S.p.erase_needed);$('#pm_eraseOk').checked=S.p.erase_ok;$('#pm_eraseOk').disabled=i.running;
 const st=$('#pm_stages');clear(st);S.p.stages.forEach((n,k)=>{const done=i.ok||k<i.stage,on=k===i.stage&&i.running;st.append(h('div',{style:'margin:4px 0'},h('span',{class:'dot '+(done?'ok':on?'on':'')}),h('span',{style:on?'font-weight:600':''},n)))});
 $('#pm_activity').textContent=i.activity;
 const bn=$('#pm_instBanner');clear(bn);
 if(i.ok)bn.append(note('ok','The coin box is online. Your details are saved. Tap Next for '+(S.mode==='wifi'?'the final check':'the phones')+'.'));
 else if(i.error)bn.append(note('bad',i.error[0].toUpperCase()+i.error.slice(1)));
 else if(i.banner)bn.append(note(i.banner[0],i.banner[1]));
 const sb=$('#pm_btnStart');sb.classList.toggle('hide',i.ok);sb.disabled=i.running;sb.textContent=i.running?'Installing...':(i.error||i.total)?'Try again':'Start the installation';
 setPre($('#pm_instLog'),i.lines);
}
$('#pm_btnUsb').onclick=()=>act('refresh_usb');$('#pm_skipFlash').onchange=e=>act('skip_flash',{value:e.target.checked});$('#pm_eraseOk').onchange=e=>act('perase_ok',{value:e.target.checked});$('#pm_btnStart').onclick=()=>act('pstart_install');
function ppaint3(){
 const p=S.p.phones,f=S.p.final;
 const bu=$('#pm_boxUp');bu.textContent=p.box_up===null?'Checking...':p.box_up?'Online at '+f.ip+': this computer reaches its admin page.':'Joined your Wi-Fi at '+f.ip+', but this computer does not reach it (is this computer on the same Wi-Fi?). Open the address on a phone that is.';bu.style.color=p.box_up===null?'':p.box_up?'var(--ok)':'var(--warn)';
 const bl=$('#pm_boxUrl');clear(bl);if(p.url)bl.append(h('a',{href:p.url,target:'_blank',rel:'noopener'},p.url));
 const q=$('#pm_boxQr');if(q.dataset.sig!==p.url){q.dataset.sig=p.url;q.innerHTML=p.qr||''}
 const list=$('#pm_pwList');clear(list);
 [[f.admin,'Coin box admin password (user admin)'],[f.mac,'Box code (give it to your vendor to buy slot licenses)'],[f.wifi,'Wi-Fi the coin box and the phones use'],[f.fw,'Coin box software version']].forEach(([v,l])=>{list.append(h('div',{style:'margin:8px 0'},h('div',{class:'mut small'},l),h('div',{class:'row'},h('span',{class:'pw grow'},v||'-'),v?h('button',{class:'small',onclick:ev=>copyText(v,ev.target)},'Copy'):null)))});
}
function ppaint4(){
 const sl=$('#pm_savedList');clear(sl);S.saved.forEach(([l,n],i)=>sl.append(h('p',{},h('a',{href:'/api/saved?i='+i,target:'_blank'},l.split(' (')[0]),' ',h('span',{class:'mut small'},n))));
 if(!S.saved.length)sl.append(h('p',{class:'mut'},'Nothing saved yet.'));
}
$('#pm_btnToCards').onclick=()=>{view='tools';paint()};$('#pm_btnNext').onclick=()=>act('next_unit');
poll();
</script></body></html>
"""


# ---- start ---------------------------------------------------------------------------------------------------------------

def build_parser():
    """The command line of this program."""
    p = argparse.ArgumentParser(description="Set up a TechNet PisoHub system: the coin box, the router and the rental phones.")
    p.add_argument("--branch", default=DEFAULT_BRANCH, help=f"the repository branch whose setup files are used (default {DEFAULT_BRANCH})")
    p.add_argument("--cli", action="store_true", help="run in the terminal instead of the setup page")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"the setup page's port (default {DEFAULT_PORT})")
    p.add_argument("--pin", help="the PIN that opens the setup page (6 to 32 letters or digits; kept for next time; default: a random one)")
    p.add_argument("--local-only", action="store_true", help="serve the setup page to this computer only (not to a phone)")
    p.add_argument("--no-open", action="store_true", help="do not open a browser on this computer")
    p.add_argument("--no-install", action="store_true", help="do not install the programs this one needs (ssh, esptool)")
    p.add_argument("--install-service", action="store_true", help="Linux: start the setup page by itself when the computer boots (run with sudo)")
    p.add_argument("--update", action="store_true", help="install new software on a router that is set up already (terminal)")
    p.add_argument("--router", help=f"the router's address (default: found by itself, {TARGET_IP} or {FACTORY_IP})")
    p.add_argument("--guest-ssid", help="the public Wi-Fi name (default PisoWiFi)")
    p.add_argument("--site-name", help="the shop / site name (default: the public Wi-Fi name)")
    p.add_argument("--country", default="PH", help="the Wi-Fi country code (default PH)")
    p.add_argument("--yes", action="store_true", help="ask nothing: defaults, generated passwords, no review (terminal)")
    p.add_argument("--out", default=".", help="the folder for the saved summary and setup sheet (default: the current folder; "
                                              "the setup page uses Documents/PisoPhone)")
    p.add_argument("--no-browser", action="store_true", help="do not open the setup sheet when done (terminal)")
    p.add_argument("--no-flash", action="store_true", help="do not write the coin box software over USB (terminal; it has it already)")
    p.add_argument("--product", choices=[key for key, _n, _w in PRODUCTS],
                   help="terminal: what to set up: wifi (PisoWiFi only), phone (same as --phone-only) or both. Asked when missing; required with --yes")
    p.add_argument("--router-online", action="store_true", help="router setup: do not copy the packages from this computer, let the router download them (it needs internet)")
    p.add_argument("--phone-only", action="store_true", help="terminal: set up a PisoPhone-only coin box (the shop's normal router, no router software, no SSH)")
    p.add_argument("--check-box", action="store_true", help="look at the coin box on USB (set up? online? its address) and stop")
    p.add_argument("--serial", help="PisoPhone-only terminal mode: the board's serial port, e.g. COM3 or /dev/ttyACM0 (default: found by itself)")
    p.add_argument("--image", help="PisoPhone-only terminal mode: write this merged image instead of the published one (a build of your own)")
    p.add_argument("--wifi-name", help="PisoPhone-only terminal mode: the shop's Wi-Fi name (default: asked)")
    p.add_argument("--wifi-password", help="PisoPhone-only terminal mode: the shop's Wi-Fi password (default: asked)")
    p.add_argument("--admin-password", help="PisoPhone-only terminal mode: the coin box admin password (default: a new strong one is made)")
    p.add_argument("--box", help="make slot license keys or time cards for this box code (AA:BB:CC:DD:EE:FF)")
    p.add_argument("--count", default="1", help="with --box: how many keys/cards")
    p.add_argument("--note", default="", help="with --box: a note for your records")
    p.add_argument("--reissue", action="store_true", help="with --box: print the keys already made for the box again")
    p.add_argument("--check-keys", action="store_true", help="verify status and fingerprints of owner, card, and admin keys")
    p.add_argument("--import-key", action="store_true", help="import a key (PEM or base64) to your keys folder")
    p.add_argument("--key-kind", choices=["owner", "card", "admin"], default="owner", help="with --import-key: which key (default owner)")
    p.add_argument("--key-text", help="with --import-key: key string (PEM or base64)")
    p.add_argument("--key-file", help="with --import-key: path to key file")
    p.add_argument("--replace", action="store_true", help="with --import-key: overwrite existing key file (kept as .old)")
    p.add_argument("--make-cards", action="store_true", help="make QR time cards for --box")
    p.add_argument("--hours", default="1", help="with --make-cards: play duration in hours (default 1)")
    p.add_argument("--flip", choices=["long", "short"], default="long", help="with --make-cards: paper flip edge (default long)")
    p.add_argument("--make-admin-qr", action="store_true", help="generate super admin QR code HTML page")
    p.add_argument("--make-recovery", action="store_true", help="generate phone recovery QR code HTML page")
    p.add_argument("--minutes", default="60", help="with --make-recovery: how many minutes code stays valid (default 60)")
    p.add_argument("--make-wifi-poster", action="store_true", help="generate the PisoWiFi wall poster")
    p.add_argument("--portal-url", default="http://10.0.30.1:2080/", help="with --make-wifi-poster: portal URL (default http://10.0.30.1:2080/)")
    return p


def main(argv=None):
    if os.environ.get("PISO_ASKPASS_MODE"):
        # started by ssh as its password helper (Ssh.set_password): the answer is the password, nothing else
        os.write(1, (os.environ.get("PISO_ASKPASS_PW", "") + "\n").encode())
        return 0
    if sys.stdout is not None and hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    web = urllib.request.build_opener()   # every download of this program says who it is (Python's own name is often refused)
    web.addheaders = [("User-Agent", USER_AGENT)]
    urllib.request.install_opener(web)
    use_private_libs()
    p = build_parser()
    args = p.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.branch):
        p.error(f"not a branch name: {args.branch}")
    if args.router and not re.fullmatch(r"[A-Za-z0-9.:-]+", args.router):
        p.error(f"not an address: {args.router}")
    args.country = args.country.upper()
    if country_problem(args.country):
        p.error(f"not a country code: {args.country}")
    if not 1 <= args.port <= 65535:
        p.error("not a port")
    try:
        if args.install_service:
            return install_service(args)
        if args.check_keys:
            return run_check_keys(say)
        if args.import_key:
            return run_import_key(args, say)
        if args.make_cards:
            return run_make_cards_cli(args, say)
        if args.make_admin_qr:
            return run_make_admin_qr_cli(args, say)
        if args.make_recovery:
            return run_make_recovery_cli(args, say)
        if args.make_wifi_poster:
            return run_make_wifi_poster_cli(args, say)
        if args.box:
            if not ensure_cryptography(say, detect_system()):
                raise SetupError("the 'cryptography' package could not be installed.")
            for text in (reissue_slot_licenses(args.box) if args.reissue else make_slot_licenses(args.box, args.count, args.note)["keys"]):
                say(text)
            return 0
        if not (args.check_box or args.update) and (args.cli or args.yes or args.router or args.phone_only or args.product):
            args.product = product_choice(args)
            args.phone_only = args.product == "phone"
        if args.check_box or args.phone_only:
            if not args.no_install:
                ensure_dependencies(say, detect_system())
            if args.check_box:
                run_box_check(args)
            else:
                run_phone_setup(args)
            return 0
        if not (args.cli or args.update or args.yes or args.router or args.product):
            return run_web(args)
    except (SetupError, ToolError) as e:
        say(f"ERROR: {e}")
        return 1
    use_private_libs()
    if not args.no_install:
        system = detect_system()
        ensure_ssh(say, system)
        if not (args.update or args.no_flash):
            ensure_esptool(say, system)
    if not os.environ.get("PISO_SSH") and not shutil.which("ssh"):
        say("ERROR: the ssh command was not found. Windows: Settings > Apps > Optional features > add 'OpenSSH Client'. "
            "Linux: install openssh-client.")
        return 2
    say(APP_NAME)
    say("Before you start: the coin box board is plugged into this computer's USB port (its software is written now), the router")
    say("is factory reset and on, the modem is in its WAN port, and this computer is in a LAN port by cable (turn its Wi-Fi")
    say("off).")
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        return run_setup(args, Ssh(workdir))
    except SetupError as e:
        say()
        say(f"ERROR: {e}")
        return 1
    except KeyboardInterrupt:
        say()
        say("Stopped. Running this again is safe: the router keeps what is done.")
        return 130
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
