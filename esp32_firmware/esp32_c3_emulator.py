#!/usr/bin/env python3
"""
=============================================================================
HARDWARE-C3 Master Kiosk Firmware Emulator (Python Edition)
Exact 1:1 Emulator for ESP32-C3 Kiosk Firmware & Web Management Portal
=============================================================================
Features:
- Port 80 (or custom e.g. 8080): HTTP Web Management Console & REST API
- Port 81 (or custom): RFC6455 Low-Latency WebSocket Server (Real-Time Push)
- Exact HTML/CSS/JS Dashboard with Theme Toggle, Live Status, 1v1 Match Mode
- Simple Beam Sensor (GPIO 4: ₱5 / 30m) & Universal Multi-Coin Pulse (₱1/₱5/₱10/₱20)
- Outbound Authenticated HTTP Handshake to Android Kiosk Terminals
- Local JSON Persistence (emulates ESP32 NVS Preferences)
- Interactive Terminal Console CLI ('coin', 'ucoin <pulses>', 'add <mins>', etc.)
=============================================================================
"""

import sys
import os
import time
import json
import math
import hmac
import hashlib
import base64
import socket
import struct
import threading
import urllib.parse
import urllib.request
import argparse
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# Default Firmware Constants
DEFAULT_SSID = "AdminSetup"
DEFAULT_PASS = "Admin@123"
DEFAULT_ADMIN_PW = "admin"
MASTER_CRYPTO_SECRET = "e9a3b7c1f4d8025e619b4c7d03a8f2e5167b094c2d3e5f8a1b6c9d0e7f4a2b5c"
DEFAULT_COIN_PIN = 4
DEFAULT_UNIVERSAL_COIN_PIN = 3
DEFAULT_LED_PIN = 8
DEFAULT_LED_ACTIVE_LOW = True
DEFAULT_RELAY_PIN = 5
DEFAULT_PORT = 8080
DEFAULT_PRICE = 5.0
DEFAULT_MINUTES = 30
DEFAULT_DEBOUNCE = 25
STATE_FILE = "kiosk_state.json"

