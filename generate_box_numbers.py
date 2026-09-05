#!/usr/bin/env python3
"""
PisoPhone ₱5,000 Coin Slot Box - 12-Character Build Number & A4 Print Generator
=============================================================================
Generates 12-character (XXXX-YYYY-ZZZZ) cryptographic hardware build numbers
for physical label printing, box manufacturing, and Cloudflare verification.

Features:
  - Cryptographic HMAC-SHA256 signature using the master hardware secret
  - 1 in 1.1 Trillion collision resistance per sequence ID
  - Outputs print-ready A4 HTML sheets with cut guides, serials, and badges
  - Zero external Python dependencies (pure Python standard library)

Usage:
  python generate_box_numbers.py                          # Generates 10 labels & box_labels_a4.html
  python generate_box_numbers.py -n 20 --html labels.html # Generates 2 pages of A4 labels
  python generate_box_numbers.py -n 50 --csv boxes.csv    # Export CSV + A4 HTML
"""

import sys
import os
import argparse
import secrets
import hashlib
import hmac
import json
import csv
import time
from typing import List, Dict, Optional

# Default master hardware signing secret
DEFAULT_BOX_SECRET = "c8f94d21e8b7a35e91264c0fd75b8a6e43198e2db90c74af1862d5e30ca57b49"

# Unambiguous character set (32 chars): No '0', 'O', '1', 'I', 'L'
SAFE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

def generate_hmac_signature(secret: str, box_id: str, sig_length: int = 8) -> str:
    """Computes HMAC-SHA256 signature for a Box ID mapped to safe Base32 alphabet."""
    msg = f"PISOBOX:{box_id}".encode("utf-8")
    h = hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).digest()
    return "".join(SAFE_ALPHABET[b % len(SAFE_ALPHABET)] for b in h[:sig_length])

def generate_signed_code(secret: str, sequence_id: int) -> str:
    """
    Generates a 12-character cryptographic build number (XXXX-YYYY-ZZZZ):
      - XXXX: 4-character Base32 Sequence ID
      - YYYY-ZZZZ: 8-character HMAC Checksum derived from Secret + XXXX
    """
    base = len(SAFE_ALPHABET)
    chars = []
    n = sequence_id
    for _ in range(4):
        chars.append(SAFE_ALPHABET[n % base])
        n //= base
    part1 = "".join(reversed(chars))
    sig = generate_hmac_signature(secret, part1, sig_length=8)
    return f"{part1}-{sig[:4]}-{sig[4:8]}"

def verify_code(secret: str, code: str) -> bool:
    """Validates a 12-character build number using the secret key."""
    parts = code.strip().upper().split("-")
    if len(parts) < 2 or len(parts[0]) != 4:
        return False
    box_id = parts[0]
    provided_sig = "".join(parts[1:])
    expected_sig = generate_hmac_signature(secret, box_id, sig_length=len(provided_sig))
    return hmac.compare_digest(provided_sig, expected_sig)

def generate_batch(count: int, secret: str = DEFAULT_BOX_SECRET, start_seq: int = 1) -> List[Dict]:
    """Generates a batch of unique 12-character build numbers with metadata."""
    results = []
    seen = set()
    
    seq = start_seq
    while len(results) < count:
        code = generate_signed_code(secret, seq)
        seq += 1
            
        if code in seen:
            continue
        seen.add(code)
        
        item = {
            "index": len(results) + 1,
            "sequenceId": seq - 1,
            "buildNumber": code,
            "maxDevices": 12,
            "linkedDevices": [],
            "pricePhp": 5000,
            "firstClaimedBy": None,
            "createdAt": int(time.time() * 1000),
            "dateFormatted": time.strftime("%Y-%m-%d")
        }
        results.append(item)
        
    return results

