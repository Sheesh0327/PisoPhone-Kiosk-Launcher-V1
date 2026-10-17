# Production hardening: master plan and status

The final pass before the system goes to more shops. Started 2026-10-17 on `beta`. Every finding below came from five independent
read-only audits (coin box money path, phone credit path, router portal, signing and updates, router setup) and was then
re-checked against the code before anything was changed. Each fix has a test that fails on the old code where a test was possible.

**Confidence** is how sure we are that the fix is right and complete, from the code and the tests. It is not a hardware result:
nothing here has run on a real box, phone or router yet. Section 4 is what must be tried on hardware before a release reaches
100% of routers or boxes.

## 1. Plan

| Phase | What | Status |
|---|---|---|
| H0 Baseline | every local suite green; inventory of the money, update and setup paths | Done |
| H1 Audit | five independent audits; each finding re-checked by reading the code | Done |
| H2 Fix | one change per finding, a regression test each, version bumps (router 1.5.5, firmware 3.6.1) | Done for the items in section 2 |
| H3 Release | CI green on `beta`; hardware checks (section 4); merge; router at 0% then 100%; firmware OTA | Waiting on CI and the owner's hardware checks |
| H4 Next batch | the open items in section 3, in the order given there | Waiting on the owner's decisions |

## 2. Fixed in this pass

### Router program (`tools/pisoportal`, router 1.5.5)
| Finding | Fix | Test | Confidence |
|---|---|---|---|
| High: a session paid before the router's clock synced (after a power cut) lost its time when the clock jumped | the clock is watched; a forward jump moves the sessions paid with the stale clock (`roll::shift`, `Core::check_clock`) | `roll` unit test | 85% |
| Medium: the box was acknowledged after the drain timed out, erasing coins the router never saw | acknowledged only when the box said idle; otherwise left to recovery | `tests/test_link_loss.py` | 90% |
| Medium: recovery acknowledged a box still draining, and a recorded window without checking for late coins | recovery waits for `idle`; late coins are credited to the same device first, then acknowledged (the marker records the total, so a failed ack never credits twice) | `tests/test_link_loss.py` | 85% |
| Medium: a window that saw no coin because the link was down was closed as empty; the box's coins were never credited | kept on flash for recovery, no "empty" strike | `tests/test_link_loss.py` (fails on the old code) | 90% |
| Medium: a power cut right after a coin could lose the open-window record | records are flushed to flash (file and folder) and write errors are logged | existing flow tests | 80% |
| Medium: a voucher could be spent twice after a crash between crediting and marking | marked spent first; given back if the time could not be added | existing voucher flow tests | 90% |
| Low: one damaged byte emptied the session or voucher file on the next write | files are decoded leniently | existing tests | 95% |
| Low: another site's page (Origin `null`) could drive a guest's coin window | the WebSocket also refuses cross-site `Sec-Fetch-Site` | by reading; the existing origin and browser tests still pass | 85% |

### Router setup (`setup/src`, router 1.5.5)
| Finding | Fix | Test | Confidence |
|---|---|---|---|
| High: a full flash emptied `/etc/piso-setup.conf` (every password, the gateway key) | written to a private temporary file, replaced only when complete | `test_setup.py` (shows the old code empties it) | 95% |
| High: `/etc/coinslot.conf` was rebuilt from scratch, losing the owner's own lines | the owner's lines are kept; only the setup's keys are replaced | `test_setup.py` | 95% |
| High: an interrupted pairing left the box network open with the published password | a trap restores the network as it was | `test_setup.py` (interrupt) | 80% |
| High: an interrupted setup re-run left openNDS stopped (free internet) | the same trap starts openNDS again | `test_setup.py` (interrupt) | 85% |
| Medium: paying guests could reach the modem's admin page and other private networks | openNDS blocks 10/8, 172.16/12, 192.168/16 for authenticated users | dry-run settings | 75% (openNDS syntax needs the router check in section 4) |
| Medium: a failed manual update left the portal stopped; a power cut mid-update could leave an empty init script | the portal is started again on an error; text files are moved in only when all are complete | `test_setup.py`, `test_install.py` | 85% |
| Medium: a crash-looping portal passed the update health check | the portal must answer `/ping` twice | by reading (the update tests use their own health command) | 75% |

