#!/usr/bin/env python3
"""Tests of the first setup without the router's internet (feature 011, part A), the computer side: reading what the router
knows (read_router_feeds), choosing the packages it is missing (resolve_packages), building the bundle from the router's own
package sources (build_offline_bundle) and copying it to the router's memory (push_offline_bundle). No router and no internet:
fixture package indexes, a local HTTP server standing in for the sources, a fake ssh.
Run with:  python3 router/tests/test_offline_setup.py"""
import gzip
import hashlib
import http.server
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
spec = importlib.util.spec_from_file_location("pisophone_setup", f"{ROOT}/setup/pisophone_setup.py")
ps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ps)

tmp = tempfile.mkdtemp()
checks = failures = 0


def check(cond, msg):
    global checks, failures
    checks += 1
    if not cond:
        failures += 1
        print("FAIL:", msg)


def sha(data):
    return hashlib.sha256(data).hexdigest()


# ---- fixture package indexes ---------------------------------------------------------------------------------------------------
def stanza(name, version, depends="", provides="", arch="mipsel_24kc", data=None, extra=""):
    """One entry of a Packages file; the .ipk's bytes are made up from its name (data=... overrides)."""
    data = data if data is not None else f"{name} {version} ipk".encode()
    fields = [f"Package: {name}", f"Version: {version}"]
    if provides:
        fields.append(f"Provides: {provides}")
    if depends is not None:
        fields.append(f"Depends: {depends}")
    fields += ["Section: net", f"Architecture: {arch}", "Installed-Size: 1234", f"Filename: {name}_{version}_{arch}.ipk",
               f"Size: {len(data)}", f"SHA256sum: {sha(data)}", f"Description:  The {name} package", " with a second line of description"]
    return "\n".join(fields) + "\n" + extra + "\n"


KERNEL_A = "5.15.167-1-0123456789abcdef0123456789abcdef"
# 23.05-like, ramips/mt7621 (mipsel_24kc): curl's TLS library is one of two alternatives, libmbedtls / libopenssl are virtual names
BASE_A = "".join([
    stanza("libc", "1.2.4-4"), stanza("zlib", "1.2.13-1", "libc"), stanza("libjson-c5", "0.16-3", "libc"),
    stanza("libubox20230523", "2023.05.23-1", "libc"),
    stanza("libmbedtls12", "2.28.5-1", "libc", provides="libmbedtls"),
    stanza("libopenssl3", "3.0.15-1", "libc, zlib", provides="libopenssl"),
    stanza("libcurl4", "8.4.0-2", "libc, zlib, libmbedtls | libopenssl"),
    stanza("curl", "8.4.0-2", "libc, libcurl4, zlib"),
    stanza("ca-bundle", "20230311-1", "libc"),
    stanza("jsonfilter", "2018-02-04-1", "libc, libjson-c5, libubox20230523"),
    stanza("kmod-foo", "5.15.167-1", f"kernel (= {KERNEL_A}), libc"),
])
PACKAGES_A = "".join([
    stanza("opennds", "10.1.3-1", "libc, libmicrohttpd, libjson-c5"),
    stanza("libmicrohttpd-no-ssl", "0.9.77-1", "libc", provides="libmicrohttpd"),
    stanza("libmicrohttpd-ssl", "0.9.77-1", "libc, libgnutls", provides="libmicrohttpd"),
    stanza("libgnutls", "3.8.2-1", "libc"),
])
LUCI_A = stanza("luci-base", "git-1", "libc")
INSTALLED_A = {"libc": "1.2.4-4", "zlib": "1.2.13-1", "libjson-c5": "0.16-3", "libubox20230523": "2023.05.23-1",
               "libmbedtls12": "2.28.5-1", "ca-bundle": "20230311-1", "kernel": KERNEL_A, "uclient-fetch": "2023-1"}
FEEDS_A = [("openwrt_core", "https://downloads.openwrt.org/releases/23.05.5/targets/ramips/mt7621/packages"),
           ("openwrt_base", "https://downloads.openwrt.org/releases/23.05.5/packages/mipsel_24kc/base"),
           ("openwrt_packages", "https://downloads.openwrt.org/releases/23.05.5/packages/mipsel_24kc/packages")]
