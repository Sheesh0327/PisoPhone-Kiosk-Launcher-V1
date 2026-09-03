#!/usr/bin/env python3
import os
import sys

# Forward directly to esp32_firmware/esp32_c3_emulator.py
target = os.path.join(os.path.dirname(__file__), "esp32_firmware", "esp32_c3_emulator.py")
if os.path.exists(target):
    with open(target, "rb") as f:
        code = compile(f.read(), target, "exec")
        exec(code, {"__name__": "__main__", "__file__": target})
else:
    print(f"Error: Could not locate {target}")
    sys.exit(1)