def save_to_csv(batch: List[Dict], filepath: str):
    """Saves generated build numbers to CSV for thermal label printers or record keeping."""
    with open(filepath, mode="w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["Index", "BuildNumber", "HardwareModel", "DeviceCapacity", "PricePhp", "LabelText"])
        for idx, item in enumerate(batch, 1):
            b_num = item["buildNumber"]
            writer.writerow([
                idx,
                b_num,
                "PisoPhone Coin Slot Box (12-Device)",
                item["maxDevices"],
                item["pricePhp"],
                f"PISOPHONE BOX #{b_num} [12-DEV]"
            ])
    print(f" Saved {len(batch)} records to CSV: {filepath}")

def save_to_cloudflare_kv_json(batch: List[Dict], filepath: str, key_prefix: str = "BOX:"):
    """
    Saves batch in Cloudflare KV bulk put format:
    [ { "key": "BOX:XXXX-YYYY-ZZZZ", "value": "{...}" } ]
    """
    kv_payload = []
    for item in batch:
        kv_payload.append({
            "key": f"{key_prefix}{item['buildNumber']}",
            "value": json.dumps(item)
        })
        
    with open(filepath, mode="w", encoding="utf-8") as f:
        json.dump(kv_payload, f, indent=2)
    print(f" Saved Cloudflare KV bulk JSON: {filepath} (Ready for 'wrangler kv bulk put')")

def generate_a4_html(batch: List[Dict], filepath: str, labels_per_page: int = 10):
    """
    Generates a print-ready HTML file designed specifically for A4 paper (210mm x 297mm).
    Layout: 2 columns x 5 rows = 10 high-quality physical labels per A4 page.
    Includes cut marks, official branding, 12-device license details, and barcode pattern.
    """
    pages = []
    for i in range(0, len(batch), labels_per_page):
        pages.append(batch[i:i + labels_per_page])

    html_content = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PisoPhone Coin Slot Box Labels - A4 Print Sheet</title>
    <style>
        /* A4 Page Print Setup */
        @page {{
            size: A4 portrait;
            margin: 8mm 8mm 8mm 8mm;
        }}
        
        * {{
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            -webkit-print-color-adjust: exact !important;
            print-color-adjust: exact !important;
        }}

        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            background-color: #f1f5f9;
            color: #0f172a;
            line-height: 1.2;
        }}

        /* Non-Printable Web Toolbar */
        .no-print {{
            background: #0f172a;
            color: white;
            padding: 16px 24px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            position: sticky;
            top: 0;
            z-index: 1000;
            box-shadow: 0 4px 12px rgba(0,0,0,0.15);
        }}
        .no-print h1 {{
            font-size: 16px;
            font-weight: 700;
            display: flex;
            align-items: center;
            gap: 10px;
        }}
        .no-print .badge {{
            background: #10b981;
            color: #064e3b;
            font-size: 11px;
            font-weight: 800;
            padding: 2px 8px;
            border-radius: 4px;
        }}
        .print-btn {{
            background: #10b981;
            color: #022c22;
            border: none;
            padding: 10px 20px;
            border-radius: 8px;
            font-weight: 800;
            font-size: 14px;
            cursor: pointer;
            box-shadow: 0 2px 8px rgba(16, 185, 129, 0.4);
            display: inline-flex;
            align-items: center;
            gap: 8px;
            transition: transform 0.1s, background 0.1s;
        }}
        .print-btn:hover {{
            background: #34d399;
            transform: translateY(-1px);
        }}
        .print-btn:active {{
            transform: translateY(0);
        }}

        /* Print Container & Sheets */
        .print-canvas {{
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 20px 0;
            gap: 20px;
        }}

        .a4-page {{
            width: 194mm;
            min-height: 281mm;
            max-height: 281mm;
            background: white;
            padding: 4mm;
            box-shadow: 0 4px 20px rgba(0,0,0,0.08);
            border-radius: 2px;
            display: grid;
            grid-template-columns: repeat(2, 1fr);
            grid-template-rows: repeat(5, 52mm);
            gap: 3mm;
            page-break-after: always;
            position: relative;
        }}

        .a4-page:last-child {{
            page-break-after: auto;
        }}

        /* Individual Box Sticker Card */
        .sticker-card {{
            border: 1.5px dashed #94a3b8;
            border-radius: 8px;
            padding: 7px 10px;
            background: #ffffff;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            position: relative;
            overflow: hidden;
        }}

        .sticker-card::before {{
            content: "";
            position: absolute;
            left: 0;
            top: 0;
            bottom: 0;
            width: 4px;
            background: #10b981;
        }}

        /* Header */
        .card-header {{
            display: flex;
            align-items: center;
            justify-content: space-between;
            border-bottom: 1px solid #e2e8f0;
            padding-bottom: 4px;
        }}
        .brand-title {{
            font-size: 11px;
            font-weight: 900;
            color: #0f172a;
            letter-spacing: 0.5px;
            text-transform: uppercase;
            display: flex;
            align-items: center;
            gap: 4px;
        }}
        .brand-title span {{
            color: #059669;
        }}
        .price-badge {{
            background: #047857;
            color: #ffffff;
            font-size: 9px;
            font-weight: 800;
            padding: 1px 6px;
            border-radius: 4px;
            letter-spacing: 0.2px;
        }}

        /* Main Build Number Section */
        .card-body {{
            text-align: center;
            padding: 3px 0;
        }}
        .label-hint {{
            font-size: 7.5px;
            font-weight: 700;
            color: #64748b;
            text-transform: uppercase;
            letter-spacing: 0.8px;
            margin-bottom: 2px;
        }}
        .build-number-box {{
            background: #0f172a;
            color: #34d399;
            font-family: "SF Mono", "Consolas", "Courier New", monospace;
            font-size: 15px;
            font-weight: 900;
            letter-spacing: 1.8px;
            padding: 4px 6px;
            border-radius: 6px;
            display: inline-block;
            width: 96%;
            box-shadow: inset 0 1px 3px rgba(0,0,0,0.5);
            border: 1px solid #1e293b;
        }}

        /* Subtext / Capacity Specs */
        .specs-row {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 8px;
            color: #334155;
            font-weight: 600;
            padding: 0 4px;
        }}
        .quota-highlight {{
            color: #047857;
            font-weight: 800;
        }}

        /* Barcode / Authenticity Strip */
        .barcode-strip {{
            display: flex;
            align-items: center;
            justify-content: space-between;
            background: #f8fafc;
            border: 1px solid #e2e8f0;
            border-radius: 4px;
            padding: 2px 6px;
            margin-top: 2px;
        }}
        .barcode-visual {{
            display: flex;
            height: 12px;
            gap: 1.5px;
            align-items: flex-end;
        }}
        .bar {{
            background: #0f172a;
            width: 1.5px;
        }}
        .bar.w-1 {{ width: 1px; }}
        .bar.w-2 {{ width: 2.5px; }}
        .bar.w-3 {{ width: 3.5px; }}
        .bar.h-1 {{ height: 8px; }}
        .bar.h-2 {{ height: 11px; }}
        .bar.h-3 {{ height: 13px; }}

        .auth-seal {{
            font-size: 7px;
            font-weight: 800;
            color: #059669;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            display: flex;
            align-items: center;
            gap: 3px;
        }}

        /* Cut Guide Marks */
        .cut-mark {{
            position: absolute;
            font-size: 8px;
            color: #cbd5e1;
            user-select: none;
        }}

        /* Print Media Query Override */
        @media print {{
            body {{
                background: transparent;
            }}
            .no-print {{
                display: none !important;
            }}
            .print-canvas {{
                padding: 0;
                gap: 0;
            }}
            .a4-page {{
                box-shadow: none;
                border-radius: 0;
                padding: 0;
                width: 100%;
                min-height: 100%;
                max-height: 100%;
            }}
        }}
    </style>