# 24.10-like (another architecture and library names, several-line descriptions, a newer kernel)
KERNEL_B = "6.6.73-1-fedcba9876543210fedcba9876543210"
BASE_B = "".join([
    stanza("libc", "1.2.5-r4", arch="aarch64_cortex-a53"), stanza("zlib", "1.3.1-r1", "libc", arch="aarch64_cortex-a53"),
    stanza("libjson-c5", "0.18-1", "libc", arch="aarch64_cortex-a53"), stanza("libubox20240329", "2024.03.29-1", "libc", arch="aarch64_cortex-a53"),
    stanza("libwolfssl5.7.2.b5ff0a8c", "5.7.2-1", "libc", arch="aarch64_cortex-a53"),
    stanza("libmbedtls21", "3.6.2-1", "libc", provides="libmbedtls", arch="aarch64_cortex-a53"),
    stanza("libcurl4", "8.11.1-1", "libc, zlib, libwolfssl5.7.2.b5ff0a8c | libmbedtls21 | libopenssl3", arch="aarch64_cortex-a53"),
    stanza("curl", "8.11.1-1", "libc, libcurl4 (>= 8.0), zlib", arch="aarch64_cortex-a53"),
    stanza("ca-bundle", "20230311-1", "libc", arch="aarch64_cortex-a53"),
    stanza("jsonfilter", "2024.01.23-1", "libc, libjson-c5, libubox20240329", arch="aarch64_cortex-a53"),
])
PACKAGES_B = "".join([
    stanza("opennds", "10.3.0-1", "libc, libmicrohttpd, libjson-c5", arch="aarch64_cortex-a53"),
    stanza("libmicrohttpd-no-ssl", "1.0.1-1", "libc", provides="libmicrohttpd", arch="aarch64_cortex-a53"),
])
INSTALLED_B = {"libc": "1.2.5-r4", "zlib": "1.3.1-r1", "libjson-c5": "0.18-1", "libubox20240329": "2024.03.29-1", "libmbedtls21": "3.6.2-1",
               "kernel": KERNEL_B}
FEEDS_B = [("openwrt_base", "https://downloads.openwrt.org/releases/24.10.0/packages/aarch64_cortex-a53/base"),
           ("openwrt_packages", "https://downloads.openwrt.org/releases/24.10.0/packages/aarch64_cortex-a53/packages")]


def info(installed, feeds, **kw):
    d = {"release": "23.05.5", "arch": "mipsel_24kc", "target": "ramips/mt7621", "feeds": feeds, "installed": dict(installed), "mem_kb": 90000, "keys": 1}
    d.update(kw)
    return d


def fx(base, packages, extra=None):
    d = {"openwrt_base": base, "openwrt_packages": packages}
    d.update(extra or {})
    return d


def names(result):
    return [p["name"] for p in result]


def not_possible(call, *words):
    """The call must raise OfflineNotPossible whose message starts 'offline setup not possible: ' and has every word."""
    try:
        call()
    except ps.OfflineNotPossible as e:
        text = str(e)
        return text.startswith("offline setup not possible: ") and all(w.lower() in text.lower() for w in words), text
    except Exception as e:  # noqa: BLE001
        return False, f"raised {type(e).__name__}: {e}"
    return False, "did not raise"


# ---- criterion 7: reading the router --------------------------------------------------------------------------------------------
def router_output(release=None, feeds=None, installed=None, conf=None, usign=True, keys=("6a7d3e1b2c4f5a90",), apk=False, mem=96000):
    release = "DISTRIB_ID='OpenWrt'\nDISTRIB_RELEASE='23.05.5'\nDISTRIB_ARCH='mipsel_24kc'\nDISTRIB_TARGET='ramips/mt7621'\n" if release is None else release
    feeds = "".join(f"src/gz {n} {u}\n" for n, u in FEEDS_A) if feeds is None else feeds
    installed = "".join(f"{n} - {v}\n" for n, v in INSTALLED_A.items()) if installed is None else installed
    conf = "dest root /\ndest ram /tmp\nlists_dir ext /var/opkg-lists\noption overlay_root /overlay\noption check_signature\n" if conf is None else conf
    return (f"@@RELEASE\n{release}@@FEEDS\n{feeds}@@INSTALLED\n{installed}@@CONF\n{conf}@@USIGN\n{'yes' if usign else 'no'}\n"
            f"@@KEYS\n{''.join(k + chr(10) for k in keys)}@@APK\n{'yes' if apk else 'no'}\n@@MEM\n{mem}\n")