### Coin box firmware (3.6.1)
| Finding | Fix | Test | Confidence |
|---|---|---|---|
| High: a pin on the flash bus (or the C3's USB pins) saved in Settings crashed the box at every start | refused before anything is written; a stored bad pin falls back to the default at start | `host_tests/pin_rules_test.cpp` | 90% |
| Known: the Settings form's port, relay type and LED type never saved (wrong field names) | the form sends the names the box reads | web assets test | 95% |
| Medium: a refused save (weak password) left the settings half-written | everything that can be refused is checked first | by reading | 90% |
| Medium: the relay test left the acceptor powered; coins then vanished without a record | the test switches itself off after 10 s | by reading | 85% |
| Medium: a captured heartbeat with `ack_tx` could clear a phone's queued coins | heartbeats no longer acknowledge (the app never used it) | by reading | 95% |
| Medium: controller payments were not counted as revenue | counted like the other paths | by reading | 95% |
| Medium: the relay dropped for one loop pass when a session's time ran out, losing the coin in flight | the relay stays on for the whole armed state | by reading | 80% |
| Medium (suspected): the pulse counter hand-off was not under the ISR's lock | read-and-zero under the same lock as the ISR | by reading | 90% |
| Medium (suspected): secrets made at first start before the radio was on | `bootloader_random_enable()` around them | by reading | 85% |
| Low: coins dropped for 3 s after the 49.7-day `millis()` wrap | the start guard runs once | by reading | 95% |
| Low: a factory reset lost the last coins' revenue | revenue is flushed first | by reading | 95% |
| Low: anyone could sign the owner out of super admin | only the session's own holder | by reading | 95% |
| Low: HMACs compared with a non-constant-time compare | `signatureMatches` (constant time) | by reading | 95% |

The firmware cannot be compiled in this environment (the PlatformIO registry is not reachable); CI's firmware build is the
compile check.

### Phone app and CI
| Finding | Fix | Test | Confidence |
|---|---|---|---|
| High: Done tapped as the coin window ran out locked a customer who had paid time | finish runs once, only while armed, and never locks while paid time is left | `SessionRulesTest` (CI) | 90% |
| Medium: the router signing job signed whatever `main` held at signing time | it signs only the files of the approved commit | by reading | 85% |

## 3. Open: not fixed in this pass

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
| 10 | App, **decision** | Paid time starts counting while the lock screen still waits for more coins | start it at Done, or keep as is? |
| 11 | Security | Router programs are built on `beta` and signed without a reproducible build check | rebuild on `main` in the signing job and compare |
| 12 | Security | First phone setup trusts the website's APK (USB path installs with `-d`, no certificate pin) | pin the certificate fingerprint in the page and the setup program |
| 13 | Security | The admin QR key is used inside a page the box serves over HTTP | sign in the owner's own app instead |
| 14 | Security | `RELEASES_TOKEN` is readable from any branch; the security rules miss one-line keys | move it to the protected environment; add patterns |
| 15 | Box | Replay windows on signed phone calls are wider than intended; one account per slot not enforced on the box | record timestamps; sign out the other account |
| 16 | Router | One guest can block others (voucher guessing limit is global; empty windows hold the slot) | per-device limits |

## 4. Hardware checks before 100%

Do these on a test site (or the owner's own router at 0% rollout) with router 1.5.5 and firmware 3.6.1:

1. **A normal day**: phone payments, Wi-Fi payments (one coin, several coins, a top-up), a voucher, a pause and resume.
2. **Power cut**: switch the router off for 10 minutes, switch it on with the modem still off, pay for Wi-Fi, then switch the modem on. The paid
   minutes must still be there after the clock syncs (`logread | grep "clock jumped"`).
3. **Box link lost during a window**: insert a coin, then switch the coin box off for 40 s right after Done, then on. Within a few
   minutes the coin is credited once (`logread | grep recover`).
4. **Guest isolation (R4)**: from a paying guest phone, `nc -zv 10.0.0.1 22`, open `http://10.0.0.10/`, `http://10.0.0.1:8081/` and the
   modem's address (for example `http://192.168.1.1/`): all must fail; normal websites must work.
5. **Interrupted setup**: start `piso-setup pair`, press Ctrl-C during the wait: the box network is back as it was and openNDS runs.
6. **Settings on the box**: save a bad pin (for example 9): it is refused with a reason and nothing changes; save the relay type and the
   port: they stay after a restart.
7. **Relay test**: switch the relay on from the dashboard: it switches itself off after 10 s.
8. **Updates**: a router at 1.5.4 takes 1.5.5 by itself; a box at 3.6.0 takes 3.6.1 from its dashboard.

## 5. Release order

1. Push `beta`, wait for Code quality, the APK build and the router-program build to be green.
2. Merge to `main` (CLAUDE.md section 5): router 1.5.5 is published at 0%, firmware 3.6.1 to the update feed.
3. Run section 4 on the owner's router (raise its rollout to 100% only for it, or install by hand).
4. Raise the router rollout to 100%; update the boxes from their dashboards.
5. Update `CLAUDE.md` section 2.