</head>
<body>

    <!-- Non-Printable Header Toolbar -->
    <div class="no-print">
        <div>
            <h1>
                <span>PisoPhone ₱5,000 Coin Slot Box</span>
                <span class="badge">A4 Print Sheet ({len(batch)} Labels)</span>
            </h1>
            <p style="font-size: 12px; color: #94a3b8; margin-top: 3px;">
                12-Character Cryptographic Build Numbers • Standard A4 (2x5 Grid • 10 labels/page)
            </p>
        </div>
        <button class="print-btn" onclick="window.print()">
            <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4H7v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"></path>
            </svg>
            <span>Print A4 Sheets</span>
        </button>
    </div>

    <!-- Print Canvas with A4 Pages -->
    <div class="print-canvas">
"""

    for page_idx, page_items in enumerate(pages, 1):
        html_content += f"""
        <!-- Page {page_idx} of {len(pages)} -->
        <div class="a4-page">
"""
        for item in page_items:
            b_num = item["buildNumber"]
            seq = item["sequenceId"]
            html_content += f"""
            <div class="sticker-card">
                <div class="card-header">
                    <div class="brand-title">
                        <span>PISOPHONE</span> COIN SLOT BOX
                    </div>
                    <div class="price-badge">₱5,000 HARDWARE</div>
                </div>

                <div class="card-body">
                    <div class="label-hint">HARDWARE BUILD NUMBER / SERIAL</div>
                    <div class="build-number-box">{b_num}</div>
                </div>

                <div class="specs-row">
                    <div>Cap: <span class="quota-highlight">12 Kiosk Devices</span></div>
                    <div>Unit #{seq:04d}</div>
                    <div>Type: <span class="quota-highlight">V2 ESP32-C3</span></div>
                </div>

                <div class="barcode-strip">
                    <div class="barcode-visual">
                        <div class="bar w-1 h-3"></div><div class="bar w-2 h-2"></div>
                        <div class="bar w-1 h-1"></div><div class="bar w-3 h-3"></div>
                        <div class="bar w-1 h-2"></div><div class="bar w-2 h-3"></div>
                        <div class="bar w-1 h-1"></div><div class="bar w-2 h-2"></div>
                        <div class="bar w-3 h-3"></div><div class="bar w-1 h-2"></div>
                        <div class="bar w-2 h-1"></div><div class="bar w-1 h-3"></div>
                        <div class="bar w-2 h-2"></div><div class="bar w-3 h-3"></div>
                        <div class="bar w-1 h-1"></div><div class="bar w-2 h-3"></div>
                    </div>
                    <div class="auth-seal">
                        <svg width="10" height="10" fill="currentColor" viewBox="0 0 20 20">
                            <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
                        </svg>
                        <span>GENUINE HARDWARE</span>
                    </div>
                </div>
            </div>
