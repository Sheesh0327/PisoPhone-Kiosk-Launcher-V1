# Which routers work

The PisoPhone portal is a small compiled program that runs on the router, so it must be built for the router's CPU. Releases carry one
for each of five CPU families (OpenWrt names a router's CPU in `/etc/openwrt_release`, as `DISTRIB_ARCH`):

| Family | `DISTRIB_ARCH` is | Typical routers (examples to check, not a promise) | Setup file |
|---|---|---|---|
| **mipsel** | `mipsel_24kc`, `mipsel_74kc` | MediaTek MT7621 / MT7620 / MT7628: Qihoo 360T6M, Xiaomi 4A Gigabit, Redmi AC2100, Cudy WR1300, GL.iNet MT300N-V2 | `piso-setup.sh` |
| **mips** | `mips_24kc`, `mips_74kc` | Atheros / Qualcomm ath79: many older TP-Link routers (WR841N, Archer C7), GL.iNet AR300M | `piso-setup-mips.sh` |
| **aarch64** | `aarch64_cortex-a53`, `aarch64_cortex-a72`, `aarch64_generic` | Newer Wi-Fi 6 routers: GL.iNet Beryl AX (MT3000), Flint 2 (MT6000), Xiaomi AX3000T, Cudy WR3000, Redmi AX6000, Linksys E8450 / Belkin RT3200; Raspberry Pi 4, NanoPi R4S / R5S | `piso-setup-aarch64.sh` |
| **armv7** | `arm_cortex-a7_neon-vfpv4`, `arm_cortex-a15_neon-vfpv4`, `arm_cortex-a9_neon` | Qualcomm IPQ40xx: GL.iNet B1300, ASUS RT-AC58U, Linksys EA8300 | `piso-setup-armv7.sh` |
| **x86_64** | `x86_64` | A mini PC or a virtual machine running OpenWrt | `piso-setup-x86_64.sh` |

How to know: on the router, `cat /etc/openwrt_release` and read `DISTRIB_ARCH`. The one-line installer (and `piso-setup update`) pick the right
file by themselves; a file for another CPU refuses to run before changing anything and names the one that fits. A CPU that is none of these
(for example `riscv64_*`, older ARM) is refused with a clear message.

What else the router needs:
- **OpenWrt 23.05 or newer** with the `opennds` package (ImmortalWrt 23.05, an OpenWrt fork, is what the owner's 360T6M runs: `DISTRIB_DESCRIPTION` reads `ImmortalWrt 23.05.0`; other forks are not checked), a **2.4 GHz** Wi-Fi radio (the coin box is 2.4 GHz only) and a LAN you can run SSH on.
- **Room (sizes from the builds; flash use is an estimate, not a reading from a router):** the portal program is 0.7 to 1.1 MB (about 0.57 MB on
  flash once compressed), the installed `piso-setup` command about 0.1 MB (about 0.05 MB), and an update keeps a backup of the old program (another
  ~0.9 MB) until the new one proves itself; the update file itself (1.1 to 1.7 MB) is held in memory, or in flash on a router with little free
  memory. The one-line installer keeps its 1.6 MB copy in memory (`/tmp`) and the setup deletes it after a good install, so it no longer sits on
  flash. By that estimate our own files take about 0.6 MB of flash; the packages (`opennds`, `curl`, `ca-bundle`, `jsonfilter`,
  `libmicrohttpd-no-ssl`) were not measured. The setup prints the flash chip it found and the free space (`piso-setup space`, and the summary) and
  warns under 2 MB free. The full PisoWiFi (game included) is the same program on every router; there is no cut-down build. Routers with
  **16 MB flash and 128 MB RAM or more** should be comfortable (reports say 6 to 8 MB writable after OpenWrt with LuCI; some boards about 4 MB, which is
  tight). Routers with 4 or 8 MB flash have too little room left after OpenWrt and openNDS (not tried on one).
- **Which product:** *PisoWiFi only* (`SHOP_PRODUCT=wifi`) leaves out the rental phones' hidden Wi-Fi and its password; the coin box and its Wi-Fi, the
  guest Wi-Fi, the isolation tables and the console are the same. *Both* is the full system. See `setup/MANUAL.md`.
- **Wi-Fi 5 or 6 dual band** is better for customers (the 5 GHz band carries more people), but only 2.4 GHz is required.

What is checked: every family's program runs its self-test, and the portal's own test suite passes, in an emulator (QEMU) in CI and here, including the
big-endian MIPS build (byte order is where a port goes wrong). That is not the same as a real router of each kind. So far only a Qihoo 360 T6
router (`mipsel`) has run the program on real hardware, according to the owner's test log (`docs/REAL_WORLD_TESTING.md`). The other
families are checked in emulation only. When you try a router, record which one it was and what `piso-setup status` said.

The router's own changes are the same everywhere (setup script, console, backups, updates); only the program and its folder on the website
differ: `mipsel` is at `https://pisophone.pages.dev/update/`, the others at `https://pisophone.pages.dev/update/<family>/`.
