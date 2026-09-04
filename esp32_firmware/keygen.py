#!/usr/bin/env python3
"""
PisoPhone Device Activation Key Generator
Generates a unique 12-character activation key for a given ESP32 MAC address
using the device-specific 256-bit secret key.

Usage:
    python keygen.py <MAC_ADDRESS> <DEVICE_SECRET>
    python keygen.py 12:34:56:78:90:AB e9a3b7c1f4d8...
"""

import sys
import hmac
import hashlib

def generate_activation_code(mac: str, secret: str) -> str:
    mac_clean = mac.strip().upper()
    secret_bytes = secret.strip().encode('utf-8')
    mac_bytes = mac_clean.encode('utf-8')
    
    h = hmac.new(secret_bytes, mac_bytes, hashlib.sha256).hexdigest()
    return h[:12].upper()

def main():
    if len(sys.argv) < 3:
        print("Usage: python keygen.py <MAC_ADDRESS> <DEVICE_SECRET>")
        print("Example: python keygen.py 24:D7:EB:12:34:56 a1b2c3d4e5f6...")
        sys.exit(1)
        
    mac = sys.argv[1]
    secret = sys.argv[2]
    
    code = generate_activation_code(mac, secret)
    print("=" * 48)
    print("PISOPHONE ACTIVATION CODE GENERATOR")
    print("=" * 48)
    print(f"MAC Address   : {mac}")
    print(f"Activation Code: {code}")
    print("=" * 48)

if __name__ == "__main__":
    main()
