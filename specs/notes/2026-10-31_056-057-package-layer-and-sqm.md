# Handoff 2026-10-31: features 057 (done on a computer) and 058 (SQM, was 056; groups 1 to 3 done then)

Branch `ccr-e31098f5-7k2rgz` (the session branch; not pushed to `beta` or `main`). Last commit: `d899233` (056 group 3). Nothing is released.

## Done
- **057 package-manager layer** (`specs/features/057-package-manager-layer/`): `setup/src/12-pkgmgr.sh` (opkg, apk or none; image type official / fork / custom), the router installer,
  `install.sh` and the setup program no longer refuse apk, security rule 11, documents, RS70. Validated on a computer (`validation.md`); router 1.5.35 raised in `setup/RELEASE`. Run only against
  fakes and the official OpenWrt 23.05 / 24.10 / 25.12 images in a Docker container (`dockerd` has to be started by hand; Docker Hub rate-limits, retry).
- **058 groups 1 to 3** (`specs/features/058-optional-sqm/`): `setup/src/13-optional.sh` (optional packages, status file `etc/coinslot.d/packages.txt`), `setup/src/14-sqm.sh`
  (`piso-setup sqm apply|install|status`, status file `etc/coinslot.d/sqm.txt`). Tests: `router/tests/test_optional.py` (21), `test_sqm.py` (34), `test_pkgmgr.py` (56).

## Decisions made on the way (owner to confirm)
- Footprint budget of `setup/piso-setup.sh.in` raised 150, then 160 (057), then **175 KB** for all of 056 (it is 162 KB now).
- irqbalance is installed but **not switched on**; the owner has not said whether the setup should enable it.
- The shell side of `sqm install` refuses only when customers are online (`ndsctl`). The **coin window / payment-in-progress check is the portal's job** (group 5c): no shell command can see it.
- The cake option strings and `layer_cake.qos` in `14-sqm.sh` are first guesses to be fixed after the bench test (plan group 0 and 9).

## Open
- Owner's router facts (commands in the chat) for `image-facts.md` part B; RS70; RS71 (not written yet).
- Whether the image keeps package signature checking on (not checked by the layer yet); CPU ceiling for fast plans; `maxclients` (056 owner decision 1).
- Cloudflare speed endpoints could not be reached from the build session (unverified).
- Taking 1.5.35 through `beta` (needs the owner's go-ahead; `CLAUDE.md` section 5).

> **Renumbered 2026-10-31 (owner's answer):** the SQM feature is now **058** (`specs/features/058-optional-sqm/`), because `main` uses 056 for the voice message. Older text below and the ledger rows keep the old number 056 for the SQM work.

## Update (later the same day): groups 5b, 4, 5c, 5 and 5d are done on a computer
Branch `claude/feature-056-package-layer-sqm-er37ac` (pushed; not `beta`, not `main`). It carries a merge of `origin/main` (`6b10568`): `main` meanwhile took the voice message as **its own feature 058**
(hardware checks RS66 to RS69), so here the 057 check is **RS70** and the planned SQM check is **RS71**. The SQM feature was renumbered 058 on the owner's answer.
- 5b `piso-setup sqm test` (`setup/src/14-sqm.sh`, `router/tests/test_sqm_test.py`, 30 checks). The command is `sqm test`, not `sqm-test` (spec, plan edited).
- 4 and 5c: `tools/pisoportal/src/sqmconsole.rs`; `POST /api/settings` (`sqm_on`, `sqm_down`, `sqm_up`, `sqm_percent`), `POST /api/speedtest`, `POST /api/sqm/install`, an `sqm` block (with `speedtest` and `install`) in `GET /api/settings`. Tests: `test_console_sqm.py` 61, `test_console_speedtest.py` 34, 6 unit tests.
- 5 and 5d: the Smooth line card (`console.html`, `console-ui.js` version 13; the SQM code is skipped when the page has no card, the layout test needs that), `test_console_sqm_card.py` 29 (Chromium). `test_console_layout` 247 and `test_console_brand` 75 pass.
- Decisions to confirm: console script budget 30 to 33 KB; page check 30000 to 31000 bytes; the installed command is 173 of 175 KB (almost no room left); Install now is hidden when the setup recorded "not in the package feed", "no package manager" or "does not fit"; a result with the CPU at 90% or more is shown but not put in the boxes.
- Not proven: `test_flow.py` failed 5 of 216 once (output lost), 3 clean reruns after; the known flake. Never run on a router: Cloudflare endpoints, real `curl -T -` uploads, the real hand-over, `shellcheck`/`busybox` (not installed here). Contrast of the card was not measured.
- Python `playwright` had to be pip-installed for the browser tests; run them with `CHROME=/opt/pw-browsers/chromium-1243/chrome-linux64/chrome` (and `PISO_TEST_TARGET_DIR` for layout/brand).

## Next step (058, the SQM feature)
1. Group 6: guard test that openNDS and firewall settings are unchanged (flow offloading 0) and that no money-path file is in the diff (`test_setup.py`).
2. Group 7: documents (`docs/ROUTERS.md`, `setup/README.md`, `CHANGELOG.md`, RS71 in `docs/REAL_WORLD_TESTING.md`; manual text for the card).
3. Group 8: every gate in the plan (cargo, test_flow, router tests, security rules, `build_piso_setup.py --check`, ruff/mypy on touched Python).
4. Groups 0 and 9 need the owner's router. Release only through `beta` (needs the owner's go-ahead): raise nothing further, `setup/RELEASE` is 1.5.35; decode the program inside the signed file and check it has the SQM text.

## Slow tests
`cd tools/pisoportal && cargo test && cargo build --release && python3 tests/test_flow.py` (about a minute; `test_flow.py` needs the release build). Router tests: `python3 router/tests/test_*.py` (the web ones need `CHROME=/opt/pw-browsers/chromium-*/chrome-linux*/chrome`). `python3 tools/build_piso_setup.py` after any `setup/src` change; `--check` must pass.
