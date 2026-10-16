#!/usr/bin/env python3
"""
Makes the TechNet PisoWiFi poster: one A4 page (PNG and PDF) for the wall next to the coin box, with TWO QR codes, because one QR code
cannot both join a Wi-Fi network and open a page. Run it on YOUR computer.
Needs: pip install qrcode pillow

  python3 scripts/make_wifi_poster.py --ssid "Rowel PisoWiFi" --shop-name "Rowel's Internet Cafe"
  python3 scripts/make_wifi_poster.py --ssid PisoWiFi --portal-url http://10.0.30.1:2080/ --out ~/poster

  --ssid        the customer Wi-Fi name, exactly as on the router (default PisoWiFi; see: piso-setup summary)
  --portal-url  the address the second code opens (default http://10.0.30.1:2080/, see below)
  --shop-name   printed under the TechNet PisoWifi name in the band (optional)
  --out         where the poster goes (default ~/pisowifi_poster; never inside the repository)
  --logo        the picture in the band (default: the TechNet PisoWifi logo of the router page)

Step 1 · Join the Wi-Fi: a standard Wi-Fi code, WIFI:T:nopass;S:<name>;; . The customer Wi-Fi is open (no password) and the coin page
opens by itself after joining, so nothing more is needed. A phone camera offers "Join" for it.
Step 2 · Already connected? Check your time or enter a voucher: a web address. A QR code can hold only one of the two, which is why the
poster has both.

Which address? A phone on the customer Wi-Fi is on the guest network 10.0.30.0/24; the router's address there is 10.0.30.1 (the kiosk
LAN address 10.0.0.1 is not reachable from it) and the coin page listens on port 2080 (setup/src/00-header.sh: GUEST_IP, PORTAL_PORT).
That page shows the time left of a connected phone, the voucher entry and, for a phone that is not connected yet, the coin payment. If
you changed the guest network (piso-setup GUEST_IP=...), pass --portal-url with your own address. The poster carries a short line of
words under the code as well, so a phone that cannot scan can type the address.

Print at 100% / "actual size" on A4. The PNG is 2480 x 3508 pixels (300 dpi).
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

DEFAULT_OUT = os.path.join(os.path.expanduser("~"), "pisowifi_poster")
DEFAULT_LOGO = os.path.join(ROOT, "tools", "pisoportal", "src", "brand.png")
DEFAULT_PORTAL_URL = "http://10.0.30.1:2080/"
DEFAULT_SSID = "PisoWiFi"

DPI = 300
W, H = 2480, 3508  # A4 at 300 dpi

# the look of docs/design/GUI_SPEC_OWNER_AND_WIFI.md: navy band, brand blue for actions, gold for money, teal for "connected"
NAVY = (10, 27, 69)
BRAND = (29, 99, 214)
GOLD = (255, 184, 28)
INK = (11, 21, 48)
MUTED = (74, 88, 120)
TEAL = (22, 201, 164)
LINE = (213, 221, 236)
PAPER = (246, 248, 252)
WHITE = (255, 255, 255)


def wifi_payload(ssid):
    """The text of a standard Wi-Fi QR code for an OPEN network. The special characters \\ ; , : and " in the name are escaped with a
    backslash; a name made only of hex digits is put in double quotes so a phone does not read it as hex."""
    if not ssid or len(ssid.encode("utf-8")) > 32:
        raise ValueError("the Wi-Fi name must be 1 to 32 bytes long")
    if any(ord(c) < 32 for c in ssid):
        raise ValueError("the Wi-Fi name has a control character")
    esc = re.sub(r'([\\;,:"])', r"\\\1", ssid)
    if re.fullmatch(r"[0-9A-Fa-f]+", ssid):
        esc = f'"{esc}"'
    return f"WIFI:T:nopass;S:{esc};;"


def check_url(url):
    if not re.fullmatch(r"https?://[^\s/]+(/\S*)?", url or ""):
        raise ValueError("the portal address must look like http://10.0.30.1:2080/")
    return url


def inside_repo(path):
    real = os.path.normcase(os.path.realpath(path))
    root = os.path.normcase(os.path.realpath(ROOT))
    return real == root or real.startswith(root + os.sep)


def font(size, bold=True):
    from PIL import ImageFont
    names = ("DejaVuSans-Bold.ttf", "arialbd.ttf", "Arial Bold.ttf", "LiberationSans-Bold.ttf") if bold else \
        ("DejaVuSans.ttf", "arial.ttf", "Arial.ttf", "LiberationSans-Regular.ttf")
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size)
    except TypeError:
        return ImageFont.load_default()


def qr_image(text, side):
    """The QR code for `text` as a white square of at most `side` pixels: a whole number of pixels per module keeps every edge crisp."""
    import qrcode
    from qrcode.constants import ERROR_CORRECT_M
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, box_size=1, border=4)
    qr.add_data(text)
    qr.make(fit=True)
    qr.box_size = max(1, side // (qr.modules_count + 2 * qr.border))
    return qr.make_image(fill_color="black", back_color="white").convert("RGB")


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split():
        trial = (line + " " + word).strip()
        if draw.textlength(trial, font=fnt) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def signal_line(img, y, h):
    """The 3 px signal line of the brand (the only gradient), scaled to the page."""
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    a, b = (47, 123, 255), TEAL
    for x in range(W):
        t = x / (W - 1)
        d.line([(x, y), (x, y + h)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)))


def panel(img, d, x, y, w, step, title, payload, lines, qr_side):
    """One step: a card with a blue step chip, the title, the QR code and the plain words under it. Returns the card's bottom."""
    pad = 70
    d.rounded_rectangle([x, y, x + w, y + 1900], radius=40, fill=WHITE, outline=LINE, width=4)
    chip = font(50)
    tw = d.textlength(step, font=chip)
    d.rounded_rectangle([x + pad, y + pad, x + pad + tw + 70, y + pad + 84], radius=42, fill=BRAND)
    d.text((x + pad + 35, y + pad + 42), step, fill=WHITE, font=chip, anchor="lm")
    ty = y + pad + 130
    for t in wrap(d, title, font(66), w - 2 * pad):
        d.text((x + pad, ty), t, fill=INK, font=font(66))
        ty += 84
    qr = qr_image(payload, qr_side)
    qx = x + (w - qr.size[0]) // 2
    qy = ty + 40
    d.rectangle([qx - 6, qy - 6, qx + qr.size[0] + 6, qy + qr.size[1] + 6], outline=LINE, width=4)
    img.paste(qr, (qx, qy))
    ty = qy + qr.size[1] + 60
    body = font(48, bold=False)
    for para in lines:
        for t in wrap(d, para, body, w - 2 * pad):
            d.text((x + pad, ty), t, fill=INK, font=body)
            ty += 64
        ty += 24
    return ty


