# PisoPhone Kiosk Architecture & Workflow Constitution

## 1. The Architect-Implementer Workflow (Strict)
This repository uses `anthropics/claude-code-action` for autonomous execution. 
* **If you are acting as the Architect (Opus):** DO NOT write extensive boilerplate or execute massive refactors directly. Your primary job is to diagnose, plan, and create GitHub Issues.
* **Issue Creation Protocol:** Every GitHub issue you create MUST include the exact target file paths, the specific function/contract changes, the required verification command, and the exact trigger phrase **`@claude`** to automatically dispatch the implementer model.
* **If you are acting as the Implementer (Sonnet/Haiku):** You were triggered by an issue. Read the issue, execute the exact file modifications requested, run the verification command, and open a Pull Request.

## 2. System Architecture Map
PisoPhone converts an Android device into a payphone kiosk, communicating with an ESP32-C3 coin pulse detector and an OpenWrt captive portal.
* **Android (`app/`):** Kiosk lockdown app utilizing Lock Task Mode and Device Owner. Communicates via a local HTTP backend (`KioskHttpServer.kt`)[cite: 1].
* **Firmware (`esp32_firmware/`):** ESP32-C3 C++ code compiled via PlatformIO. Handles pulse detection, embedded web assets, and HMAC authentication (`GatewayCoinslot.h`)[cite: 1].
* **Router (`router/` & `tools/pisoportal/`):** OpenWrt configuration, shell scripts (`piso_monitor.sh`), and the Rust-based captive portal backend[cite: 1].

## 3. Definition of Done & Verification Gates
You must run the appropriate tests and achieve a clean exit code before concluding any task:
* **ESP32 Firmware:** Run `cd esp32_firmware/host_tests && ./run.sh`[cite: 1]. Use `uint32_t` instead of `unsigned long` for 32-bit/64-bit cross-compatibility between the host and the ESP32[cite: 2].
* **Router/Portal:** Run `cd router && python3 -m pytest tests/test_monitor.py`[cite: 1]. 
* **Android App:** Ensure Gradle builds cleanly by running `./gradlew assembleDebug`[cite: 1]. Use Kotlin Coroutines, never RxJava.

## 4. Progressive Disclosure (Read Before Modifying)
Do not guess API contracts, deployment flows, or security rules. If you touch these domains, read the authoritative documentation first:
* **Coin Pulse/Network Handshake:** Read `docs/api/gateway-coinslot.md` and `docs/api/superadmin-credentials.md`[cite: 1].
* **Router Deployment:** Read `docs/DEPLOY.md`[cite: 1].
* **Anti-Spoofing:** Refer to `ReplayGuard.kt` for timestamp and nonce enforcement rules before modifying the Android server[cite: 1].