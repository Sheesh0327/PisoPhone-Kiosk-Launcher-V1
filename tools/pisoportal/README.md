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

Guest-network limits (a public network gets any kind of traffic): a request head must arrive within 10 s and 8 KB; at most
8 connections per client address and 4 x `MAX_CLIENTS` in total; at most `MAX_CLIENTS` open coin pages (3 per device); a
coin page must be opened from the portal itself (WebSocket Origin check); a page that stays silent for 65 s (a browser
answers the 20 s pings by itself) is dropped. Nothing a client sends can stop the program: input is handled without
panicking, and a panic would abort it (procd then restarts it, and recovery settles any open window).

Reconcile: the ledger is compared with the box's own coin count (`lifetime_pulses`), which starts again at 0 whenever the
box's revenue is collected. The portal keeps a base in `DATA_DIR/reconcile.state` and moves it when that count goes down
(checked every 10 minutes); `pisoportal reconcile rebase` moves it by hand.

CLI: `pisoportal serve | box | reconcile [rebase] | verify | report [days] | selftest | version`. Admin (router only):
`/admin/start?mac=&plan=`, `/admin/status?mac=`, `/admin/finish?mac=`, `/admin/info` (used by `piso-setup test-coin`).
Config: `/etc/coinslot.conf` (written by `piso-setup`); keys are listed in `src/config.rs`.

## Build and test
```
cargo test
cargo build --release
python3 tests/test_flow.py        # whole flow against a fake box and fake ndsctl
python3 tests/test_browser.py     # real Chromium (needs: pip install playwright)
```
CI (`router-program.yml`) builds `bin/pisoportal-mipsel` for `mipsel_24kc` with the OpenWrt toolchain (the exact
toolchain is recorded in `bin/BUILD-INFO`), regenerates `setup/piso-setup.sh` (which embeds that binary) and commits both
to `beta`. `quality.yml` checks rustfmt, clippy (`-D warnings`), the unit tests, `tests/test_flow.py` and
`tests/test_browser.py` on every push. You do not need Rust on the router or on your computer.
