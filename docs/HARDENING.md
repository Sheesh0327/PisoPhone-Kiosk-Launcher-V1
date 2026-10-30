# Open hardening items

What was still open after the 2026-10-17 pre-production audit and hardening pass (written then; a later release may have fixed an item, so check `CHANGELOG.md` before you start one). Everything that was fixed is in `CHANGELOG.md`; the checks to run on a router are in `docs/REAL_WORLD_TESTING.md`. The full text of the two reports this was taken from (AUDIT_REPORT.md and HARDENING_PLAN.md, both in docs/maintainers/) is in git history at commit `aba69c8`. The numbers below are the ones the roadmap and the specs use (HARDENING_PLAN item N, audit risk N).

## Open items (HARDENING_PLAN section 3)

Ordered by risk to money first. Items marked **decision** need the owner's answer before work starts.

| # | Area | Finding | Proposed fix |
|---|---|---|---|
| 1 | Box + app | The box's coin proof (`v_sig`) is also accepted as the phone's acknowledgement | give the acknowledgement its own prefix on both sides, with test vectors (protocol change) |
| 2 | Box + app | `seconds` in the status coin path is not signed | add it to the signed message (protocol change) |
| 3 | Box + app | An unsigned heartbeat reply (`lockdown`, an empty account) from a fake box can wipe paid time | sign those replies; require the discovery HMAC on the HTTP probe (protocol change) |
| 4 | Box | A coin's account credit goes to whoever is signed in when the ack arrives | store the account id with the payment when the coin is queued |
| 5 | Router | Recovery prices coins at today's prices | store the price list in the open-window record |
| 6 | Router | Rare double credit or missing sales row around a power cut between the roll and the marker | keep the window id on the ledger row and check it |
| 7 | Router | Fair use can race a pause and lift its 1 kbit/s | re-check under the file lock before granting |
| 8 | Router | The monitor does not watch the portal's health | `/ping` in the monitor, restart after 3 failures, alert |
| 9 | App, **decision** | Phones with no box secret accept the public legacy key | refuse it once no box runs in legacy mode: are any of your boxes still in legacy mode? |
| 11 | Security | Router programs are built on `beta` and signed without a reproducible build check | rebuild on `main` in the signing job and compare |
| 12 | Security | First phone setup trusts the website's APK (USB path installs with `-d`, no certificate pin) | pin the certificate fingerprint in the page and the setup program |
| 13 | Security | The admin QR key is used inside a page the box serves over HTTP | sign in the owner's own app instead |
| 14 | Security | `RELEASES_TOKEN` is readable from any branch; the security rules miss one-line keys | move it to the protected environment; add patterns |
| 15 | Box | Replay windows on signed phone calls are wider than intended; one account per slot not enforced on the box | record timestamps; sign out the other account |
| 17 | Router | Guests could reach the modem's own pages | router 1.5.7: one narrow firewall rule refuses the modem's addresses (192.168.1.1 and the router's default gateway); other private networks behind the router are still reachable and need a router check first |
| 16 | Router | One guest can block others (voucher guessing limit is global; empty windows hold the slot) | per-device limits |


## Open risks from the audit (AUDIT_REPORT "Top 10 risks")

| # | Risk | Who can use it | Severity | Status |
|---|---|---|---|---|
| 4 | Heartbeat signatures cover only `device id:time`, so an `ack_tx` (which erases a queued coin) or the phone's IP can be replayed, and a replay works after a box reboot | Someone who can sniff the kiosk LAN | Medium (not re-checked) | **Open** (E-H2) |
| 9 | One global coin slot: scripted empty windows from rotating MAC addresses can keep real customers out | A guest on the portal Wi-Fi | Medium / Low | **Open** (R-F4) |
| 10 | The auto-update paths have no staged rollout or health check on the phone side; the manual `install.sh ... update` skipped the signature (now hands over to the signed self-update) | An operator mistake, or a hijacked website | Low | **Open** (A-F9); R-F5 fixed |

## Owner questions still open (AUDIT_REPORT section 7)

- **Q5 Coin pin:** which GPIO do your classic ESP32 boxes really use for the acceptor (GPIO3 is also the USB serial receive pin)? You answered "yes", but the question asks for a pin number.
- **Q7 Rental box license:** should the signed box-type token also be able to expire, so a rental box that is not paid for drops back to 6 slots? You asked for an explanation first.
