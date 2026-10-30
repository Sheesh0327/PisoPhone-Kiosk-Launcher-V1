# pisoportal: the router's coin portal

One small resident program (Rust, static, a few hundred KB) that is the whole customer side of PisoWiFi on the router.
openNDS only redirects and enforces (FAS mode, `fas_secure_enabled 1`); `pisoportal` serves the coin page from memory and
talks to every customer over one WebSocket (Insert Coin, live coins, Done). Nothing is spawned per request.
It uses only the `sha2`, `hmac` and `p256` crates. Which routers work: `docs/ROUTERS.md`. How the program gets onto a router: `setup/README.md`.

## Layout
| Part | File |
|---|---|
| Page (English, WebSocket only; shared style in `src/base.css`) | `src/page.html` |
| HTTP/WebSocket, admin interface (127.0.0.1:8099) | `src/http.rs` |
| Coin windows, settlement, recovery, cooldown | `src/core.rs` |
| Signed box calls and coin events (UDP 8101) | `src/boxlink.rs` |
| openNDS (`ndsctl auth/deauth/json`) | `src/nds.rs` |
| Pricing, roll file, hash-chained ledger, fair use, config | `src/pricing.rs`, `src/roll.rs`, `src/ledger.rs`, `src/fair.rs`, `src/config.rs` |
| Reconcile against the box's coin count, voucher codes, guests sending a file | `src/reconcile.rs`, `src/voucher.rs`, `src/send.rs`, `src/send.html` |
| Shop branding of the guest page: settings, signed brand pack, the branded page, Filipino texts | `src/brand.rs`, `src/brandpack.rs`, `src/brandpage.rs`, `src/brand_page.css`, `src/brand_fil.js` |
| The owner's console, its Branding screen, the box's admin page inside it | `src/console.rs`, `src/console.html`, `src/console-ui.js`, `src/console-verify.js`, `src/brandconsole.rs`, `src/boxview.rs` |
| Signed router updates | `src/update.rs` |
| Tests, a fake box and a fake `ndsctl` | `tests/` |
| The programs CI builds for each router family | `bin/` |

## How it behaves
Rules that matter: access is granted only when the customer is done paying (the phone closes its login page at first
internet); one coin window at a time; a window is priced once, recorded in the roll and ledger, acknowledged to the box, then
granted; open windows survive a restart (`DATA_DIR/open`, `.rec` markers prevent double credit).

Guest-network limits (a public network gets any kind of traffic): a request head must arrive within 10 s and 8 KB; at most
8 connections per client address and 4 x `MAX_CLIENTS` in total; at most `MAX_CLIENTS` open coin pages (3 per device); a
coin page must be opened from the portal itself (WebSocket Origin check); a page that stays silent for 65 s (a browser
answers the 20 s pings by itself) is dropped. Nothing a client sends can stop the program: input is handled without
panicking, and a panic would abort it (procd then restarts it, and recovery settles any open window).

Reconcile: the ledger is compared with the box's own coin count (`lifetime_pulses`). Box firmware newer than 3.3.3 never
resets that count when revenue is collected (a collection is a logged event on the box); older firmware started it again at 0
at every collection. The portal keeps a base in `DATA_DIR/reconcile.state` and moves it when that count goes down
(checked every 10 minutes), so both work; `pisoportal reconcile rebase` moves it by hand.

CLI: `pisoportal serve | box | reconcile [rebase] | verify | report [days] | selftest | version`. Admin (router only):
`/admin/start?mac=&plan=`, `/admin/status?mac=`, `/admin/finish?mac=`, `/admin/info` (used by `piso-setup test-coin`).
Config: `/etc/coinslot.conf` (written by `piso-setup`); keys are listed in `src/config.rs`.

### Router updates (`src/update.rs`)
`pisoportal update-check <router.json> <installed release> <router id>` and `update-verify <router.json> <router-setup.sh>` are what
`piso-setup self-update` uses: ECDSA P-256 (the owner key, `owner_key.b64`, built in; empty = every update refused), a signature over
`pisophone-router-v1|<version>|<sha256>|<size>|<rollout>`, a newer version only, and a staged rollout by a hash of the router's own
id. Tests: `cargo test` (a signature made by the Python tool) and `router/tests/test_selfupdate.py` (the whole path; builds this
program with `--features test-owner-key`, which only tests use).

## Build
From this folder:
```
cargo build --release
```
You do not need Rust on the router or on your computer to ship: CI (`router-program.yml`) builds `bin/pisoportal-<family>` for each router
family (`mipsel`, `mips`, `aarch64`, `armv7`, `x86_64`) with the OpenWrt toolchain (the exact toolchain is recorded in `bin/BUILD-INFO`),
regenerates `setup/piso-setup.sh` (which embeds that binary) and commits both to `beta`.

## Test
```
cargo fmt --check
cargo clippy --locked --release --all-targets -- -D warnings
cargo test
python3 tests/test_flow.py        # whole flow against a fake box and fake ndsctl
python3 tests/test_browser.py     # real Chromium (needs: pip install playwright)
```
The browser tests use Chromium found on the computer, or the one named in `CHROME=<path>`. `quality.yml` (job `pisoportal`) runs these
and the other `tests/test_*.py` files on pushes and pull requests to `main` and `beta` that touch this folder's source, tests or Cargo files.

## Generated files
`bin/*` is written by the `router-program` workflow on pushes to `beta`. Never edit it by hand, and do not open it (`CLAUDE.md` section 4).
A change under `src/` changes what routers install: it goes through `beta` and raises `setup/RELEASE` (`CLAUDE.md` section 5).

## See also
`router/README.md`, `setup/README.md`, `tools/README.md`, `docs/api/gateway-coinslot.md` (the box's router API), `docs/ARCHITECTURE.md` (section 5),
`docs/RELEASE.md`.
