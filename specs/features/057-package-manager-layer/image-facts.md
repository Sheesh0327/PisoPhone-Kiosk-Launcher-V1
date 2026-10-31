# Image facts (group 0)

Two sources. **A. Emulator (read on 2026-10-31):** official OpenWrt `rootfs` container images run under Docker in the build session (`openwrt/rootfs:x86-64-23.05.5`, `24.10.8`, `25.12.1`). They are the
real OpenWrt userland (busybox ash, opkg or apk, uci, `/etc/openwrt_release`), but **not a router**: no kernel of their own (they use the host's), no network to `downloads.openwrt.org` (the session's
proxy denies it), no ImmortalWrt image yet (Docker Hub answered 429 for `immortalwrt/rootfs:x86-64-openwrt-24.10`; to retry). **B. The owner's router: still to be pasted** (see the spec's owner decisions).

## A. What the emulator showed

| Fact | 23.05.5 | 24.10.8 | 25.12.1 |
|---|---|---|---|
| Package manager | opkg (`d038e5b`, 2022-02-24) | opkg | **apk-tools 3.0.5**; no opkg at all |
| `DISTRIB_ID` | `'OpenWrt'` | `'OpenWrt'` | `'OpenWrt'` |
| `DISTRIB_REVISION` / `TARGET` | `r24106-10cc5fcd00` / `x86/64` | `r29233-443ec4032a` / `x86/64` | `r32768-b21cfa8f8c` / `x86/64` |
| Feed files | `/etc/opkg/distfeeds.conf`, `customfeeds.conf` (`src/gz <name> https://downloads.openwrt.org/...`) | same | `/etc/apk/repositories.d/distfeeds.list`, `customfeeds.list` (one URL per line, ending `packages.adb`) |
| Feed host | `downloads.openwrt.org` | same | same |
| Signature check | `option check_signature` is on in `/etc/opkg.conf` | same | apk verifies against `/etc/apk/keys` |

Commands, with exit codes (no network, so these test the syntax and the "not found" paths):

| Question | opkg | apk |
|---|---|---|
| Is `busybox` installed? | `opkg list-installed busybox` prints `busybox - 1.36.1-1` | `apk info -e busybox` exit 0; unknown package exit 1 |
| Dry-run install | `opkg install --noaction <pkg>`: exit 0 if installed, **255** if unknown | `apk add --simulate <pkg>`: exit 0 if installed, **1** if unknown |
| Is it in the feed? | `opkg info <pkg>` prints a `Package:` block **but exits 0 even for an unknown package** (so the output must be read, not the exit code), and it also answers for installed packages | `apk search -e <pkg>` prints a line, exit 1 when nothing matches |
| Update | `opkg update` (exit 6 with no network) | `apk update` |
| Remove | `opkg remove` (`--noaction` exit 0 even for unknown) | `apk del` (`--simulate` exit 1 for unknown) |

Notes that change the plan:
- A container needs `mkdir -p /var/lock` before opkg works; a router has it.
- `opkg info` cannot tell "in the feed" from "installed": the **dry run** is the real test, as the spec says.
- The apk feed file lists plain URLs (no `src/gz` prefix), so the host reader must take the URL out of either shape of line.

### A2. The layer run against the real package managers (same containers, `. piso-setup.sh` with `PISO_SETUP_SOURCE_ONLY=1`)
On 23.05.5 and 24.10.8 (real opkg) and 25.12.1 (real apk 3.0.5): `pkg_manager` says opkg / opkg / apk; `pkg_installed busybox` yes and `nosuchpkg` no; `pkg_dry_run` of an installed package passes and of an
unknown one is refused; `pkg_feed_hosts` reads `downloads.openwrt.org` from both the opkg and the apk feed files; `pkg_free_kb` answers. `pkg_image_type` says **custom** in all three, for the right reason
here: with no network the package lists are empty, so the kernel-fit probe (`kmod-ifb`) cannot pass. It needs the router's lists, so it runs after `pkg_update` (the plan's group 2). The emulator cannot show an `official`
answer; the fixture tests do (`router/tests/test_pkgmgr.py`). `pkg_available` for an installed package is yes under opkg and no under apk offline (apk has no index here).

## B. The owner's router (to fill)
`cat /etc/openwrt_release`, `uname -r`, `command -v opkg apk`, `ls /etc/opkg* /etc/apk*`, the module check for `sch_cake` and `ifb`, the init scripts. Pasted by the owner: not yet.
