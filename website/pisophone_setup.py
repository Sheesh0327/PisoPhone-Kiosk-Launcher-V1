#!/usr/bin/env python3
"""TechNet PisoHub Setup: the one program that sets up a whole PisoHub system, from a bare coin box and a factory-reset router
to working rental phones. It needs no screen: it serves its setup as a web page, so a small headless computer (Armbian, Parrot,
a Raspberry Pi, a laptop) can run it and you open the page on your phone.

    python3 pisophone_setup.py                    the setup page: open the address it prints, type the PIN
    python3 pisophone_setup.py --branch beta      the same with the beta branch's files (testing)
    sudo python3 pisophone_setup.py --install-service   start the setup page by itself whenever the computer boots (Linux)
    python3 pisophone_setup.py --cli              the same in the terminal (no web page)
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
import hashlib
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
ANSWER_ORDER = [env for env, *_ in PASSWORDS] + ["GUEST_SSID", "SITE_NAME", "COUNTRY"]
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


def collect_answers(args, read=input, read_secret=getpass.getpass):
    """The answers the setup needs (environment name -> value), asked here or taken from the options."""
    answers = {"COUNTRY": (getattr(args, "country", None) or "PH").upper()}
    if args.yes:
        answers["GUEST_SSID"] = args.guest_ssid or "PisoWiFi"
        answers["SITE_NAME"] = args.site_name or answers["GUEST_SSID"]
        for env, _label, _lo, _hi, length in PASSWORDS:
            answers[env] = generate(length)
    else:
        say()
        say("A few questions first. Nothing on the router is changed until you have reviewed your answers.")
        say()
        answers["GUEST_SSID"] = ask_text("Name of the public customer Wi-Fi", args.guest_ssid or "PisoWiFi", "wifi", read)
        answers["SITE_NAME"] = ask_text("Name of the shop / site (on the setup sheet and in alerts)",
                                        args.site_name or answers["GUEST_SSID"], "site", read)
        for env, label, lo, hi, length in PASSWORDS:
            answers[env] = ask_password(label, lo, hi, length, read_secret)
    check_answers(answers)
    return answers


def kept_answers(country="PH"):
    """The answers sent to a router that was set up before: none (it keeps its own), except the Wi-Fi country."""
    answers = {key: "" for key in ANSWER_ORDER}
    answers["COUNTRY"] = country
    return answers


def review(answers):
    say()
    say("================ PLEASE REVIEW (write the passwords down now) ================")
    say(f"  Public Wi-Fi (customers):  {answers['GUEST_SSID']}  (open, behind the coin payment page)")
    say(f"  Rental-phone Wi-Fi:        {KIOSK_SSID}-xxxx  (hidden; xxxx is this router's own ending, shown at the end)")
    say(f"  Site name:                 {answers['SITE_NAME']}")
    say(f"  Router address:            {TARGET_IP}   Country: {answers.get('COUNTRY', 'PH')}")
    say(f"  Router password:           {answers['ROOT_PASSWORD']}")
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


def find_router(probe=port_open):
    """The router's address: 10.0.0.1 (moved already) or 192.168.1.1 (factory), or None."""
    for host in (TARGET_IP, FACTORY_IP):
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
        self.command = shlex.split(os.environ.get("PISO_SSH", "")) or ["ssh"]
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


def setup_command(branch):
    """The remote command for the setup itself: the answers are read from the connection's input (never on a command line,
    where any process on the router could read them), then the installer runs the setup, then the results are sent back
    between markers."""
    reads = "; ".join(f"IFS= read -r {n}" for n in ANSWER_ORDER)
    return (f"{reads}; export {' '.join(ANSWER_ORDER)}; {installer_command(branch)}; rc=$?; echo; echo \"@@PISO-RC $rc\"; "
            f"echo \"@@PISO-STATE $(cat /tmp/piso-setup.state 2>/dev/null)\"; "
            f"if [ -r /root/piso-setup-summary.txt ]; then echo @@PISO-SUMMARY; cat /root/piso-setup-summary.txt; fi; "
            f"if [ -r /root/piso-handout.html ]; then echo @@PISO-HANDOUT; cat /root/piso-handout.html; fi; echo @@PISO-END")


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
            proc.stdin.write(data.encode())
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