class FakeSsh:
    def __init__(self, stdout="", code=0, exc=None):
        self.stdout, self.code, self.exc, self.calls = stdout, code, exc, []

    def run(self, host, remote, *extra, **kw):
        self.calls.append((host, remote, extra, kw))
        if self.exc:
            raise self.exc
        return subprocess.CompletedProcess([], self.code, self.stdout, "")


s = FakeSsh(router_output())
got = ps.read_router_feeds(s, "10.0.0.1")
check(len(s.calls) == 1, "read_router_feeds asks the router once (one ssh command)")
remote = s.calls[0][1]
for what in ("/etc/openwrt_release", "/etc/opkg/distfeeds.conf", "opkg list-installed", "/etc/opkg.conf", "usign", "/etc/opkg/keys", "MemAvailable"):
    check(what in remote, f"and that one command reads {what}")
check("BatchMode" not in s.calls[0][2] and s.calls[0][0] == "10.0.0.1", "with the session's login (it may need the password)")
check(got["arch"] == "mipsel_24kc" and got["release"] == "23.05.5" and got["target"] == "ramips/mt7621", "it gives the release, CPU family and target: " + str(got))
check(got["feeds"] == FEEDS_A, "and the router's own package sources in order: " + str(got["feeds"]))
check(got["installed"] == INSTALLED_A, "and what is installed: " + str(got["installed"]))
check(got["mem_kb"] == 96000 and got["keys"] == 1, "and the free memory and the number of signing keys")
for label, kw, words in (
    ("an apk router (OpenWrt 25.x)", {"feeds": "", "apk": True, "installed": ""}, ("apk",)),
    ("no package sources and no apk", {"feeds": ""}, ("sources",)),
    ("no signature checking (the line is commented out)", {"conf": "option overlay_root /overlay\n# option check_signature\n"}, ("signature",)),
    ("no signature checking (no line at all)", {"conf": "option overlay_root /overlay\n"}, ("signature",)),
    ("signature checking switched off", {"conf": "option check_signature 0\n"}, ("signature",)),
    ("no usign", {"usign": False}, ("usign",)),
    ("no keys", {"keys": ()}, ("key",)),
):
    ok, text = not_possible(lambda kw=kw: ps.read_router_feeds(FakeSsh(router_output(**kw)), "10.0.0.1"), *words)
    check(ok, f"{label}: 'offline setup not possible: <reason>': {text}")
check(ps.read_router_feeds(FakeSsh(router_output(conf="option check_signature 1\n")), "h")["keys"] == 1, "'option check_signature 1' counts as on")
ok, text = not_possible(lambda: ps.read_router_feeds(FakeSsh("", code=255), "10.0.0.1"), "router")
check(ok, "a router that cannot be asked: " + text)
ok, text = not_possible(lambda: ps.read_router_feeds(FakeSsh(exc=subprocess.TimeoutExpired("ssh", 60)), "10.0.0.1"), "router")
check(ok, "a timeout: " + text)
check(ps.OfflineNotPossible("x").reason == "x" and isinstance(ps.OfflineNotPossible("x"), ps.SetupError), "the reason is kept for the fallback message and it is a SetupError")

# ---- criterion 8: choosing the packages -----------------------------------------------------------------------------------------
idx_a = lambda **kw: ps.parse_packages_index(kw.get("text", BASE_A)) 
p = ps.parse_packages_index(BASE_A)
byn = {x["name"]: x for x in p}
check(byn["libcurl4"]["depends"] == [[("libc", "", "")], [("zlib", "", "")], [("libmbedtls", "", ""), ("libopenssl", "", "")]], "the index parser reads alternatives: " + str(byn["libcurl4"]["depends"]))
check(byn["libmbedtls12"]["provides"] == ["libmbedtls"] and byn["curl"]["size"] == len(b"curl 8.4.0-2 ipk") and byn["curl"]["sha256"] == sha(b"curl 8.4.0-2 ipk")
      and byn["curl"]["filename"] == "curl_8.4.0-2_mipsel_24kc.ipk", "and Provides, Filename, Size and SHA256sum; description lines do not confuse it")
check(ps.parse_packages_index(BASE_B + PACKAGES_B)[7]["depends"][1] == [("libcurl4", ">=", "8.0")], "and a version constraint in brackets")
check(len(p) == 11, "all the stanzas are read: " + str(len(p)))

