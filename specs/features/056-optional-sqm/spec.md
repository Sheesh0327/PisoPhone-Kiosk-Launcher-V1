# Feature 056: Optional smooth-line packages (SQM) and a console setting to match the real ISP speed

Status: **approved 2026-10-31; speed test added 2026-10-31 and the owner said proceed; in progress (groups that need the router wait)**   Branch: `feat/056-optional-sqm` (a router change: from `beta`, see `CLAUDE.md` section 5; this session works on `ccr-e31098f5-7k2rgz`)   Area: setup + router
Backlog rows: owner request 2026-10-31 (no backlog row)   Release: router (through `beta`)

## Intent
When many customers share one internet line, a download by one phone fills the modem's queue and everybody's ping jumps (bufferbloat). The router can
prevent that with SQM ("smart queue management": cake or fq_codel on the WAN), but the installer installs nothing for it today (it installs only
`opennds curl ca-bundle jsonfilter libmicrohttpd-no-ssl`). This feature makes the installer **try to add the smoothing packages when the router's
package feed has them**, never at the cost of the setup, and adds a **Settings card in the router console** where the owner types the real speed of the
line (from an ordinary speed test) so SQM is set just under it. Serves the goal that a non-programmer owner runs a shop with nothing to read or copy.

## Invariants touched
- Money and time (a payment recorded first, credited once, nothing lost on a power cut): **not touched.** No change to `roll.rs`, `ledger.rs`,
  `core.rs` grant/credit paths, the coin window or the box. Installing or switching SQM never starts while a coin window is open (SQ-10).
- Only owner-signed software (OTA, signing, keys): **kept.** Packages come from the router's own signed opkg feeds, as openNDS does today. No package file
  is added to the repository or fetched from anywhere else. The new code reaches routers only inside the signed router release.

## Constraints
- **openNDS stays the one required package.** If it cannot be installed the setup stops exactly as it does today (`could not install opennds`).
- Every other package here is **optional**: a failure to find, fit or install one never stops the setup, never changes what the setup does otherwise,
  and is **reported** (summary, `piso-setup status`, console).
- Install priority after openNDS, owner's answer 2026-10-31: (1) `sqm-scripts` with `kmod-ifb` and `tc` (the tc package that `sqm-scripts` needs; the
  installer takes `tc-full` if the feed has it, else what `sqm-scripts` pulls in), (2) `kmod-sched-cake`, (3) `irqbalance`. `luci-app-sqm` is **not**
  installed (it would duplicate the console setting). The first group is all-or-nothing: a half-installed shaper is removed again.
- **Package manager: opkg or apk** (owner, 2026-10-31: the router is a custom, unofficial ImmortalWrt image, and the setup must work with either manager).
  This feature does **not** call `opkg` or `apk` directly: it uses the layer from **feature 057** (detect, update, is-installed, dry-run install, install,
  remove). 056 cannot start before 057 is built.
