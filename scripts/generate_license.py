#!/usr/bin/env python3
"""
PisoPhone Lifetime License Key Generator
Generates slot expansion license keys for ESP32 Coin Slot Boxes.
Compatible with Python 3, Pydroid 3 (Android), Windows, macOS, and Linux.

Usage:
  GUI Mode (default):
    python generate_license.py

  CLI Mode:
    python generate_license.py --code "PISO-AABBCCDDEEFF-1-10-8F3A" --slots 5
    python generate_license.py --mac "AA:BB:CC:DD:EE:FF" --slots 5
"""

import sys
import hmac
import hashlib
import argparse

DEFAULT_SECRET = "PISOPHONE_HMAC_MASTER_KEY"
MAX_SUPPORTED_SLOTS = 6

def format_mac(mac: str) -> str:
    cleaned = "".join(c for c in mac.upper() if c in "0123456789ABCDEF")
    if len(cleaned) == 12:
        return ":".join(cleaned[i:i+2] for i in range(0, 12, 2))
    return mac.strip().upper()

def clean_mac_str(mac: str) -> str:
    cleaned = "".join(c for c in mac.upper() if c in "0123456789ABCDEF")
    if len(cleaned) == 12:
        return cleaned
    return mac.strip().upper().replace(":", "")

def parse_box_request_code(code_str: str, secret: str = DEFAULT_SECRET):
    """
    Parses Box Request Code: PISO-<MAC>-<CURRENT_SLOTS>-<MAX_SLOTS>-<CHECKSUM>
    Returns tuple: (mac_formatted, current_slots, max_slots, is_valid_sig, error_msg)
    """
    cleaned = code_str.strip().upper()
    parts = cleaned.split('-')
    
    if len(parts) >= 4 and parts[0] in ("PISO", "PISOBX", "BOX"):
        mac_raw = parts[1]
        clean_mac = "".join(c for c in mac_raw if c in "0123456789ABCDEF")
        if len(clean_mac) != 12:
            return None, 0, 0, False, "Invalid MAC length in Request Code"

        try:
            current_slots = int(parts[2])
            max_slots = int(parts[3])
        except ValueError:
            return None, 0, 0, False, "Invalid numbers in Request Code"

        checksum = parts[4] if len(parts) >= 5 else ""
        valid_sig = True

        if checksum:
            payload = f"BOXREQ:{clean_mac}:{current_slots}:{max_slots}"
            expected_sig = hmac.new(
                secret.encode('utf-8'),
                payload.encode('utf-8'),
                hashlib.sha256
            ).hexdigest().upper()[:4]
            valid_sig = (checksum == expected_sig)

        mac_formatted = ":".join(clean_mac[i:i+2] for i in range(0, 12, 2))
        return mac_formatted, current_slots, max_slots, valid_sig, None

    # Fallback: Plain MAC
    clean_mac = "".join(c for c in cleaned if c in "0123456789ABCDEF")
    if len(clean_mac) == 12:
        mac_formatted = ":".join(clean_mac[i:i+2] for i in range(0, 12, 2))
        return mac_formatted, 1, MAX_SUPPORTED_SLOTS, True, None

    return None, 0, 0, False, "Unrecognized Request Code or MAC format"

