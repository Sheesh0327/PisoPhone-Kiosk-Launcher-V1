#!/usr/bin/env python3
"""Builds opennds-coinslot_<version>_all.ipk without the OpenWrt SDK (the package is shell scripts only).

  python3 opennds/package/build_ipk.py [--version 1.0.0] [--out dist]

An .ipk is a gzipped tar of: debian-binary, control.tar.gz, data.tar.gz. Line endings are normalised to LF, so a
checkout that went through Windows still installs and runs. Install on the router:
  opkg install ./opennds-coinslot_1.0.0_all.ipk
"""
import argparse
import gzip
import io
import os
import re
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(HERE)

# (path in the package, source file, mode)
FILES = [
    ("usr/bin/coinslot-listener.sh", "coinslot-listener.sh", 0o755),
    ("usr/lib/opennds/theme_coinslot.sh", "theme_coinslot.sh", 0o755),
    ("usr/lib/opennds/coinslot_status.sh", "coinslot_status.sh", 0o755),
    ("etc/init.d/coinslot", "coinslot.init", 0o755),
    # the "flash coin" portal (see INSTRUCTIONS-FLASH.md): installed but not enabled; use flash_coin INSTEAD OF coinslot
    ("usr/lib/opennds/flash_coin.sh", "flash_coin.sh", 0o755),
    ("usr/lib/opennds/flash_coin_lib.sh", "flash_coin_lib.sh", 0o755),
    ("usr/lib/opennds/flash_coin_status.sh", "flash_coin_status.sh", 0o755),
    ("usr/lib/opennds/flash_fairuse.sh", "flash_fairuse.sh", 0o755),
    ("etc/init.d/flash_coin", "flash_coin.init", 0o755),
    ("etc/config/coinslot", "package/coinslot.uci", 0o600),
]

POSTINST = """#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
chmod 600 /etc/config/coinslot
mkdir -p /etc/coinslot.d/vouchers
# an older install kept its settings in /etc/coinslot.conf: move them into UCI once
[ -r /etc/coinslot.conf ] && /usr/bin/coinslot-listener.sh migrate
/etc/init.d/coinslot enable
echo "opennds-coinslot installed. Set the box key (INSTRUCTIONS-SHELL.md), then: /etc/init.d/coinslot start"
exit 0
"""

PRERM = """#!/bin/sh
[ -n "$IPKG_INSTROOT" ] && exit 0
/etc/init.d/coinslot stop 2>/dev/null
/etc/init.d/coinslot disable 2>/dev/null
exit 0
"""


def lf(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def add(tar, name, data, mode):
    info = tarfile.TarInfo(name)
    info.size, info.mode, info.mtime = len(data), mode, 0
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    tar.addfile(info, io.BytesIO(data))


def targz(entries):
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as t:
        for name, data, mode in entries:
            add(t, name, data, mode)
    out = io.BytesIO()
    with gzip.GzipFile(fileobj=out, mode="wb", mtime=0) as g:
        g.write(raw.getvalue())
    return out.getvalue()


def build(version, outdir):
    if not re.fullmatch(r"\d+\.\d+\.\d+(-r\d+)?", version):
        raise SystemExit("version must look like 1.2.3 or 1.2.3-r1")
    data_entries = []
    size = 0
    for dest, src, mode in FILES:
        content = lf(open(os.path.join(SRC, src), "rb").read())
        size += len(content)
        data_entries.append(("./" + dest, content, mode))
    control = (
        "Package: opennds-coinslot\n"
        f"Version: {version}\n"
        "Depends: opennds, socat, openssl-util, curl\n"
        "Section: net\n"
        "Architecture: all\n"
        f"Installed-Size: {size}\n"
        "Maintainer: PisoPhone\n"
        "Description: PisoPhone coin-slot manager and portal theme for openNDS\n"
    ).encode()
    control_entries = [
        ("./control", control, 0o644),
        ("./conffiles", b"/etc/config/coinslot\n", 0o644),
        ("./postinst", lf(POSTINST.encode()), 0o755),
        ("./prerm", lf(PRERM.encode()), 0o755),
    ]
    outer = io.BytesIO()
    with tarfile.open(fileobj=outer, mode="w", format=tarfile.GNU_FORMAT) as t:
        add(t, "./debian-binary", b"2.0\n", 0o644)
        add(t, "./control.tar.gz", targz(control_entries), 0o644)
        add(t, "./data.tar.gz", targz(data_entries), 0o644)
    ipk = io.BytesIO()
    with gzip.GzipFile(fileobj=ipk, mode="wb", mtime=0) as g:
        g.write(outer.getvalue())
    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, f"opennds-coinslot_{version}_all.ipk")
    with open(path, "wb") as f:
        f.write(ipk.getvalue())
    return path


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--out", default="dist")
    a = ap.parse_args()
    print(build(a.version, a.out))
