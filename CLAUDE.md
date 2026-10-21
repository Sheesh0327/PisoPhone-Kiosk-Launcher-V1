# CLAUDE.md: what this project is and how to work on it

Spec-driven work: read `constitution.md`, then `specs/README.md` (the loop: plan, implement, validate; skills in `.claude/skills/sdd-*`). No code without an approved feature spec.

Read this first. It is short on purpose: the details are in the documents it points to. Section 2 points to where the repository stands
today (`specs/RELEASE_STATE.md`); update that file whenever a release goes out.

## 1. What this project is, and the goal
**TechNet PisoHub** sells time with a coin slot, for real shops that run it every day. One ESP32 coin box serves two products:
- **PisoPhone**: rental Android phones (the kiosk app) that lock and unlock by the minute, with prepaid QR time cards.
- **PisoWiFi**: customer Wi-Fi sold by the coin through an OpenWrt router running openNDS and the `pisoportal` program.

Parts: `app/` (Kotlin kiosk), `esp32_firmware/` (coin box), `tools/pisoportal/` + `router/` + `setup/` (router add-on and its
installer), `website/` (setup pages and the signed update feeds), `scripts/` (owner and release tools). How they fit:
`docs/ARCHITECTURE.md`.

**The goal:** a shop owner who is not a programmer can open a shop, add phones and run it, with nothing to read or copy that is a
key; and the money is always right. Two rules decide every trade-off:
1. **Money and time are never wrong.** The box records a payment before anything else; a payment is credited exactly once (by
   transaction id on the phone, by coin window id on the router); nothing is lost on a power cut.
2. **Only software the owner signed is installed.** Firmware, router and app updates are signed; the signing keys are never in
   the repository, in a cloud session or in CI outside the protected `production-signing` environment (`docs/KEYS.md`).

Real customers use the live release. Prefer a smaller, tested change over a clever one, and say plainly what you did not test.

## 2. Where the repository is now
Release state (what is live, what has never run on real hardware, open cleanups) is in `specs/RELEASE_STATE.md`; the board of
features and owner decisions is `specs/STATUS.md`. Both change often, so they are not kept here: this file stays the same between
releases. Update `specs/RELEASE_STATE.md` whenever a release goes out. Other pointers: the hardware test checklist and issue log is
`docs/REAL_WORLD_TESTING.md`; what changed for owners is `CHANGELOG.md`.

## 3. Checks to run before you push
Run every check for the area you touched; the commands are in the table in `CONTRIBUTING.md`, "Before you open a pull request".
Always run `bash scripts/check_security_rules.sh`. For the router add-on also run `cargo test` and `python3 tests/test_flow.py`
in `tools/pisoportal`, and `python3 router/tests/test_setup.py`, `test_pisophone_setup.py`, `test_setup_web.py`,
`test_setup_tools.py`. CI (`.github/workflows/quality.yml`) runs the same for the parts a change touches. Code style: ktlint
(Kotlin), clang-format (`esp32_firmware/.clang-format`).

## 4. Generated and published files: never edit by hand
Change the source and regenerate. Do not read the big ones either; they cost a lot and tell you nothing.

| File | Made by |
|---|---|
| `esp32_firmware/include/WebAssets.h` | `python3 scripts/embed_web.py` (from `esp32_firmware/web/`) |
| `setup/piso-setup*.sh` (+ `.sha256`), `website/setup/*`, `website/install.sh`, `website/pisophone_setup.py` | `python3 tools/build_piso_setup.py` (from `setup/src/*.sh`, `setup/pisophone_setup.py`); `--check` must pass |
| `tools/pisoportal/bin/*` | the `router-program` workflow (cross-compiles and commits) |
| `website/update/*`, `website/flash/*` | CI (`firmware-images.yml`, `build-apk.yml`, `publish-router.yml`) |
| `app/schemas/*` | Room's export during the Gradle build |
| `website/js/*-bundle.js` | vendored; recorded in `website/js/VENDORED.md` (`scripts/check_vendored.py`) |

A change under `setup/src/` or `tools/pisoportal/` changes what routers install: raise `setup/RELEASE`. Firmware code needs a higher
`PISO_FW_VERSION` or boxes ignore it. A change that only touches docs, tests, the setup program on a computer or the website
pages needs no router, firmware or app release.

## 5. Branches and releasing
- Work on `beta`. Do not open a pull request, merge to `main` or release unless the owner asks.
- Do not push to `beta` while a needed "Code quality" run is in flight: a new push cancels it.
- **Merge to `main`** (when asked, and only if Code quality is green on that exact commit): merge that commit in a separate git
  worktree; take `main`'s version of `website/update`, `website/flash` and every generated router file unless the change really
  alters the router; `python3 scripts/verify_release.py` must say 0 FAIL; push; then CI commits "router release X signed and
  published (0%)". Raising to 100% is a separate, explicit step: run `publish-router.yml` on `main` with rollout 100 and check
  `verify_release.py` again. The owner-only steps (keys, approvals) are in `docs/RELEASE.md`.
- Documents that workflows or the signed router script read by path (`docs/RELEASE.md`, `docs/KEYS.md`, `docs/DEPLOY.md`,
  `docs/REAL_WORLD_TESTING.md`) must not move or be renamed.

## 6. House rules
- Match the surrounding code: naming, comment density, plain English. The router program uses only `sha2`, `hmac` and `p256`;
  the setup program uses only the Python standard library (`cryptography` is installed on request for the owner tools).
- The box <-> phone message contract changes on both sides in one change (`protocol/gen_vectors.py`,
  `esp32_firmware/host_tests/protocol_contract_test.cpp`).
- Never commit a key, password or token; never switch off a TLS check without a stated reason (`check_security_rules.sh`).
- Anything touching credit, expiry, deduction or the payment queue: say how you checked it.
- Commits say what changed and why for the owner to read; the commit trailer lines are given by the session.
- When unsure whether a change can hurt a running shop, ask the owner before shipping it.
