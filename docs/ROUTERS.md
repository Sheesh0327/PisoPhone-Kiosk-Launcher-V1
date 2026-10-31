# Which routers work

## The router we recommend

**Qihoo 360 T6M** (the router's own board name is `360,360t6m`; chip MT7621, family `mipsel`; 16 MB of small flash memory plus 128 MB of larger flash memory).
It is the only router this software has ever run on (**the exact model name is unconfirmed: the 011 validation files call the owner's router a 360 T6GS, this log and RS35 call it a 360T6M; the owner will read the label and settle it**), and it runs ImmortalWrt 23.05.0 (build r27285) in the owner's test log.

What "tested" means here, and what it does not:
- **The program runs on it.** That is all that has been shown.
- **Not every feature has been checked on it.** In particular the PisoWiFi-only setup on this router's 16 MB memory (check RS35 in `docs/REAL_WORLD_TESTING.md`) has never been run. If you find a problem on it, tell us.
- **Appearing in the table below is not the same as being supported.** The table lists the kinds of CPU the software is built for, with routers that probably use them. Only the recommended model above has actually been tried.

We do not sell or stock this router; buy it from any shop you like. The rule behind the recommendation is the owner's: **recommend only hardware that has actually been run, and let the list grow one model at a time as models are proven, never ahead of the evidence.** When another model has run in a shop and been written up in the test log, it can be added here.

## Which CPU families are built

The PisoPhone portal is a small compiled program that runs on the router, so it must be built for the router's CPU. Releases carry one
for each of five CPU families (OpenWrt names a router's CPU in `/etc/openwrt_release`, as `DISTRIB_ARCH`):

| Family | Support | `DISTRIB_ARCH` is | Typical routers (examples to check, not a promise) | Setup file |
|---|---|---|---|---|
| **mipsel** | **Supported**: has run on a real router (the Qihoo 360 T6M above) | `mipsel_24kc`, `mipsel_74kc` | MediaTek MT7621 / MT7620 / MT7628: Qihoo 360T6M, Xiaomi 4A Gigabit, Redmi AC2100, Cudy WR1300, GL.iNet MT300N-V2 | `piso-setup.sh` |
| **aarch64** | **Untested** | `aarch64_cortex-a53`, `aarch64_cortex-a72`, `aarch64_generic` | Newer Wi-Fi 6 routers: GL.iNet Beryl AX (MT3000), Flint 2 (MT6000), Xiaomi AX3000T, Cudy WR3000, Redmi AX6000, Linksys E8450 / Belkin RT3200; Raspberry Pi 4, NanoPi R4S / R5S | `piso-setup-aarch64.sh` |
| **mips** | **Untested** | `mips_24kc`, `mips_74kc` | Atheros / Qualcomm ath79: many older TP-Link routers (WR841N, Archer C7), GL.iNet AR300M | `piso-setup-mips.sh` |
| **armv7** | **Untested** | `arm_cortex-a7_neon-vfpv4`, `arm_cortex-a15_neon-vfpv4`, `arm_cortex-a9_neon` | Qualcomm IPQ40xx: GL.iNet B1300, ASUS RT-AC58U, Linksys EA8300 | `piso-setup-armv7.sh` |
| **x86_64** | **Untested** | `x86_64` | A mini PC or a virtual machine running OpenWrt | `piso-setup-x86_64.sh` |

**Supported** means we expect it to work and will fix it if it does not. **Untested** means the software is built and checked on a computer for that family, but it has never run on a real router of that kind: it may work, and you would be the first to find out. The installer **warns you and goes on** with an untested family; it never refuses one. We still build all five families with every release, so a family is promoted to supported the day one runs in a real shop and is written up in the test log.

How to know: on the router, `cat /etc/openwrt_release` and read `DISTRIB_ARCH`. The one-line installer (and `piso-setup update`) pick the right
file by themselves; a file for another CPU refuses to run before changing anything and names the one that fits. A CPU that is none of these
(for example `riscv64_*`, older ARM) is refused with a clear message.

What else the router needs:
- **Internet during the first setup.** The router downloads the installer (over https, checked against its published checksum) and its packages (`opkg`, against the router's own signed feeds) itself, so the modem must be plugged into its WAN port and online. A router with no internet cannot be set up: the setup stops before it changes anything and says so. (Until router 1.5.25 the setup program could copy the packages from the computer instead; that was removed by feature 041.)
- **OpenWrt 23.05 or newer** (opkg or apk) with the `opennds` package (ImmortalWrt 23.05, an OpenWrt fork, is what the owner's 360T6M runs: `DISTRIB_DESCRIPTION` reads `ImmortalWrt 23.05.0`; other forks are not checked), a **2.4 GHz** Wi-Fi radio (the coin box is 2.4 GHz only) and a LAN you can run SSH on.
- **Room (sizes from the builds; flash use is an estimate, not a reading from a router):** the portal program is 0.7 to 1.1 MB (about 0.57 MB on
  flash once compressed), the installed `piso-setup` command about 0.1 MB (about 0.05 MB), and an update keeps a backup of the old program (another
  ~0.9 MB) until the new one proves itself; the update file itself (1.1 to 1.7 MB) is held in memory, or in flash on a router with little free
  memory. The one-line installer keeps its 1.6 MB copy in memory (`/tmp`) and the setup deletes it after a good install, so it no longer sits on
  flash. By that estimate our own files take about 0.6 MB of flash; the packages (`opennds`, `curl`, `ca-bundle`, `jsonfilter`,
  `libmicrohttpd-no-ssl`) were not measured. The setup prints the flash chip it found and the free space (`piso-setup space`, and the summary) and
  warns under 2 MB free. The full PisoWiFi is the same program on every router; there is no cut-down build. Routers with
  **16 MB flash and 128 MB RAM or more** should be comfortable (reports say 6 to 8 MB writable after OpenWrt with LuCI; some boards about 4 MB, which is
  tight). Routers with 4 or 8 MB flash have too little room left after OpenWrt and openNDS (not tried on one).
- **Which product:** *PisoWiFi only* (`SHOP_PRODUCT=wifi`) leaves out the rental phones' hidden Wi-Fi and its password; the coin box and its Wi-Fi, the
  guest Wi-Fi, the isolation tables and the console are the same. *Both* is the full system. See the advanced part of `setup/README.md`.
- **Wi-Fi 5 or 6 dual band** is better for customers (the 5 GHz band carries more people), but only 2.4 GHz is required.

What is checked: every family's program runs its self-test, and the portal's own test suite passes, in a software copy of a router (QEMU) on a computer in CI and here, including the
big-endian MIPS build (byte order is where a port goes wrong). That is not the same as a real router of each kind. So far only a Qihoo 360 T6
router (`mipsel`) has run the program on real hardware, according to the owner's test log (`docs/REAL_WORLD_TESTING.md`); every other family is
checked on a computer only. That is why the table marks them as it does. When you try a router, record which one it was and what `piso-setup status` said.
A CPU that is none of the five families has no program built and is refused before anything changes; that is different from an untested family, which is allowed.

**Releases and routers the setup supports (features 046 and 057).** The setup installs packages with **opkg** (OpenWrt **23.05 and 24.10**, and ImmortalWrt 23.05) or with **apk**
(OpenWrt **25.x** and snapshots), whichever the router has; a router with **no package manager at all** is set up too when **openNDS is already installed** (a custom image with the
packages built in). Only a router with neither a package manager nor openNDS is refused, before anything changes, with a message that says what to install first.
**What has been tried:** the opkg path has run on the owner's router. The **apk path and the no-package-manager path have been tried only against fake commands and against the
official OpenWrt 25.12.1 / 23.05.5 / 24.10.8 images in a container (no network, no kernel of their own): no real apk router has run the setup, so treat apk as untested** until one has
(check RS70 in `docs/REAL_WORLD_TESTING.md`). A release whose package sources have moved to the archive (an old 21.02 for example) gets "the package
sources of OpenWrt <release> do not answer" instead of "no internet".

**What kind of image it is.** After the package lists are fresh, the setup works out whether the image is `official` OpenWrt, a `fork` it knows (ImmortalWrt, with its own feed) or a `custom`
image, from facts on the router only: the distribution id in `/etc/openwrt_release`, the hosts of the configured package feeds, and a dry run of one kernel module package
(`kmod-ifb`) that shows whether the feed fits the image's kernel. The result and its reason are in the setup summary and in `piso-setup status` ("Image: ... (known fork: feeds from
immortalwrt.org, kernel fit ok)"). It is a label for how careful the installer is, **not a promise of support**: the table below still lists only routers someone has set up. **Routers someone has set up** (this list grows only when an owner reports a
real setup with the release used; a model that is not here may still work):

| Router | OpenWrt release | CPU family | Notes |
|---|---|---|---|
| Qihoo 360 T6M / T6GS | ImmortalWrt 23.05.0 | `mipsel_24kc` | the owner's router; the first setup in a real shop (RS17, RS36, RS61) is still to be recorded |

The router's own changes are the same everywhere (setup script, console, backups, updates); only the program and its folder on the website
differ: `mipsel` is at `https://pisophone.pages.dev/update/`, the others at `https://pisophone.pages.dev/update/<family>/`.

## The smooth line (SQM), optional (feature 058)

When many customers share one line, one big download can fill it and every other page crawls. **SQM** (smart queue management) stops that: it keeps the line just below its real
speed so the router, not the modem, decides who waits. It is **extra**: the setup tries to add it, and never stops or fails because of it.

* **What the setup adds, when the router's package list has it and at least 2048 KB of flash is free:** `sqm-scripts`, `kmod-ifb` and `tc-full` (all or nothing: if one fails, all three are taken out
  again), `kmod-sched-cake` (the better queue; without it the line uses `fq_codel`) and `irqbalance` (spreads network work over the processor cores; it is installed but **not switched on**).
  What was added or skipped, and why, is in the setup summary and in `piso-setup status`. A router with no package manager, an image whose kernel does not match the package list, or a
  package list without these packages simply skips them and says so.
* **It is off after the install.** The owner switches it on in the console, **Settings, Smooth line**: type the download and upload speed of the line (or press **Test my line**), leave a
  margin (95% by default) and choose On. The router then limits the line to that share. The same from the router: `piso-setup sqm apply` (see `setup/README.md`).
* **Test my line** runs on the router: four parallel streams each way against Cloudflare's public speed servers, about 250 MB at most per direction, nothing downloaded is kept or run. It
  only starts when **no customer is online and nobody is paying**, pauses SQM while it measures and puts it back, and fills the two boxes; nothing is saved until the owner presses Save.
  If the router's own processor was nearly full the number is shown with a warning and not offered as the line speed: confirm it with a speed test on a phone.
* **Install now** on a router that was set up before: the card offers it when the packages are missing and trying again could help. An automatic update never installs them.
* **The plans' own speed limits (Endurance and the others) are openNDS's and are not changed.** SQM only shapes the router's internet link as a whole.
* **What has been tried:** only against fake commands on a computer. **Never on a router** (hardware check RS71). The flash and memory the packages cost will be measured on the 360T6M and written here.
