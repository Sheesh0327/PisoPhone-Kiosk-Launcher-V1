# GEMINI.md: Antigravity Guidelines for TechNet PisoHub

Spec-driven development: read `constitution.md`, then `specs/README.md`, `specs/STATUS.md`, and `specs/RELEASE_STATE.md`. Skills are in `.agents/skills/sdd-*`. No code is written without an approved feature spec.

---

## 1. Project Purpose and Core Objectives
**TechNet PisoHub** sells time via coin slots for real shops operating daily. A single ESP32 coin box coordinates two products:
- **PisoPhone**: Rental Android phones running a locked kiosk app (`app/`) metered by the minute via prepaid QR time cards.
- **PisoWiFi**: Customer Wi-Fi sold per coin through an OpenWrt router running openNDS and the compiled Rust daemon `pisoportal` (`tools/pisoportal/`, `router/`, `setup/`).
- **Coin Box Firmware**: ESP32 pulse counting and hardware control (`esp32_firmware/`).
- **Support & Distribution**: Signed update feeds (`website/update/`), setup pages (`website/`), protocol definitions (`protocol/`), and owner scripts (`scripts/`).

### Non-Negotiable Core Principles
1. **Money and time are never wrong.**
   - The coin box commits transactions to persistent storage (NVS) before anything else.
   - Payments are credited exactly once (idempotent transaction IDs on phones, coin window IDs on routers).
   - Zero state or money lost across sudden power cuts.
2. **Only software signed by the owner is installed.**
   - Firmware, router binaries, and kiosk APK updates are cryptographically signed.
   - Signing keys never live in the repository or CI outside protected signing environments.

---

## 2. Spec-Driven Development (SDD) & Constitutional Rules
Follow [constitution.md](constitution.md) and [specs/README.md](specs/README.md):
- **Spec First**: No code changes without an approved `specs/features/NNN-name/spec.md` and `plan.md`.
- **Zero Raw File Dumps**: Hard cap of 200 lines / 1,500 tokens per snippet. Use targeted `grep -n` and line ranges. Never dump large files.
- **Negative Constraint Ledger**: Whenever a test fails or unexpected behavior is found, log it in [ledger/negative-constraints.md](ledger/negative-constraints.md) before implementing the fix.
- **Test-First Implementation**: Write the test first, observe failure, record the red result, fix in source, regenerate generated files, and confirm the test passes.
- **Spec is the Contract**: If requirements are missing or ambiguous during implementation, stop and propose a precise edit to `spec.md` for owner approval first.

---

## 3. Generated and Published Files (Never Edit Directly)
Always edit the source and run the generator. Never edit these directly:

| Target File | Source / Generator Command |
|---|---|
| `esp32_firmware/include/WebAssets.h` | `python3 scripts/embed_web.py` (from `esp32_firmware/web/`) |
| `setup/piso-setup*.sh` (+ `.sha256`), `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py` | `python3 tools/build_piso_setup.py` (from `setup/src/*.sh`, `setup/pisophone_setup.py`); `--check` must pass |
| `tools/pisoportal/bin/*` | Built via CI workflow `router-program.yml` |
| `website/update/*`, `website/flash/*` | Published via CI signing workflows |
| `app/schemas/*` | Room database export during Gradle build |

---

## 4. Branching and Release Rules
- Branch convention: `feat/NNN-name` or `fix/NNN-name`.
- **Router Changes**: Any change modifying router code (`tools/pisoportal/src/`, `setup/`, `router/`, `setup/RELEASE`) must go through `beta` to let CI build the multi-arch router programs.
- Do not merge to `main`, push to `beta`, or trigger releases without explicit owner confirmation.
- After releases, update [specs/RELEASE_STATE.md](specs/RELEASE_STATE.md) and [specs/STATUS.md](specs/STATUS.md).

---

## 5. Verification Commands Before Push
Run the appropriate verification gates for any touched area before requesting review or merging:
- **Mandatory for all changes**: `bash scripts/check_security_rules.sh`
- **Router & Portal**:
  - `cargo test` in `tools/pisoportal`
  - `python3 tests/test_flow.py` in `tools/pisoportal`
  - `python3 router/tests/test_setup.py`
  - `python3 router/tests/test_pisophone_setup.py`
  - `python3 router/tests/test_setup_web.py`
  - `python3 router/tests/test_setup_tools.py`
- **Setup Program**:
  - `python3 router/tests/test_setup_phone_web.py`
  - `python3 router/tests/test_phone_only.py`
  - `python3 tools/build_piso_setup.py --check`
- **Python Code Verification Gate**:
  - `ruff check <files>` + `mypy <files>` + `pytest -x`
- **Android App**:
  - `./gradlew testDebugUnitTest`
  - `./gradlew ktlintCheck`
- **ESP32 Firmware**:
  - clang-format check (`esp32_firmware/.clang-format`)
  - `bash esp32_firmware/host_tests/run.sh`