def install(ssh, host, answers, branch, out_dir, emit, wait_move, stage=lambda n: None):
    """The installation: on a factory router the installer first moves it to 10.0.0.1 (wait_move() waits for that), then
    the setup runs with the answers. Returns the result (ok, state, saved files, summary); raises SetupError when it could
    not run to its end."""
    if host == FACTORY_IP:
        stage(0)
        emit(f"Downloading the setup to the router and moving it to {TARGET_IP}...")
        r = run_remote(ssh, host, installer_command(branch), emit)
        if r["exit"] != 0:
            raise SetupError("the router could not be prepared (see the messages above). Nothing else was changed; try again.")
        stage(1)
        if not wait_move():
            raise SetupError(
                f"the router does not answer at {TARGET_IP}. Unplug and replug this computer's network cable (or restart the "
                "computer's network), then try again: it continues where it stopped.")
        host = TARGET_IP
    stage(2)
    values = "".join(answers.get(key, "") + "\n" for key in ANSWER_ORDER)
    save_router_password(out_dir, answers.get("ROOT_PASSWORD"), host)   # before the router changes it: a repeat can log in
    result = run_remote(ssh, host, setup_command(branch), emit, data=values)

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


def run_checkup(branch, probe=port_open, opener=urllib.request.urlopen):
    """The Check-up page: a read-only look at the router, the coin box, the update feed and the print server. Each item has
    a state (ok, bad, or off for the optional print server), one line saying what was found and, when it is not ok, the one-line fix."""
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

    def take(line):
        seen.append(line)
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
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/manifest.json"), timeout=15) as r:
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


def choose_build(manifest, chip):
    """The image for a chip ("ESP32-C3", "ESP32"), or None."""
    name = (chip or "").upper()
    return next((b for b in manifest["builds"] if str(b.get("chip", "")).upper() == name), None)


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
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/{build['file']}"), timeout=60) as r:
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