res = ps.resolve_packages(info(INSTALLED_A, FEEDS_A), fx(BASE_A, PACKAGES_A))
check(names(res) == ["libmicrohttpd-no-ssl", "opennds", "libcurl4", "curl", "jsonfilter"],
      "23.05-like: the missing packages and their dependencies, dependencies first; ca-bundle is installed already; the installed libmbedtls12 (which Provides libmbedtls) satisfies libcurl4: " + str(names(res)))
first = res[0]
check(first["source"] == "openwrt_packages" and first["url"] == FEEDS_A[2][1] and first["filename"] == "libmicrohttpd-no-ssl_0.9.77-1_mipsel_24kc.ipk"
      and first["size"] == len(b"libmicrohttpd-no-ssl 0.9.77-1 ipk") and first["sha256"] == sha(b"libmicrohttpd-no-ssl 0.9.77-1 ipk"),
      "each entry says which source it comes from, that source's URL, its file name, size and sha256: " + str(first))
inst = dict(INSTALLED_A); inst.pop("libmbedtls12")
check(names(ps.resolve_packages(info(inst, FEEDS_A), fx(BASE_A, PACKAGES_A))) == ["libmicrohttpd-no-ssl", "opennds", "libmbedtls12", "libcurl4", "curl", "jsonfilter"],
      "alternatives with none installed: the first (libmbedtls, which libmbedtls12 provides) is taken")
inst["libopenssl3"] = "3.0.15-1"
check(names(ps.resolve_packages(info(inst, FEEDS_A), fx(BASE_A, PACKAGES_A))) == ["libmicrohttpd-no-ssl", "opennds", "libcurl4", "curl", "jsonfilter"],
      "alternatives with the second installed: it is used and the first is not added")
inst = dict(INSTALLED_A); inst["libmicrohttpd-ssl"] = "0.9.77-1"
check(names(ps.resolve_packages(info(inst, FEEDS_A), fx(BASE_A, PACKAGES_A))) == ["opennds", "libcurl4", "curl", "jsonfilter"],
      "libmicrohttpd-no-ssl only when no libmicrohttpd* is installed (opennds is happy with the installed one)")
inst = dict(INSTALLED_A, **{"libmicrohttpd-no-ssl": "0.9.77-1", "opennds": "1", "curl": "1", "libcurl4": "1", "jsonfilter": "1"})
check(ps.resolve_packages(info(inst, FEEDS_A), fx(BASE_A, PACKAGES_A)) == [], "everything installed already: nothing to download")
check(names(ps.resolve_packages(info(INSTALLED_A, FEEDS_A), fx(BASE_A, PACKAGES_A, {"openwrt_core": "", "luci": LUCI_A}))) == ["libmicrohttpd-no-ssl", "opennds", "libcurl4", "curl", "jsonfilter"],
      "other sources (even empty ones) change nothing")
res_b = ps.resolve_packages(info(INSTALLED_B, FEEDS_B, arch="aarch64_cortex-a53"), fx(BASE_B, PACKAGES_B))
check(names(res_b) == ["libmicrohttpd-no-ssl", "opennds", "libcurl4", "curl", "ca-bundle", "jsonfilter"],
      "24.10-like: the installed libmbedtls21 (the second alternative) is used, a constraint (>= 8.0) does not stop it, ca-bundle is added: " + str(names(res_b)))

only = lambda inst, base, pk, feeds=FEEDS_A, wanted=None: ps.resolve_packages(info(inst, feeds), fx(base, pk), wanted) 
ok, text = not_possible(lambda: only(INSTALLED_A, BASE_A.replace("jsonfilter", "jsonfilterx"), PACKAGES_A), "jsonfilter")
check(ok, "a package no source has: 'offline setup not possible: jsonfilter ...': " + text)
inst = dict(INSTALLED_A, kernel="5.15.200-1-ffff")
ok, text = not_possible(lambda: only(inst, BASE_A, PACKAGES_A, wanted=["kmod-foo"]), "kmod-foo", "kernel", "5.15.200-1-ffff", KERNEL_A)
check(ok, "a package built for another kernel than the router's: " + text)
check(names(only(INSTALLED_A, BASE_A, PACKAGES_A, wanted=["kmod-foo"])) == ["kmod-foo"], "and for the same kernel it is fine")
ok, text = not_possible(lambda: only(dict(INSTALLED_A, curl="x"), BASE_A.replace("libmbedtls12", "libzzz").replace("libopenssl3", "libyyy").replace("Provides: libmbedtls", "Provides: q1").replace("Provides: libopenssl", "Provides: q2"),
                                     PACKAGES_A, wanted=["libcurl4"]), "libmbedtls")