"""
        html_content += """
        </div>
"""

    html_content += """
    </div>
</body>
</html>
"""

    with open(filepath, mode="w", encoding="utf-8") as f:
        f.write(html_content)
    print(f" Generated A4 Print Sheet: {filepath} ({len(batch)} labels across {len(pages)} A4 page(s))")

def print_summary_table(batch: List[Dict]):
    """Displays terminal table of generated build numbers."""
    print("\n" + "=" * 66)
    print("  PISOPHONE ₱5,000 COIN SLOT BOX - 12-CHARACTER BUILD NUMBERS")
    print("=" * 66)
    print(f" {'#':<4} | {'BUILD NUMBER':<16} | {'CAPACITY':<10} | {'PRICE':<8} | {'STATUS'}")
    print("-" * 66)
    for idx, item in enumerate(batch, 1):
        print(f" {idx:<4} | {item['buildNumber']:<16} | {item['maxDevices']} devices | ₱{item['pricePhp']:<6} | Authorized")
    print("=" * 66)
    print(f" Total Generated: {len(batch)} units (12 device capacity each)")
    print("=" * 66 + "\n")

def main():
    parser = argparse.ArgumentParser(
        description="Generate 12-character (XXXX-YYYY-ZZZZ) build numbers & A4 print sheets for PisoPhone Coin Slot Boxes."
    )
    parser.add_argument("-n", "--count", type=int, default=10, help="Number of build numbers to generate (default: 10 = 1 A4 sheet)")
    parser.add_argument("--html", type=str, default="box_labels_a4.html", help="HTML file path for A4 printing (default: box_labels_a4.html)")
    parser.add_argument("--csv", type=str, default=None, help="Export to CSV file path for sticker printing")
    parser.add_argument("--kv-json", type=str, default=None, help="Export to Cloudflare KV bulk JSON file")
    parser.add_argument("--secret", type=str, default=DEFAULT_BOX_SECRET, help="HMAC secret for deterministic signed generation")
    parser.add_argument("--start-seq", type=int, default=1, help="Starting sequence number for signed generation (default: 1)")

    args = parser.parse_args()

    batch = generate_batch(count=args.count, secret=args.secret, start_seq=args.start_seq)
    print_summary_table(batch)

    if args.html:
        generate_a4_html(batch, args.html)

    if args.csv:
        save_to_csv(batch, args.csv)

    if args.kv_json:
        save_to_cloudflare_kv_json(batch, args.kv_json)

if __name__ == "__main__":
    main()
