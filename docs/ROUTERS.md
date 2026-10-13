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
- **OpenWrt 23.05 or newer** with the `opennds` package, a **2.4 GHz** Wi-Fi radio (the coin box is 2.4 GHz only) and a LAN you can run SSH on.
- **Room (measured from the builds):** the portal program is 0.7 to 0.9 MB, the installed `piso-setup` command about 0.1 MB, and an update keeps a
  backup of the old program (another ~0.9 MB) until the new one proves itself; the update file itself (1.1 to 1.3 MB) is held in memory, or in flash
  on a router with little free memory. The records are small text files. Routers with **16 MB flash and 128 MB RAM or more** are comfortable;
  routers with 4 or 8 MB flash have too little room left after OpenWrt and openNDS (this has not been tried on one).
- **Wi-Fi 5 or 6 dual band** is better for customers (the 5 GHz band carries more people), but only 2.4 GHz is required.

What is checked: every family's program runs its self-test, and the portal's own test suite passes, in an emulator (QEMU) in CI and here, including the
big-endian MIPS build (byte order is where a port goes wrong). That is not the same as a real router of each kind: only the 360T6M
(`mipsel`) has run on real hardware. Tell the owner which router you tried and what `piso-setup status` said.

The router's own changes are the same everywhere (setup script, console, backups, updates); only the program and its folder on the website
differ: `mipsel` is at `https://pisophone.pages.dev/update/`, the others at `https://pisophone.pages.dev/update/<family>/`.
