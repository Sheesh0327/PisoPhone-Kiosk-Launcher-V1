#!/usr/bin/env python3
"""
Prints a batch of QR time cards for ONE box. Run it on YOUR computer. Needs: pip install cryptography qrcode pillow

  python3 scripts/make_cards.py --box AA:BB:CC:DD:EE:FF --count 48
  python3 scripts/make_cards.py --box AA:BB:CC:DD:EE:FF --count 24 --hours 5 --key /path/key.pem --out /path/cards

  --box    the box's MAC address, as shown on its dashboard (the cards only work on that box)
  --count  how many cards to make
  --hours  the starter time the first scan adds (default 3)
  --key    the card key from make_card_key.py (default ~/pisophone_card_key.pem)
  --out    where cards and the ledger go (default ~/pisophone_cards; never inside the repository)

Writes print-ready A4 sheets (PNG, plus one PDF) of 12 cards each, and keeps a ledger per box (ledger_<box>.csv) that remembers
every serial ever issued, so a serial is never printed twice. Fill in the "sold" column when you sell a card.
The QR code IS the account key: keep unsold cards and the sheet files private.
"""

import argparse
import csv
import datetime
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import card_format  # noqa: E402

DEFAULT_KEY = os.path.join(os.path.expanduser("~"), "pisophone_card_key.pem")
DEFAULT_OUT = os.path.join(os.path.expanduser("~"), "pisophone_cards")
LEDGER_FIELDS = ["serial", "seconds", "box", "printed", "batch", "sold", "note"]

DPI = 300
PAGE_W, PAGE_H = 2480, 3508  # A4 at 300 dpi
COLS, ROWS = 3, 4
MARGIN = 100


def inside_repo(path):
    real = os.path.normcase(os.path.realpath(path))
    root = os.path.normcase(os.path.realpath(ROOT))
    return real == root or real.startswith(root + os.sep)


def read_ledger(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def next_serials(rows, count):
    used = [int(r["serial"]) for r in rows]
    if len(used) != len(set(used)):
        sys.exit("The ledger lists a serial twice; fix it by hand before printing more cards.")
    start = (max(used) if used else 0) + 1
    if start + count - 1 > card_format.MAX_SERIAL:
        sys.exit(f"Not enough serial numbers left for this box: {card_format.MAX_SERIAL - start + 1} remain, "
                 f"{count} asked for.")
    return list(range(start, start + count))


def font(size):
    from PIL import ImageFont
    for name in ("DejaVuSans-Bold.ttf", "arialbd.ttf", "Arial Bold.ttf", "LiberationSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size)
    except TypeError:
        return ImageFont.load_default()


def draw_card(draw, image, x, y, w, h, text, serial, hours_label):
    import qrcode
    from qrcode.constants import ERROR_CORRECT_M
    draw.rectangle([x, y, x + w, y + h], outline=(120, 120, 120), width=3)  # cut line
    draw.text((x + w // 2, y + 70), "PisoPhone", fill=(0, 0, 0), font=font(64), anchor="mm")
    draw.text((x + w // 2, y + 135), f"{hours_label} play card", fill=(60, 60, 60), font=font(44), anchor="mm")
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_M, box_size=1, border=4)
    qr.add_data(text)
    qr.make(fit=True)
    avail = min(w - 120, h - 330)
    # A whole number of pixels per module keeps every edge crisp, which is what a phone camera needs to decode it.
    qr.box_size = max(1, avail // (qr.modules_count + 2 * qr.border))
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    side = img.size[0]
    image.paste(img, (x + (w - side) // 2, y + 190 + (avail - side) // 2))
    side = avail
    draw.text((x + w // 2, y + 190 + side + 45), "Scan at the PisoPhone to start", fill=(0, 0, 0), font=font(34), anchor="mm")
    draw.text((x + w // 2, y + h - 40), f"No. {serial:05d}", fill=(80, 80, 80), font=font(32), anchor="mm")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--box", required=True)
    ap.add_argument("--count", type=int, required=True)
    ap.add_argument("--hours", type=float, default=3.0)
    ap.add_argument("--key", default=DEFAULT_KEY)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--note", default="")
    a = ap.parse_args()
    try:
        from cryptography.hazmat.primitives import serialization
        from PIL import Image, ImageDraw
        import qrcode  # noqa: F401
    except ImportError:
        sys.exit("This tool needs: pip install cryptography qrcode pillow")

    try:
        box = card_format.normalize_box(a.box)
    except ValueError as e:
        sys.exit(str(e))
    seconds = int(round(a.hours * 3600))
    if a.count < 1 or a.count > 5000:
        sys.exit("--count must be 1 to 5000")
    try:
        card_format.check_fields(box, 1, seconds)
    except ValueError as e:
        sys.exit(str(e))
    if inside_repo(a.out):
        sys.exit(f"Refusing: {a.out} is inside the repository. Cards are secrets; keep them out of git.")
    if not os.path.isfile(a.key):
        sys.exit(f"Card key not found: {a.key} (create it with scripts/make_card_key.py)")
    with open(a.key, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)

    os.makedirs(a.out, exist_ok=True)
    ledger_path = os.path.join(a.out, f"ledger_{box}.csv")
    serials = next_serials(read_ledger(ledger_path), a.count)
    batch = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    stamp = datetime.datetime.now().isoformat(timespec="seconds")

    cards = [(s, card_format.encode(key, box, s, seconds)) for s in serials]
    for s, text in cards:  # never print a card that the box would refuse
        assert card_format.verify(key.public_key(), text), f"self-check failed for serial {s}"

    # Reserve the serials BEFORE rendering: if anything fails below, they are simply skipped, never reused.
    new = not os.path.exists(ledger_path)
    with open(ledger_path, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=LEDGER_FIELDS)
        if new:
            w.writeheader()
        for s in serials:
            w.writerow({"serial": s, "seconds": seconds, "box": box, "printed": stamp, "batch": batch, "sold": "", "note": a.note})

    folder = os.path.join(a.out, f"batch_{batch}")
    os.makedirs(folder)
    per_page = COLS * ROWS
    cell_w = (PAGE_W - 2 * MARGIN) // COLS
    cell_h = (PAGE_H - 2 * MARGIN) // ROWS
    hours_label = f"{a.hours:g}-hour"
    pages = []
    for p in range(0, len(cards), per_page):
        page = Image.new("RGB", (PAGE_W, PAGE_H), "white")
        d = ImageDraw.Draw(page)
        for i, (s, text) in enumerate(cards[p:p + per_page]):
            x = MARGIN + (i % COLS) * cell_w
            y = MARGIN + (i // COLS) * cell_h
            draw_card(d, page, x + 10, y + 10, cell_w - 20, cell_h - 20, text, s, hours_label)
        path = os.path.join(folder, f"sheet_{len(pages) + 1:02d}.png")
        page.save(path, dpi=(DPI, DPI))
        pages.append(page)
    pages[0].save(os.path.join(folder, "all_sheets.pdf"), save_all=True, append_images=pages[1:], resolution=DPI)

    print(f"Made {len(cards)} cards, serials {serials[0]} to {serials[-1]}, {hours_label} each, for box {box}.")
    print(f"Sheets: {folder}  (print all_sheets.pdf at 100% scale on A4, then cut along the grey lines)")
    print(f"Ledger: {ledger_path}  (fill in the 'sold' column as you sell; keep a backup)")
    print("The QR codes are the accounts: keep these files private.")


if __name__ == "__main__":
    main()
