# Plan: 057 one package-manager layer (opkg and apk)

Context manifest (constitution rule 1): `file | purpose | lines | est_tokens`. Unresolved ledger entries loaded: none.

| file | purpose | lines | est_tokens |
|---|---|---|---|
| setup/src/20-preflight.sh | `command -v opkg` refusal (line 34), `update_failed`, `install_packages` (lines 64 to 86) | 87 | 1.9k |
| setup/install.sh | the one-line installer's own `opkg` check (grep `opkg`, read ranges) | (grep) | read ranges only |
| setup/pisophone_setup.py | `ApkRouter` and the ssh probe (about lines 1986 to 2010) | (range) | 0.6k |
| setup/src/96-space-backup.sh, 15-flash.sh | free space (the layer's `free_space`) | 67 | 1.2k |
| router/tests/test_setup.py, test_install.py | fake-`opkg` tests to extend with a fake `apk` and with neither | (grep) | ranges only |
| scripts/check_security_rules.sh | add the forbidden-flag rule | (grep) | ranges only |
| docs/ROUTERS.md (lines 35 to 60), setup/README.md (line 109) | documents | ranges | ranges only |

Never opened: `setup/piso-setup*.sh`, `website/install.sh` (generated).

## Task groups
- [ ] 0. **Facts from the real image (no code).** From the owner's router: release file, `uname -r`, which of opkg/apk exist, repository files, `opkg print-architecture` or `apk --print-arch`, `opkg list-installed | wc -l` or `apk list -I | wc -l`, whether `ndsctl` and `/etc/init.d/opennds` exist. Write `specs/features/057-package-manager-layer/image-facts.md`. (test: n/a)
- [ ] 1. **The layer** (`setup/src/` new piece, sourced before preflight): detect, update, is_installed, available, dry_run, install, remove, free_space, name map. (test: new `router/tests/test_pkgmgr.py` with fake `opkg`, fake `apk`, neither; red result logged: yes)
- [ ] 2. **Use it in the router installer.** `20-preflight.sh` `install_packages` and `update_failed` call the layer; the "no opkg" refusal becomes "no package manager and no openNDS". (test: `test_setup.py` unchanged results with fake opkg; plus apk and neither cases; red: yes)
- [ ] 3. **`install.sh` and the setup program.** Stop refusing apk; keep refusing an unknown release. (test: `test_install.py`, `test_setup_phone_web.py`, `test_phone_only.py`; red: yes)
- [ ] 4. **Security rule.** Forbidden flags in `check_security_rules.sh` with a failing and a passing test. (red: yes)
- [ ] 5. **Documents** (criterion 6). No code.
- [ ] 6. **Gates and hardware.** `bash scripts/check_security_rules.sh`; `cargo test` and `python3 tests/test_flow.py` in `tools/pisoportal` (nothing there changes, they must still pass); the four router test files; `test_setup_phone_web.py`, `test_phone_only.py`; `python3 tools/build_piso_setup.py --check`; ruff/mypy on touched Python. Then the run on the owner's image (criterion 7) and `validation.md`.

## Release
Raise `setup/RELEASE` (1.5.34 now; 056 raises it again after 057). Regenerate with `python3 tools/build_piso_setup.py`. Router path through `beta` (`CLAUDE.md` section 5): CI builds the program and setup files; decode the program inside the signed file before anyone raises the release.