check(ok, "alternatives of which none exists anywhere: the reason names the first: " + text)
ok, text = not_possible(lambda: only({}, BASE_A, PACKAGES_A, wanted=["no-such-package"]), "no-such-package")
check(ok, "an unknown wanted package: " + text)


# ---- criterion 9: building the bundle from the router's own package sources ----------------------------------------------------
SERVED, HITS = {}, []


class Site(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        HITS.append(self.path)
        data = SERVED.get(self.path)
        if data is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Site)
threading.Thread(target=srv.serve_forever, daemon=True).start()
HOST = f"http://127.0.0.1:{srv.server_address[1]}"
INSTALLER = b"#!/bin/sh\nPISO_RELEASE='9.9.9'\nexit 0\n# ---- payload: the portal files (extracted by extract_payload) ----\n"
INSTALL_SH = b"#!/bin/sh\n# the one-line installer\n"


def serve_site(feeds_text, gz_only=(), sig=True, arch_file="piso-setup.sh"):
    """Fills SERVED: the sources' Packages (+ .sig), their .ipk files, the installer and install.sh. Returns the router's feed list."""
    SERVED.clear()
    del HITS[:]
    feeds = []
    for name, text in feeds_text.items():
        feeds.append((name, f"{HOST}/feeds/{name}"))
        raw = text.encode()
        if name in gz_only:
            SERVED[f"/feeds/{name}/Packages.gz"] = gzip.compress(raw)
        else:
            SERVED[f"/feeds/{name}/Packages"] = raw
        if sig:
            SERVED[f"/feeds/{name}/Packages.sig"] = b"signed:" + sha(raw).encode()
        for rec in ps.parse_packages_index(text):
            SERVED[f"/feeds/{name}/{rec['filename']}"] = f"{rec['name']} {rec['version']} ipk".encode()
    SERVED[f"/setup/{arch_file}"] = INSTALLER
    SERVED[f"/setup/{arch_file}.sha256"] = (sha(INSTALLER) + f"  {arch_file}\n").encode()
    SERVED["/install.sh"] = INSTALL_SH
    return feeds


SOURCES_A = {"openwrt_core": "", "openwrt_base": BASE_A, "openwrt_packages": PACKAGES_A}
feeds = serve_site(SOURCES_A, gz_only=("openwrt_packages",))
inf = info(INSTALLED_A, feeds)
out = os.path.join(tmp, "bundle1")
b = ps.build_offline_bundle(inf, "main", out, site=HOST)
want = ["libmicrohttpd-no-ssl", "opennds", "libcurl4", "curl", "jsonfilter"]
order_txt = open(f"{out}/pkgs/order.txt").read().split()
check([n.split("_")[0] for n in order_txt] == want, "the bundle's order.txt lists the .ipk files, dependencies first: " + str(order_txt))
for n in order_txt:
    check(os.path.isfile(f"{out}/pkgs/{n}"), f"and {n} was downloaded into pkgs/")
check(open(f"{out}/pkgs/openwrt_base/Packages", "rb").read() == BASE_A.encode() and open(f"{out}/pkgs/openwrt_base/Packages.sig", "rb").read() == SERVED["/feeds/openwrt_base/Packages.sig"],
      "each used source's Packages and Packages.sig are saved")
check(open(f"{out}/pkgs/openwrt_packages/Packages", "rb").read() == PACKAGES_A.encode(), "a source that serves only Packages.gz is saved unpacked, byte for byte")
check(not os.path.exists(f"{out}/pkgs/openwrt_core") and "/feeds/openwrt_core/Packages.sig" not in HITS, "a source nothing is taken from is not in the bundle and its signature is not fetched")
check(open(f"{out}/piso-setup.sh", "rb").read() == INSTALLER and open(f"{out}/piso-setup.sh.sha256").read().startswith(sha(INSTALLER)) and open(f"{out}/install.sh", "rb").read() == INSTALL_SH,
      "the setup file for the router's CPU, its .sha256 and install.sh are in the bundle")
check(b["total"] == sum(os.path.getsize(os.path.join(out, f["path"])) for f in b["files"]) and {f["path"] for f in b["files"]} >= {"install.sh", "piso-setup.sh", "pkgs/order.txt"},
      "the bundle says what it holds and how big it is: " + str([f["path"] for f in b["files"]][:5]))