class KioskState:
    def __init__(self, filepath=STATE_FILE):
        self.filepath = filepath
        self.lock = threading.Lock()
        
        # Configuration
        self.wifi_ssid = DEFAULT_SSID
        self.wifi_pass = DEFAULT_PASS
        self.admin_password = DEFAULT_ADMIN_PW
        self.shared_secret = MASTER_CRYPTO_SECRET
        self.coin_pin = DEFAULT_COIN_PIN
        self.universal_coin_pin = DEFAULT_UNIVERSAL_COIN_PIN
        self.led_pin = DEFAULT_LED_PIN
        self.led_active_low = DEFAULT_LED_ACTIVE_LOW
        self.relay_pin = DEFAULT_RELAY_PIN
        self.target_port = DEFAULT_PORT
        self.coin_price = DEFAULT_PRICE
        self.minutes_per_coin = DEFAULT_MINUTES
        self.lockout_debounce_ms = DEFAULT_DEBOUNCE
        self.android_ips = ""
        self.p1_ip = ""
        self.p2_ip = ""
        self.match_minutes = 15
        self.match_status_msg = ""
        self.is_licensed = True
        self.mac_address = "AA:BB:CC:DD:EE:FF"
        
        # Vault Stats
        self.total_coins_lifetime = 0
        self.total_coins_session = 0
        self.total_earnings_lifetime = 0.0
        self.total_earnings_session = 0.0
        
        # Runtime State
        self.armed_device_id = ""
        self.armed_until = 0.0
        self.session_start_time = 0.0
        self.ws_clients = []
        self.tracked_devices = {}  # device_id/ip -> dict
        
        self.load()

    def load(self):
        if os.path.exists(self.filepath):
            try:
                with open(self.filepath, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.wifi_ssid = data.get("wifi_ssid", self.wifi_ssid)
                    self.wifi_pass = data.get("wifi_pass", self.wifi_pass)
                    self.admin_password = data.get("admin_password", self.admin_password)
                    self.shared_secret = data.get("shared_secret", self.shared_secret)
                    self.coin_pin = data.get("coin_pin", self.coin_pin)
                    self.universal_coin_pin = data.get("universal_coin_pin", self.universal_coin_pin)
                    self.led_pin = data.get("led_pin", self.led_pin)
                    self.led_active_low = data.get("led_active_low", self.led_active_low)
                    self.relay_pin = data.get("relay_pin", self.relay_pin)
                    self.target_port = data.get("target_port", self.target_port)
                    self.coin_price = float(data.get("coin_price", self.coin_price))
                    self.minutes_per_coin = int(data.get("minutes_per_coin", self.minutes_per_coin))
                    self.lockout_debounce_ms = int(data.get("lockout_debounce_ms", self.lockout_debounce_ms))
                    self.android_ips = data.get("android_ips", self.android_ips)
                    self.p1_ip = data.get("p1_ip", self.p1_ip)
                    self.p2_ip = data.get("p2_ip", self.p2_ip)
                    self.match_minutes = int(data.get("match_minutes", self.match_minutes))
                    self.is_licensed = data.get("is_licensed", self.is_licensed)
                    self.total_coins_lifetime = int(data.get("total_coins_lifetime", self.total_coins_lifetime))
                    self.total_earnings_lifetime = float(data.get("total_earnings_lifetime", self.total_earnings_lifetime))
                print(f"[+] Loaded persistent kiosk state from {self.filepath}")
            except Exception as e:
                print(f"[!] Warning loading {self.filepath}: {e}")

    def save(self):
        with self.lock:
            data = {
                "wifi_ssid": self.wifi_ssid,
                "wifi_pass": self.wifi_pass,
                "admin_password": self.admin_password,
                "shared_secret": self.shared_secret,
                "coin_pin": self.coin_pin,
                "universal_coin_pin": self.universal_coin_pin,
                "led_pin": self.led_pin,
                "led_active_low": self.led_active_low,
                "relay_pin": self.relay_pin,
                "target_port": self.target_port,
                "coin_price": self.coin_price,
                "minutes_per_coin": self.minutes_per_coin,
                "lockout_debounce_ms": self.lockout_debounce_ms,
                "android_ips": self.android_ips,
                "p1_ip": self.p1_ip,
                "p2_ip": self.p2_ip,
                "match_minutes": self.match_minutes,
                "is_licensed": self.is_licensed,
                "total_coins_lifetime": self.total_coins_lifetime,
                "total_earnings_lifetime": self.total_earnings_lifetime,
            }
            try:
                with open(self.filepath, "w", encoding="utf-8") as f:
                    json.dump(data, f, indent=2)
            except Exception as e:
                print(f"[!] Error saving {self.filepath}: {e}")

    def factory_reset(self):
        with self.lock:
            self.wifi_ssid = DEFAULT_SSID
            self.wifi_pass = DEFAULT_PASS
            self.admin_password = DEFAULT_ADMIN_PW
            self.shared_secret = MASTER_CRYPTO_SECRET
            self.coin_pin = DEFAULT_COIN_PIN
            self.universal_coin_pin = DEFAULT_UNIVERSAL_COIN_PIN
            self.led_pin = DEFAULT_LED_PIN
            self.led_active_low = DEFAULT_LED_ACTIVE_LOW
            self.relay_pin = DEFAULT_RELAY_PIN
            self.target_port = DEFAULT_PORT
            self.coin_price = DEFAULT_PRICE
            self.minutes_per_coin = DEFAULT_MINUTES
            self.lockout_debounce_ms = DEFAULT_DEBOUNCE
            self.android_ips = ""
            self.p1_ip = ""
            self.p2_ip = ""
            self.match_minutes = 15
            self.total_coins_lifetime = 0
            self.total_coins_session = 0
            self.total_earnings_lifetime = 0.0
            self.total_earnings_session = 0.0
            self.save()
            print("[⚠️ FACTORY RESET] All settings restored to factory defaults.")

    def parse_device_list(self):
        devices = []
        if not self.android_ips:
            return devices
        entries = [e.strip() for e in self.android_ips.split(",") if e.strip()]
        for entry in entries:
            parts = entry.split("|")
            d_id, ip, name = "", "", ""
            if len(parts) >= 3:
                p1, p2, p3 = parts[0].strip(), parts[1].strip(), parts[2].strip()
                if "." in p1 and "." not in p2:
                    ip, d_id, name = p1, p2, p3
                else:
                    d_id, ip, name = p1, p2, p3
            elif len(parts) == 2:
                p1, p2 = parts[0].strip(), parts[1].strip()
                if "." in p2:
                    d_id, ip = p1, p2
                elif "." in p1:
                    ip, name = p1, p2
                else:
                    d_id, ip = p1, p2
            elif len(parts) == 1:
                p = parts[0].strip()
                if "." in p:
                    ip = p
                else:
                    d_id = p
            if ip and len(ip) >= 7:
                devices.append({"id": d_id, "ip": ip, "name": name})
        return devices

    def update_dynamic_device(self, device_id, ip):
        device_id = device_id.strip()
        ip = ip.strip()
        if not ip or len(ip) < 7:
            return
        devices = self.parse_device_list()
        found = False
        changed = False
        for dev in devices:
            if (device_id and dev["id"] and dev["id"] == device_id) or dev["ip"] == ip:
                found = True
                if dev["id"] != device_id or dev["ip"] != ip:
                    if device_id:
                        dev["id"] = device_id
                    dev["ip"] = ip
                    changed = True
                break
        if not found:
            devices.append({"id": device_id, "ip": ip, "name": ""})
            changed = True
            print(f"[+] Auto-registered new Android Terminal: {ip} (ID: {device_id})")

        if changed:
            self.android_ips = ",".join([f"{d['id']}|{d['ip']}|{d['name']}" for d in devices])
            self.save()

    def update_telemetry(self, device_id, ip, time_rem, state, battery=100, charging=False, ts=0):
        self.update_dynamic_device(device_id, ip)
        key = device_id if device_id else ip
        now = time.time()
        self.tracked_devices[key] = {
            "id": device_id,
            "ip": ip,
            "time": time_rem,
            "state": state,
            "battery": battery,
            "charging": charging,
            "last_seen": now,
            "ts": ts
        }

    def get_tracked_time(self, ip, max_age_s=15, dev_id=""):
        now = time.time()
        for key, dev in self.tracked_devices.items():
            match = (dev.get("ip") == ip or dev.get("id") == ip or (dev_id and dev.get("id") == dev_id))
            if match:
                if now - dev.get("last_seen", 0) <= max_age_s:
                    elapsed = int(now - dev.get("last_seen", 0))
                    rem = dev.get("time", 0) - elapsed
                    return max(0, rem)
        return -1


state = KioskState()

# =============================================================================
# CRYPTOGRAPHY UTILITIES
# =============================================================================
def calculate_hmac(challenge: str, secret: str) -> str:
    h = hmac.new(secret.encode("utf-8"), challenge.encode("utf-8"), hashlib.sha256)
    return h.hexdigest()

def compute_sec_websocket_accept(key: str) -> str:
    key = key.strip() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    sha1 = hashlib.sha1(key.encode("utf-8")).digest()
    return base64.b64encode(sha1).decode("utf-8")

def verify_telemetry_auth(device_id: str, ts_str: str, sig: str) -> bool:
    if not device_id or not ts_str or not sig:
        return False
    expected = calculate_hmac(f"{device_id}:{ts_str}", state.shared_secret)
    return sig.lower() == expected.lower()

# =============================================================================
# OUTBOUND HTTP CLIENT (Simulates sendAuthenticated from ESP32)
# =============================================================================
def send_authenticated_async(ip: str, port: int, action_path: str, challenge_path: str = "/challenge", params: str = "", timeout_s: float = 2.0):
    def worker():
        try:
            challenge_url = f"http://{ip}:{port}{challenge_path}"
            req = urllib.request.Request(challenge_url, headers={"User-Agent": "HARDWARE-C3-Emulator"})
            with urllib.request.urlopen(req, timeout=timeout_s) as response:
                if response.status == 200:
                    challenge = response.read().decode("utf-8").strip()
                    signature = calculate_hmac(challenge, state.shared_secret)
                    action_url = f"http://{ip}:{port}{action_path}?challenge={challenge}&signature={signature}"
                    if params:
                        action_url += f"&{params}"
                    action_req = urllib.request.Request(action_url, headers={"User-Agent": "HARDWARE-C3-Emulator"})
                    with urllib.request.urlopen(action_req, timeout=timeout_s) as act_res:
                        print(f"[⚡ HTTP OUTBOUND] Action {action_path} to {ip}:{port} returned {act_res.status}")
        except Exception as e:
            print(f"[-] HTTP Outbound to {ip}:{port} failed: {e}")
    threading.Thread(target=worker, daemon=True).start()

def send_add_time(minutes: int, target_ip: str, tx_id: str = ""):
    targets = []
    
    # 1. Static devices
    devices = state.parse_device_list()
    for dev in devices:
        ip = dev.get("ip")
        if ip and ip not in ("127.0.0.1", "0.0.0.0"):
            if target_ip == "ALL" or target_ip == ip:
                if ip not in targets:
                    targets.append(ip)

    # 2. Dynamically tracked devices
    for td_ip, td_data in state.tracked_devices.items():
        actual_ip = td_data.get("ip")
        if actual_ip and actual_ip not in ("127.0.0.1", "0.0.0.0"):
            if target_ip == "ALL" or target_ip == actual_ip:
                if actual_ip not in targets:
                    targets.append(actual_ip)
    
    # 3. Direct IP (if not in ALL and not found above)
    if target_ip != "ALL" and target_ip not in targets and target_ip not in ("127.0.0.1", "0.0.0.0"):
        targets.append(target_ip)

    for ip in targets:
        params = f"minutes={minutes}"
        if tx_id:
            params += f"&tx_id={tx_id}"
        send_authenticated_async(ip, state.target_port, "/add_time", "/challenge", params)

# =============================================================================
# COIN EVENT LOGIC (Simple Beam vs Universal Multi-Coin)
# =============================================================================
def trigger_coin_event():
    print(f"[+] Physical coin pulse on GPIO {state.coin_pin} (Simple Beam Sensor)! Checking armed status...")
    now = time.time()
    is_armed = len(state.ws_clients) > 0 or (state.armed_device_id and now < state.armed_until)

    beam_credit = int(round(state.coin_price)) if state.coin_price > 0 else 1
    state.total_coins_lifetime += beam_credit
    state.total_coins_session += beam_credit
    state.total_earnings_lifetime += state.coin_price
    state.total_earnings_session += state.coin_price
    state.save()

    tx_id = f"{int(now * 1000)}-{os.urandom(2).hex()}"
    added_seconds = state.minutes_per_coin * 60
    ts_str = str(int(now * 1000))
    amount_str = f"{state.coin_price:.2f}"
    sig = calculate_hmac(f"{tx_id}:{added_seconds}:{amount_str}:{ts_str}", state.shared_secret)

    print(f"[⚡] SIMPLE BEAM COIN: Awarding ₱{state.coin_price:.2f} credit (+{state.minutes_per_coin} mins / {added_seconds}s)")

    # Push to WebSocket
    ws_payload = json.dumps({
        "event": "COIN_DETECTED",
        "seconds": added_seconds,
        "minutes": state.minutes_per_coin,
        "amount": f"{state.coin_price:.2f}",
        "slot": "beam",
        "tx_id": tx_id,
        "ts": ts_str,
        "sig": sig
    })
    broadcast_ws(ws_payload)
    state.armed_until = now + 15

    # Target specific armed device, or all registered devices
    targets = []
    if state.armed_device_id and now < state.armed_until:
        for dev in state.parse_device_list():
            if dev["id"] == state.armed_device_id or dev["ip"] == state.armed_device_id:
                targets.append(dev["ip"])
                break
        if not targets:
            targets.append(state.armed_device_id)
    else:
        # If no active armed slot, push to all registered devices or tracked devices
        dev_list = state.parse_device_list()
        for dev in dev_list:
            if dev.get("ip"):
                targets.append(dev["ip"])
        for td_ip, td_data in state.tracked_devices.items():
            actual_ip = td_data.get("ip")
            if actual_ip and actual_ip not in targets:
                targets.append(actual_ip)

    params = f"minutes={state.minutes_per_coin}&seconds={added_seconds}&amount={state.coin_price:.2f}&tx_id={tx_id}"
    for target_ip in targets:
        send_authenticated_async(target_ip, state.target_port, "/add_time", "/challenge", params)
    return True

def trigger_universal_coin_event(pulses: int):
    if pulses <= 0:
        return False
    print(f"[⚡ UNIVERSAL COIN] {pulses} pulses accumulated on GPIO {state.universal_coin_pin} (₱{pulses} PHP)")
    now = time.time()

    effective_rate_mins = (state.minutes_per_coin / state.coin_price) if state.coin_price > 0 else float(state.minutes_per_coin)
    added_seconds = int(round(pulses * effective_rate_mins * 60.0))
    added_minutes = added_seconds // 60
    if added_seconds <= 0:
        added_seconds = 60
        added_minutes = 1

    state.total_coins_lifetime += pulses
    state.total_coins_session += pulses
    state.total_earnings_lifetime += float(pulses)
    state.total_earnings_session += float(pulses)
    state.save()

    tx_id = f"{int(now * 1000)}-{os.urandom(2).hex()}"
    ts_str = str(int(now * 1000))
    sig = calculate_hmac(f"{tx_id}:{added_seconds}:{pulses}:{ts_str}", state.shared_secret)
    print(f"[⚡] UNIVERSAL COIN: Awarding ₱{pulses} (+{added_minutes}m / {added_seconds}s)")

    ws_payload = json.dumps({
        "event": "COIN_DETECTED",
        "seconds": added_seconds,
        "minutes": added_minutes,
        "amount": pulses,
        "slot": "universal",
        "tx_id": tx_id,
        "ts": ts_str,
        "sig": sig
    })
    broadcast_ws(ws_payload)
    state.armed_until = now + 15

    targets = []
    if state.armed_device_id and now < state.armed_until:
        for dev in state.parse_device_list():
            if dev["id"] == state.armed_device_id or dev["ip"] == state.armed_device_id:
                targets.append(dev["ip"])
                break
        if not targets:
            targets.append(state.armed_device_id)
    else:
        dev_list = state.parse_device_list()
        for dev in dev_list:
            if dev.get("ip"):
                targets.append(dev["ip"])
        for td_ip, td_data in state.tracked_devices.items():
            actual_ip = td_data.get("ip")
            if actual_ip and actual_ip not in targets:
                targets.append(actual_ip)

    params = f"minutes={added_minutes}&seconds={added_seconds}&amount={pulses}&tx_id={tx_id}"
    for target_ip in targets:
        send_authenticated_async(target_ip, state.target_port, "/add_time", "/challenge", params)
    return True

# =============================================================================
# PURE PYTHON RFC6455 WEBSOCKET SERVER (PORT 81)
# =============================================================================
def encode_ws_frame(text: str) -> bytes:
    payload = text.encode("utf-8")
    length = len(payload)
    if length < 126:
        header = struct.pack("!BB", 0x81, length)
    elif length <= 65535:
        header = struct.pack("!BBH", 0x81, 126, length)
    else:
        header = struct.pack("!BBQ", 0x81, 127, length)
    return header + payload

def broadcast_ws(message: str):
    frame = encode_ws_frame(message)
    dead_clients = []
    for client in state.ws_clients:
        try:
            client.sendall(frame)
        except Exception:
            dead_clients.append(client)
    for dc in dead_clients:
        if dc in state.ws_clients:
            state.ws_clients.remove(dc)

def run_ws_server(host="0.0.0.0", port=81):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind((host, port))
        sock.listen(5)
        print(f"[!] WebSocket Server active on ws://0.0.0.0:{port}/ws")
    except Exception as e:
        print(f"[!] WebSocket bind failed on port {port} (try running as admin/root or changing port): {e}")
        return

    def client_thread(conn, addr):
        sec_key = None
        req_device_id = ""
        ts_str = ""
        sig = ""
        try:
            conn.settimeout(10.0)
            data = conn.recv(2048).decode("utf-8", errors="ignore")
            lines = data.split("\r\n")
            first_line = lines[0] if lines else ""
            
            # Extract params from GET /ws?device_id=...&ts=...&sig=...
            parsed = urllib.parse.urlparse(first_line.split()[1] if len(first_line.split()) > 1 else "")
            query = urllib.parse.parse_qs(parsed.query)
            req_device_id = query.get("device_id", [""])[0]
            ts_str = query.get("ts", [""])[0]
            sig = query.get("sig", [""])[0]

            for line in lines:
                if line.lower().startswith("sec-websocket-key:"):
                    sec_key = line.split(":", 1)[1].strip()

            if not sec_key:
                conn.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\nMissing Sec-WebSocket-Key")
                conn.close()
                return

            if req_device_id and ts_str and sig:
                if not verify_telemetry_auth(req_device_id, ts_str, sig):
                    print(f"[-] WS Auth Failed for {req_device_id}: Signature Mismatch")
                    conn.sendall(b"HTTP/1.1 403 Forbidden\r\n\r\nInvalid Signature")
                    conn.close()
                    return

            accept_key = compute_sec_websocket_accept(sec_key)
            handshake = (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept_key}\r\n\r\n"
            )
            conn.sendall(handshake.encode("utf-8"))
            conn.settimeout(None)
            
            state.ws_clients.append(conn)
            now = time.time()
            client_ip = addr[0]
            state.armed_device_id = req_device_id or client_ip
            state.armed_until = now + 15
            state.session_start_time = now

            # Dynamically register and track device on WS connection
            state.update_dynamic_device(req_device_id, client_ip)
            ts_val = int(ts_str) if ts_str.isdigit() else int(now)
            state.update_telemetry(req_device_id, client_ip, time_rem=0, state=1, battery=100, charging=False, ts=ts_val)

            print(f"[⚡ WS Port {port}] WebSocket ARMED securely for {state.armed_device_id} ({client_ip}) (15s TTL)")
            conn.sendall(encode_ws_frame('{"event":"ARMED"}'))

            while True:
                head = conn.recv(2)
                if not head or len(head) < 2:
                    break
                b0, b1 = head[0], head[1]
                opcode = b0 & 0x0F
                if opcode == 0x08:  # Close
                    break
                masked = (b1 & 0x80) != 0
                payload_len = b1 & 0x7F
                if payload_len == 126:
                    ext = conn.recv(2)
                    payload_len = struct.unpack("!H", ext)[0]
                elif payload_len == 127:
                    ext = conn.recv(8)
                    payload_len = struct.unpack("!Q", ext)[0]
                
                mask = conn.recv(4) if masked else b""
                raw_data = conn.recv(payload_len)
                if masked:
                    raw_data = bytes([b ^ mask[i % 4] for i, b in enumerate(raw_data)])
                msg = raw_data.decode("utf-8", errors="ignore")
                if msg in ("DONE", "CLOSE"):
                    break
        except Exception as e:
            pass
        finally:
            if conn in state.ws_clients:
                state.ws_clients.remove(conn)
            try:
                conn.close()
            except Exception:
                pass
            print(f"[*] WS Client {addr} disconnected. Released slot.")

    while True:
        try:
            conn, addr = sock.accept()
            threading.Thread(target=client_thread, args=(conn, addr), daemon=True).start()
        except Exception:
            break

