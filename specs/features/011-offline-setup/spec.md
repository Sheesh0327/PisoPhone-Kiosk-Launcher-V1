# Feature 011: first setup without internet on the router (the computer brings the files)

Status: **draft, waiting for the owner's approval. No code until approved.**
Branch: `feat/011-offline-setup` (from `beta`). Area: setup, router. Depends on: feature 010 (it changes the same files: `setup/install.sh`, `20-preflight.sh`, the setup program's router flow); build 011 after 010 is merged into `beta`, and rebase this branch on it first. Release: router (`setup/RELEASE`, the number after 010's 1.5.17) and the setup program (`website/pisophone_setup.py`).
Backlog rows: none (owner request 2026-10-22, idea checked against the code before this spec).

## How to read this spec (for the execution model)
"Facts", "Decisions", "Names", "Invariants", "Constraints" and "Success criteria" are the contract; "Owner decisions" must be answered before code. Every criterion names its test file. If something needed is not here, stop and ask the owner (constitution rule 8); do not guess.

## Intent
Today the router must have internet during its first setup. A shop owner who sets up the router with the setup program on a computer (before the ISP has connected the modem, or with a flaky line) is stopped. With this feature the computer, which has internet, downloads the installer and the OpenWrt packages the router needs, copies them to the router's RAM, the router checks them, and the setup runs without the router going online. If anything cannot be prepared, the setup says so and uses today's path.

## Facts (checked in the code on 2026-10-22)
1. **Where the first setup goes online today:**
   - the setup program tells the router to `wget` `install.sh` from the website (`setup/pisophone_setup.py:1840-1847`, `installer_command`); `install.sh` downloads the installer for the router's CPU and its `.sha256` (`setup/install.sh:117-143`, `fetch` at line 33);
   - on a factory router this happens twice: once at the factory address, which moves the router to 10.0.0.1 (a network restart, `/tmp` survives), then again at 10.0.0.1 with the answers (`pisophone_setup.py:1945-1966`, `install()`; `setup_command` line 1850 calls `installer_command` again);
   - the installer stops when the router cannot ping 1.1.1.1 or 8.8.8.8 (`setup/src/20-preflight.sh:38`);
   - it runs `opkg update`, then installs `opennds curl ca-bundle jsonfilter` when missing and `libmicrohttpd-no-ssl` when no `libmicrohttpd` is installed (`20-preflight.sh:43-56`, `install_packages`, called from `98-setup-flow.sh:106`).
   Nothing else in the first setup needs the router's internet: the coin box pairing, Wi-Fi, portal, isolation and checks are local. `kmod-nft-bridge` is not installed by the setup (it only warns, `75-checks-stage2.sh:15`).