check("SHA256SUMS" not in [f["path"] for f in b["files"]] and b["sums"].count("\n") == len(b["files"]) and f"{sha(INSTALL_SH)}  install.sh" in b["sums"], "and carries a sha256 list of its files (pushed last)")
check(b["names"] == want, "and the package names: " + str(b["names"]))

# which setup file: the same choice as install.sh
for arch, f in (("mipsel_24kc", "piso-setup.sh"), ("mipsel_74kc", "piso-setup.sh"), ("mips_24kc", "piso-setup-mips.sh"), ("aarch64_cortex-a53", "piso-setup-aarch64.sh"),
                ("arm_cortex-a7_neon-vfpv4", "piso-setup-armv7.sh"), ("arm_cortex-a9_vfpv3-d16", "piso-setup-armv7.sh"), ("x86_64", "piso-setup-x86_64.sh"), ("", "piso-setup.sh")):
    check(ps.installer_file_for(arch) == f, f"the setup file for {arch or 'an unknown CPU'} is {f}")
ok, text = not_possible(lambda: ps.installer_file_for("riscv64_generic"), "riscv64_generic")
check(ok, "a CPU with no portal program: " + text)

def expect_refusal(label, mutate, words, sources=SOURCES_A, serve=None):
    feeds = serve_site(sources, **(serve or {}))
    mutate()
    out2 = os.path.join(tmp, "bundle_bad")
    shutil.rmtree(out2, ignore_errors=True)
    ok, text = not_possible(lambda: ps.build_offline_bundle(info(INSTALLED_A, feeds), "main", out2, site=HOST), *words)
    check(ok, f"{label}: {text}")


curl = "/feeds/openwrt_base/curl_8.4.0-2_mipsel_24kc.ipk"
expect_refusal("a package whose bytes differ from the list", lambda: SERVED.__setitem__(curl, b"evil bytes of other length"), ("curl_8.4.0-2_mipsel_24kc.ipk", "size"))
expect_refusal("a package with the right size but another sha256", lambda: SERVED.__setitem__(curl, b"x" * len(b"curl 8.4.0-2 ipk")), ("curl_8.4.0-2_mipsel_24kc.ipk", "sha256"))
expect_refusal("a package that cannot be downloaded", lambda: SERVED.pop(curl), ("curl_8.4.0-2_mipsel_24kc.ipk",))
expect_refusal("a used source without a signature", lambda: SERVED.pop("/feeds/openwrt_base/Packages.sig"), ("openwrt_base", "Packages.sig"))
expect_refusal("an installer that does not match its checksum", lambda: SERVED.__setitem__("/setup/piso-setup.sh", INSTALLER + b"x"), ("piso-setup.sh", "sha256"))
expect_refusal("no installer", lambda: SERVED.pop("/setup/piso-setup.sh"), ("piso-setup.sh",))
expect_refusal("no install.sh", lambda: SERVED.pop("/install.sh"), ("install.sh",))
expect_refusal("a source that is down while a package is only there", lambda: [SERVED.pop(k) for k in list(SERVED) if k.startswith("/feeds/openwrt_packages/Packages")], ("not in any", "openwrt_packages"))
# a source that is down but not needed does not matter
feeds = serve_site(SOURCES_A)
SERVED.pop("/feeds/openwrt_core/Packages")
out3 = os.path.join(tmp, "bundle3")
b3 = ps.build_offline_bundle(info(INSTALLED_A, feeds), "main", out3, site=HOST)
check(b3["names"] == want, "a source that cannot be read, from which nothing is needed, is skipped: " + str(b3["names"]))
# nothing missing: no packages, but the installer files are there
feeds = serve_site(SOURCES_A)
inst_all = dict(INSTALLED_A, **{"libmicrohttpd-no-ssl": "1", "opennds": "1", "curl": "1", "libcurl4": "1", "jsonfilter": "1"})
b4 = ps.build_offline_bundle(info(inst_all, feeds), "main", os.path.join(tmp, "bundle4"), site=HOST)
check(b4["names"] == [] and open(f"{tmp}/bundle4/pkgs/order.txt").read() == "" and os.path.isfile(f"{tmp}/bundle4/piso-setup.sh"), "nothing missing: an empty order.txt and the setup files")

