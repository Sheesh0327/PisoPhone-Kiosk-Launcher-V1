# router: scripts that run on the router, and the tests of the router side

PisoWiFi runs on an OpenWrt router with openNDS and the `pisoportal` program (`tools/pisoportal/`). This folder holds the small shell
scripts that sit next to that program on the router. The router side as a whole is installed by `setup/` (see `setup/README.md`);
the supported routers are in `docs/ROUTERS.md`.

## Layout
| File | Installed on the router as | What it does |
|---|---|---|
| `pisoportal.init` | `/etc/init.d/pisoportal` | procd service for the portal: the coin page, the box link, the money records |
| `pisoportal_status.sh` | `/usr/lib/opennds/pisoportal_status.sh` | openNDS status script: sends a guest to the portal's page |
| `piso_monitor.sh` | `/usr/bin/piso-monitor.sh` | Telegram alerts and remote commands (BusyBox ash, curl) |
| `piso_monitor.init` | `/etc/init.d/piso_monitor` | procd service for the monitor |
| `piso_isolation.sh` | `/etc/hotplug.d/firewall/50-piso-isolation` | limits who may talk to whom on the router's networks (guests do not reach each other); applied again at every firewall reload |
| `tests/` | not installed | tests for the monitor, the installer, the setup file, the setup program and router updates |

The install paths are the ones in `tools/build_piso_setup.py`, which embeds these files into the setup file.

## Build
There is nothing to build here. A change to a file in this folder changes what routers install, so it goes through `beta`, needs
`setup/RELEASE` raised and a regenerated setup file (`CLAUDE.md` section 5, `docs/RELEASE.md`):
```
python3 tools/build_piso_setup.py
```

## Test
Run from the repository root:
```
python3 router/tests/test_setup.py
python3 router/tests/test_monitor.py
python3 router/tests/test_install.py
python3 router/tests/test_footprint.py
python3 router/tests/test_pisophone_setup.py
python3 router/tests/test_boxupdate.py
PISO_TEST_TARGET_DIR=$HOME/test-target python3 router/tests/test_selfupdate.py   # builds tools/pisoportal with cargo
shellcheck -s sh router/piso_monitor.sh router/piso_isolation.sh
shellcheck -s sh -e SC2034 router/*.init
```
`test_setup_web.py`, `test_setup_tools.py`, `test_setup_phone_web.py` (these three use Chromium, or the one named in `CHROME=<path>`) and
`test_phone_only.py` test the setup program (`setup/pisophone_setup.py`). The full list CI runs is the `pisoportal` job in
`.github/workflows/quality.yml`. The tests use fake routers, a fake Telegram and a fake website. What a real router does is tracked
in `docs/REAL_WORLD_TESTING.md`.

## Generated files
Nothing in this folder is generated. The setup file built from it (`setup/piso-setup*.sh`) is: never edit it by hand.

## See also
`setup/README.md`, `tools/pisoportal/README.md`, `docs/ROUTERS.md`, `docs/api/gateway-coinslot.md`, `docs/ARCHITECTURE.md` (section 5).
