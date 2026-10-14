#!/usr/bin/env python3
"""Tests for the PisoWiFi poster maker. Run: python3 scripts/tests/test_make_wifi_poster.py

The payload strings are checked in plain Python (they are what a phone reads out of the codes). When an image decoder (zxing-cpp) is
installed the generated PNG is decoded too and must hold exactly those two strings."""

import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)

try:
    import qrcode  # noqa: F401
    from PIL import Image
except ImportError:
    print("skipped: qrcode and pillow are not installed (pip install qrcode pillow)")
    sys.exit(0)

import make_wifi_poster as mp  # noqa: E402

failures = 0


def check(cond, what):
    global failures
    if not cond:
        failures += 1
        print("FAIL:", what)


# ---- the Wi-Fi code text (WPA/Wi-Fi QR format of the Wi-Fi Alliance / ZXing) -----------------------------------------------------
check(mp.wifi_payload("PisoWiFi") == "WIFI:T:nopass;S:PisoWiFi;;", "an open network: no password, no P: field")
check(mp.wifi_payload("Rowel's Cafe") == "WIFI:T:nopass;S:Rowel's Cafe;;", "spaces and apostrophes are kept as they are")
check(mp.wifi_payload('a;b,c:d\\e"f') == 'WIFI:T:nopass;S:a\\;b\\,c\\:d\\\\e\\"f;;', "; , : \\ and \" are escaped with a backslash: " + mp.wifi_payload('a;b,c:d\\e"f'))
check(mp.wifi_payload("1234ABCD") == 'WIFI:T:nopass;S:"1234ABCD";;', "a name of hex digits only is quoted so it is not read as hex")
check(mp.wifi_payload("Tindahan WiFi").startswith("WIFI:T:nopass;S:") and mp.wifi_payload("Tindahan WiFi").endswith(";;"), "starts and ends as the format wants")
for bad in ("", "x" * 33, "a\nb"):
    try:
        mp.wifi_payload(bad)
        check(False, f"a bad Wi-Fi name is refused: {bad!r}")
    except ValueError:
        pass
check(mp.check_url("http://10.0.30.1:2080/") == "http://10.0.30.1:2080/" and mp.DEFAULT_PORTAL_URL == "http://10.0.30.1:2080/", "the default address is the coin page on the guest network")
for bad in ("", "10.0.30.1", "ftp://x/", "http://a b/", "javascript:alert(1)"):
    try:
        mp.check_url(bad)
        check(False, f"a bad address is refused: {bad!r}")
    except ValueError:
        pass

# ---- the poster itself --------------------------------------------------------------------------------------------------------------
img = mp.build_poster("Tindahan WiFi", "http://10.0.30.1:2080/", "Tindahan ni Rowel")
check(img.size == (2480, 3508), f"A4 at 300 dpi: {img.size}")
check(img.getpixel((10, 10))[:3] == mp.NAVY and img.getpixel((10, 3450))[:3] == mp.NAVY, "a navy band at the top and the foot")

decoder = None
try:
    import zxingcpp as decoder  # noqa: F401
except ImportError:
    pass
if decoder:
    got = sorted(r.text for r in decoder.read_barcodes(img))
    want = sorted([mp.wifi_payload("Tindahan WiFi"), "http://10.0.30.1:2080/"])
    check(got == want, f"the poster holds exactly the two codes (Wi-Fi join, portal address): {got}")
else:
    print("note: zxing-cpp is not installed: the payload texts were checked, the picture was not decoded")

with tempfile.TemporaryDirectory() as tmp:
    out = os.path.join(tmp, "poster")
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "make_wifi_poster.py"), "--ssid", 'Rowel; "Net"', "--portal-url", "http://10.0.31.1:2080/",
                        "--shop-name", "Rowel's", "--out", out], capture_output=True, text=True)
    check(r.returncode == 0, "the script runs: " + r.stdout + r.stderr)
    png, pdf = os.path.join(out, "pisowifi_poster.png"), os.path.join(out, "pisowifi_poster.pdf")
    check(os.path.exists(png) and os.path.exists(pdf), "it writes a PNG and a PDF")
    if os.path.exists(png):
        check(Image.open(png).size == (2480, 3508), "the PNG is A4 at 300 dpi")
        if decoder:
            got = sorted(x.text for x in decoder.read_barcodes(Image.open(png)))
            check(got == sorted([mp.wifi_payload('Rowel; "Net"'), "http://10.0.31.1:2080/"]), f"a name with special characters and another address: {got}")
    if os.path.exists(pdf):
        data = open(pdf, "rb").read()
        check(data.startswith(b"%PDF") and data.count(b"/Type /Page") - data.count(b"/Type /Pages") == 1, "the PDF is one page")
        check(b"/MediaBox [ 0 0 595" in data or b"/MediaBox [0 0 595" in data, "and A4 sized (595 x 842 points)")
    # refusals: inside the repository, a bad name, a bad address, a missing logo
    leak = os.path.join(os.path.dirname(SCRIPTS), "poster_leak")
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "make_wifi_poster.py"), "--out", leak], capture_output=True, text=True)
    check(r.returncode != 0 and not os.path.exists(leak), "output inside the repository is refused")
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "make_wifi_poster.py"), "--ssid", "x" * 40, "--out", os.path.join(tmp, "a")], capture_output=True, text=True)
    check(r.returncode != 0 and not os.path.exists(os.path.join(tmp, "a")), "a Wi-Fi name over 32 bytes is refused before anything is written")
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "make_wifi_poster.py"), "--portal-url", "nonsense", "--out", os.path.join(tmp, "b")], capture_output=True, text=True)
    check(r.returncode != 0, "a bad address is refused")
    r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "make_wifi_poster.py"), "--logo", os.path.join(tmp, "none.png"), "--out", os.path.join(tmp, "c")], capture_output=True, text=True)
    check(r.returncode != 0, "a missing logo is refused")

print("poster:", "OK" if not failures else f"{failures} problem(s)")
sys.exit(1 if failures else 0)