# ---- criterion 10: copying the bundle to the router's memory ------------------------------------------------------------------
class LocalProc:
    """What ssh.popen gives: a process whose standard input takes the bytes and whose output is read line by line."""

    def __init__(self, argv, corrupt):
        self.p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.stdin, self.stdout, self.corrupt = self.p.stdin, self.p.stdout, corrupt
        if corrupt:
            real = self.stdin.write
            self.stdin.write = lambda data: real(data[:-1] + bytes([data[-1] ^ 1]) if data else data)   # the link damages the last byte

    def wait(self):
        self.p.wait()

    @property
    def returncode(self):
        return self.p.returncode


class LocalSsh:
    """A router that is this computer: the remote command runs with sh, and every command is logged."""

    def __init__(self):
        self.commands, self.corrupt_part = [], None

    def popen(self, host, remote, *extra, **kw):
        self.commands.append(remote)
        return LocalProc(["sh", "-c", remote], bool(self.corrupt_part) and self.corrupt_part in remote and ".part" in remote)


def pushed(bundle, router_dir, ssh=None, mem=90000):
    ssh = ssh or LocalSsh()
    lines = []
    result = ps.push_offline_bundle(ssh, "10.0.0.1", bundle, mem, lines.append, offline_dir=router_dir)
    return ssh, result, lines


rd = os.path.join(tmp, "router_ram", "piso-offline")
ssh, result, lines = pushed(b, rd)
check(result == "copied", "push_offline_bundle copies the bundle: " + str(result))
for f in b["files"]:
    check(open(os.path.join(rd, f["path"]), "rb").read() == open(os.path.join(out, f["path"]), "rb").read(), f"{f['path']} arrived byte for byte")
check(os.path.isfile(f"{rd}/SHA256SUMS") and open(f"{rd}/SHA256SUMS").read() == b["sums"], "with its sha256 list")
file_cmds = [c for c in ssh.commands if "cat >" in c]
check(len(file_cmds) == len(b["files"]) + 1 and all(".part" in c and "mv " in c for c in file_cmds), "one ssh command per file (cat > file.part && mv), and one for the list: " + str(len(file_cmds)))
check(not any("scp" in c or "tar " in c or "sftp" in c for c in ssh.commands), "no scp, tar or sftp")
check(any("sha256sum -c" in c for c in ssh.commands) and "".join(ssh.commands).count("sha256sum -c") >= 1, "and the router checks all sha256 with sha256sum in one more command")
check(not [f for root, _, fs in os.walk(rd) for f in fs if f.endswith(".part")], "no half-written file is left")
ssh2, result2, _ = pushed(b, rd)
check(result2 == "already there" and not [c for c in ssh2.commands if "cat >" in c] and len(ssh2.commands) == 1, "the same bundle again (the second run at 10.0.0.1): only a check, nothing is copied: " + str(ssh2.commands))
open(f"{rd}/pkgs/order.txt", "a").write("x\n")
ssh3, result3, _ = pushed(b, rd)
check(result3 == "copied" and open(f"{rd}/pkgs/order.txt").read() == open(f"{out}/pkgs/order.txt").read(), "a bundle that is there but changed is copied again")
rd2 = os.path.join(tmp, "router_ram2", "piso-offline")
os.makedirs(rd2)
open(f"{rd2}/stale.ipk", "w").write("old")
pushed(b, rd2)
check(not os.path.exists(f"{rd2}/stale.ipk"), "an older copy's files are cleared first")
low = LocalSsh()
ok, text = not_possible(lambda: ps.push_offline_bundle(low, "10.0.0.1", b, 24000 + b["total"] // 1024 - 1, lambda l: None, offline_dir=os.path.join(tmp, "router_low")), "memory")
check(ok and low.commands == [], "less free memory than 24000 KB plus the bundle: the fallback, and nothing is sent: " + text)
ssh4, res4, _ = pushed(b, os.path.join(tmp, "router_exact"), mem=24000 + b["total"] // 1024 + 1)
check(res4 == "copied", "exactly enough memory is enough")
bad = LocalSsh()
bad.corrupt_part = "curl_8.4.0-2"
ok, text = not_possible(lambda: ps.push_offline_bundle(bad, "10.0.0.1", b, 90000, lambda l: None, offline_dir=os.path.join(tmp, "router_bad")), "curl_8.4.0-2", "intact")
check(ok, "a file damaged on the way is found by the router's sha256 check: " + text)

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