# =============================================================================
# HTML WEB PORTAL RENDERING
# =============================================================================
PORTAL_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Admin Console (Emulator)</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <style>
        :root {
            --bg: #f8fafc;
            --sub-bg: #ffffff;
            --input-bg: #f8fafc;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --border: #e2e8f0;
            --danger: #ef4444;
            --danger-hover: #dc2626;
            --success: #10b981;
            --warning: #f59e0b;
            --info: #0ea5e9;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03);
        }
        [data-theme="dark"] {
            --bg: #0b1120;
            --sub-bg: #1e293b;
            --input-bg: #0f172a;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --border: #334155;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
        }
        * { box-sizing: border-box; }
        body { font-family: 'Inter', sans-serif; background: var(--bg); color: var(--text-main); margin: 0; padding: 20px; line-height: 1.5; transition: background-color 0.2s ease, color 0.2s ease; }
        .app-container { max-width: 900px; margin: 0 auto; }
        .header-bar { display: flex; justify-content: space-between; align-items: center; background: var(--sub-bg); padding: 20px 24px; border-radius: 16px; box-shadow: var(--card-shadow); margin-bottom: 24px; border: 1px solid var(--border); }
        h2 { margin: 0; font-size: 22px; font-weight: 700; display: flex; align-items: center; gap: 10px; }
        .status-badge { background: #dbeafe; color: #1e40af; padding: 6px 12px; border-radius: 20px; font-size: 12px; font-weight: 700; letter-spacing: 0.5px; }
        [data-theme="dark"] .status-badge { background: #1e3a8a; color: #93c5fd; }
        .btn { background: var(--primary); color: white; border: none; padding: 10px 18px; border-radius: 8px; font-weight: 600; cursor: pointer; transition: all 0.2s; font-size: 14px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; text-decoration: none; box-sizing: border-box; }
        .btn:hover { background: var(--primary-hover); transform: translateY(-1px); }
        .btn-danger { background: var(--danger); }
        .btn-danger:hover { background: var(--danger-hover); }
        .btn-warning { background: var(--warning); color: #fff; }
        .btn-warning:hover { background: #d97706; }
        .btn-outline { background: var(--sub-bg); border: 1px solid var(--border); color: var(--text-main); }
        .btn-outline:hover { background: var(--border); }
        
        .tabs { display: flex; gap: 8px; margin-bottom: 24px; border-bottom: 2px solid var(--border); padding-bottom: 12px; overflow-x: auto; }
        .tab { padding: 10px 20px; border-radius: 8px; font-weight: 600; font-size: 15px; color: var(--text-muted); cursor: pointer; transition: 0.2s; white-space: nowrap; }
        .tab:hover:not(.active) { background: var(--border); color: var(--text-main); }
        .tab.active { background: var(--primary); color: white; }
        .tab-content { display: none; animation: fadeIn 0.3s ease; }
        .tab-content.active { display: block; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
        
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }
        .grid-full { grid-column: 1 / -1; }
        .card { background: var(--sub-bg); border: 1px solid var(--border); border-radius: 16px; padding: 24px; box-shadow: var(--card-shadow); }
        .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; border-bottom: 1px solid var(--border); padding-bottom: 12px; }
        .card-title { margin: 0; font-size: 16px; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 8px; }
        
        .form-group { margin-bottom: 16px; }
        label { display: block; font-weight: 600; font-size: 13px; margin-bottom: 6px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
        input[type=text], input[type=password], input[type=number], select { width: 100%; padding: 10px 14px; border: 1px solid var(--border); border-radius: 8px; font-size: 15px; background: var(--input-bg); transition: all 0.2s; color: var(--text-main); box-sizing: border-box; }
        input:focus, select:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.2); background: var(--sub-bg); }
        .hint { font-size: 12px; color: var(--text-muted); margin-top: 4px; line-height: 1.4; }
        
        .stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
        .stat-box { background: var(--input-bg); padding: 16px; border-radius: 12px; text-align: center; border: 1px solid var(--border); }
        .stat-val { font-size: 28px; font-weight: 800; color: var(--success); margin: 4px 0; font-variant-numeric: tabular-nums; }
        .stat-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1px; font-weight: 700; }
    </style>
    <script>
        (function() {
            const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
            document.documentElement.setAttribute('data-theme', savedTheme);
        })();

        window.toggleTheme = function() {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            const next = cur === 'dark' ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('kiosk_theme', next);
            updateThemeButtonText();
        };

        function updateThemeButtonText() {
            const btn = document.getElementById('theme_toggle_btn');
            if (btn) {
                const cur = document.documentElement.getAttribute('data-theme') || 'light';
                btn.innerHTML = cur === 'dark' ? '☀️ Light Mode' : '🌙 Dark Mode';
            }
        }

        window.switchTab = function(tabId) {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            const targetTab = document.querySelector(`[onclick="switchTab('${tabId}')"]`);
            if (targetTab) targetTab.classList.add('active');
            const targetContent = document.getElementById(tabId);
            if (targetContent) targetContent.classList.add('active');
            localStorage.setItem('activeTab', tabId);
        };

        document.addEventListener('DOMContentLoaded', () => {
            const savedTab = localStorage.getItem('activeTab') || 'tab-dashboard';
            if (document.getElementById(savedTab)) switchTab(savedTab);
            updateThemeButtonText();
        });

        window.triggerAction = function(ip, action, btn) {
            let origText = '';
            if (btn) {
                origText = btn.innerHTML;
                btn.disabled = true;
                btn.innerHTML = '⏳ Locating...';
            }
            fetch('/trigger_android?ip=' + encodeURIComponent(ip) + '&action=' + encodeURIComponent(action))
                .then(res => { 
                    if (res.ok) {
                        if (btn) {
                            btn.innerHTML = '🔔 Signal Sent!';
                            setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                        } else {
                            alert('📍 Locate signal (Sound, Vibrate & Flash) sent to ' + ip);
                        }
                    } else {
                        if (btn) {
                            btn.innerHTML = '❌ Unreachable';
                            setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                        } else {
                            alert('Failed to send trigger to ' + ip);
                        }
                    }
                })
                .catch(err => {
                    if (btn) {
                        btn.innerHTML = '❌ Error';
                        setTimeout(() => { btn.disabled = false; btn.innerHTML = origText; }, 2500);
                    } else {
                        alert('Error: ' + err);
                    }
                });
        };

        window.triggerCoin = function() {
            fetch('/insert_coin', { method: 'POST' })
                .then(res => res.json())
                .then(data => {
                    if (data && data.success) {
                        alert(data.message || '✅ Simple beam coin drop simulated successfully!');
                    } else {
                        alert('⚠️ ' + (data.message || 'Coin slot drop simulated.'));
                    }
                })
                .catch(err => alert('Network error: ' + err));
        };

        window.triggerUniversalCoin = function(pulses) {
            fetch('/insert_ucoin?pulses=' + pulses, { method: 'POST' })
                .then(res => res.json())
                .then(data => {
                    if (data && data.success) {
                        alert(data.message || ('✅ Universal coin drop (' + pulses + ' PHP) simulated successfully!'));
                    } else {
                        alert('⚠️ ' + (data.message || 'Coin drop simulated.'));
                    }
                })
                .catch(err => alert('Network error: ' + err));
        };
    </script>
</head>
<body>
    <div class="app-container">
        <!-- Header -->
        <div class="header-bar">
            <h2>⚙️ Kiosk Admin <span style="font-size: 13px; font-weight: 500; color: var(--primary); background: rgba(99,102,241,0.1); padding: 4px 8px; border-radius: 6px;">ESP32-C3 Emulator</span></h2>
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <button type="button" id="theme_toggle_btn" onclick="toggleTheme()" class="btn btn-outline" style="padding: 6px 12px; font-size: 13px;">🌙 Dark Mode</button>
                <span class="status-badge">🟢 EMULATOR ONLINE</span>
                <a href="/logout" onclick="return confirm('Log out?');" class="btn btn-outline" style="padding: 6px 12px; font-size: 13px;">🚪 Logout</a>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="tabs">
            <div class="tab active" onclick="switchTab('tab-dashboard')">📊 Dashboard</div>
            <div class="tab" onclick="switchTab('tab-settings')">🛠️ Settings</div>
            <div class="tab" onclick="switchTab('tab-tools')">⚡ Advanced Tools</div>
        </div>

        <!-- TAB 1: DASHBOARD -->
        <div id="tab-dashboard" class="tab-content active">
            <div class="grid">
                <!-- Live Devices -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">📡 Live Device Status</h3>
                        <span class="status-badge" style="background: #ecfccb; color: #3f6212;">LIVE SYNC</span>
                    </div>
                    <div id="live_devices_container" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px;">
                        <div style="padding: 16px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">Loading devices...</div>
                    </div>
                </div>

                <!-- Vault Stats -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">💰 Revenue Vault</h3>
                    </div>
                    <div style="background: var(--input-bg); padding: 18px; border-radius: 12px; text-align: center; border: 1px solid var(--border); margin-bottom: 12px;">
                        <div class="stat-label" style="font-size: 13px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px;">Total Coins (PHP)</div>
                        <div class="stat-val" style="font-size: 32px; font-weight: 800; color: var(--primary);">₱{TOTAL_COINS}</div>
                    </div>
                    <div style="font-size: 13px; text-align: center; color: var(--text-muted); background: var(--bg); padding: 10px; border-radius: 8px; border: 1px solid var(--border);">
                        Session: <b style="color: var(--text-main);">₱{SESSION_COINS}</b>
                    </div>
                    <div style="margin-top: 16px; display: flex; flex-direction: column; gap: 8px;">
                        <button type="button" class="btn" style="width: 100%; font-size: 13px;" onclick="triggerCoin()">🪙 Simulate Simple Beam Coin (GPIO 4: ₱{PRICE})</button>
                        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(1)">₱1 (1p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(5)">₱5 (5p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(10)">₱10 (10p)</button>
                            <button type="button" class="btn btn-outline" style="padding: 6px; font-size: 12px; font-weight: 700;" onclick="triggerUniversalCoin(20)">₱20 (20p)</button>
                        </div>
                    </div>
                </div>

                <!-- Quick Add Time -->
                <form action="/add_time" method="POST" class="card">
                    <div class="card-header">
                        <h3 class="card-title">⏱️ Quick Adjust Time</h3>
                    </div>
                    <div class="form-group">
                        <label>Target Device</label>
                        <select name="target_ip">
                            <option value="ALL">All Devices (Broadcast)</option>
                            {DEVICE_OPTIONS}
                        </select>
                    </div>
                    <div class="form-group">
                        <label>Minutes</label>
                        <input type="number" name="add_minutes" value="60">
                    </div>
                    <div style="display: flex; gap: 10px; margin-top: 20px;">
                        <button type="submit" name="action" value="add" class="btn btn-warning" style="flex: 1;">+ Add</button>
                        <button type="submit" name="action" value="subtract" class="btn btn-danger" style="flex: 1;">- Subtract</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- TAB 2: SETTINGS -->
        <div id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="
                event.preventDefault();
                const rows = document.querySelectorAll('.dev-ip-row');
                const ips = [];
                rows.forEach(row => {
                    const idField = row.querySelector('.dev-id-field');
                    const ipField = row.querySelector('.dev-ip-field');
                    const nameField = row.querySelector('.dev-name-field');
                    if(ipField) {
                        const ipVal = ipField.value.trim();
                        const idVal = idField ? idField.value.trim() : '';
                        let nameVal = nameField ? nameField.value.trim().replace(/\\|/g, '') : '';
                        if (ipVal.length > 0) ips.push(idVal + '|' + ipVal + '|' + nameVal);
                    }
                });
                document.getElementById('ips_hidden').value = ips.join(',');
                const formData = new FormData(this);
                fetch('/save', { method: 'POST', body: new URLSearchParams(formData) })
                    .then(res => { if (res.ok) alert('✅ Configuration saved & pushed!'); else alert('❌ Failed to save.'); })
                    .catch(err => alert('Error: ' + err));
            ">
                <div class="grid">
                    <!-- Network -->
                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">📡 Wi-Fi & Network</h3>
                        <div class="form-group">
                            <label>SSID</label>
                            <input type="text" name="wifi_ssid" value="{WIFI_SSID}">
                        </div>
                        <div class="form-group">
                            <label>Password</label>
                            <input type="password" name="wifi_pass" value="{WIFI_PASS}">
                        </div>
                        <div class="form-group">
                            <label>Android App Port</label>
                            <input type="number" name="port" value="{PORT}">
                            <div class="hint">Default is 8080.</div>
                        </div>
                    </div>

                    <!-- Pricing & Rules (Simple Beam Sensor Only) -->
                    <div class="card">
                        <div class="card-header" style="margin-bottom: 12px;">
                            <h3 class="card-title">🪙 Simple Beam Sensor Pricing & Rules</h3>
                            <span class="status-badge" style="background: #fef3c7; color: #92400e; font-size: 11px;">GPIO 4 ONLY</span>
                        </div>
                        <div class="hint" style="background: var(--input-bg); border: 1px solid var(--border); border-radius: 8px; padding: 10px; margin-bottom: 14px; font-size: 12px; line-height: 1.4;">
                            ⚠️ <b>Note:</b> These pricing, minutes, and debounce settings apply <b>exclusively to the Simple Optical Beam Sensor (GPIO 4)</b>.<br>
                            The <i>Universal Multi-Coin Acceptor (GPIO 3)</i> automatically recognizes hardware pulse denominations (₱1, ₱5, ₱10, ₱20) and calculates time dynamically from this rate.
                        </div>
                        <div class="form-group">
                            <label>Simple Beam Coin Price / Credit (PHP)</label>
                            <input type="number" step="0.01" name="price" value="{PRICE}">
                            <div class="hint">Fixed PHP credit awarded per coin drop on the simple beam sensor (GPIO 4).</div>
                        </div>
                        <div class="form-group">
                            <label>Minutes per Beam Coin Drop</label>
                            <input type="number" name="minutes" value="{MINUTES}">
                            <div class="hint">Session minutes granted per coin drop on the simple beam sensor (GPIO 4).</div>
                        </div>
                        <div class="form-group">
                            <label>Simple Beam Lockout Debounce (ms)</label>
                            <input type="number" name="debounce" value="{DEBOUNCE}">
                            <div class="hint">Debounce lockout period for simple beam sensor to prevent double-counting. Default 25ms.</div>
                        </div>
                    </div>

                    <!-- Devices -->
                    <div class="card grid-full">
                        <h3 class="card-title" style="margin-bottom: 16px;">📱 Registered Android Terminals</h3>
                        <div class="hint" style="margin-bottom: 12px;">Devices automatically register here when they connect to this Wi-Fi network and authenticate via the Android App.</div>
                        {DEVICE_IP_INPUTS}
                    </div>

                    <!-- Advanced Security & Pins -->
                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">🔒 Security</h3>
                        <div class="form-group">
                            <label>Admin Web Password</label>
                            <input type="password" name="admin_pw" value="{ADMIN_PASSWORD}">
                        </div>
                    </div>

                    <div class="card">
                        <h3 class="card-title" style="margin-bottom: 16px;">🔌 Hardware Pins</h3>
                        <div class="form-group">
                            <label>Simple Beam Sensor GPIO (Uses Rules Above)</label>
                            <input type="number" name="coin_pin" value="{COIN_PIN}">
                            <div class="hint">Single-coin infrared/optical beam sensor (Default GPIO 4). Awards the configured Coin Price and Minutes.</div>
                        </div>
                        <div class="form-group">
                            <label>Universal Multi-Coin Slot GPIO</label>
                            <input type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                            <div class="hint">Pulse-based multi-coin acceptor for ₱1, ₱5, ₱10, ₱20 (Default GPIO 3).</div>
                        </div>
                        <div class="form-group">
                            <label>Indicator LED GPIO</label>
                            <input type="number" name="led_pin" value="{LED_PIN}">
                        </div>
                        <div class="form-group">
                            <label>LED Polarity / Active Logic</label>
                            <select name="led_active_low" style="width: 100%; padding: 10px; border-radius: 6px; border: 1px solid #cbd5e1; background: #fff; font-size: 14px;">
                                <option value="1" {LED_ACTIVE_LOW_SELECTED}>Active LOW (Tenstar Robot / ESP32-C3 Super Mini)</option>
                                <option value="0" {LED_ACTIVE_HIGH_SELECTED}>Active HIGH (Standard DevKit / External LED)</option>
                            </select>
                            <div class="hint">Tenstar Robot and ESP32-C3 Super Mini onboard blue LEDs require Active LOW logic.</div>
                        </div>
                        <div class="form-group">
                            <label>Coin Slot Relay GPIO (Auto Power Cutoff)</label>
                            <input type="number" name="relay_pin" value="{RELAY_PIN}">
                            <div class="hint">Relay control pin to power/enable the coin slot when a user presses 'Insert Coin' on their phone (Default GPIO 5). Automatically cuts power / disables coin slot when idle or session expires to prevent lost coins.</div>
                        </div>
                    </div>

                    <div class="grid-full">
                        <button type="submit" class="btn" style="width: 100%; padding: 14px; font-size: 16px;">💾 Save & Push Configuration Live</button>
                    </div>
                </div>
            </form>
        </div>

        <!-- TAB 3: TOOLS -->
        <div id="tab-tools" class="tab-content">
            <div class="grid">
                <!-- 1v1 Match -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">⚔️ 1v1 Match Mode</h3>
                        <span class="status-badge" style="background: #f3e8ff; color: #7e22ce;">ESPORTS</span>
                    </div>
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" style="display: flex; flex-direction: column; gap: 16px;">
                        <div class="form-group" style="max-width: 200px;">
                            <label>Stake Minutes</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px;">
                            <div style="background: var(--bg); padding: 16px; border-radius: 12px; border: 1px solid var(--border);">
                                <label style="color: var(--primary);">🎮 Player 1</label>
                                <select id="p1_select" name="p1_ip" style="margin-bottom: 12px;">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P1</button>
                            </div>
                            <div style="background: var(--bg); padding: 16px; border-radius: 12px; border: 1px solid var(--border);">
                                <label style="color: var(--danger);">🎮 Player 2</label>
                                <select id="p2_select" name="p2_ip" style="margin-bottom: 12px;">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P2</button>
                            </div>
                        </div>
                        
                        <button type="button" onclick="checkMatchQualification()" class="btn" style="background: #7e22ce; align-self: flex-start;">🔍 Verify Both Players' Balances</button>
                    </form>
                    <div id="match_qual_result" style="display: none; margin-top: 16px; padding: 16px; border-radius: 8px; font-size: 14px;"></div>
                </div>

                <!-- OTA Update -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">🚀 Firmware Upgrade</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 16px;">Flash a new <code style="background: #e2e8f0; padding: 2px 4px; border-radius: 4px;">.bin</code> compiled firmware wirelessly without a USB cable.</p>
                    <a href="/update" class="btn btn-outline" style="width: 100%;">Upload Firmware (OTA) &rarr;</a>
                </div>

                <!-- System Recovery -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">⚠️ System Recovery</h3>
                    </div>
                    
                    <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset lifetime coin counts?');" style="margin-bottom: 24px;">
                        <label>Reset Vault Counters</label>
                        <div style="display: flex; gap: 8px;">
                            <input type="password" name="reset_pw" placeholder="Admin password">
                            <button type="submit" class="btn btn-danger">Reset</button>
                        </div>
                    </form>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 16px 0;">
                    <div style="display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" style="width: 100%; background: #0284c7;" onclick="if(confirm('🔄 Reboot emulator?')) { fetch('/reboot', {method: 'POST'}).then(() => { alert('Rebooting. Reconnecting in 3 seconds...'); setTimeout(() => window.location.reload(), 3000); }); }">
                            🔄 Reboot HARDWARE Controller
                        </button>
                        <button type="button" class="btn btn-danger" style="width: 100%;" onclick="if(confirm('⚠️ Factory Reset? All settings will be wiped.')) { fetch('/factory_reset', {method: 'POST'}).then(() => { alert('Resetting...'); setTimeout(() => window.location.reload(), 3000); }); }">
                            Restore Factory Defaults
                        </button>
                    </div>
                </div>

                <!-- Android Provisioning (QR) -->
                <div class="card" style="grid-column: 1 / -1;">
                    <div class="card-header">
                        <h3 class="card-title">📱 Android Provisioning (QR Code)</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 12px;">
                        Use this QR code to setup a factory-reset Android device. 
                        Tap the Android "Welcome" screen 6 times to open the QR scanner, connect to Wi-Fi if prompted, and scan the code.
                    </p>
                    <div style="display: flex; flex-wrap: wrap; gap: 16px;">
                        <div style="flex: 1; min-width: 250px;">
                            <div class="form-group">
                                <label>Wi-Fi SSID</label>
                                <input type="text" id="qr_ssid" value="{WIFI_SSID}">
                            </div>
                            <div class="form-group">
                                <label>Wi-Fi Password</label>
                                <input type="password" id="qr_pwd" value="{WIFI_PASS}">
                            </div>
                            <div class="form-group">
                                <label>APK Download URL</label>
                                <input type="text" id="qr_apk" placeholder="https://your-server/app.apk" value="https://github.com/google/aistudio-kiosk/releases/latest/download/app-debug.apk">
                            </div>
                            <button type="button" class="btn" style="width: 100%;" onclick="generateQR()">Generate QR Code</button>
                        </div>
                        <div style="display: flex; align-items: center; justify-content: center; flex: 1; min-width: 250px; background: #fff; padding: 16px; border-radius: 8px; border: 1px solid var(--border);">
                            <div id="qrcode"></div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script>
    window.generateQR = function() {
        const ssid = document.getElementById('qr_ssid').value;
        const pwd = document.getElementById('qr_pwd').value;
        const apkUrl = document.getElementById('qr_apk').value;
        
        const payload = {
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.pisophone.kiosk/com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver",
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apkUrl,
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "",
            "android.app.extra.PROVISIONING_WIFI_SSID": ssid,
            "android.app.extra.PROVISIONING_WIFI_PASSWORD": pwd,
            "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
        };
        
        document.getElementById("qrcode").innerHTML = "";
        new QRCode(document.getElementById("qrcode"), {
            text: JSON.stringify(payload),
            width: 200,
            height: 200
        });
    };
    
    window.fetchDeviceStatus = function() {
        fetch('/api/status')
            .then(res => res.json())
            .then(data => {
                const container = document.getElementById('live_devices_container');
                if (!container) return;
                if (data.length === 0) {
                    container.innerHTML = '<div style=\"padding: 16px; text-align: center; color: var(--text-muted); grid-column: 1/-1;\">No PisoPhone devices registered.</div>';
                    return;
                }
                let html = '';
                data.forEach((dev, idx) => {
                    const name = dev.name || ('PisoPhone ' + (idx + 1));
                    const battery = (typeof dev.battery === 'number' && dev.battery >= 0) ? dev.battery : 100;
                    const isCharging = !!dev.charging;
                    
                    let batteryColor = '#16a34a';
                    let batteryBg = '#f0fdf4';
                    let batteryBorder = '1.5px solid #bbf7d0';
                    let statusLabel = 'GOOD CHARGE';
                    
                    if (battery <= 15) {
                        batteryColor = '#dc2626';
                        batteryBg = '#fef2f2';
                        batteryBorder = '1.5px solid #fca5a5';
                        statusLabel = 'CRITICAL LOW';
                    } else if (battery <= 30) {
                        batteryColor = '#d97706';
                        batteryBg = '#fffbeb';
                        batteryBorder = '1.5px solid #fcd34d';
                        statusLabel = 'LOW BATTERY';
                    }

                    if (dev.online) {
                        const mins = Math.floor(dev.time / 60);
                        const secs = dev.time % 60;
                        const timeStr = mins + 'm ' + secs + 's';
                        const active = dev.time > 0;
                        const stateBadge = active 
                            ? '<span style=\"padding:4px 8px;background:#16a34a;color:white;border-radius:6px;font-size:11px;font-weight:700;\">ACTIVE</span>'
                            : '<span style=\"padding:4px 8px;background:#64748b;color:white;border-radius:6px;font-size:11px;font-weight:700;\">STANDBY</span>';
                            
                        const batteryIcon = isCharging ? '⚡' : '🔋';
                        const batteryText = (isCharging ? '⚡ Charging ' : '') + battery + '%';

                        html += '<div style=\"display:flex; flex-direction:column; justify-content:space-between; padding:16px; border-radius:14px; background:' + batteryBg + '; border:' + batteryBorder + '; box-shadow: 0 2px 4px rgba(0,0,0,0.03); gap: 12px; transition: all 0.3s ease;\">' +
                                '<div style=\"display:flex; justify-content:space-between; align-items:center;\">' +
                                    '<div><strong style=\"color:#0f172a; font-size: 16px; display:block; margin-bottom:2px;\">' + name + '</strong><span style=\"font-size:12px; color:var(--text-muted); font-family: monospace;\">' + dev.ip + '</span></div>' +
                                    '<div style=\"text-align:right;\">' +
                                        '<div style=\"font-weight:800; font-size:18px; color:#0f172a; font-variant-numeric: tabular-nums; line-height: 1.2; margin-bottom:6px;\">' + timeStr + '</div>' +
                                        '<div>' + stateBadge + '</div>' +
                                    '</div>' +
                                '</div>' +
                                '<div style=\"background: rgba(255,255,255,0.7); padding: 10px; border-radius: 10px; border: 1px solid rgba(0,0,0,0.06);\">' +
                                    '<div style=\"display:flex; justify-content:space-between; align-items:center; font-size: 12px; font-weight: 700; margin-bottom: 6px;\">' +
                                        '<span style=\"color:' + batteryColor + '; display:flex; align-items:center; gap:4px;\">' + batteryIcon + ' ' + batteryText + '</span>' +
                                        '<span style=\"font-size: 10px; color:' + batteryColor + '; text-transform: uppercase; letter-spacing: 0.5px;\">' + statusLabel + '</span>' +
                                    '</div>' +
                                    '<div style=\"height: 8px; background: #e2e8f0; border-radius: 999px; overflow: hidden;\">' +
                                        '<div style=\"height: 100%; width: ' + battery + '%; background: ' + batteryColor + '; border-radius: 999px; transition: width 0.4s ease-in-out;\"></div>' +
                                    '</div>' +
                                '</div>' +
                                '<div style=\"display:flex; gap: 8px; margin-top: 2px;\">' +
                                    '<button type=\"button\" class=\"btn\" onclick=\"triggerAction(\\'' + dev.ip + '\\', \\'locate\\', this)\" style=\"flex:1; padding: 8px 12px; font-size: 12px; font-weight: 700; background: #6366f1; color: white; border: none; border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 6px; box-shadow: 0 1px 2px rgba(99,102,241,0.2);\">' +
                                        '📍 Locate Device (Sound, Vibrate & Flash)' +
                                    '</button>' +
                                '</div>' +
                                '</div>';
                    } else {
                        html += '<div style=\"display:flex; flex-direction:column; justify-content:space-between; padding:16px; border-radius:14px; background:#f8fafc; border:1px solid #e2e8f0; opacity: 0.75; gap: 12px;\">' +
                                '<div style=\"display:flex; justify-content:space-between; align-items:center;\">' +
                                    '<div><strong style=\"color:#64748b; font-size: 16px;\">' + name + '</strong><br><span style=\"font-size:12px; color:var(--text-muted); font-family: monospace;\">' + dev.ip + '</span></div>' +
                                    '<div><span style=\"padding:4px 8px;background:#94a3b8;color:white;border-radius:6px;font-size:11px;font-weight:700;\">OFFLINE</span></div>' +
                                '</div>' +
                                '<div style=\"background: rgba(241,245,249,0.8); padding: 8px 10px; border-radius: 8px; font-size: 12px; color: #64748b; text-align: center;\">Disconnected / Reconnecting...</div>' +
                                '</div>';
                    }
                });
                container.innerHTML = html;
            })
            .catch(err => console.log('Status polling error', err));
    };
    setInterval(fetchDeviceStatus, 3000);
    document.addEventListener(\"DOMContentLoaded\", fetchDeviceStatus);

    window.checkMatchQualification = function() {
        const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
        const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
        const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
        const resDiv = document.getElementById('match_qual_result');
        if (!p1 || !p2) { alert('Select both players.'); return; }
        if (p1 === p2) { resDiv.style.display = 'block'; resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b'; resDiv.innerHTML = '❌ <b>Error:</b> Players cannot be the same device.'; return; }
        resDiv.style.display = 'block'; resDiv.style.background = '#f8fafc'; resDiv.style.border = '1px solid #e2e8f0'; resDiv.style.color = '#334155'; resDiv.innerHTML = '⏳ Verifying balances...';
        fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    if (data.qualified) {
                        resDiv.style.background = '#f0fdf4'; resDiv.style.border = '1px solid #bbf7d0'; resDiv.style.color = '#166534';
                        resDiv.innerHTML = '✅ <b>BOTH QUALIFIED FOR ' + data.stake_minutes + 'm MATCH!</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted;
                    } else {
                        resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b';
                        resDiv.innerHTML = '❌ <b>NOT QUALIFIED</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted + '<br><i>' + data.message + '</i>';
                    }
                } else { resDiv.innerHTML = '❌ Error checking qualification.'; }
            }).catch(err => resDiv.innerHTML = '❌ Network error.');
    };
    </script>
</body>
</html>"""

def render_device_options(selected_ip=""):
    opts = ""
    devices = state.parse_device_list()
    seen_ips = set()
    idx = 1
    for dev in devices:
        ip = dev.get("ip")
        if ip and ip not in ("127.0.0.1", "0.0.0.0"):
            name = dev.get("name") or f"PisoPhone {idx}"
            sel = " selected" if ip == selected_ip else ""
            opts += f'<option value="{ip}"{sel}>{name} ({ip})</option>'
            seen_ips.add(ip)
            idx += 1
            
    for td_ip, td_data in state.tracked_devices.items():
        actual_ip = td_data.get("ip")
        if actual_ip and actual_ip not in ("127.0.0.1", "0.0.0.0") and actual_ip not in seen_ips:
            name = f"PisoPhone {idx}"
            sel = " selected" if actual_ip == selected_ip else ""
            opts += f'<option value="{actual_ip}"{sel}>{name} ({actual_ip})</option>'
            seen_ips.add(actual_ip)
            idx += 1
            
    return opts

def render_device_ip_inputs():
    html = '<div id="dev_ip_container" style="background-color: var(--sub-bg); border: 1px solid var(--border); border-radius: 8px; padding: 12px; margin-top: 6px;">'
    devices = state.parse_device_list()
    seen_ips = set()
    idx = 1
    for dev in devices:
        ip = dev.get("ip")
        if ip and ip not in ("127.0.0.1", "0.0.0.0"):
            html += '<div class="dev-ip-row" style="display: block; padding: 10px; border-bottom: 1px solid var(--border);">'
            html += '<div style="display: flex; align-items: center; gap: 8px;">'
            html += f'<span class="dev-label" style="min-width: 95px; font-size: 13px; font-weight: 700;">PisoPhone {idx}:</span>'
            html += f'<input type="hidden" class="dev-id-field" value="{dev.get("id", "")}">'
            html += f'<input type="text" class="dev-ip-field" value="{ip}" placeholder="192.168.1.X" style="flex: 1; min-width: 140px;" readonly title="IP dynamically bound">'
            html += '<button type="button" class="remove-btn" onclick="this.closest(\'.dev-ip-row\').remove(); updateDeviceLabels();" title="Remove Device" style="background: none; border: none; font-size: 18px; cursor: pointer; color: var(--danger);">&times;</button>'
            html += '</div></div>'
            seen_ips.add(ip)
            idx += 1
            
    for td_ip, td_data in state.tracked_devices.items():
        actual_ip = td_data.get("ip")
        if actual_ip and actual_ip not in ("127.0.0.1", "0.0.0.0") and actual_ip not in seen_ips:
            html += '<div class="dev-ip-row" style="display: block; padding: 10px; border-bottom: 1px solid var(--border);">'
            html += '<div style="display: flex; align-items: center; gap: 8px;">'
            html += f'<span class="dev-label" style="min-width: 95px; font-size: 13px; font-weight: 700;">PisoPhone {idx}:</span>'
            html += f'<input type="hidden" class="dev-id-field" value="{td_data.get("id", "")}">'
            html += f'<input type="text" class="dev-ip-field" value="{actual_ip}" placeholder="192.168.1.X" style="flex: 1; min-width: 140px;" readonly title="IP dynamically bound">'
            html += '<button type="button" class="remove-btn" onclick="this.closest(\'.dev-ip-row\').remove(); updateDeviceLabels();" title="Remove Device" style="background: none; border: none; font-size: 18px; cursor: pointer; color: var(--danger);">&times;</button>'
            html += '</div></div>'
            seen_ips.add(actual_ip)
            idx += 1


    no_dev_display = "none" if devices else "block"
    html += f'<div id="no_dev_msg" style="display: {no_dev_display}; color: var(--text-muted); font-size: 13px; text-align: center; padding: 14px 8px;">No devices registered. Connected Android terminals will appear automatically, or you can add IP manually below.</div>'
    html += '</div>'
    html += '<div style="display: flex; gap: 8px; margin-top: 8px;">'
    html += '<button type="button" class="btn btn-outline" style="font-size: 12px; padding: 6px 12px;" onclick="addDeviceIpRow()">+ Add IP Manually</button>'
    html += '<button type="button" class="btn btn-outline" style="font-size: 12px; padding: 6px 12px; color: var(--danger); border-color: var(--danger);" onclick="clearAllDevices()">🗑️ Clear All Devices</button>'
    html += '</div>'
    html += f'<input type="hidden" id="ips_hidden" name="ips" value="{state.android_ips}">'
    html += """<script>
    window.updateDeviceLabels = function() {
      const rows = document.querySelectorAll('.dev-ip-row');
      rows.forEach((row, idx) => {
        const label = row.querySelector('.dev-label'); if (label) label.textContent = 'PisoPhone ' + (idx + 1) + ':';
      });
      const msg = document.getElementById('no_dev_msg');
      if (msg) msg.style.display = (rows.length === 0) ? 'block' : 'none';
    };
    window.clearAllDevices = function() {
      if (confirm('Remove all registered devices? Click Save after clearing.')) {
        document.querySelectorAll('.dev-ip-row').forEach(r => r.remove());
        updateDeviceLabels();
      }
    };
    window.addDeviceIpRow = function() {
      const container = document.getElementById('dev_ip_container');
      const devNum = container.querySelectorAll('.dev-ip-row').length + 1;
      const div = document.createElement('div');
      div.className = 'dev-ip-row'; div.style.display = 'block'; div.style.padding = '10px'; div.style.borderBottom = '1px solid var(--border)';
      div.innerHTML = '<div style="display: flex; align-items: center; gap: 8px;"><span class="dev-label" style="min-width: 95px; font-size: 13px; font-weight: 700;">PisoPhone ' + devNum + ':</span>' +
                      '<input type="hidden" class="dev-id-field" value="">' +
                      '<input type="text" class="dev-ip-field" value="" placeholder="192.168.1.X" style="flex: 1; min-width: 140px;">' +
                      '<button type="button" class="remove-btn" onclick="this.closest(\x27.dev-ip-row\x27).remove(); updateDeviceLabels();" title="Remove Device" style="background: none; border: none; font-size: 18px; cursor: pointer; color: var(--danger);">&times;</button></div>';
      container.appendChild(div);
      updateDeviceLabels();
    };
    </script>"""
    return html

def render_portal_html():
    html = PORTAL_HTML
    html = html.replace("{WIFI_SSID}", state.wifi_ssid)
    html = html.replace("{WIFI_PASS}", state.wifi_pass)
    html = html.replace("{COIN_PIN}", str(state.coin_pin))
    html = html.replace("{U_COIN_PIN}", str(state.universal_coin_pin))
    html = html.replace("{LED_PIN}", str(state.led_pin))
    html = html.replace("{LED_ACTIVE_LOW_SELECTED}", "selected" if state.led_active_low else "")
    html = html.replace("{LED_ACTIVE_HIGH_SELECTED}", "selected" if not state.led_active_low else "")
    html = html.replace("{RELAY_PIN}", str(state.relay_pin))
    html = html.replace("{DEVICE_IP_INPUTS}", render_device_ip_inputs())
    html = html.replace("{PORT}", str(state.target_port))
    html = html.replace("{ADMIN_PASSWORD}", state.admin_password)
    html = html.replace("{PRICE}", f"{state.coin_price:.2f}")
    html = html.replace("{MINUTES}", str(state.minutes_per_coin))
    html = html.replace("{DEBOUNCE}", str(state.lockout_debounce_ms))
    html = html.replace("{DEVICE_OPTIONS}", render_device_options(""))
    html = html.replace("{MATCH_MINUTES}", str(state.match_minutes))
    devices = state.parse_device_list()
    first_ip = devices[0]["ip"] if devices else ""
    html = html.replace("{P1_OPTIONS}", render_device_options(state.p1_ip or first_ip))
    html = html.replace("{P2_OPTIONS}", render_device_options(state.p2_ip))
    html = html.replace("{MATCH_ALERT}", state.match_status_msg)
    state.match_status_msg = ""
    html = html.replace("{TOTAL_COINS}", str(state.total_coins_lifetime))
    html = html.replace("{SESSION_COINS}", str(state.total_coins_session))
    return html

# =============================================================================
# HTTP REQUEST HANDLER (PORT 80 / 8080)
# =============================================================================
class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class KioskHTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Clean console log
        pass

    def check_auth(self, query=None):
        # 1. Digest/Basic Auth emulation or parameter auth
        auth_header = self.headers.get("Authorization")
        if auth_header:
            # Check basic auth for simplicity if provided
            if auth_header.startswith("Basic "):
                try:
                    decoded = base64.b64decode(auth_header[6:]).decode("utf-8")
                    user, pw = decoded.split(":", 1)
                    if user == "admin" and pw == state.admin_password:
                        return True
                except Exception:
                    pass
        
        # 2. Query param signature verification
        if query:
            if "device_id" in query and "ts" in query and "sig" in query:
                dev_id = query["device_id"][0]
                ts_str = query["ts"][0]
                sig = query["sig"][0]
                if verify_telemetry_auth(dev_id, ts_str, sig):
                    return True
            if "challenge" in query and "sig" in query:
                ch = query["challenge"][0]
                sig = query["sig"][0]
                if sig.lower() == calculate_hmac(ch, state.shared_secret).lower() or sig.lower() == calculate_hmac(ch, state.admin_password).lower():
                    return True

        # In browser environment, allow local access or prompt
        return True

    def parse_body(self):
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length > 0:
            body = self.rfile.read(content_length).decode("utf-8", errors="ignore")
            return urllib.parse.parse_qs(body)
        return {}

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/":
            if not self.check_auth(query):
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="HARDWARE Admin Login"')
                self.end_headers()
                return
            html = render_portal_html()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))

        elif path == "/api/status":
            devices = state.parse_device_list()
            res = []
            seen_ips = set()
            for idx, dev in enumerate(devices):
                dev_ip = dev.get("ip", "")
                if dev_ip:
                    seen_ips.add(dev_ip)
                rem = state.get_tracked_time(dev_ip, 25, dev.get("id", ""))
                td = state.tracked_devices.get(dev.get("id") or dev_ip, {})
                bat = td.get("battery", 100)
                chg = td.get("charging", False)
                name = dev.get("name") or f"PisoPhone {idx+1}"
                is_online = (rem >= 0 or (len(state.ws_clients) > 0 and state.armed_device_id in (dev.get("id"), dev_ip)))
                res.append({
                    "id": dev.get("id", ""),
                    "ip": dev_ip,
                    "name": name,
                    "time": max(0, rem) if rem >= 0 else 0,
                    "online": is_online,
                    "battery": bat,
                    "charging": chg
                })
            # Also include any dynamically tracked devices
            for key, td in state.tracked_devices.items():
                td_ip = td.get("ip", "")
                td_id = td.get("id", "")
                if td_ip and td_ip not in seen_ips:
                    seen_ips.add(td_ip)
                    rem = state.get_tracked_time(td_ip, 25, td_id)
                    res.append({
                        "id": td_id,
                        "ip": td_ip,
                        "name": f"PisoPhone {len(res)+1}",
                        "time": max(0, rem) if rem >= 0 else 0,
                        "online": (rem >= 0),
                        "battery": td.get("battery", 100),
                        "charging": td.get("charging", False)
                    })
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode("utf-8"))

        elif path == "/check_qualification":
            p1 = query.get("p1", [""])[0].strip()
            p2 = query.get("p2", [""])[0].strip()
            mins = int(query.get("minutes", [15])[0])
            mins = max(1, mins)
            p1_sec = state.get_tracked_time(p1, 15)
            p2_sec = state.get_tracked_time(p2, 15)
            stake_sec = mins * 60
            p1_ok = (p1_sec >= stake_sec)
            p2_ok = (p2_sec >= stake_sec)
            both_qual = (p1_ok and p2_ok)

            p1_fmt = f"{p1_sec // 60}m {p1_sec % 60}s" if p1_sec >= 0 else "Offline"
            p2_fmt = f"{p2_sec // 60}m {p2_sec % 60}s" if p2_sec >= 0 else "Offline"
            msg = f"Both devices meet the {mins}m stake requirement." if both_qual else "One or both devices have insufficient time or are offline."

            res = {
                "success": True,
                "qualified": both_qual,
                "stake_minutes": mins,
                "p1_ip": p1,
                "p1_seconds": p1_sec,
                "p1_formatted": p1_fmt,
                "p1_ok": p1_ok,
                "p1_err": "" if p1_sec >= 0 else "Offline",
                "p2_ip": p2,
                "p2_seconds": p2_sec,
                "p2_formatted": p2_fmt,
                "p2_ok": p2_ok,
                "p2_err": "" if p2_sec >= 0 else "Offline",
                "message": msg
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode("utf-8"))

        elif path in ("/identify", "/get_config", "/config"):
            req_ip = query.get("ip", [self.client_address[0]])[0]
            dev_name = "PisoPhone 1"
            for idx, dev in enumerate(state.parse_device_list()):
                if dev["ip"] == req_ip:
                    dev_name = dev["name"] or f"PisoPhone {idx+1}"
                    break
            res = {
                "device": "HARDWARE_kiosk",
                "version": "3.0",
                "price": state.coin_price,
                "minutes": state.minutes_per_coin,
                "device_name": dev_name
            }
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode("utf-8"))

        elif path in ("/heartbeat", "/ping", "/announce"):
            dev_id = query.get("device_id", [""])[0]
            raw_ip = query.get("ip", [""])[0].strip()
            req_ip = raw_ip if (raw_ip and len(raw_ip) >= 7) else self.client_address[0]
            ts_str = query.get("ts", [""])[0]
            sig = query.get("sig", [""])[0]
            
            auth_ok = True
            if sig:
                auth_ok = verify_telemetry_auth(dev_id, ts_str, sig)

            if auth_ok:
                t_rem = int(query.get("time", [0])[0])
                st = int(query.get("state", [0])[0])
                bat = int(query.get("battery", [100])[0])
                chg = query.get("charging", ["false"])[0] in ("1", "true", "True")
                ts = int(ts_str) if ts_str.isdigit() else int(time.time())
                state.update_telemetry(dev_id, req_ip, t_rem, st, bat, chg, ts)

                res = {
                    "status": "ok" if state.is_licensed else "unlicensed",
                    "device": "HARDWARE_kiosk",
                    "mac": state.mac_address,
                    "price": state.coin_price,
                    "minutes": state.minutes_per_coin,
                    "device_name": "PisoPhone 1"
                }
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode("utf-8"))
            else:
                print(f"[!] Heartbeat auth signature mismatch from {req_ip} (ID: {dev_id}).")
                self.send_response(403)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"Forbidden"}')

        elif path == "/trigger_android":
            target_ip = query.get("ip", [""])[0]
            action = query.get("action", ["locate"])[0]
            if target_ip:
                print(f"[⚡ TRIGGER] Sending {action} signal to {target_ip}")
                send_authenticated_async(target_ip, state.target_port, "/trigger_action", "/challenge", f"action={action}")
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.end_headers()
                self.wfile.write(b"Trigger sent")
            else:
                self.send_response(400)
                self.end_headers()

        elif path == "/logout":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            logout_html = """<!DOCTYPE html><html><body style="background:#0f172a;color:#fff;font-family:sans-serif;text-align:center;padding:50px;">
            <h2>🔒 Logged Out</h2><p>You have logged out of the Kiosk Admin Console.</p><a href="/" style="color:#6366f1;">🔑 Log In Again</a></body></html>"""
            self.wfile.write(logout_html.encode("utf-8"))

        elif path == "/update":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            ota_html = """<!DOCTYPE html><html><body style="background:#f8fafc;font-family:sans-serif;padding:30px;text-align:center;">
            <h2>📲 Firmware OTA Update (Emulated)</h2>
            <p>Select a .bin firmware file.</p>
            <input type="file" accept=".bin"><br><br>
            <button onclick="alert('OTA Update Flashing Emulated Successfully!')">Upload & Flash Firmware</button><br><br>
            <a href="/">&larr; Back to Kiosk Dashboard</a></body></html>"""
            self.wfile.write(ota_html.encode("utf-8"))

        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)
        form = self.parse_body()

        if path == "/save":
            if "wifi_ssid" in form: state.wifi_ssid = form["wifi_ssid"][0]
            if "wifi_pass" in form: state.wifi_pass = form["wifi_pass"][0]
            if "port" in form: state.target_port = int(form["port"][0])
            if "admin_pw" in form: state.admin_password = form["admin_pw"][0]
            if "price" in form: state.coin_price = float(form["price"][0])
            if "minutes" in form: state.minutes_per_coin = int(form["minutes"][0])
            if "debounce" in form: state.lockout_debounce_ms = int(form["debounce"][0])
            if "coin_pin" in form: state.coin_pin = int(form["coin_pin"][0])
            if "u_coin_pin" in form: state.universal_coin_pin = int(form["u_coin_pin"][0])
            if "led_pin" in form: state.led_pin = int(form["led_pin"][0])
            if "led_active_low" in form: state.led_active_low = (form["led_active_low"][0] == "1")
            if "relay_pin" in form: state.relay_pin = int(form["relay_pin"][0])
            if "ips" in form:
                state.android_ips = form["ips"][0]
            state.save()
            print("[+] Config saved and pushed live!")
            
            # Push config to devices
            for dev in state.parse_device_list():
                params = f"price={state.coin_price}&minutes={state.minutes_per_coin}&admin_pin={state.admin_password}"
                if dev["name"]:
                    params += f"&device_name={urllib.parse.quote(dev['name'])}"
                send_authenticated_async(dev["ip"], state.target_port, "/config", "/challenge", params)

            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")

        elif path == "/insert_coin":
            success = trigger_coin_event()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "success": success,
                "message": f"Simple beam coin drop (₱{state.coin_price:.2f}) processed successfully (+{state.minutes_per_coin}m)!"
            }).encode("utf-8"))

        elif path == "/insert_ucoin":
            pulses = int(query.get("pulses", form.get("pulses", [1]))[0])
            success = trigger_universal_coin_event(pulses)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({
                "success": success,
                "pulses": pulses,
                "message": f"Universal coin drop (₱{pulses}) processed successfully!"
            }).encode("utf-8"))

        elif path == "/add_time":
            mins = int(form.get("add_minutes", [60])[0])
            action = form.get("action", ["add"])[0]
            if action == "subtract":
                mins = -abs(mins)
            target = form.get("target_ip", ["ALL"])[0]
            send_add_time(mins, target)
            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()

        elif path == "/one_vs_one":
            state.p1_ip = form.get("p1_ip", [""])[0]
            state.p2_ip = form.get("p2_ip", [""])[0]
            state.match_minutes = int(form.get("match_minutes", [15])[0])
            winner = form.get("winner", [""])[0]
            state.save()

            if winner and state.p1_ip and state.p2_ip:
                if state.p1_ip == state.p2_ip:
                    state.match_status_msg = "<div style='background:#ffebee;color:#c62828;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>❌ <b>Match Blocked:</b> Player 1 and Player 2 cannot be the same device!</div>"
                else:
                    if winner == "p1":
                        send_add_time(state.match_minutes, state.p1_ip)
                        send_add_time(-state.match_minutes, state.p2_ip)
                        state.match_status_msg = f"<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 1 Won:</b> Transferred +{state.match_minutes}m to Player 1 and deducted -{state.match_minutes}m from Player 2.</div>"
                    elif winner == "p2":
                        send_add_time(state.match_minutes, state.p2_ip)
                        send_add_time(-state.match_minutes, state.p1_ip)
                        state.match_status_msg = f"<div style='background:#e8f5e9;color:#2e7d32;padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px;'>🏆 <b>Player 2 Won:</b> Transferred +{state.match_minutes}m to Player 2 and deducted -{state.match_minutes}m from Player 1.</div>"

            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()

        elif path == "/reset_vault":
            pw = form.get("reset_pw", [""])[0]
            if pw == state.admin_password:
                state.total_coins_lifetime = 0
                state.total_coins_session = 0
                state.total_earnings_lifetime = 0.0
                state.total_earnings_session = 0.0
                state.save()
                print("[💰 VAULT] Lifetime revenue counter reset to 0.")
            self.send_response(303)
            self.send_header("Location", "/")
            self.end_headers()

        elif path == "/reboot":
            print("[🔄 REBOOT] Rebooting emulator...")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"REBOOTING")

        elif path == "/factory_reset":
            state.factory_reset()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")

        elif path == "/crash_report":
            body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", errors="ignore")
            print(f"\n[⚠️ CRASH REPORT FROM CLIENT]\n{body}\n")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")

        else:
            self.send_response(404)
            self.end_headers()

# =============================================================================
# INTERACTIVE TERMINAL CLI
# =============================================================================
def run_cli():
    time.sleep(0.5)
    print("\n" + "="*70)
    print(" 🚀 ESP32-C3 KIOSK HARDWARE EMULATOR CONSOLE")
    print("="*70)
    print(" Available Commands:")
    print("   • coin           : Simulate Simple Beam Coin (GPIO 4: configured price & minutes)")
    print("   • ucoin <pulses> : Simulate Universal Multi-Coin drop (e.g. ucoin 1, 5, 10, 20)")
    print("   • add <mins>     : Broadcast add time to all registered devices")
    print("   • arm [device_id]: Manually arm coin slot for 15 seconds")
    print("   • status         : Print current vault stats and registered devices")
    print("   • help           : Show this help menu")
    print("   • quit / exit    : Stop emulator")
    print("="*70 + "\n")

    while True:
        try:
            cmd = input("kiosk-c3> ").strip()
            if not cmd:
                continue
            parts = cmd.split()
            c = parts[0].lower()

            if c == "coin":
                trigger_coin_event()
            elif c == "ucoin":
                pulses = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 1
                trigger_universal_coin_event(pulses)
            elif c == "add":
                mins = int(parts[1]) if len(parts) > 1 and parts[1].lstrip('-').isdigit() else 60
                send_add_time(mins, "ALL")
            elif c == "arm":
                dev = parts[1] if len(parts) > 1 else "TEST_DEVICE"
                state.armed_device_id = dev
                state.armed_until = time.time() + 15
                print(f"[⚡] Armed coin slot for device '{dev}' for 15 seconds!")
            elif c == "status":
                print(f" Vault Total Coins: ₱{state.total_coins_lifetime} (Session: ₱{state.total_coins_session})")
                print(f" Simple Beam Rate : ₱{state.coin_price:.2f} -> {state.minutes_per_coin} mins")
                print(f" Registered IPs   : {state.android_ips or '(None)'}")
                print(f" Tracked Devices  : {len(state.tracked_devices)}")
            elif c in ("help", "?"):
                print("Commands: coin | ucoin <1|5|10|20> | add <mins> | arm [id] | status | quit")
            elif c in ("quit", "exit"):
                print("Stopping emulator...")
                os._exit(0)
            else:
                print(f"Unknown command: '{cmd}'. Type 'help' for options.")
        except (KeyboardInterrupt, EOFError):
            print("\nExiting emulator...")
            os._exit(0)

# =============================================================================
# MAIN ENTRYPOINT
# =============================================================================
def main():
    parser = argparse.ArgumentParser(description="ESP32-C3 Master Kiosk Hardware Emulator")
    parser.add_argument("--port", type=int, default=8055, help="HTTP Web Management Port (Default: 8080)")
    parser.add_argument("--ws-port", type=int, default=81, help="WebSocket Server Port (Default: 81)")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Bind host (Default: 0.0.0.0)")
    args = parser.parse_args()

    http_port = args.port
    ws_port = args.ws_port

    # Start WebSocket Server thread
    ws_thread = threading.Thread(target=run_ws_server, args=(args.host, ws_port), daemon=True)
    ws_thread.start()

    # Start HTTP Server
    server = None
    try:
        server = ThreadedHTTPServer((args.host, http_port), KioskHTTPHandler)
    except PermissionError:
        print(f"[!] Cannot bind to port {http_port} (permission required). Falling back to port 8080...")
        http_port = 8080
        server = ThreadedHTTPServer((args.host, http_port), KioskHTTPHandler)
    except Exception as e:
        if http_port != 8080:
            print(f"[!] Port {http_port} failed: {e}. Trying port 8080...")
            http_port = 8080
            server = ThreadedHTTPServer((args.host, http_port), KioskHTTPHandler)
        else:
            raise e

    # Find local IPs to display friendly URLs
    local_ips = ["127.0.0.1"]
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ips.append(s.getsockname()[0])
        s.close()
    except Exception:
        pass

    print("\n" + "="*70)
    print(" 🔥 ESP32-C3 KIOSK WEB PORTAL EMULATOR RUNNING!")
    print("="*70)
    for ip in set(local_ips):
        print(f" 🌐 Web Admin Console : http://{ip}:{http_port}")
        print(f" ⚡ WebSocket Server  : ws://{ip}:{ws_port}/ws")
    print("="*70 + "\n")

    http_thread = threading.Thread(target=server.serve_forever, daemon=True)
    http_thread.start()

    # Run terminal CLI on main thread
    run_cli()

if __name__ == "__main__":
    main()
