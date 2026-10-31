# Handoff 2026-10-31: features 057 (done on a computer) and 056 (groups 1 to 3 done)

Branch `ccr-e31098f5-7k2rgz` (the session branch; not pushed to `beta` or `main`). Last commit: `d899233` (056 group 3). Nothing is released.

## Done
- **057 package-manager layer** (`specs/features/057-package-manager-layer/`): `setup/src/12-pkgmgr.sh` (opkg, apk or none; image type official / fork / custom), the router installer,
  `install.sh` and the setup program no longer refuse apk, security rule 11, documents, RS67. Validated on a computer (`validation.md`); router 1.5.35 raised in `setup/RELEASE`. Run only against
  fakes and the official OpenWrt 23.05 / 24.10 / 25.12 images in a Docker container (`dockerd` has to be started by hand; Docker Hub rate-limits, retry).
- **056 groups 1 to 3** (`specs/features/056-optional-sqm/`): `setup/src/13-optional.sh` (optional packages, status file `etc/coinslot.d/packages.txt`), `setup/src/14-sqm.sh`
  (`piso-setup sqm apply|install|status`, status file `etc/coinslot.d/sqm.txt`). Tests: `router/tests/test_optional.py` (21), `test_sqm.py` (34), `test_pkgmgr.py` (56).

## Decisions made on the way (owner to confirm)
- Footprint budget of `setup/piso-setup.sh.in` raised 150, then 160 (057), then **175 KB** for all of 056 (it is 162 KB now).
- irqbalance is installed but **not switched on**; the owner has not said whether the setup should enable it.
- The shell side of `sqm install` refuses only when customers are online (`ndsctl`). The **coin window / payment-in-progress check is the portal's job** (group 5c): no shell command can see it.
- The cake option strings and `layer_cake.qos` in `14-sqm.sh` are first guesses to be fixed after the bench test (plan group 0 and 9).

## Open
- Owner's router facts (commands in the chat) for `image-facts.md` part B; RS67; RS66 (not written yet).
- Whether the image keeps package signature checking on (not checked by the layer yet); CPU ceiling for fast plans; `maxclients` (056 owner decision 1).
- Cloudflare speed endpoints could not be reached from the build session (unverified).
- Taking 1.5.35 through `beta` (needs the owner's go-ahead; `CLAUDE.md` section 5).

## Next step (056, in this order, test first, one commit each)
1. Group 5b: `piso-setup sqm-test` (refuses `BUSY` with customers online; stops the sqm service for the test and always restarts it; parallel `curl` to Cloudflare with a data cap; CPU from `/proc/stat`; writes `/tmp/piso-speedtest.json`).
2. Group 4 and 5c: portal `GET/POST /api/settings` with the `sqm` fields (validation 0.1 to 10000 Mbps, margin 50 to 100) saved with `override_set`, `POST /api/speedtest` (one at a time, one a minute, refused while a coin window is open), hand-over to `piso-setup sqm apply` / `sqm-test` outside the request thread.
3. Groups 5 and 5d: the console card and the Test my line button (greyed out with a reason when SQM is missing or customers are online).
4. Groups 6 to 8: openNDS/firewall unchanged check (flow offloading 0), documents (RS66), gates. Group 9 and group 0 need the owner's router.

## Slow tests
`cd tools/pisoportal && cargo test && cargo build --release && python3 tests/test_flow.py` (about a minute; `test_flow.py` needs the release build). Router tests: `python3 router/tests/test_*.py` (the web ones need `CHROME=/opt/pw-browsers/chromium-*/chrome-linux*/chrome`). `python3 tools/build_piso_setup.py` after any `setup/src` change; `--check` must pass.
