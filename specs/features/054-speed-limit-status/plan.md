# Plan: feature 054, the speed-limit status line

Context manifest (constitution rule 1): unresolved ledger entries loaded: none for this area (the two 053 entries about `cargo fmt` and the wide font are the reason for criterion 8).

| file | purpose | lines | est_tokens |
|---|---|---|---|
| `tools/pisoportal/src/nds.rs` | `ndsctl` calls: `auth` (kbit/s rates), `detail()` (byte counters per customer) | 185 | read by range |
| `tools/pisoportal/src/console.rs` | `overview()` (line 379), `clients()` (line 476: speed from two counter readings), routes | 1033 | read by range |
| `tools/pisoportal/src/fair.rs` | an existing background tick that reads every customer's kB (the place to sample) | 73 | 1,000 |
| `tools/pisoportal/src/config.rs` | `ENDURANCE_DOWN_KBPS`, `ENDURANCE_UP_KBPS` (cap in kbit/s, 0 = none) | 302 | read by range |
| `tools/pisoportal/src/console.html`, `console-ui.js` | the Overview tiles (`tile()`, `ICO`), script v12 | 183, 163 | read by range |
| `tools/pisoportal/tests/fake_ndsctl.sh`, `harness.py` | the fake openNDS: per-customer `DL`, `UL`, state files | 49 | 700 |
| `tools/pisoportal/tests/test_console_layout.py`, `test_console.py` | the browser and API tests to extend | 377, 328 | read by range |
| `setup/src/30-router-settings.sh` (lines 93 to 95, 249 to 253) | flow offloading off; bursting on | read | 400 |

## Task groups
Small, ordered, each finished and checkable on its own. Test first. The owner says "go" for each group.

- [ ] 0. Evidence from the router: the owner runs `ndsctl json` (one client), `ndsctl status` and `tc -s qdisc show` on the router that sells PisoWiFi; I write `evidence/group0-findings.md` (which fields exist, whether byte counters grow, what the cap looks like, whether `tc` exists) and correct the spec if a field is missing. No code. (files: `evidence/`; test: none; red result logged: n/a)
- [ ] 1. The rule as a pure function: new `limits.rs` (`verdict(samples, caps, now) -> Limits`), unit tests for every case in criterion 1, red first (files: `src/limits.rs`, `src/main.rs`; test: `cargo test limits`; red result logged: yes)
- [ ] 2. The sampler and the field: a 30-second tick keeps RAM-only samples of Endurance customers (64 at most), `overview()` adds `limits`; API tests with the fake openNDS, including "only read commands" and "nothing written under DATA_DIR" (files: `src/core.rs` or `src/fair.rs`, `src/console.rs`, `tests/test_console.py`, `tests/fake_ndsctl.sh`; red result logged: yes)
- [ ] 3. The tile: page and script v13, the four states, owner wording, icon; browser test at 1280 and 390 px, light and dark, contrast, wide font forced; compatibility with the v12 script and the 1.5.33 page kept as fixtures (files: `src/console.html`, `src/console-ui.js`, `tests/test_console_layout.py`, `tests/golden/`; red result logged: yes)
- [ ] 4. Validate: all gates run locally including `cargo fmt --check`, `cargo clippy -D warnings`, the wide-font browser tests, the router and setup tests; `validation.md`; manual, `CHANGELOG.md`, RS65, `specs/STATUS.md`
- [ ] 5. Release (only when the owner asks): `beta` route as for 1.5.33; decode the program inside the signed file; the owner raises the rollout after RS65

## Release
Router release: raise `setup/RELEASE` (1.5.34), `console-ui.js` version 13 (built in). A router change goes through `beta` (`CLAUDE.md` section 5): bring `main` into `beta`, push, wait for CI's commits, check Code quality on the code commit, merge to `main`, decode the program inside the signed file. Generated programs and setup files are never edited by hand. Signed at 0%; the owner raises it after RS65.
