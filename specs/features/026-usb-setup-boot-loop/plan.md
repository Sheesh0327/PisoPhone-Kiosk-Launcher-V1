# Plan: a new board must not crash in a loop (feature 026)

Context manifest: `esp32_firmware/src/main.cpp` lines 94 to 112 and 228 to 268 (the early return and the code after it), `esp32_firmware/src/WebServerModule.cpp` line 46 (`setupWebServer`). Ledger: none unresolved.

- [x] 1. Read the owner's serial log and the code; name the line.
- [x] 2. The fix: `WiFi.mode(WIFI_STA);` in the "no Wi-Fi saved" branch of `startWifiConnect()` (a host test cannot run Arduino's Wi-Fi; CI compiles it for all four boards).
- [ ] 3. clang-format 23.1.2, host tests with sanitizers, `check_security_rules.sh`; push; CI (four firmware builds) green.
- [ ] 4. `PISO_FW_VERSION` 3.12.2, CHANGELOG, RS50, ledger; merge to `main` (owner asked); CI builds and publishes the images; merge `main` into `beta`.

## Release
Firmware 3.12.2 (CI signs and publishes). The router and the app are not touched.