def build_poster(ssid, portal_url, shop_name="", logo_path=DEFAULT_LOGO):
    """The poster as a PIL image (A4, 300 dpi). Raises ValueError for a name or address that cannot be encoded."""
    from PIL import Image, ImageDraw
    wifi = wifi_payload(ssid)
    url = check_url(portal_url)
    img = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(img)

    # the navy band with the logo and the name
    d.rectangle([0, 0, W, 430], fill=NAVY)
    x = 120
    if logo_path:
        logo = Image.open(logo_path).convert("RGBA")
        side = 250
        scale = min(side / logo.width, side / logo.height)
        logo = logo.resize((max(1, int(logo.width * scale)), max(1, int(logo.height * scale))), Image.LANCZOS)
        img.paste(logo, (x, (430 - logo.height) // 2), logo)
        x += logo.width + 60
    d.text((x, 150), "TechNet", fill=WHITE, font=font(140))
    d.text((x + d.textlength("TechNet ", font=font(140)), 150), "PisoWifi", fill=(151, 166, 199), font=font(140, bold=False))
    if shop_name:
        d.text((x, 320), shop_name, fill=(238, 243, 255), font=font(64, bold=False))
    signal_line(img, 430, 12)

    # the headline: coins are money, so the peso badge is the one gold thing
    d.text((W // 2, 640), "Get online with a coin", fill=INK, font=font(150), anchor="mm")
    badge_r = 70
    d.ellipse([W // 2 - badge_r - 560, 800 - badge_r, W // 2 + badge_r - 560, 800 + badge_r], fill=GOLD)
    d.text((W // 2 - 560, 800), "₱", fill=INK, font=font(90), anchor="mm")
    d.text((W // 2 - 460, 800), "Insert a coin at the coin box and you are online", fill=MUTED, font=font(58, bold=False), anchor="lm")

    gap, margin = 80, 120
    pw = (W - 2 * margin - gap) // 2
    top = 1000
    qr_side = pw - 2 * 110
    panel(img, d, margin, top, pw, "Step 1", "Join the Wi-Fi", wifi,
          ["Open your phone camera, scan this code and tap Join.",
           f"Or pick \"{ssid}\" in your Wi-Fi list. There is no password.",
           "The payment page opens by itself."], qr_side)
    panel(img, d, margin + pw + gap, top, pw, "Step 2", "Already connected? Check your time or enter a voucher", url,
          ["Scan this to see how much time you have left, add more time, or type a voucher code.",
           "Not connected yet? Do step 1 first.",
           f"No scanner? Open {url} in your browser."], qr_side)

    # the footer
    d.text((W // 2, 3010), "Pay with coins, or get a voucher code to use later.", fill=INK, font=font(60), anchor="mm")
    d.text((W // 2, 3100), "A voucher code works once and is good for 30 days.", fill=MUTED, font=font(52, bold=False), anchor="mm")
    d.rectangle([0, 3300, W, H], fill=NAVY)
    d.text((W // 2, 3404), "TechNet Solutions  ·  Connect  ·  Play  ·  More Possibilities", fill=(151, 166, 199), font=font(54, bold=False), anchor="mm")
    signal_line(img, 3290, 10)
    return img


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ssid", default=DEFAULT_SSID)
    ap.add_argument("--portal-url", default=DEFAULT_PORTAL_URL)
    ap.add_argument("--shop-name", default="")
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--logo", default=DEFAULT_LOGO)
    a = ap.parse_args()
    try:
        import qrcode  # noqa: F401
        from PIL import Image  # noqa: F401
    except ImportError:
        sys.exit("This tool needs: pip install qrcode pillow")
    if inside_repo(a.out):
        sys.exit(f"Refusing: {a.out} is inside the repository. Make the poster somewhere else (your documents folder).")
    if a.logo and not os.path.isfile(a.logo):
        sys.exit(f"Logo not found: {a.logo} (use --logo to point at your picture)")
    try:
        img = build_poster(a.ssid, a.portal_url, a.shop_name, a.logo)
    except ValueError as e:
        sys.exit(str(e))
    except Exception as e:  # an unreadable picture
        sys.exit(f"Could not make the poster: {e}")
    os.makedirs(a.out, exist_ok=True)
    png, pdf = os.path.join(a.out, "pisowifi_poster.png"), os.path.join(a.out, "pisowifi_poster.pdf")
    img.save(png, dpi=(DPI, DPI))
    img.save(pdf, resolution=DPI)
    print(f"Poster made: {png}")
    print(f"             {pdf}")
    print("Print the PDF on A4 at 100% / 'actual size'. Scan both codes with a phone before you hang it up.")
    print(f"  Code 1 joins the Wi-Fi \"{a.ssid}\" (open, no password).")
    print(f"  Code 2 opens {a.portal_url}")


if __name__ == "__main__":
    main()
