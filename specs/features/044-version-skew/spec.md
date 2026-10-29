# Feature 044: show on the console when the router or the coin box is waiting for an update (version skew, design B3)

Status: **approved by the owner 2026-10-29 (D38; Q1 to Q3 answered with the recommendations)**   Branch: `feat/044-version-skew` (cut from the tip of `feat/036-site-status-panel`, because it adds a line next to 036's money line on the console front page; 036 is not merged, so rebase onto `main` once it is)   Area: router (setup scripts, monitor, console)
Backlog rows: epic `specs/spec_03_overhaul.md` feature 044 (split out of 036 by D35), D6   Release: router only. **No firmware release, no app release.**

## Intent
The owner can already have the router update itself and the coin box every hour. What the owner cannot see, without Telegram, is *that* one of them is waiting and *why*: the update scripts decide and hold, and tell Telegram only. A shop that is never quiet, a shop with automatic updates turned off, and an update that failed three times and was given up all look exactly like a shop that is up to date. This feature makes the update scripts record their state, and shows it as one quiet line on the console front page and, when something has waited too long or failed, in `alerts.txt`. It only shows and warns: it never blocks a coin, a pairing or a page, and it changes nothing about *how* anything is updated (D6).

## What was found (read in the code on 2026-10-29, as the epic requires)
The epic said "nothing notices when the app, firmware and router feeds drift out of step". For the router and the coin box that is **mostly already built**; the gap is smaller and different:
1. **Both are already compared and installed by the router, every hour.** `setup/src/90-updates.sh` (`cmd_self_update`) fetches `router.json`, runs the signature-checked `pisoportal update-check <router.json> <installed> <router id>` (exit 0 available, 10 current, 11 not this router's turn in a staged rollout, 1 refused) and installs. `setup/src/91-box-update.sh` (`cmd_box_update`) reads the box's firmware from `GET /api/diagnostics` (`box_info`), compares with `firmware.json` (`ver_newer`, a strict three-number compare) and installs through the box's own signed-image check. Both run from cron (`install_update_timer`, `90-updates.sh:183`), the box half an hour after the router. So **no new fetching and no new comparing is needed**; the epic's design (the box holds the expected set) and my first draft of this spec (a new Rust compare and a new monitor fetch) both duplicated this.
2. **They hold, for reasons the script already names, and say so only to Telegram.** `_hold` is one of: automatic updates are off (`AUTO_UPDATE=0`), customers are online, a phone has time running (box only), the box is still checking its last image, or the same version failed three times (`BOX_UPDATE_TRIES`). A refusal, a failed download and "the box did not come back" are also reported. All of it goes through `notify`, which calls the monitor's `send` (Telegram) and nothing else; and the "available but held" message is sent **once per version** (`UPDATE_NOTIFIED`, `BOX_UPDATE_NOTIFIED`).
3. **So three things are invisible to a shop without Telegram, and two are weak even with it:** (a) automatic updates off: nothing says an update is waiting; (b) a shop that is never quiet (customers online or a phone with time running all day): the update waits for "the next quiet moment" for ever, with one Telegram message on the first day; (c) given up after three failures: one message; (d) the console shows no update state at all; `alerts.txt` gets nothing from the update scripts. This is the same reason AO-06 made `alerts.txt` exist.
4. **The console can already read what the shell writes.** The portal reads `BOX_ADMIN_PASS` and other keys from the setup conf (`conf_value` on `cfg.setup_conf`, `console.rs:482`), and the monitor already writes `alerts.txt`, which the console shows. So the shell scripts record state, the monitor raises alerts from it, and the console reads it: three small steps, no network code.
5. **Phone apps are not covered** (unchanged from the first draft): the heartbeat carries no app version, so checking them needs a box <-> phone contract change (both sides, new vectors, app and firmware releases). Out of scope; a later feature if wanted.
6. **A box built as the `-phone` or `-wifi` variant reads its own feed path** (`Product.h`); the router's `box-update` already compares with the full build's feed. Existing behaviour, not changed or made worse here.

## Invariants touched
- Money and time: **not touched.** The scripts that decide updates are not changed in what they decide; they only record what they decided. No ledger, window, price or coin-path file changes (criterion 6).
- Only owner-signed software: **not touched, and kept.** Nothing is installed from anything new. `update-check`'s signature check, the sha256 and size check of the box image, and the box's own signed-image check run exactly as before. No TLS check is touched; `fetch_url` is unchanged.

## Constraints
- Warn and show only (D6). The update scripts' decisions, their holds, their retry counts and their Telegram messages (the words and when they are sent) do not change; existing `test_selfupdate.py` and `test_boxupdate.py` pass with the same counts.
- Flash wear: the persistent record is written only when the state changes (or the first time), never once an hour while nothing changed. The "last checked" time goes to RAM (`/tmp`), like other hourly stamps.
- The setup conf holds secrets (`BOX_ADMIN_PASS`, `GW_KEY`): the console may read only the new keys, and nothing secret is copied into `alerts.txt`, the overview JSON or a log.
- A change under `setup/src/`, `router/` or `tools/pisoportal/` raises `setup/RELEASE`; generated files are rebuilt, never edited by hand (`CLAUDE.md` section 4). Installer stays under 1700 KB. Shell is BusyBox `ash`-clean and ShellCheck-clean for POSIX sh.

## What each person sees (the states; the owner reads the line, so "without the condition" means every case with no clear answer)
Placement: a quiet line on the console front page under 036's money line, always shown, one sentence for the router and one for the coin box. Words reuse the scripts' own hold texts.

| # | Situation (router or coin box) | The line says | Tone | alerts.txt |
|---|---|---|---|---|
| 1 | on the published release | "Router 1.5.27 is up to date." / "Coin box 3.12.4 is up to date." | neutral | no |
| 2 | newer release published, will install at the next hourly check (auto on, nothing blocking) | "Router 1.5.28 is available and installs at the next check." | neutral | no |
| 3 | waiting for a quiet moment (customers online, or a phone has time running) | "Coin box 3.12.5 is waiting for a quiet moment." | neutral, then gold after the grace time: "... has waited N days for a quiet moment. Install it when you close: piso-setup box-update." | after the grace time, one line a day |
| 4 | automatic updates are off | "Coin box 3.12.5 is available. Automatic updates are off: run piso-setup box-update." | neutral, then gold after the grace time | after the grace time, one line a day |
| 5 | failed three times and given up, or refused (by the router's signature check, the file check, or the box) | "Coin box 3.12.5 could not be installed: <the script's reason>. Run piso-setup box-update to try by hand." | gold **at once** | one line when it happens, then one a day |
| 6 | a newer router release is rolling out in stages and this router's turn has not come | "Router 1.5.28 is rolling out in stages; this router's turn has not come." | neutral, never warns | no |
| 7 | the coin box is newer than the published release (the owner's own build) | "Coin box 3.13.0 is newer than the published 3.12.4." | neutral | no |
| 8 | cannot tell: no internet to the website, the box does not answer, no box password, the box answers 503 | "Coin box: not checked: <reason>." | neutral | no |
| 9 | the last hourly check is more than 3 hours old (cron stopped, router just restarted) | the lines above followed by "(checked N hours ago)" or "(not checked yet)" | as above | no |
| 10 | a PisoPhone-only shop with no router console | not applicable | | |

## Success criteria
1. `cmd_self_update` and `cmd_box_update` record, after every run in `auto` and `check` mode, one of the states in rows 1 to 8 for the router and for the box, with the latest version, the reason and the time it was first seen. Tests in `router/tests/test_selfupdate.py` and `test_boxupdate.py` (which already run the real setup file against a fake website and a fake box) cover every state, including each hold reason, three failures, a refusal, a staged-rollout wait, a box newer than the feed, a 503 and a missing password. They fail first (logged in `ledger/`).
2. The existing assertions of both test files, and their Telegram texts and send-once behaviour, pass unchanged. `git diff` of the two scripts shows additions (recording) only, plus no change to any `_hold`, `notify`, `conf_set BOX_UPDATE_TRIES` or `fetch_url` line.
3. The persistent record is written only on a change of state: running `auto` twice with nothing changed writes the setup conf once (a test counts changes), and `since` survives a router restart.
4. The monitor's `check_updates` (a cheap read, at most hourly, like `check_ledger`) raises through the existing `alert` function: rows 3 and 4 after the grace time, one line a day; row 5 at once; rows 1, 2, 6, 7, 8 never. Updating clears it. Tested in `router/tests/test_monitor.py` with fixture conf files.
5. `/api/overview` carries a `versions` object (router and box: state, latest, since, checked); no secret is in it. The front page draws the line for rows 1, 3, 5, 8 in light, dark and at 360 px (browser test).
6. A router and a box that are waiting still take a coin, pair a phone and sell time: the portal tests give the same counts as before and `git diff` shows no change to `core.rs`, `ledger.rs`, `pricing.rs`, `voucher.rs`, `boxlink.rs`, `esp32_firmware/` or `app/`.
7. `bash scripts/check_security_rules.sh` passes; no `-k` / `--insecure`; the setup conf's secrets do not appear in the overview JSON or `alerts.txt` (a test greps both for the fixture password and key).
8. `setup/RELEASE` raised, `python3 tools/build_piso_setup.py --check` passes, `router/tests/test_footprint.py` passes, hardware check **RS59** added to `docs/REAL_WORLD_TESTING.md` (a real router and box: the line shows each state; a never-quiet shop warns after the grace time).

## Out of scope
Phone app versions; a line on the box's own dashboard (needs a firmware change); changing when or how anything is installed (for example installing in a busy shop); doing anything about a skew; checking the signature of the version text shown (it comes from state the already-verified scripts wrote); telling box variants apart; a version history page; changing the Telegram messages.

## Owner decisions
Answered by the owner on 2026-10-29:
- **D38: approved.** 044 is router only and writes no new fetch or compare code; the epic's "router + firmware" and "the box holds the expected set" are withdrawn.
- **Q1: grace time 24 hours** for the router and the coin box alike (rows 3 and 4). Failures (row 5) warn at once.
- **Q2: always show** the quiet "up to date" lines.
- **Q3: a shop that is never quiet is told, not changed.** The line says how long the update has waited and the command to run at closing time; when or how updates install does not change (that would be a separate feature).