- **Scan, then install (owner's answer 2026-10-31):** for each optional package the installer (1) first checks whether the router **already has it**: the
  package installed, or for the kernel parts the module already built in or loadable (`sch_cake`, `ifb`, `sch_fq_codel`; read from the kernel, no guessing by router
  model), then (2) if not, **dry-runs** the install through the layer (`opkg install --noaction` / `apk add --simulate`) and installs only if that passes.
  A kmod package from a feed never fits a custom kernel (modules are tied to the exact kernel build): that shows as "does not fit this image's kernel" in the
  report and is never forced (no `--force-*`, no `--allow-untrusted`).
- **Caution by image type (owner, 2026-10-31; labels from feature 057):** on an `official` OpenWrt image every optional package in the list is tried (still scan, then dry run, so the
  guard against a bad install is the same). On a `fork` or `custom` image a package is installed only if it first passes the built-in check and the dry run, and anything else is
  skipped and reported. The label sets how cautious the installer is; the probes always decide, so a wrong label cannot cause a bad install. The image type and what it means are
  shown in the setup summary, in `piso-setup status` and on the console's Settings card (under the SQM card), for example "Image: ImmortalWrt 23.05 (known fork)".
- **No package manager at all** (a custom image with everything baked in): the setup carries on if openNDS is already installed and working (checked by the
  program and its init script); the optional packages show "no package manager" or "already built in".
- **Flash guard:** optional packages are skipped, with the reason recorded, when free flash is under **2048 KB** (the installer's existing warning level,
  `setup/src/15-flash.sh`). Owner's reasoning: updates are downloaded to RAM, not flash. Caveat for the owner (not a blocker): the docs say an update keeps a
  ~0.9 MB backup of the old program on flash, and a router with little RAM keeps the update file on flash too; with 2 MB free before the packages that margin can
  be thin. The packages' real size is **not measured yet**; validation must measure it (SQ-13).
- SQM shapes **only the WAN link as a whole.** The per-plan speed caps (Endurance 5 Mbps by default), the openNDS settings (bursting, `checkinterval` 15,
  `ratecheckwindow` 1) and **flow offloading = off** stay exactly as they are (checked by `router/tests/test_setup.py`).
- SQM is **off after install.** It is switched on only when the owner saves line speeds in the console. Default margin **95%** of the typed speed, which
  the owner can change (50 to 100%).
- **Speed test on the router (owner request 2026-10-31, replaces "the owner types the speed" as the main way; typing stays possible).** The Settings card has a **Test my line**
  button. The router runs the test itself and **prefills the two boxes; nothing changes on the line until the owner presses Save.** How it works, as answered:
  - **Server:** Cloudflare's public speed endpoints (`speed.cloudflare.com`, `__down` and `__up`) with `curl` (the installer already installs it) over several parallel streams, a short fixed
    list of fallback hosts kept in one place in the code. **Data only: nothing downloaded is ever run or stored.** The data is capped (about 250 MB per direction, about 10 to 15 seconds each),
    because a fast line would otherwise use gigabytes. **I could not reach this host from the build session, so the endpoint names and what they accept are unverified (plan group 0).**
  - **When:** only when **no customer is online** (no authenticated client in `ndsctl json`) and no coin window or payment is in progress; otherwise the button is greyed out with the reason and the
    owner can still type a speed. If a customer connects and pays during a test, the test stops at once and the line is put back.
  - **SQM during the test:** if SQM is on, the test **stops the SQM service for its duration** (it never edits the saved SQM settings, so a power cut or reboot brings shaping back by itself) and
    starts it again afterwards, also after an error; the whole test has a hard limit of about 90 seconds.
  - **Honest number:** the router reads its CPU use during the test. If the CPU was near full, the result is shown with "this router may be the limit: confirm with a phone speed test" and is not
    presented as the line speed. A test that cannot reach any host reports that, and prefills nothing.
  - **Safety:** one test at a time, at most one per minute, behind the console password and its same-origin check, with no input from the page in the command that runs. The portal hands the work to
    `piso-setup sqm test` (root) and reads the result from a small status file; it does not run curl in a request thread.
  - **Shown:** measured down and up, the value SQM would use at the margin, the host used, and how old the test is.
- Qdisc: **cake** when `kmod-sched-cake` is present, else **fq_codel**, and the console says which one is in use. cake options: `nat`, `dual-dsthost`
  (download), `dual-srchost` (upload), `wash`, `diffserv3`; the exact strings are fixed in the plan after the bench test.
- The program (`pisoportal`) uses only `sha2`, `hmac` and `p256` (`CLAUDE.md` section 6): no new crates. It does not run `uci`/`opkg` itself in its
  request threads; it hands the work to `piso-setup` (a root script) and reads the result back from a small status file.
- Anything under `setup/src/` or `tools/pisoportal/src/` changes what routers install: raise `setup/RELEASE`; generated files only by
  `python3 tools/build_piso_setup.py`; go through `beta`; **before anyone raises the release, decode the program inside the signed file and check it has the new text.**

## What a visitor without the condition sees (spec rule 3b)
- **Router where SQM was not installed** (not in the feed, too little flash, no internet, 25.x): the Settings card is shown **greyed out** with the
  reason in plain words and an **Install now** button where trying again could work (not for "not in the feed" or "OpenWrt too new"). Nothing is hidden.
- **SQM installed but no speeds saved yet:** the card says "Line speed not set; smoothing is off" and shows the empty fields. Customers notice nothing.
- **Customers (the guest page):** see nothing new, ever. This feature adds no guest-page text.
- **Console login:** the card is behind the same console password as every other setting.

## Success criteria
1. **openNDS first, others optional.** A test installer run (fake `opkg`) where openNDS fails stops with the same message as today; where only an optional
   package fails (not in feed, install error, flash under 2048 KB) the setup finishes and the summary lists what was skipped and why.
2. **Image type:** an `official` image is offered the whole optional list; a `fork` or `custom` image only what passes the probes; the report and the console show the label and the reason.
3. **Scan:** with a feed that has only some packages, exactly those are installed, in the priority order above; `kmod-sched-cake` missing gives fq_codel.
   Parts the kernel already has (module built in or loadable) are not installed again and show "already built in"; a package whose dry run fails (for example a
   kernel mismatch) is skipped with that reason. Tested with a fake `opkg` **and** a fake `apk`, and with neither.
4. **All-or-nothing core:** if `sqm-scripts` installs but `kmod-ifb` or `tc` fails, `sqm-scripts` is removed again and SQM is reported "not available".
5. **Status file:** the installer writes a status of each optional package (installed / skipped / failed + reason) that `piso-setup status` and the
   console both read.
6. **Settings card:** Download Mbps, Upload Mbps, margin % (default 95), an on/off switch. It rejects 0, non-numbers and values outside
   1 to 10000 Mbps / 50 to 100%, saves nothing on a bad value, and shows "SQM will limit to X down / Y up Mbps".
7. **Apply:** saving writes `/etc/config/sqm` for the WAN's real device (resolved at apply time, so PPPoE works) with 95% of the typed speeds, and
   restarts SQM; switching off removes the shaping. `tc qdisc show` on the router shows cake (or fq_codel) on the WAN and on its `ifb`.
8. **Per-plan caps untouched:** the generated openNDS and firewall settings in `router/tests/test_setup.py` are identical with and without SQM
   (flow offloading still 0).
9. **No money path touched:** the diff changes nothing under the grant/credit/roll/ledger code; `cargo test` and `python3 tests/test_flow.py` pass.
10. **Install now on a router that is already running:** nothing is installed by an automatic update. The button installs the optional packages once,
   and **refuses while a coin window is open or a payment is being recorded**, saying so; it can be run again.
11. **Router reboot:** after a reboot with SQM on, the shaping comes back by itself (sqm-scripts' own init), checked on the bench.
12. **Bench test before release (stop point, SQ-12):** on the 360T6M, with a phone on Endurance and a second phone downloading, ping to 1.1.1.1 is
    measured with SQM off and on, with openNDS's own caps active. If SQM and openNDS's shaping clash (caps no longer hold, or CPU stays near 100%),
    **stop and ask the owner**; do not release.
13. **Measured cost:** the flash used by the optional packages and the idle RAM they add are measured on the 360T6M and written in `docs/ROUTERS.md`.
14. **Speed test, no customers online:** the test measures both directions through a fake `curl`, fills the two boxes with the results, and saves nothing. With SQM on, the service is stopped for the test and
    started again, and still started again when the test fails or is interrupted (tested with a fake `sqm` init script).
15. **Speed test refused while a customer is online** or a coin window is open: the button is greyed out with the reason, and a direct call is refused with `BUSY` and starts nothing. A customer who connects mid-test stops it.
16. **CPU limit and failure:** a test run with the CPU near full shows the "this router may be the limit" warning; a test that reaches no host says so and prefills nothing; a second test within a minute is refused.
17. **Bench (stop point):** on the 360T6M, the router's number is compared with a phone's Ookla result on the same line; both are written in `validation.md`. If the router's number is more than about 15% below the phone's, the
    spec is changed before release (the test may then only be offered as a lower bound). Cloudflare's endpoints are reached from the router first (plan group 0).
18. Documents: `docs/ROUTERS.md` (the package list and the reasons), `setup/README.md`, `CHANGELOG.md`, a new hardware check **RS71** in
    `docs/REAL_WORLD_TESTING.md`, `specs/RELEASE_STATE.md` when released. `bash scripts/check_security_rules.sh` and every router check in `CLAUDE.md` section 3 pass.

## Out of scope
- Replacing or changing openNDS's per-plan speed limits, or turning cake's per-host fairness into the way plans are enforced (riskier for money and time rules).
- A speed test at peak hours, a scheduled re-test, or SQM that follows the line speed by itself (`cake-autorate`): later features.
- `luci-app-sqm`, `qos-scripts`, `nft-qos`; Wi-Fi tuning; conntrack size. (Routers with apk and routers with no package manager are no longer out of scope: feature 057 and the rule above.)
- A feed address the owner types in (only owner-signed or the router's own signed feeds are used).
- Raising openNDS `maxclients` (default believed 250) or the portal's `MAX_CLIENTS` (24, portal page connections): see owner decision 1.
- Automatic installation of the packages on live shops by the hourly update.

## Owner decisions
Answered 2026-10-31: the router runs the speed test and prefills the boxes (Cloudflare endpoints with fallbacks; only when no customer is online; the owner presses Save; SQM is paused during the test and restored; a warning when the CPU was the limit; typing a speed stays possible); SQM off until speeds are entered; new setups plus a button for existing routers; skip with a clear message
on failure; packages sqm-scripts + kmod-ifb + tc, kmod-sched-cake, irqbalance; flash guard at the minimum (2048 KB); SQM shapes only the whole WAN;
greyed-out card with a reason; 95% margin, changeable.

Decided while building (please confirm): **the footprint budget of the installed command `setup/piso-setup.sh.in` was raised from 160 KB (set by feature 057) to 175 KB** (`router/tests/test_footprint.py`). It was 148 KB before 057; the optional-package step alone took it to 162 KB and the `sqm` commands and the speed test are still to come. About 27 KB more than before on a 16 MB router, around 10 KB once compressed on flash. If you prefer a tighter limit, tell me which pieces to cut.

Still open:
1. **Also raise the user limits?** Your first question was about "as many users as the router can handle". SQM fixes the lag; it does not raise the
   number of devices. Do you want openNDS `maxclients` and the connection-tracking table set for many users in this feature, or in a separate one?
2. **Is irqbalance's gain worth one more package?** It only helps dual-core routers like the 360T6M; I kept it as you chose, last in priority.
3. **Is the 2048 KB guard right once the real package size is measured?** Revisit after SQ-13.
