#!/usr/bin/env python3
"""
PisoPhone Universal Multi-Coin Slot Access Script
Allows external devices, Raspberry Pis, POS terminals, and backend microservices
to interact with the ESP32 Multi-Coin Controller over HTTP/REST.

Features:
- Configurable ESP32 Static IP (via CLI argument --ip or env var ESP32_IP)
- Automatic timeout disarming (default 60s)
- Immediate manual disarm when transaction is complete so the slot is available for others
- Real-time pulse and status polling
"""

import sys
import time
import argparse
import os
import json
import urllib.request
import urllib.error

DEFAULT_ESP32_IP = "192.168.1.10"
DEFAULT_PORT = 80

def get_base_url(ip: str, port: int) -> str:
    return f"http://{ip}:{port}"

def http_get(url: str, timeout: int = 5) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "CoinslotClient/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            return json.loads(body)
        except Exception:
            return {"status": "error", "http_code": e.code, "error": body}
    except Exception as e:
        return {"status": "error", "error": str(e)}

def http_post(url: str, data: dict = None, timeout: int = 5) -> dict:
    encoded_data = urllib.parse.urlencode(data).encode("utf-8") if data else b""
    req = urllib.request.Request(url, data=encoded_data, headers={"User-Agent": "CoinslotClient/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            return json.loads(body)
        except Exception:
            return {"status": "error", "http_code": e.code, "error": body}
    except Exception as e:
        return {"status": "error", "error": str(e)}

def cmd_status(base_url: str):
    url = f"{base_url}/api/coinslot/status"
    print(f"[*] Querying coin slot status from {url}...")
    res = http_get(url)
    print(json.dumps(res, indent=2))

def cmd_activate(base_url: str, session_id: str, timeout_sec: int):
    url = f"{base_url}/api/coinslot/activate"
    data = {"session_id": session_id, "timeout": str(timeout_sec)}
    print(f"[*] Activating coin slot at {url} (Session: {session_id}, Timeout: {timeout_sec}s)...")
    res = http_post(url, data)
    print(json.dumps(res, indent=2))

def cmd_disarm(base_url: str, session_id: str):
    url = f"{base_url}/api/coinslot/disarm"
    data = {"session_id": session_id}
    print(f"[*] Disarming coin slot at {url} (Session: {session_id})...")
    res = http_post(url, data)
    print(json.dumps(res, indent=2))

def cmd_collect(base_url: str, session_id: str, timeout_sec: int, poll_interval: float = 1.0):
    print(f"=======================================================")
    print(f"  PISOPHONE COIN INSERTION SESSION")
    print(f"  Controller IP: {base_url}")
    print(f"  Session ID:    {session_id}")
    print(f"  Auto-Disarm:   {timeout_sec}s timeout")
    print(f"=======================================================")

    # 1. Activate Coin Slot
    act_url = f"{base_url}/api/coinslot/activate"
    act_res = http_post(act_url, {"session_id": session_id, "timeout": str(timeout_sec)})
    
    if act_res.get("status") != "ok" and not act_res.get("is_armed"):
        print(f"[-] Failed to arm coin slot: {act_res}")
        sys.exit(1)

    print(f"[+] Coin slot ARMED successfully. Awaiting coins (press Ctrl+C to stop and disarm)...")

    start_time = time.time()
    last_pulses = 0

    try:
        while time.time() - start_time < timeout_sec:
            elapsed = int(time.time() - start_time)
            remaining = timeout_sec - elapsed

            stat_url = f"{base_url}/api/coinslot/status"
            stat_res = http_get(stat_url)

            if stat_res.get("status") == "ok":
                pulses = stat_res.get("pulses", 0)
                is_armed = stat_res.get("is_armed", False)

                if pulses != last_pulses:
                    diff = pulses - last_pulses
                    print(f"  -> [COIN INSERTED] +{diff} pulse(s) | Total Session: {pulses} pulse(s) (₱{pulses})")
                    last_pulses = pulses

                if not is_armed:
                    print(f"[*] Coin slot was disarmed by controller (auto-timeout expired).")
                    break

            time.sleep(poll_interval)
            print(f"\r  Listening... Time remaining: {remaining:02d}s | Collected: ₱{last_pulses}", end="", flush=True)

    except KeyboardInterrupt:
        print("\n\n[*] Interrupted by operator.")

    finally:
        print(f"\n[*] Releasing & disarming coin slot immediately to free it for other devices...")
        disarm_url = f"{base_url}/api/coinslot/disarm"
        disarm_res = http_post(disarm_url, {"session_id": session_id})
        print(f"[+] Final session summary: ₱{last_pulses} collected. Status: {disarm_res.get('state', 'IDLE')}")

def main():
    default_ip = os.environ.get("ESP32_IP", DEFAULT_ESP32_IP)

    parser = argparse.ArgumentParser(description="PisoPhone Universal Coinslot Access CLI")
    parser.add_argument("--ip", default=default_ip, help=f"ESP32 Static IP address (default: {default_ip} or env ESP32_IP)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"ESP32 HTTP port (default: {DEFAULT_PORT})")
    
    subparsers = parser.add_subparsers(dest="command", help="Command to execute")

    # Status
    subparsers.add_parser("status", help="Get coin slot status and total pulse count")

    # Activate
    p_act = subparsers.add_parser("activate", help="Arm coin slot")
    p_act.add_argument("--session-id", default=f"cli_{int(time.time())}", help="Session ID")
    p_act.add_argument("--timeout", type=int, default=60, help="Auto-disarm timeout in seconds (default: 60)")

    # Disarm
    p_dis = subparsers.add_parser("disarm", help="Disarm coin slot manually")
    p_dis.add_argument("--session-id", default="", help="Session ID to release")

    # Collect session (interactive)
    p_col = subparsers.add_parser("collect", help="Interactive payment session with live polling and immediate manual disarm")
    p_col.add_argument("--session-id", default=f"cli_{int(time.time())}", help="Session ID")
    p_col.add_argument("--timeout", type=int, default=60, help="Session duration before auto-disarm (default: 60s)")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    base_url = get_base_url(args.ip, args.port)

    if args.command == "status":
        cmd_status(base_url)
    elif args.command == "activate":
        cmd_activate(base_url, args.session_id, args.timeout)
    elif args.command == "disarm":
        cmd_disarm(base_url, args.session_id)
    elif args.command == "collect":
        cmd_collect(base_url, args.session_id, args.timeout)

if __name__ == "__main__":
    main()
