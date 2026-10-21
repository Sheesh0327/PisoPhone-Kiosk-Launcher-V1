#!/usr/bin/env python3
"""Checks that what is about to be published can actually be installed, before `beta` is merged into `main`. Read-only.

    python3 scripts/verify_release.py [--against origin/main]

It looks at the update feeds in website/update (what boxes, phones and routers download) and at the sources, and prints PASS, WARN
or FAIL for each check; the exit code is 1 when anything FAILs. It needs `pip install cryptography` and, for the version checks,
a git checkout that has the --against ref (run `git fetch` first).

  - the owner key compiled into the box firmware is the same one the router program trusts (a mismatch means one of them
    would refuse everything the other accepts);
  - every firmware image in firmware.json matches its checksum and size, has the right header for its chip, and carries a
    signature that verifies against that key, and firmware.json agrees with the per-image manifests;
  - router.json: the setup file matches its checksum and size and the signature verifies;
  - app.json and the APK agree (scripts/check_published_apk.py);
  - THE VERSION RULES (the usual reason an update "does nothing"): a box installs firmware only if its version is newer than the
    one it runs, and a router only a newer setup/RELEASE. If the box firmware sources differ from the --against ref but
    PISO_FW_VERSION is the same, or the router setup file differs but setup/RELEASE is the same, nothing will install: FAIL.
"""
import argparse, base64, hashlib, json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
results = []


def report(level, text):
    results.append(level)
    print("%-5s %s" % (level, text))


