# pisoportal: the router's coin portal

One small resident program (Rust, static, a few hundred KB) that is the whole customer side of PisoWiFi on the router.
openNDS only redirects and enforces (FAS mode, `fas_secure_enabled 1`); `pisoportal` serves the coin page from memory and
talks to every customer over one WebSocket (Insert Coin, live coins, Done). Nothing is spawned per request.

| Part | File |
|---|---|
| Page (bilingual, WebSocket only) | `src/page.html` |
| HTTP/WebSocket, admin interface (127.0.0.1:8099) | `src/http.rs` |
| Coin windows, settlement, recovery, cooldown | `src/core.rs` |
| Signed box calls and coin events (UDP 8101) | `src/boxlink.rs` |
| openNDS (`ndsctl auth/deauth/json`) | `src/nds.rs` |
| Pricing, roll file, hash-chained ledger, fair use, config | `src/pricing.rs`, `src/roll.rs`, `src/ledger.rs`, `src/fair.rs`, `src/config.rs` |

Rules that matter: access is granted only when the customer is done paying (the phone closes its login page at first
internet); one coin window at a time; a window is priced once, recorded in the roll and ledger, acknowledged to the box, then
granted; open windows survive a restart (`DATA_DIR/open`, `.rec` markers prevent double credit).

CLI: `pisoportal serve | box | reconcile | verify | report [days] | selftest | version`. Admin (router only):
`/admin/start?mac=&plan=`, `/admin/status?mac=`, `/admin/finish?mac=`, `/admin/info` (used by `piso-setup test-coin`).
Config: `/etc/coinslot.conf` (written by `piso-setup`); keys are listed in `src/config.rs`.

## Build and test
```
cargo test
cargo build --release
python3 tests/test_flow.py        # whole flow against a fake box and fake ndsctl
python3 tests/test_browser.py     # real Chromium (needs: pip install playwright)
```
CI (`rust-router-probe.yml`) builds `bin/pisoportal-mipsel` for `mipsel_24kc` with the OpenWrt toolchain, regenerates
`setup/piso-setup.sh` (which embeds that binary) and commits both to `beta`. You do not need Rust on the router or on your computer.
