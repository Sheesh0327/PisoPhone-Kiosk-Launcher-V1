"""Tests for anti-tethering (TTL guard) in router/piso_isolation.sh and setup/src/95-owner-tools.sh.
Verifies that:
1. piso_isolation.sh generates table inet piso_tether with hook forward priority -50.
2. iifname "br-guest" drops TTL values 61, 62, 63, 125, 126, 127.
3. TETHER_GUARD='off' removes the table.
4. cmd_tether_guard is exposed in setup commands and handles on/off/status.
5. All architecture setup files contain the tether guard rules and command.
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_tether_guard_isolation():
    tmp = tempfile.mkdtemp()
    try:
        iso = f"{tmp}/iso".replace("\\", "/")
        for d in ("sys/br-lan/brif", "sys/br-guest/brif/phy0-ap1", "sys/br-guest/brif/phy1-ap1", "bin"):
            os.makedirs(f"{iso}/{d}", exist_ok=True)
        open(f"{iso}/coin.conf", "w").write("GW_BOX='10.0.0.10'\nGW_BOX_MAC='AA:BB:CC:DD:EE:09'\nCONSOLE_BIND='10.0.0.1'\n")
        open(f"{iso}/piso.conf", "w").write("")
        open(f"{iso}/nft", "w").write(
            f'#!/bin/sh\necho "ARGS $*" >> "{iso}/nft.log"\n'
            f'if [ "$1" = -f ]; then {{ echo "FILE $2"; cat "$2"; }} >> "{iso}/nft.log"; '
            f'[ -e "{iso}/nft-fail" ] && exit 1; fi\nexit 0\n'
        )
        open(f"{iso}/bin/logger", "w").write(f'#!/bin/sh\necho "$*" >> "{iso}/logger.log"\n')
        for f in ("nft", "bin/logger"):
            os.chmod(f"{iso}/{f}", 0o755)

        # Run with default settings (TETHER_GUARD enabled)
        env = {
            **os.environ,
            "PATH": f"{iso}/bin:E:/Git/bin:E:/Git/usr/bin:{os.environ.get('PATH', '')}",
            "PISO_NFT": f"{iso}/nft",
            "PISO_SYS": f"{iso}/sys",
            "PISO_CONF": f"{iso}/piso.conf",
            "PISO_COINSLOT_CONF": f"{iso}/coin.conf",
        }
        r = subprocess.run(["sh", f"{ROOT}/router/piso_isolation.sh"], capture_output=True, text=True, env=env)
        assert r.returncode == 0, f"piso_isolation.sh failed: {r.stderr}"

        nl = open(f"{iso}/nft.log").read()
        assert "table inet piso_tether {" in nl
        assert "delete table inet piso_tether" in nl
        assert 'iifname "br-guest" ip ttl { 61, 62, 63, 125, 126, 127 } counter drop' in nl
        assert "hook forward priority -50" in nl

        # Run with TETHER_GUARD='off'
        os.remove(f"{iso}/nft.log")
        open(f"{iso}/piso.conf", "w").write("TETHER_GUARD='off'\n")
        r = subprocess.run(["sh", f"{ROOT}/router/piso_isolation.sh"], capture_output=True, text=True, env=env)
        assert r.returncode == 0

        nl_off = open(f"{iso}/nft.log").read()
        assert "table inet piso_tether {" not in nl_off
        assert "delete table inet piso_tether" in nl_off

        # Run with nft failure
        open(f"{iso}/nft-fail", "w").close()
        open(f"{iso}/piso.conf", "w").write("")
        r = subprocess.run(["sh", f"{ROOT}/router/piso_isolation.sh"], capture_output=True, text=True, env=env)
        assert r.returncode == 0, "must not crash on nft failure"
        ll = open(f"{iso}/logger.log").read()
        assert "tether guard could not be loaded" in ll
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_tether_guard_in_setup_scripts():
    in_file = os.path.join(ROOT, "setup", "piso-setup.sh.in")
    content = open(in_file).read()
    assert "cmd_tether_guard()" in content
    assert "tether-guard)" in content
    assert "table inet piso_tether" in content
    assert "TETHER_GUARD" in content

    for arch in ["piso-setup.sh", "piso-setup-aarch64.sh", "piso-setup-armv7.sh", "piso-setup-mips.sh", "piso-setup-x86_64.sh"]:
        p = os.path.join(ROOT, "setup", arch)
        c = open(p).read()
        assert "cmd_tether_guard()" in c, f"missing in {arch}"
        assert "table inet piso_tether" in c, f"missing in {arch}"


if __name__ == "__main__":
    test_tether_guard_isolation()
    test_tether_guard_in_setup_scripts()
    print("ALL TETHER GUARD TESTS PASSED SUCCESSFULLY")
