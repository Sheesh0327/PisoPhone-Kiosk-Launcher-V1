# Feature 057: One package-manager layer for the router setup (opkg and apk)

Status: **built and validated on a computer 2026-10-31; not run on a real router (RS70)**   Branch: `feat/057-package-manager-layer` (a router change: from `beta`, see `CLAUDE.md` section 5; this session works on `ccr-e31098f5-7k2rgz`)   Area: setup (router installer, `install.sh`, setup program)
Backlog rows: owner request 2026-10-31; replaces the "full apk support is a later feature" note of feature 046   Release: router (through `beta`)

## Intent
The owner's router is a custom, unofficial image (ImmortalWrt), so the setup cannot assume which package manager it has. Today the router installer, `install.sh`
and the setup program all refuse a router without `opkg` (OpenWrt 25.x and snapshots use `apk`). This feature gives the installer **one small set of package
functions that work with opkg or apk**, and lets a router with **no package manager** be set up when openNDS is already installed. It is the base for feature 056 (optional SQM
packages). Serves the owner's goal: a shop owner who is not a programmer can set up whatever router they have.

## Invariants touched
- Money and time: **not touched.** No change to the portal program, the payment path, the box or the phones.
- Only owner-signed software: **kept.** Packages still come only from the router's own configured feeds, which the package manager verifies (opkg and apk check signatures
  by default). The layer never passes `--force-signature`, `--force-checksum`, `--allow-untrusted` or `--no-check-certificate`; `scripts/check_security_rules.sh` gets a rule for it.

## Constraints
- **Functions** (shell, in `setup/src/`): detect the manager, `update`, `is_installed <pkg>`, `available <pkg>` (the feed has it), `dry_run <pkg...>` (the manager's own simulation), `install <pkg...>`, `remove <pkg>`,
  and `free_space`. Everything in the installer that calls `opkg` today goes through them. The existing messages (for example "could not install opennds",
  "the package sources of OpenWrt X do not answer") keep their meaning and name the manager in use.
- **Which manager:** the one that owns the installed system (decided on the real image in group 0; both present: the one that lists base packages). Neither present: see below.
- **Image type (owner, 2026-10-31):** the layer also says what kind of image the router runs, as one of `official` (OpenWrt), `fork` (ImmortalWrt and similar, with
  their own feeds) or `custom` (self-built, vendor, no feed, or a feed that does not fit the kernel). It is worked out from facts on the router only, never from the model name:
  `DISTRIB_ID`, `DISTRIB_DESCRIPTION`, `DISTRIB_REVISION` and `DISTRIB_TARGET` in `/etc/openwrt_release`, the hosts of the configured feeds (`/etc/opkg/distfeeds.conf`,
  `/etc/opkg/customfeeds.conf` or `/etc/apk/repositories.d/*`), and a **kernel-fit probe** (a dry run of one small kmod such as `kmod-ifb`). Rule: `official` needs the OpenWrt id **and**
  OpenWrt's own feed host **and** the probe passing; `fork` needs a known fork id (one list, in one place) **and** that fork's feed host **and** the probe passing; everything else is `custom`.
  A failed probe can only lower the label, never raise it. The label sets how cautious the installer is (feature 056); it never replaces a probe, and it is **not** a claim of support
  (`docs/ROUTERS.md` still lists only hardware that has actually run).
- **No package manager:** if openNDS is installed and its init script exists, the setup carries on and reports every package step as "no package manager"; otherwise it stops before changing anything, as today.
- **Package names:** the layer holds one name map (a package that is called differently under apk is mapped there, nowhere else). Names are checked on a real image, not assumed.
- `install.sh` (`setup/install.sh`, copied to `website/install.sh` by `tools/build_piso_setup.py`) and `setup/pisophone_setup.py` (the `ApkRouter` refusal, lines about 1986 to 2010) stop refusing apk routers; they still refuse a router whose release is unknown.
- Generated files only by `python3 tools/build_piso_setup.py` (`--check` must pass). Raise `setup/RELEASE`. Through `beta`; decode the program inside the signed file before anyone raises the release (`CLAUDE.md` section 5).
- The setup program on a computer uses only the Python standard library.

## What a visitor without the condition sees
- A router with **opkg only**: nothing changes (same output as today, tested).
- A router with **apk only**: the setup runs; messages say apk.
- A router with **no package manager and openNDS missing**: the setup stops before changing anything and says what to install first.
- Customers and the console: nothing new.

## Success criteria
1. With a fake `opkg` the installer output and the openNDS install are the same as today (the 481 checks of `router/tests/test_setup.py` still pass).
2. With a fake `apk` the same installs happen with the apk commands; `update`, `is_installed`, `available`, `dry_run`, `install`, `remove` each have a test.
3. With neither: openNDS present gives a run that finishes with the "no package manager" report; openNDS missing stops with the message and changes nothing.
4. `install.sh` and the setup program no longer refuse apk; `router/tests/test_install.py`, `test_setup_phone_web.py` and `test_phone_only.py` are updated and pass.
5. `check_security_rules.sh` fails a test file that adds a forbidden flag, and passes the real tree.
6. `docs/ROUTERS.md` (which releases and images the setup supports), `setup/README.md` (the apk row of its error table), `CHANGELOG.md` and a new hardware check RS70 in `docs/REAL_WORLD_TESTING.md`.
7. **Image type** is tested from fixture files (a release file, a feeds file, a fake dry run): an official OpenWrt 24.10 gives `official`; ImmortalWrt 23.05 with its feed gives `fork`;
   the same files with a feed from an unknown host, or with a failing kmod probe, give `custom`; no release file gives `custom`; a missing or unreadable feed file never crashes the setup.
   The label and the reason are in the setup summary and in `piso-setup status`.
8. Run on the owner's custom image: `piso-setup status` says which manager and which image type were found. Recorded in `validation.md`.

## Out of scope
- Feed addresses typed by the owner, and installing from a file on the computer (removed in feature 041).
- The optional packages themselves (feature 056).
- A router whose release is unknown, or a CPU family with no program built.

## Owner decisions
Answered 2026-10-31: the installer works out the image type (official, fork, custom) from router facts; new prerequisite feature before 056; carry on without a package manager if openNDS is already there; scan by "already present, then dry run".
Decided while building (please confirm): **the footprint budget of the installed command `setup/piso-setup.sh.in` was raised from 150 KB to 160 KB** (`router/tests/test_footprint.py`): the layer made it 155 KB (148 KB before). On a 16 MB router that is about 3 KB more flash once compressed. The test would not pass otherwise; if you prefer a tighter limit, the layer's comments can be cut instead.
Still open: please paste these from the router so group 0 can start from facts (see the plan): `cat /etc/openwrt_release`, `uname -r`, `command -v opkg apk`, and `ls /etc/opkg* /etc/apk* 2>/dev/null`.