def git(*args):
    p = subprocess.run(["git", *args], capture_output=True, text=True)
    return p.stdout if p.returncode == 0 else None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--against", default="origin/main", help="the ref that is live now (default origin/main)")
    a = ap.parse_args()
    try:
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError:
        sys.exit("needs: pip install cryptography")

    # --- the key
    hdr = open("esp32_firmware/include/LicensePubKey.h").read()
    body = hdr.split("LICENSE_PUBKEY_DER", 1)[1].split("};")[0] if "LICENSE_PUBKEY_DER" in hdr else ""
    der_fw = bytes(int(x, 16) for x in re.findall(r"0x([0-9a-fA-F]{2})", body))
    b64 = open("tools/pisoportal/owner_key.b64").read().strip()
    der_router = base64.b64decode(b64) if b64 else b""
    if not der_fw or not der_router:
        report("FAIL", "no owner key installed (firmware: %d bytes, router: %d bytes): boxes would accept UNSIGNED updates and routers none" % (len(der_fw), len(der_router)))
        pub = None
    elif der_fw != der_router:
        report("FAIL", "the owner key in the firmware and the one in the router program differ")
        pub = None
    else:
        report("PASS", "the same owner key is in the box firmware and in the router program")
        pub = serialization.load_der_public_key(der_fw)

    def verifies(sig_b64, text):
        try:
            pub.verify(base64.b64decode(sig_b64), text.encode(), ec.ECDSA(hashes.SHA256()))
            return True
        except Exception:
            return False

    # --- firmware feeds: the full product's, and the PisoPhone-only and PisoWiFi-only products' when they are published
    for feed_dir, label in (("website/update", "firmware feed"), ("website/update/phone", "PisoPhone-only firmware feed"),
                            ("website/update/wifi", "PisoWiFi-only firmware feed")):
        if feed_dir != "website/update" and not os.path.exists(feed_dir + "/firmware.json"):
            continue
        fw_version = re.search(r'PISO_FW_VERSION "([^"]+)"', open("esp32_firmware/include/FirmwareVersion.h").read()).group(1)
        feed = json.load(open(feed_dir + "/firmware.json"))
        chip_ids = {"esp32c3": 5, "esp32": 0}
        problems = []
        for chip, ent in feed.get("chips", {}).items():
            try:
                img = open(feed_dir + "/firmware-%s.bin" % chip, "rb").read()
                man = json.load(open(feed_dir + "/firmware-%s.bin.manifest.json" % chip))
            except OSError as e:
                problems.append("%s: %s" % (chip, e))
                continue
            sha = hashlib.sha256(img).hexdigest()
            if not (sha == ent["sha256"] == man["sha256"] and len(img) == ent["size"] == man["size"]):
                problems.append("%s: checksum or size differs between the image, its manifest and firmware.json" % chip)
            if img[:1] != b"\xe9" or int.from_bytes(img[12:14], "little") != chip_ids.get(chip, -1):
                problems.append("%s: not an image for this chip" % chip)
            if pub and not (verifies(man["sig"], "pisophone-fw-v1|%s|%s|%s|%d" % (chip, man["version"], sha, len(img))) and ent["sig"] == man["sig"]):
                problems.append("%s: signature does not verify" % chip)
            if man["version"] != feed["version"]:
                problems.append("%s: manifest says %s, firmware.json says %s" % (chip, man["version"], feed["version"]))
        if not feed.get("chips"):
            problems.append("firmware.json lists no chip")
        report("FAIL" if problems else "PASS", label + " (version %s, build %s): %s" % (feed["version"], feed.get("build"), "; ".join(problems) or "checksums, sizes, headers and signatures all verify"))

    # --- router feed
    r = json.load(open("website/update/router.json"))
    rb = open("website/update/router-setup.sh", "rb").read()
    rp = []
    if hashlib.sha256(rb).hexdigest() != r["sha256"] or len(rb) != r["size"]:
        rp.append("setup file differs from router.json (checksum or size)")
    if pub and not verifies(r["sig"], "pisophone-router-v1|%s|%s|%s|%s" % (r["version"], r["sha256"], r["size"], r["rollout"])):
        rp.append("signature does not verify")
    report("FAIL" if rp else "PASS", "router feed (release %s, rollout %s%%): %s" % (r["version"], r["rollout"], "; ".join(rp) or "checksum, size and signature verify"))

    # --- the router feeds of the other CPU families (website/update/<family>/): the same checks
    import glob
    for jp in sorted(glob.glob("website/update/*/router.json")):
        fam = os.path.basename(os.path.dirname(jp))
        try:
            rr = json.load(open(jp))
            rrb = open(os.path.join(os.path.dirname(jp), "router-setup.sh"), "rb").read()
        except (OSError, ValueError) as e:
            report("FAIL", "router feed %s: %s" % (fam, e))
            continue
        fp = []
        if hashlib.sha256(rrb).hexdigest() != rr["sha256"] or len(rrb) != rr["size"]:
            fp.append("setup file differs from router.json (checksum or size)")
        if pub and not verifies(rr["sig"], "pisophone-router-v1|%s|%s|%s|%s" % (rr["version"], rr["sha256"], rr["size"], rr["rollout"])):
            fp.append("signature does not verify")
        if ("PISO_ARCH='%s'" % fam).encode() not in rrb:
            fp.append("the setup file is not built for %s" % fam)
        report("FAIL" if fp else "PASS", "router feed %s (release %s, rollout %s%%): %s" % (fam, rr["version"], rr["rollout"], "; ".join(fp) or "checksum, size and signature verify"))

    # --- the app
    p = subprocess.run([sys.executable, "scripts/check_published_apk.py"], capture_output=True, text=True)
    report("PASS" if p.returncode == 0 else "FAIL", "app feed: " + ((p.stdout or p.stderr).strip().splitlines() or ["no output"])[-1])

    # --- the version rules, against what is live
    ref = a.against
    if git("rev-parse", "--verify", "-q", ref) is None:
        report("WARN", "no ref %s here (run git fetch): the version rules were not checked" % ref)
    else:
        live_fw = git("show", "%s:esp32_firmware/include/FirmwareVersion.h" % ref)
        live_ver = re.search(r'PISO_FW_VERSION "([^"]+)"', live_fw).group(1) if live_fw else None
        src = ["esp32_firmware/src", "esp32_firmware/include", "esp32_firmware/web", "esp32_firmware/platformio.ini", "esp32_firmware/envs"]
        changed = [l for l in (git("diff", "--name-only", ref, "HEAD", "--", *src) or "").splitlines()
                   if not re.search(r"include/(WebAssets|FirmwareVersion)\.h$", l) or l.endswith("WebAssets.h")]
        if changed and live_ver == fw_version:
            report("FAIL", "box firmware changed since %s (%d files) but PISO_FW_VERSION is still %s: no box will install it. Raise it in esp32_firmware/include/FirmwareVersion.h" % (ref, len(changed), fw_version))
        elif changed:
            report("PASS", "box firmware changed since %s and its version was raised (%s -> %s)" % (ref, live_ver, fw_version))
        else:
            report("PASS", "box firmware is the same as on %s" % ref)
        live_rel = (git("show", "%s:setup/RELEASE" % ref) or "").strip()
        rel = open("setup/RELEASE").read().strip()
        # only what a router installs: not the tests under router/tests or tools/pisoportal/tests
        rchanged = bool(git("diff", "--name-only", ref, "HEAD", "--", "setup/piso-setup.sh", "setup/src", "router", "tools/pisoportal/src",
                            ":(exclude)router/tests"))
        key = lambda v: tuple(int(x) for x in re.findall(r"\d+", v))
        if rchanged and live_rel and key(rel) <= key(live_rel):
            report("FAIL", "router software changed since %s but setup/RELEASE is still %s: no router will install it" % (ref, rel))
        else:
            report("PASS", "router release %s (live: %s)%s" % (rel, live_rel or "?", "" if not rchanged else ", changed and raised"))
        if key(r["version"]) < key(rel):
            report("WARN", "router.json here is release %s but setup/RELEASE is %s: publish-router.yml signs the newer one when this reaches main" % (r["version"], rel))

    print()
    bad = results.count("FAIL")
    print("%d FAIL, %d WARN, %d PASS" % (bad, results.count("WARN"), results.count("PASS")))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
