# Validation: setting up a router in a real shop (feature 011, parts A and B)

Work in progress: filled group by group while building. The final table of the 15 criteria is written in group 12.

## Criterion 1: baseline (group 1)
Measured on branch `feat/011-offline-setup` at `c647fef` (rebased on `beta` `b1db87f`), 2026-10-23, before any change. `pip install playwright` was possible in this session (Chromium was already installed), so the browser-backed files were run too.

| Check | Result |
|---|---|
| `router/tests/test_setup.py` | 379 checks, 0 failures (BusyBox-only decoding test skipped: busybox not installed) |
| `router/tests/test_install.py` | 39 checks, 0 failures |
| `router/tests/test_pisophone_setup.py` | 189 checks, 0 failures (QR decoding skipped: zxing-cpp / pillow not installed; card drawing was skipped here before Playwright was installed) |
| `router/tests/test_phone_only.py` | 39 checks, 0 failures |
| `router/tests/test_setup_web.py` | 139 checks, 0 failures |
| `router/tests/test_setup_tools.py` | 87 checks, 0 failures |
| `router/tests/test_setup_phone_web.py` | 44 checks, 0 failures |
| `bash scripts/check_security_rules.sh` | all passed |
| `python3 tools/build_piso_setup.py --check` | up to date |

Not run, by the owner's instruction: `cargo test` and `tools/pisoportal/tests/test_flow.py` (this feature touches no Rust).