def generate_slot_token(mac: str, slots: int, secret: str = DEFAULT_SECRET):
    mac_clean = clean_mac_str(mac)
    payload = f"PISOSLOT:{mac_clean}:{slots}"
    
    signature = hmac.new(
        secret.encode('utf-8'),
        payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest().upper()
    
    short_key = signature[:8]
    full_token = f"PISOSLOT.{mac_clean}.{slots}.{short_key}"
    return short_key, full_token

def run_gui():
    try:
        import tkinter as tk
        from tkinter import ttk, messagebox
    except ImportError:
        print("Error: Tkinter is required for GUI mode. Running in CLI mode instead.")
        print("Install tkinter or pass --mac and --slots arguments.")
        return

    root = tk.Tk()
    root.title("PisoPhone License Generator")
    root.geometry("480x640")
    root.resizable(True, True)

    # Style
    style = ttk.Style()
    try:
        style.theme_use('clam')
    except Exception:
        pass

    bg_dark = "#0f172a"
    card_bg = "#1e293b"
    fg_white = "#f8fafc"
    accent_emerald = "#10b981"
    accent_hover = "#059669"

    root.configure(bg=bg_dark)

    # Header
    header_frame = tk.Frame(root, bg=bg_dark, pady=15)
    header_frame.pack(fill="x")

    lbl_title = tk.Label(
        header_frame,
        text="₱ PisoPhone License Generator",
        font=("Helvetica", 16, "bold"),
        bg=bg_dark,
        fg=accent_emerald
    )
    lbl_title.pack()

    lbl_sub = tk.Label(
        header_frame,
        text="ESP32 Coin Slot Capacity & Request Code Inspector",
        font=("Helvetica", 9),
        bg=bg_dark,
        fg="#94a3b8"
    )
    lbl_sub.pack()

    # Main Card
    card = tk.Frame(root, bg=card_bg, padx=20, pady=20, bd=1, relief="solid")
    card.pack(fill="both", expand=True, padx=15, pady=10)

    # Box Request Code / MAC Field
    lbl_mac = tk.Label(card, text="Box Request Code or MAC Address:", font=("Helvetica", 10, "bold"), bg=card_bg, fg=fg_white, anchor="w")
    lbl_mac.pack(fill="x", pady=(0, 2))

    ent_code = tk.Entry(card, font=("Courier", 10, "bold"), bg="#0f172a", fg="#34d399", insertbackground="white", bd=1, relief="solid")
    ent_code.pack(fill="x", ipady=6, pady=(0, 6))
    ent_code.insert(0, "PISO-AABBCCDDEEFF-1-6-E183")

    # Machine Details Badge Box
    frame_info = tk.Frame(card, bg="#0f172a", padx=10, pady=8, bd=1, relief="solid")
    frame_info.pack(fill="x", pady=(0, 12))

    lbl_info_mac = tk.Label(frame_info, text="📟 MAC: --:--:--:--:--:--", font=("Courier", 9, "bold"), bg="#0f172a", fg="#60a5fa", anchor="w")
    lbl_info_mac.pack(fill="x")

    lbl_info_slots = tk.Label(frame_info, text="⚡ Active Seats: 1  |  🔒 Max Limit: 6 Seats", font=("Helvetica", 9), bg="#0f172a", fg="#94a3b8", anchor="w")
    lbl_info_slots.pack(fill="x", pady=(2, 0))

    # Detected Max Limit storage
    detected_max_slots = [MAX_SUPPORTED_SLOTS]
    detected_cur_slots = [1]
    parsed_mac_addr = ["AA:BB:CC:DD:EE:FF"]

    def update_parsed_info(*args):
        raw_val = ent_code.get().strip()
        sec_raw = ent_secret.get().strip() or DEFAULT_SECRET
        mac, cur_s, max_s, valid_sig, err = parse_box_request_code(raw_val, sec_raw)
        
        if mac:
            parsed_mac_addr[0] = mac
            detected_cur_slots[0] = cur_s if cur_s > 0 else 1
            detected_max_slots[0] = max_s if max_s > 0 else MAX_SUPPORTED_SLOTS
            
            lbl_info_mac.config(text=f"📟 MAC: {mac}", fg="#60a5fa")
            sig_text = "✓ Valid Code Signature" if valid_sig else "⚠️ Unverified Signature"
            lbl_info_slots.config(
                text=f"⚡ Active Seats: {detected_cur_slots[0]}  |  🔒 Max Limit: {detected_max_slots[0]} Seats  ({sig_text})",
                fg="#10b981" if valid_sig else "#f59e0b"
            )
        else:
            lbl_info_mac.config(text="📟 Enter Box Request Code or MAC", fg="#ef4444")
            lbl_info_slots.config(text="Paste code from ESP32 Dashboard (e.g. PISO-AABBCCDDEEFF-1-6-E183)", fg="#94a3b8")

    ent_code.bind("<KeyRelease>", update_parsed_info)

    # Slots Field
    lbl_slots = tk.Label(card, text="Target Total Capacity (Seats):", font=("Helvetica", 10, "bold"), bg=card_bg, fg=fg_white, anchor="w")
    lbl_slots.pack(fill="x", pady=(0, 2))

    slot_options = ["1 (Single)", "2 (Dual)", "3 (Triple)", "4 (Quad)", "5 (Penta)", "6 (Max Hardware)"]
    var_slots = tk.StringVar(value="3 (Triple)")
    opt_slots = ttk.Combobox(card, textvariable=var_slots, values=slot_options, state="readonly", font=("Helvetica", 10))
    opt_slots.pack(fill="x", ipady=4, pady=(0, 12))

    # Secret Key Field
    lbl_secret = tk.Label(card, text="HMAC Secret Key:", font=("Helvetica", 10, "bold"), bg=card_bg, fg=fg_white, anchor="w")
    lbl_secret.pack(fill="x", pady=(0, 2))

    ent_secret = tk.Entry(card, font=("Courier", 9), bg="#0f172a", fg="#cbd5e1", insertbackground="white", bd=1, relief="solid")
    ent_secret.pack(fill="x", ipady=4, pady=(0, 15))
    ent_secret.insert(0, DEFAULT_SECRET)
    ent_secret.bind("<KeyRelease>", update_parsed_info)

    # Output Frame
    lbl_key_title = tk.Label(card, text="8-Character License Key:", font=("Helvetica", 10, "bold"), bg=card_bg, fg=accent_emerald, anchor="w")
    lbl_key_title.pack(fill="x", pady=(0, 2))

    out_key_var = tk.StringVar(value="--------")
    lbl_key_val = tk.Label(
        card,
        textvariable=out_key_var,
        font=("Courier", 18, "bold"),
        bg="#0f172a",
        fg="#34d399",
        bd=1,
        relief="solid",
        pady=8
    )
    lbl_key_val.pack(fill="x", pady=(0, 10))

    # Copy Button
    def copy_key():
        key = out_key_var.get()
        if key and key != "--------":
            root.clipboard_clear()
            root.clipboard_append(key)
            root.update()
            btn_copy.config(text="✓ Copied to Clipboard!")
            root.after(1500, lambda: btn_copy.config(text="📋 Copy License Key"))

    btn_copy = tk.Button(
        card,
        text="📋 Copy License Key",
        font=("Helvetica", 9, "bold"),
        bg="#334155",
        fg="white",
        activebackground="#475569",
        activeforeground="white",
        bd=0,
        pady=5,
        command=copy_key
    )
    btn_copy.pack(fill="x", pady=(0, 15))

    # Generate Handler
    def on_generate():
        update_parsed_info()
        raw_code = ent_code.get().strip()
        sec_raw = ent_secret.get().strip() or DEFAULT_SECRET
        slot_str = var_slots.get().split()[0]
        
        try:
            target_slots = int(slot_str)
        except ValueError:
            target_slots = 2

        mac, cur_s, max_s, valid_sig, err = parse_box_request_code(raw_code, sec_raw)

        if not mac:
            messagebox.showerror("Invalid Input", "Please enter a valid Box Request Code or 12-character MAC address.")
            return

        # CAPACITY LIMIT WARNING
        if target_slots > max_s:
            proceed = messagebox.askyesno(
                "⚠️ Capacity Limit Exceeded",
                f"Warning: Target capacity ({target_slots} slots) exceeds this machine's maximum supported limit ({max_s} slots).\n\n"
                f"The ESP32 firmware will clamp this license to {max_s} slots maximum.\n\nDo you still want to generate the key?"
            )
            if not proceed:
                return

        if target_slots <= cur_s:
            messagebox.showinfo(
                "ℹ️ Capacity Notice",
                f"Notice: This box already has {cur_s} active seat(s).\nGenerating key for target {target_slots} seat(s)."
            )

        short_k, full_t = generate_slot_token(mac, target_slots, sec_raw)
        out_key_var.set(short_k)
        btn_copy.config(text="📋 Copy License Key")

    # Generate Button
    btn_gen = tk.Button(
        card,
        text="⚡ GENERATE LICENSE KEY",
        font=("Helvetica", 11, "bold"),
        bg=accent_emerald,
        fg="#0f172a",
        activebackground=accent_hover,
        activeforeground="white",
        bd=0,
        pady=10,
        cursor="hand2",
        command=on_generate
    )
    btn_gen.pack(fill="x", pady=(5, 0))

    # Initial trigger
    update_parsed_info()
    on_generate()

    root.mainloop()

def main():
    if len(sys.argv) > 1:
        parser = argparse.ArgumentParser(description="PisoPhone Slot License Key Generator")
        parser.add_argument("--code", "--mac", dest="code", help="Box Request Code (e.g., PISO-AABBCCDDEEFF-1-10-8F3A) or MAC address")
        parser.add_argument("--slots", type=int, help="Total target capacity slots (e.g., 2, 3, 5, 10)")
        parser.add_argument("--secret", default=DEFAULT_SECRET, help="Shared HMAC secret key")
        parser.add_argument("--gui", action="store_true", help="Launch GUI window mode")

        args = parser.parse_args()

        if args.gui or not args.code:
            run_gui()
            return

        mac, cur_s, max_s, valid_sig, err = parse_box_request_code(args.code, args.secret)
        if not mac:
            print(f"❌ Error: {err or 'Invalid Request Code or MAC address'}")
            sys.exit(1)

        target_slots = args.slots or 2

        print("\n" + "="*60)
        print(" ₱ PISOPHONE LIFETIME LICENSE KEY GENERATOR")
        print("="*60)
        print(f" Request Code Input : {args.code}")
        print(f" Parsed Box MAC     : {mac}")
        print(f" Current Active     : {cur_s} Seat(s)")
        print(f" Max Machine Capacity: {max_s} Seat(s)")
        print(f" Target Total Slots : {target_slots} Seat(s)")
        print(f" Signature Verified : {'YES' if valid_sig else 'UNVERIFIED'}")
        print(f" Secret Key         : {args.secret}")
        
        if target_slots > max_s:
            print(f" ⚠️ WARNING: Requested slots ({target_slots}) exceeds hardware limit ({max_s}). Firmware will clamp to {max_s}.")

        short_key, full_token = generate_slot_token(mac, target_slots, args.secret)

        print("-" * 60)
        print(f" 🔑 SHORT LICENSE KEY (8-Char): {short_key}")
        print(f" 📜 FULL TOKEN (Legacy):      {full_token}")
        print("="*60 + "\n")
    else:
        run_gui()

if __name__ == "__main__":
    main()