2. **The setup program already talks to the router over SSH** and can send data on a command's standard input (`run_remote(..., data=...)`, `pisophone_setup.py:1897-1907`; `SshRunner.run` passes `input=` to `subprocess.run`). It uses only the Python standard library (`CLAUDE.md` section 6).
3. **OpenWrt package sources:** each router lists its own in `/etc/opkg/distfeeds.conf` (`src/gz <name> <url>`); each source has `Packages.gz`, `Packages` and `Packages.sig` (a usign signature over the uncompressed `Packages`). `Packages` gives per package: `Package`, `Version`, `Depends` (comma separated; `a | b` alternatives; version constraints in brackets), `Provides`, `Filename`, `Size`, `SHA256sum`. Community builds (such as the owner's 360 T6GS) list their own sources there.
4. **Signature checks:** `opkg update` checks `Packages.sig` with `usign` and the keys in `/etc/opkg/keys/` when `/etc/opkg.conf` has `option check_signature`; `opkg install <file>.ipk` from a local file checks nothing. Python's standard library cannot check usign (ed25519) signatures; the router can (`usign -V -m <Packages> -P /etc/opkg/keys -x <Packages.sig>`).
5. **OpenWrt 25.x replaces opkg with apk** (other index format and signatures). Releases 22.03 to 24.10 use opkg.
6. **RAM:** `opkg update` keeps all package lists in RAM (`/tmp/opkg-lists`), several MB. The router's update already uses the rule "work in RAM unless `MemAvailable` is under 24000 KB" (`96-space-backup.sh:15-25`).

## Decisions (proposed; the owner approves them with the spec)
1. **Only the setup program path.** The one-line installer typed on the router by hand keeps needing the router's internet (there is no computer).
2. **Offline first, today's path as the fallback.** The setup program prepares the files on the computer before it touches the router. If preparing fails (no source reachable, a package missing, apk router, too little RAM, no usign or no keys), it says why and, if the router has internet, runs today's path; if not, it stops before changing anything.
3. **The router checks what it installs:** each source's `Packages` against its `Packages.sig` with the router's own `usign` and keys, then each package's sha256 against that `Packages`; the installer against its published `.sha256` as today. Anything that fails: nothing is installed, the setup stops with the reason.
4. **The router's own sources** (`/etc/opkg/distfeeds.conf`) decide where the computer downloads from, so the packages match the router's release, CPU and kernel.
5. **Only what is missing** is downloaded: the packages of fact 1 and their dependencies, minus what `opkg list-installed` shows.
6. **RAM only:** everything goes to `/tmp/piso-offline/` (about 1.6 MB installer, the packages, and the `Packages` files of the sources used); it is deleted after the setup, success or not. Offline mode needs `MemAvailable` of at least 24000 KB plus the bundle's size; otherwise the fallback.

## Names (use exactly these)
| What | Name |
|---|---|
| Bundle folder on the router | `/tmp/piso-offline/`, holding `install.sh`, `piso-setup*.sh` + `.sha256`, `pkgs/<source name>/Packages`, `pkgs/<source name>/Packages.sig`, `pkgs/*.ipk`, `pkgs/order.txt` (one `.ipk` file name per line, dependencies first) |
| One-line installer option | `install.sh --local /tmp/piso-offline`: use the files there instead of downloading (first setup only; `update` ignores it) |
| Installer setting | environment variable `PISO_LOCAL_PKGS=/tmp/piso-offline/pkgs`: skip the ping and `opkg update`, check and install from there |
| Router-side check and install | shell function `install_local_packages <dir>` in a new file `setup/src/21-local-packages.sh` |
| Setup program code | in `setup/pisophone_setup.py` (it stays one file): `read_router_feeds`, `resolve_packages`, `build_offline_bundle`, `push_offline_bundle` |
| Setup program option | `--router-online`: skip the offline path and use today's path |
| New test file | `router/tests/test_offline_setup.py` (fixture `Packages` files, a local HTTP server for the sources, a stub `usign` and `opkg`), added to `.github/workflows/quality.yml` |

## Invariants touched
- **Money and time:** not touched (no change to the program, the payment queue or the data folder).
- **Only owner-signed software:** not weakened. Our installer is checked against its published sha256 exactly as `install.sh` does today (the owner's signature applies to updates, as today). OpenWrt packages are checked against OpenWrt's own signed indexes by the router before install, which today's online path also does through `opkg update`.

## Constraints
- Setup program: Python standard library only, one file. Router side: POSIX shell and what OpenWrt has (`usign`, `sha256sum`, `opkg`).
- The online path (today's) stays exactly as it is and keeps passing its tests.
- `install.sh --local` and `PISO_LOCAL_PKGS` are only used on a first setup; the hourly update and `piso-setup update` never use them.
- Generated files (`website/install.sh`, `setup/piso-setup*.sh`, `website/pisophone_setup.py`) are regenerated by `tools/build_piso_setup.py`.

## Success criteria (each becomes a line in `validation.md`; test file in brackets)
1. **Baseline:** the tests of feature 010's criterion 1 pass on the branch before any change; counts recorded.
2. **Reading the router** [`test_offline_setup.py`]. `read_router_feeds` reads `/etc/openwrt_release`, `/etc/opkg/distfeeds.conf`, `opkg list-installed`, `/etc/opkg.conf` (`check_signature`), the presence of `usign` and `/etc/opkg/keys/*`, and `MemAvailable`, in one SSH command. An apk router (no `/etc/opkg/distfeeds.conf`, `apk` present), no `check_signature`, no `usign` or no keys each give "offline setup not possible: <reason>".
3. **Resolving packages** [`test_offline_setup.py`, fixture indexes]. `resolve_packages` returns the missing packages of fact 1 and their dependencies in install order: alternatives (`a | b`) take the one already installed, else the first; `Provides` satisfy a dependency (for example `libcurl`'s SSL library); already-installed packages are skipped; `libmicrohttpd-no-ssl` only when no `libmicrohttpd*` is installed; a dependency on a `kernel` version different from the router's, or a package not in any source, gives "offline setup not possible: <package> <reason>". Fixtures: a 23.05-like ramips/mt7621 set and a 24.10-like set, each with curl's SSL alternatives.
4. **Building the bundle** [`test_offline_setup.py`, local HTTP server]. `build_offline_bundle` downloads from the router's own source URLs (`http` and `https`) each needed `.ipk`, checks its size and sha256 against `Packages` on the computer too, saves each used source's `Packages` (unpacked from `Packages.gz` when only that is served) and `Packages.sig`, and the installer for the router's CPU (the same file choice as `install.sh`) with its `.sha256`, and `install.sh`. A download that fails or does not match: "offline setup not possible: <file> <reason>".
5. **Pushing the bundle** [`test_offline_setup.py`, stub SSH]. `push_offline_bundle` writes the files into `/tmp/piso-offline/` through SSH standard input (no `scp`), checks their sha256 on the router, and is skipped when the same bundle is already there (the second run at 10.0.0.1 after the address move). If `MemAvailable` is under 24000 KB plus the bundle's size: the fallback (decision 2).
6. **The router checks before it installs** [`router/tests/test_setup.py`, stub `usign`, `sha256sum`, `opkg`]. `install_local_packages`: every `Packages` must pass `usign -V` with `/etc/opkg/keys`; every `.ipk` in `order.txt` must be listed in one of them with the same sha256 and size; then `opkg install` of the files in `order.txt` order. A bad signature, an unknown file or a wrong sha256: nothing installed, the setup stops with the reason. Test with a tampered `.ipk` and with a tampered `Packages`.
7. **Installer without internet** [`router/tests/test_setup.py`, `test_install.py`]. With `PISO_LOCAL_PKGS` set, the setup does not ping and does not run `opkg update`, installs through `install_local_packages`, and runs to the end with no network (all `wget`/`curl`/`ping` stubs fail). `install.sh --local <dir>` uses the files there, checks the installer's sha256 as today, and never calls `fetch`. Without the option and the setting everything is exactly as today (the existing tests pass unchanged).
8. **The whole flow, both ways** [`router/tests/test_pisophone_setup.py`, `test_offline_setup.py`]. A factory router with no internet: the setup program builds and pushes the bundle, the address move and the setup run without the router going online, and `/tmp/piso-offline/` is gone afterwards. When the bundle cannot be prepared and the router has internet: today's path runs and the message names the reason. When neither works: it stops before changing the router, with both reasons. `--router-online` runs today's path. `phone` (PisoPhone only) does nothing with the router, as today.
9. **Hardware** [`docs/REAL_WORLD_TESTING.md`, a new check, "not run"]. A factory router on official OpenWrt 23.05 or 24.10, with the WAN cable unplugged: the setup program sets it up completely; plugging the WAN cable in afterwards: customers get Wi-Fi and the router's next hourly update works. The owner's 360 T6GS: whether its community sources serve signed indexes (offline mode works) or the fallback is used.
10. **Gates:** `bash scripts/check_security_rules.sh`; the router and setup Python tests (`CLAUDE.md` section 3) and `test_offline_setup.py`, listed in `quality.yml`; `python3 tools/build_piso_setup.py --check`.

## Out of scope
- The one-line installer typed on the router (decision 1).
- apk-based OpenWrt (25.x and snapshots that use apk): the fallback is used.
- Installing `kmod-nft-bridge` or any kernel module the setup does not install today.
- Updates (they keep using the router's internet and the owner's signature).
- A copy of the OpenWrt package sources kept on the website.

## Owner decisions (answer before code)
1. **Offline first by default?** Proposed: yes, the setup program always tries the offline path first (decision 2), with today's path as the automatic fallback and `--router-online` to skip it. The alternative is an opt-in option `--router-offline`, which is less tested by use.
2. **A router with no internet and no way to prepare offline:** proposed: stop before changing anything, with both reasons (criterion 8). The alternative is to set up without the packages and let the owner add them later, which leaves a router without its portal: not proposed.
3. **Release order:** proposed: after 010 is merged into `beta` and published; 011 is its own router release.