def flash_box(branch, port, emit, cache_dir, opener=urllib.request.urlopen, tool=None):
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
    manifest = flash_manifest(branch, cache_dir, opener, emit)
    build = choose_build(manifest, chip)
    if not build:
        raise SetupError(f"this board is an {chip}; PisoPhone software exists for "
                         f"{' and '.join(b['chip'] for b in manifest['builds'])}.")
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
    emit("Written and verified. The board restarts by itself; keep it powered (the USB cable does that) while the router is set up.")
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
              board_finder=usb_boards, flasher=None):
    if flasher is None:
        def flasher(port, emit):
            return flash_box(args.branch, port, emit, flash_cache_dir())
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
        answers = kept_answers((getattr(args, "country", None) or "PH").upper())
    else:
        answers = collect_answers(args, read, read_secret)
        review(answers)
        if not args.yes and read("Apply these settings to the router? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1

    if not getattr(args, "no_flash", False):
        flash_in_terminal(args, read, board_finder, flasher)
    say()
    if host == FACTORY_IP:
        say(f"Step 1 of 2: downloading the setup to the router and moving it to {TARGET_IP}...")
    else:
        say("Step 1 of 2: the router is at its address already.")

    def stage(n):
        if n == 2:
            say()
            say("Step 2 of 2: the setup runs on the router (about 3 to 8 minutes; it waits for the coin box to join, so keep the")
            say("box powered on). Do not close this window.")
            say()

    result = install(ssh, host, answers, args.branch, args.out, say, lambda: wait(probe=probe, renew=renew_address), stage)
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


# ---- owner tools: time cards, the admin QR code, the phone recovery code ------------------------------------------------------
# What used to be separate owner scripts (and the owner console) lives here, in the one program: the QR codes are drawn by this
# program's own QR encoder and printed from the browser, so only the signing needs a package (`cryptography`, installed on request).
# The key files are the same as the scripts' (the home folder), so keys and ledgers made earlier keep working.
CARD_PREFIX = "PISO1"
CARD_MAX_SERIAL, CARD_MIN_SECONDS, CARD_MAX_SECONDS, CARD_MAX_BATCH = 65535, 60, 240 * 3600, 600
CARD_COLS, CARD_ROWS = 3, 4
CARD_LEDGER_FIELDS = ["serial", "seconds", "box", "printed", "batch", "sold", "note"]
ADMIN_PREFIX, RECOVERY_PREFIX, RECOVERY_MAX_MINUTES = "PISOADMIN1", "PISOREC1", 24 * 60
KEY_FILES = {   # kind: (file in the home folder, what it is for)
    "card": ("pisophone_card_key.pem", "Time cards"),
    "admin": ("pisophone-superadmin.key", "Admin QR code"),
    "owner": ("pisophone_license_key.pem", "Phone recovery code"),
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


def b64u(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def load_key(kind, path=None):
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
    """Saves a key the owner already has (its PEM text, or the path of its file on this computer) after checking it."""
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
        raise ToolError("Paste the key (the text between -----BEGIN and -----END) or type the path of its file.")
    try:
        key = serialization.load_pem_private_key(raw, password=None)
    except Exception:
        raise ToolError("That is not a usable key: it must be an unprotected PEM private key.")
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ToolError("That is not a P-256 key.")
    write_private_key(kind, raw if raw.endswith(b"\n") else raw + b"\n", replace)
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
.card b{display:block;margin-top:5.5mm;font-size:5.6mm;line-height:6mm}.card .h{margin-top:1.4mm;font-size:3.7mm;color:#3c3c3c}
.card svg{position:absolute;left:50%;top:15.5mm;width:40mm;height:40mm;margin-left:-20mm}
.card .s{position:absolute;left:0;right:0;top:56.5mm;font-size:2.9mm}.card .n{position:absolute;left:0;right:0;bottom:2.4mm;font-size:2.7mm;color:#505050}
.back{position:absolute;width:63mm;height:68.6mm;background:#0a1b45;display:flex;align-items:center;justify-content:center}.back img{width:46mm;height:auto}
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
            body.append(f'<div class="back" style="left:{x - 1 + shift[0]:.2f}mm;top:{y - 1 + shift[1]:.2f}mm"><img src="{LOGO_LARGE_URI}" alt=""></div>')
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



# ---- the setup page: a small web server on this computer ----------------------------------------------------------------------
# The setup is a web page served by this program (standard library only), so it needs no screen on the computer that runs it:
# open the address on any phone or laptop in the same network. A PIN keeps everyone else out.

STEPS = [("welcome", "Start"), ("router", "Router"), ("settings", "Settings"), ("install", "Install"), ("phones", "Phones"),
         ("finish", "Finish")]
INSTALL_STAGES = ["Write the coin box software (USB)", "Prepare the router", f"Move it to {TARGET_IP}", "Install and set up",
                  "Check everything"]
STAGE_ACTIVITY = ["Writing the coin box software", "Downloading the setup to the router", f"Waiting for the router at {TARGET_IP}",
                  "Installing on the router", "Checking"]
PIN_RE = re.compile(r"[A-Za-z0-9]{6,32}")
DEFAULT_PORT = 8765
LOG_KEEP = 3000


class Core:
    """What the setup does with the network, the router and the coin box; tests replace it with a fake."""

    def __init__(self, branch, workdir):
        self.branch = branch
        self.ssh = Ssh(workdir, hidden=True)
        self.ssh.set_password("")   # never wait for a password on a terminal there is not

    def preflight(self):
        return preflight(self.branch)

    def find_router(self):
        return find_router()

    def probe(self, host):
        return probe_router(self.ssh, host)

    def saved_login(self, host, out_dir):
        """The router password saved on this computer, when the router accepts it (else None)."""
        return use_saved_password(self.ssh, host, [out_dir, default_out_dir()], notify=lambda text: None)

    def login(self, host, password):
        self.ssh.set_password(password)
        return check_login(self.ssh, host)

    def usb_boards(self):
        return usb_boards()

    def flash_box(self, port, emit):
        system = detect_system()
        if not serial_access_fix(port, system, emit):
            raise SetupError("this account may not use the USB port (see above).")
        return flash_box(self.branch, port, emit, flash_cache_dir())

    def install(self, host, answers, out_dir, emit, stage, notify):
        return install(self.ssh, host, answers, self.branch, out_dir, emit,
                       lambda: wait_for_move(renew=renew_address, notify=notify), stage)

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
        return run_checkup(self.branch)


class Session:
    """The state of the setup, for the web page: one unit after the other. Worker threads change it, the page reads it."""

    def __init__(self, core, branch=DEFAULT_BRANCH, out_dir=None, system=None, install_deps=None):
        self.core, self.branch = core, branch
        self.out_dir = out_dir or default_out_dir()
        self.system = system or detect_system()
        self.install_deps = install_deps or (lambda emit: ensure_dependencies(emit, self.system))
        self.lock = threading.RLock()
        self.unit = 0
        self.checks = None
        self.deps = {"running": False, "lines": []}
        self.country = "PH"
        # the owner tools do not belong to one unit: they stay when the next unit starts
        self.tools = {"error": None, "message": None, "batch": None, "qr": None, "prep": {"running": False, "lines": []}}
        self.tool_files = []
        self.checkup = {"running": False, "done": False, "items": []}   # the Check-up page, also not tied to one unit
        self.reset_unit()
        self.run_checks()

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
            self.coin = {"running": False, "ok": None, "lines": []}
            self.telegram = {"running": False, "ok": False, "error": None, "lines": [], "confirm": None}
            self._tg_answer = None
            if self.step == 1:
                self._enter(1)

    def spawn(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def log(self, line):
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
                checks = self.core.preflight()
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
            problems[env] = password_problem(v[env], lo, hi)
        return problems

    def answers(self):
        a = {k: v.strip() if k in ("GUEST_SSID", "SITE_NAME") else v for k, v in self.values.items()}
        a["COUNTRY"] = self.country
        return a

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
        key = STEPS[self.step][0]
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
            key = STEPS[self.step][0]
            if key == "router" and self.router["state"] == "password" and not self.router["login_ok"]:
                return self._check_password()
            if not self.can_next() or self.install["running"]:
                return
            self.step += 1
            self._enter(self.step)

    def do_back(self, a):
        with self.lock:
            if self.step > 0 and not self.install["running"]:
                self.router["searching"] = False
                self.step -= 1
                self._enter(self.step)

    def _enter(self, n):
        key = STEPS[n][0]
        if key == "router" and (not self.router["host"] or self.router["error"]):
            self.do_search({})
        elif key == "install" and self.usb is None:
            self.do_refresh_usb({})
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
                if ok and STEPS[self.step][0] == "router":
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
            if self.install["running"] or STEPS[self.step][0] != "install":
                return
            self.install.update(running=True, stage=0, activity="Starting...", error=None, banner=None, ok=False, lines=[])
            self.result = None
            answers = kept_answers(self.country) if self.kept() else self.answers()
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

    def _finish_install(self, result, error):
        with self.lock:
            self.install["running"] = False
            self.result = result
            if result and result["host"]:
                self.router["host"] = result["host"]
            if result and not result["ok"]:
                error = failure_message(result)
            self.install["error"] = error
            if result and result["ok"]:
                self.install.update(ok=True, stage=len(INSTALL_STAGES), activity="Done.", banner=("ok", "Installed and checked."))
                found = parse_summary(result.get("summary_text", ""))
                self.final = dict(self.answers(), **found) if not self.kept() else found
                self.saved = [[label, path] for label, path in result.get("saved", [])]
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
                items = self.core.checkup()
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
            if self.install["running"] or self.coin["running"] or self.telegram["running"]:
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
                if mode == "create" and kind != "owner":
                    self.tools["message"] += " Boxes accept it only after their firmware is built with its public half (a developer step)."
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

    def do_tool_hide(self, a):
        with self.lock:
            self.tools["qr"] = None

    def tools_snapshot(self):
        keys = []
        for kind, (_name, label) in KEY_FILES.items():
            path = key_path(kind)
            keys.append({"kind": kind, "label": label, "path": path, "found": os.path.isfile(path)})
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
        return {"crypto": have, "keys": keys, "boxes": known_boxes(), "error": t["error"], "message": t["message"], "batch": t["batch"],
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
                "unit": self.unit, "step": self.step, "steps": [label for _key, label in STEPS],
                "keys": [key for key, _label in STEPS], "can_next": self.can_next(),
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
                           "router": f"{self.router['host'] or '?'} -> {TARGET_IP}" if self.router["host"] == FACTORY_IP else TARGET_IP},
                "phones": dict(self.prov, box_up=self.box_up, box_ip=BOX_IP, kiosk_ssid=(self.final or {}).get("KIOSK_SSID_NAME") or KIOSK_SSID),
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
                    run=run_command, supported=None):
    """Linux: makes the setup page start by itself at boot (as root: it writes to the USB port and installs packages)."""
    if supported is None:
        supported = sys.platform.startswith("linux") and bool(shutil.which("systemctl"))
    if not supported:
        raise SetupError("--install-service needs a Linux system with systemd")
    if hasattr(os, "geteuid") and os.geteuid() != 0:
        raise SetupError("run it as administrator:  sudo python3 " + os.path.abspath(sys.argv[0]) + " --install-service")
    os.environ["PISOPHONE_HOME"] = state_dir
    os.makedirs(state_dir, mode=0o700, exist_ok=True)
    pin = load_pin(wanted=args.pin)
    with open(unit_path, "w", encoding="utf-8") as f:
        f.write(service_unit(os.path.abspath(sys.argv[0]), args.port))
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
        session = Session(core or Core(args.branch, workdir), args.branch, None if args.out == "." else args.out, system)
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
</style></head><body>
<div id="login"><div class="card"><div class="hero"><img src="@@MASCOT@@" alt=""><h1>TechNet PisoHub Setup</h1></div><p class="mut">Type the PIN shown on the computer that runs the setup (in its terminal, or <code>pisophone-setup.service</code>'s log).</p>
<label for="pin">PIN</label><input id="pin" type="password" inputmode="text" autocomplete="off"><div class="err" id="pinErr"></div>
<p><button class="primary" id="pinBtn" style="width:100%">Open the setup</button></p></div></div>
<header><img src="@@LOGO@@" alt=""><b>TechNet PisoHub Setup</b><span id="unit"></span><span id="sysname"></span><button class="small" id="btnTools" style="margin-left:auto">Owner tools</button></header>
<nav id="nav"></nav>
<main id="toolsMain" class="hide">
<div>
 <h1>Owner tools</h1>
 <p class="mut">Everything that is not a new installation, in one place: time cards, your admin QR code and the phone recovery code. Your keys stay on this computer and are never uploaded.</p>
 <div id="tBanner"></div>
 <div class="card"><h2>Check-up</h2><p class="mut">Is everything working? A read-only look at the router, the coin box, the update list and the print server. It changes nothing.</p>
  <button class="primary" id="btnCheckup">Check now</button><div id="cuList" style="margin-top:8px"></div></div>
 <div id="tPrep" class="card hide"><h2>One more package is needed</h2><p class="mut">The QR codes are signed with the <b>cryptography</b> package. It installs into this program's own folder (no administrator rights).</p>
  <button class="primary" id="btnPrep">Install what is missing</button><pre id="tPrepLog" class="hide"></pre></div>
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
<section id="s0">
 <div class="hero"><img src="@@MASCOT@@" alt=""><h1>Set up your PisoHub system</h1></div>
 <p class="mut">One unit at a time: the coin box gets its software over USB, the router is set up and paired with it, then the rental phones. About 15 minutes. Nothing on the router is changed until you start the installation.</p>
 <div class="card"><h2>What you need</h2><ul class="steps"><li>The ESP32 coin box board and a USB <b>data</b> cable, plugged into this computer</li><li>An OpenWrt router (for example a 360T6M), factory reset</li><li>The modem in the router's WAN port, and a network cable from this computer to a LAN port</li><li>The rental phones, factory reset</li></ul></div>
 <details class="card"><summary><b>My phone can't open this page</b></summary><p class="mut">The page runs on this computer, so the phone must be on a network that reaches it. A factory-reset router has no Wi-Fi yet, so the phone cannot use that one. Give this computer a second connection (its Wi-Fi or another network cable) to your own network and put the phone on that network; keep the cable to the router plugged in. Or use this computer's own browser at <b>http://127.0.0.1:8765</b>. A firewall may block the port (<b>--port</b> changes it).</p></details>
 <div class="card"><h2>This computer</h2><p class="mut small" id="sysline"></p><div id="checks"><p class="mut">Checking...</p></div>
  <div class="row" style="margin-top:8px"><button id="btnDeps" class="hide">Install what is missing</button><button id="btnRecheck">Check again</button></div>
  <pre id="depsLog" class="hide"></pre></div>
</section>
<section id="s1" class="hide">
 <h1>Connect the router</h1>
 <p class="mut">The router is set up over a network cable from this computer. It is found by itself as soon as it is connected.</p>
 <div class="card"><ul class="steps"><li>Factory reset the router (hold its reset button about 10 seconds) and wait 2 minutes.</li><li>Plug the modem into the router's <b>WAN</b> (internet) port.</li><li>Plug this computer into one of the router's <b>LAN</b> ports.</li><li>Turn this computer's Wi-Fi off if it is also on the router's network.</li></ul></div>
 <div id="unitNote" class="note hide"></div>
 <div id="routerBox" class="note">Looking for the router...</div>
 <div id="pwRow" class="card hide"><label for="routerPw">Router password <span class="hint">this router was set up before and has a password (it is in your saved summary)</span></label><input id="routerPw" type="password" autocomplete="off"><div class="err" id="pwErr"></div></div>
 <button id="btnSearch">Search again</button>
</section>
<section id="s2" class="hide">
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
  <label for="KIOSK_PASSWORD">PisoKiosk Wi-Fi password <span class="hint">the hidden network of the rental phones</span></label><input id="KIOSK_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="KIOSK_PASSWORD"></div>
  <label for="BOX_NEW_ADMIN_PASSWORD">Coin box admin password <span class="hint">the box's admin page; also the phones' admin PIN</span></label><input id="BOX_NEW_ADMIN_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="BOX_NEW_ADMIN_PASSWORD"></div>
  </details></div>
</section>
<section id="s3" class="hide">
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
<section id="s4" class="hide">
 <h1>Set up the rental phones</h1>
 <p class="mut">Each phone is set up once with a QR code: Android installs PisoPhone, locks the phone as a kiosk and connects it to its coin box slot. Open the setup page on a phone or laptop that has internet, and scan its code with the rental phone.</p>
 <div class="card"><h2>For each phone</h2><ul class="steps"><li>Factory reset the phone. On its first welcome screen tap the same spot 6 times: a QR reader opens.</li><li>Open the setup page below, tap <b>Show the setup code</b>, and scan it with the rental phone.</li><li>Accept the screens until PisoPhone shows <b>One last step</b>: on most phones switch on <i>Allow display over other apps</i>. An Android Go phone asks for USB: plug it into a computer and use <b>Finish over USB</b> on the setup page (Chrome or Edge).</li><li>Pair the slot on the coin box's page. The kiosk is running.</li></ul>
  <div class="row" style="margin-top:10px"><label style="margin:0">Slot</label><select id="slot" style="width:90px"></select><button class="primary" id="btnPhone">Make the setup page link</button></div>
  <div id="provBox"></div></div>
 <div class="card"><h2>Coin box</h2><p id="boxUp" class="mut">Checking...</p></div>
 <div class="card"><h2>Your passwords</h2><div id="pwList"></div></div>
</section>
<section id="s5" class="hide">
 <div class="hero"><img src="@@MASCOT@@" alt=""><h1>Check that it all works</h1></div>
 <div class="card"><h2>1. Test a coin</h2><p class="mut">Tap the button, then insert one coin when the box beeps (within 30 seconds).</p><button class="primary" id="btnCoin">Test a coin</button><div id="coinBox"></div></div>
 <div class="card"><h2>2. Try it as a customer</h2><p class="mut" id="custText"></p></div>
 <div class="card"><h2>Alerts on your phone (optional)</h2><p class="mut">Telegram messages when the coin box goes offline or the revenue does not add up, plus a daily report. You need a bot token from @BotFather.</p>
  <input id="tgToken" placeholder="123456789:AAH..." autocomplete="off"><div class="err" id="tgErr"></div><button id="btnTg">Connect Telegram</button><div id="tgBox"></div></div>
 <div class="card"><h2>Saved on this computer</h2><div id="savedList"></div><p class="mut small" id="savedDir"></p></div>
 <div class="card"><h2>Time cards</h2><p class="mut">Print QR time cards for this unit's coin box now, or any day later (the same page, any number of batches).</p><button id="btnToCards">Open Owner tools</button></div>
 <div class="card"><h2>Another unit?</h2><p class="mut">Starts over for the next router and coin box: the shop and Wi-Fi names are asked again and new passwords are made. This unit's summary stays saved.</p><button class="primary" id="btnNext">Set up the next unit</button></div>
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
 $('#unit').textContent='Unit '+S.unit;$('#sysname').textContent=S.system.name;
 if(S.unit!==shownUnit){shownUnit=S.unit;shown=-1;fillSettingsOnce()}
 const nav=$('#nav');if(nav.children.length!==S.steps.length){clear(nav);S.steps.forEach(n=>nav.append(h('div',{},n)))}
 [...nav.children].forEach((d,i)=>d.className=i===S.step?'on':i<S.step?'done':'');
 if(shown!==S.step){shown=S.step;$$('main>section').forEach((s,i)=>s.classList.toggle('hide',i!==S.step));window.scrollTo(0,0)}
 $('#btnBack').classList.toggle('hide',S.step===0||S.install.running);
 const last=S.step===S.steps.length-1;$('#btnNextStep').classList.toggle('hide',last);$('#btnNextStep').disabled=!S.can_next&&!(S.step===1&&S.router.state==='password'&&S.router.has_password);
 [paint0,paint1,paint2,paint3,paint4,paint5][S.step]();
}
function paint0(){
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
 $('#namesCard').classList.toggle('hide',kept);$('#pwCard').classList.toggle('hide',kept);
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
 cu.items.forEach(i=>cl.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+(i.state==='ok'?'ok':i.state==='bad'?'bad':'warn')+')'},i.state==='ok'?'OK':i.state==='bad'?'Problem':'Not used'),h('span',{style:'color:var(--ink)'},i.name+' '),h('span',{class:'mut small'},i.text+(i.fix&&i.state!=='ok'?' '+i.fix:'')))));
}
$('#btnCheckup').onclick=()=>act('checkup');
function paintTools(){
 paintCheckup();
 const t=S.tools,b=$('#tBanner');clear(b);
 if(t.error)b.append(note('bad',t.error));if(t.message)b.append(note('ok',t.message));
 $('#tPrep').classList.toggle('hide',t.crypto&&!t.prep.running&&!t.prep.lines.length);$('#btnPrep').disabled=t.prep.running||t.crypto;
 const pl=$('#tPrepLog');pl.classList.toggle('hide',!t.prep.lines.length);if(t.prep.lines.length)setPre(pl,t.prep.lines);
 const dl=$('#cBoxes');if(dl.children.length!==t.boxes.length){clear(dl);t.boxes.forEach(x=>dl.append(h('option',{value:x.box.replace(/(..)(?=.)/g,'$1:').toUpperCase()})))}
 const kl=$('#kList');clear(kl);t.keys.forEach(k=>kl.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+(k.found?'ok':'warn')+')'},k.found?'Found':'Missing'),h('span',{style:'color:var(--ink)'},k.label+' '),h('span',{class:'mut small'},k.path))));
 const ks=$('#kKind');if(!ks.children.length)t.keys.forEach(k=>ks.append(h('option',{value:k.kind},k.label)));
 const cr=$('#cResult');clear(cr);
 if(t.batch){const x=t.batch;cr.append(note('ok','Made '+(x.last-x.first+1)+' cards, numbers '+x.first+' to '+x.last+', '+x.hours+' each, for box '+x.box+' ('+x.sheets+' sheet'+(x.sheets>1?'s':'')+').'),
  h('ul',{class:'steps'},h('li',{},'Open ',h('b',{},'Cards: QR sides'),', print it at 100% on A4 (Ctrl+P, no margins, no headers).'),h('li',{},'Turn the paper over and print ',h('b',{},'Cards: logo sides'),' on the back (try one sheet first). A printer that prints both sides: use ',h('b',{},'Cards: both sides'),'.'),h('li',{},'Cut along the grey lines.')),
  h('div',{},x.files.map(([l,i])=>h('p',{},h('a',{href:'/api/toolfile?i='+i,target:'_blank',rel:'noopener'},l)))),h('p',{class:'mut small'},'The QR codes are the accounts: keep the printed cards and the ledger private. Fill in the "sold" column of the ledger as you sell.'))}
 const bl=$('#cBoxList');clear(bl);if(t.boxes.length){bl.append(h('p',{class:'mut small'},'Printed before: '+t.boxes.map(x=>x.box.replace(/(..)(?=.)/g,'$1:').toUpperCase()+' ('+x.printed+' cards, '+x.sold+' sold, last number '+x.last+')').join('; ')))}
 const q=$('#qrResult');const sig=t.qr?t.qr.title+t.qr.note:'';if(q.dataset.sig!==sig){q.dataset.sig=sig;clear(q);
  if(t.qr){const sv=h('div',{style:'max-width:360px;margin:10px 0;background:#fff;padding:4px;border-radius:8px'});sv.innerHTML=t.qr.svg;
   q.append(note('warn',t.qr.note),sv,h('p',{},h('a',{href:'/api/toolfile?i='+t.qr.file,target:'_blank',rel:'noopener'},'Open the printable page'),' ',h('button',{class:'small',onclick:()=>act('tool_hide')},'Hide it')))}}
 ['btnCards','btnAdminQr','btnRecovery','btnKeyImport','btnKeyCreate'].forEach(id=>$('#'+id).disabled=!t.crypto);
}
$('#btnToCards').onclick=()=>{view='tools';paint()};
$('#btnTools').onclick=()=>{view=view==='tools'?'setup':'tools';paint()};
$('#btnPrep').onclick=()=>act('tool_prepare');
$('#btnCards').onclick=()=>act('tool_cards',{box:$('#cBox').value,count:$('#cCount').value,hours:$('#cHours').value,note:$('#cNote').value,flip:$('#cFlip').value,shift_x:$('#cSx').value,shift_y:$('#cSy').value});
$('#btnAdminQr').onclick=()=>act('tool_adminqr');
$('#btnRecovery').onclick=()=>act('tool_recovery',{minutes:$('#rMin').value});
$('#btnKeyImport').onclick=()=>{act('tool_key',{kind:$('#kKind').value,mode:'import',path:$('#kPath').value,text:$('#kText').value,replace:$('#kReplace').checked});$('#kText').value=''};
$('#btnKeyCreate').onclick=()=>{if(confirm('Make a NEW '+$('#kKind').selectedOptions[0].textContent.toLowerCase()+' key? Boxes only trust the key their firmware was built with.'))act('tool_key',{kind:$('#kKind').value,mode:'create',replace:$('#kReplace').checked})};
$('#btnCoin').onclick=()=>act('coin_test');$('#btnTg').onclick=()=>act('telegram',{token:$('#tgToken').value});$('#btnNext').onclick=()=>act('next_unit');
$('#btnBack').onclick=()=>act('back');$('#btnNextStep').onclick=()=>act('next');
$('#btnDeps').onclick=()=>act('install_deps');$('#btnRecheck').onclick=()=>act('recheck');$('#btnSearch').onclick=()=>act('search');
poll();
</script></body></html>
"""


# ---- start ---------------------------------------------------------------------------------------------------------------

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
        if not (args.cli or args.update or args.yes or args.router):
            return run_web(args)
    except SetupError as e:
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
