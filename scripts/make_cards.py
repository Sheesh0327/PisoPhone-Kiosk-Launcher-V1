#!/usr/bin/env python3
"""
Prints a batch of QR time cards for ONE box, ready for back-to-back printing. Run it on YOUR computer.
Needs: pip install cryptography qrcode pillow

  python3 scripts/make_cards.py --box AA:BB:CC:DD:EE:FF --count 48
  python3 scripts/make_cards.py --box AA:BB:CC:DD:EE:FF --count 24 --hours 5 --key /path/key.pem --out /path/cards

  --box    the box's MAC address, as shown on its dashboard (the cards only work on that box)
  --count  how many cards to make
  --hours  the starter time the first scan adds (default 3)
  --key    the card key from make_card_key.py (default ~/pisophone_card_key.pem)
  --out    where cards and the ledger go (default ~/pisophone_cards; never inside the repository)
  --logo   the picture for the back of the cards (default: the PisoPhone logo from the app)
  --flip   how you turn the paper over: long (default, like turning a page) or short edge
  --back-shift-x-mm / --back-shift-y-mm   nudge the backs if your printer prints them slightly off (see below)

Each batch folder holds A4 sheets of 12 cards (3 x 4):
  fronts.pdf             the QR sides. Print this first, at 100% / "actual size" (not "fit to page").
  backs.pdf              the logo sides, in the same page order, laid out as a MIRROR IMAGE so that each logo lands exactly
                         behind its QR once you turn the paper over. Put the printed fronts back in the tray so the backs print
                         on the other side (try one sheet first to learn which way your printer wants it), then print this.
  duplex_both_sides.pdf  front, back, front, back...: for a printer with automatic two-sided printing, set to "flip on long
                         edge" (or short edge if you used --flip short).
  front_NN.png / back_NN.png   the same pages as pictures.
Then cut along the grey lines on the fronts. The backs have no lines, only a little extra colour around the edge, so a small
printing error does not leave a white sliver. If every back sits a bit to one side, print again with --back-shift-x-mm (to the
right, minus for left) or --back-shift-y-mm (down, minus for up); only the backs move.

It also keeps a ledger per box (ledger_<box>.csv) that remembers every serial ever issued, so a serial is never printed twice.
Fill in the "sold" column when you sell a card. The QR code IS the account key: keep unsold cards and these files private.
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
GAP = 40       # space between neighbouring cards (the cut lines are on the card edges)
BLEED = 12     # the back's colour extends this far past the card edge, so a small misalignment shows no white sliver
DEFAULT_LOGO = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "piso_lock_logo_poster.webp")


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


def card_rect(col, row):
    """The card at grid position (col, row) on a FRONT page: (x, y, width, height) in pixels."""
    cell_w = (PAGE_W - 2 * MARGIN) // COLS
    cell_h = (PAGE_H - 2 * MARGIN) // ROWS
    return MARGIN + col * cell_w + GAP // 2, MARGIN + row * cell_h + GAP // 2, cell_w - GAP, cell_h - GAP


def back_position(col, row, flip):
    """Where the back of the card at (col, row) goes on the back page. Turning the paper over mirrors it: along the long edge
    left becomes right, along the short edge top becomes bottom."""
    return (COLS - 1 - col, row) if flip == "long" else (col, ROWS - 1 - row)


def draw_front(draw, image, rect, text, serial, hours_label):
    import qrcode
    from qrcode.constants import ERROR_CORRECT_M
    x, y, w, h = rect
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


def logo_back(logo, w, h):
    """The back of one card: the logo centred on its own background colour, filling the card plus the bleed."""
    from PIL import Image
    logo = logo.convert("RGB")
    # The logo's own edge colour continues to the card edge, so there is no visible frame around the picture.
    edge = [logo.getpixel((x, y)) for x in range(0, logo.width, max(1, logo.width // 16)) for y in (0, logo.height - 1)]
    bg = tuple(sum(c[i] for c in edge) // len(edge) for i in range(3))
    back = Image.new("RGB", (w, h), bg)
    scale = min(w / logo.width, h / logo.height)
    size = (max(1, int(logo.width * scale)), max(1, int(logo.height * scale)))
    resized = logo.resize(size, Image.LANCZOS)
    back.paste(resized, ((w - size[0]) // 2, (h - size[1]) // 2))
    return back


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--box", required=True)
    ap.add_argument("--count", type=int, required=True)
    ap.add_argument("--hours", type=float, default=3.0)
    ap.add_argument("--key", default=DEFAULT_KEY)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--note", default="")
    ap.add_argument("--logo", default=DEFAULT_LOGO)
    ap.add_argument("--flip", choices=("long", "short"), default="long")
    ap.add_argument("--back-shift-x-mm", type=float, default=0.0)
    ap.add_argument("--back-shift-y-mm", type=float, default=0.0)
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
    if not os.path.isfile(a.logo):
        sys.exit(f"Logo not found: {a.logo} (use --logo to point at your picture)")
    try:
        logo = Image.open(a.logo)
        logo.load()
    except Exception as e:
        sys.exit(f"Cannot read the logo {a.logo}: {e}")
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
    hours_label = f"{a.hours:g}-hour"
    shift_x = int(round(a.back_shift_x_mm / 25.4 * DPI))
    shift_y = int(round(a.back_shift_y_mm / 25.4 * DPI))
    _, _, card_w, card_h = card_rect(0, 0)
    back_art = logo_back(logo, card_w + 2 * BLEED, card_h + 2 * BLEED)

    fronts, backs = [], []
    for p in range(0, len(cards), per_page):
        front = Image.new("RGB", (PAGE_W, PAGE_H), "white")
        back = Image.new("RGB", (PAGE_W, PAGE_H), "white")
        d = ImageDraw.Draw(front)
        for i, (s, text) in enumerate(cards[p:p + per_page]):
            col, row = i % COLS, i // COLS
            draw_front(d, front, card_rect(col, row), text, s, hours_label)
            bc, br = back_position(col, row, a.flip)
            bx, by, _, _ = card_rect(bc, br)
            back.paste(back_art, (bx - BLEED + shift_x, by - BLEED + shift_y))
        fronts.append(front)
        backs.append(back)

    n = len(fronts)
    for i, (front, back) in enumerate(zip(fronts, backs), start=1):
        front.save(os.path.join(folder, f"front_{i:02d}.png"), dpi=(DPI, DPI))
        back.save(os.path.join(folder, f"back_{i:02d}.png"), dpi=(DPI, DPI))
    fronts[0].save(os.path.join(folder, "fronts.pdf"), save_all=True, append_images=fronts[1:], resolution=DPI)
    backs[0].save(os.path.join(folder, "backs.pdf"), save_all=True, append_images=backs[1:], resolution=DPI)
    both = [img for pair in zip(fronts, backs) for img in pair]
    both[0].save(os.path.join(folder, "duplex_both_sides.pdf"), save_all=True, append_images=both[1:], resolution=DPI)

    print(f"Made {len(cards)} cards, serials {serials[0]} to {serials[-1]}, {hours_label} each, for box {box}.")
    print(f"Folder: {folder}")
    print(f"  1. Print fronts.pdf ({n} sheet{'s' if n > 1 else ''}) at 100% / 'actual size' on A4.")
    print(f"  2. Turn the paper over ({'long' if a.flip == 'long' else 'short'} edge, like turning a page) and print backs.pdf on the other side.")
    print("     Try one sheet first. Printer with automatic two-sided printing: print duplex_both_sides.pdf instead.")
    print("  3. Cut along the grey lines.")
    print(f"Ledger: {ledger_path}  (fill in the 'sold' column as you sell; keep a backup)")
    print("The QR codes are the accounts: keep these files private.")


if __name__ == "__main__":
    main()
