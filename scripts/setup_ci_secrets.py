#!/usr/bin/env python3
"""Puts the CI secrets into the CircleCI project, checked, without them ever passing through the clipboard, a file or the
screen. Run it on YOUR OWN computer (never in CI or a cloud session), from the repository folder:

    python3 scripts/setup_ci_secrets.py                  your existing signing keystore (the normal case)
    python3 scripts/setup_ci_secrets.py --new-keystore   a brand-new signing key (see below: almost never what you want)
    python3 scripts/setup_ci_secrets.py --dry-run        every check, nothing uploaded

What it sets (docs/CI.md): KEYSTORE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, GITHUB_PUSH_TOKEN and, if you use it,
RELEASES_TOKEN. Every secret is typed hidden (or generated), checked, and sent straight to CircleCI's API over https:
  - the keystore must open with the passwords you type, the key must be in it, and its certificate must be the one the
    published app is signed with (signatureChecksum in website/update/app.json): an APK signed with any other key cannot
    update the phones that are installed already, so a wrong keystore is refused before anything is uploaded;
  - keytool gets the passwords through its environment (-storepass:env), never on its command line;
  - the GitHub tokens must open this repository (and the releases repository); a classic token is refused unless you
    insist, because it reaches every repository you have;
  - the CircleCI token is only used for this run: revoke it afterwards (the script says where).
Needs Python 3.8+ and keytool (it comes with any Java JDK, e.g. Android Studio's). Nothing to install.

--new-keystore makes a new 4096-bit RSA signing key in a new PKCS12 keystore with a random 43-character password. Use it
only before the first release to real phones: phones that have the app already must be factory reset or have the app
removed to take an APK signed with the new key. The keystore file and its password are then shown ONCE: keep both in your
password manager and a second, offline place. CircleCI cannot give them back, and without them no update can ever be built."""
import argparse
import base64
import getpass
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "Sheesh0327/PisoPhone-Kiosk-Launcher-V1"
RELEASES_REPO = "Sheesh0327/PisoPhone-Releases"
GITHUB_API = os.environ.get("PISO_GITHUB_API", "https://api.github.com")      # (tests point these at a fake server)
CIRCLECI_API = os.environ.get("PISO_CIRCLECI_API", "https://circleci.com/api/v2")
ALIAS_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")


class Stop(Exception):
    pass


def say(text=""):
    print(text, flush=True)


def ask_secret(prompt, read=getpass.getpass):
    value = read(prompt)
    if value != value.strip() or "\n" in value:
        raise Stop("that value has spaces or a line break at its ends: paste it again without them")
    return value


# ---- the signing key -----------------------------------------------------------------------------------------------------

def find_keytool():
    for base in (os.environ.get("JAVA_HOME"), os.environ.get("ANDROID_STUDIO_JDK")):
        if base:
            path = os.path.join(base, "bin", "keytool.exe" if os.name == "nt" else "keytool")
            if os.path.exists(path):
                return path
    path = shutil.which("keytool")
    if not path:
        raise Stop("keytool was not found. Install a Java JDK (or use Android Studio's: set JAVA_HOME to its jbr folder).")
    return path


def keytool(tool, args, passwords):
    """Runs keytool with the passwords in its environment only (its -xxxpass:env options), never on its command line."""
    env = dict(os.environ)
    env.update(passwords)
    r = subprocess.run([tool, *args], env=env, capture_output=True, text=True, timeout=180)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def cert_sha256(tool, keystore, alias, store_password):
    """The SHA-256 of the key's certificate, or raises Stop with keytool's reason."""
    rc, out = keytool(tool, ["-list", "-v", "-keystore", keystore, "-alias", alias, "-storepass:env", "PISO_SP"],
                      {"PISO_SP": store_password})
    if rc != 0:
        if re.search(r"password was incorrect|tampered|Keystore was tampered", out, re.I):
            raise Stop("the keystore password is wrong")
        if re.search(r"does not exist", out, re.I):
            raise Stop(f"there is no key called {alias!r} in that keystore (the aliases in it: {', '.join(aliases(tool, keystore, store_password)) or 'none'})")
        raise Stop("keytool could not read the keystore: " + (out.strip().splitlines() or ["?"])[-1])
    m = re.search(r"SHA256:\s*([0-9A-F]{2}(?::[0-9A-F]{2}){31})", out)
    if not m:
        raise Stop("keytool did not show the certificate's SHA-256")
    return m.group(1).replace(":", "").lower()


def aliases(tool, keystore, store_password, strict=False):
    """The keys in the keystore. strict: a keystore that does not open is an error (and says why)."""
    rc, out = keytool(tool, ["-list", "-keystore", keystore, "-storepass:env", "PISO_SP"], {"PISO_SP": store_password})
    if rc != 0:
        if not strict:
            return []
        if re.search(r"password was incorrect|tampered", out, re.I):
            raise Stop("the keystore password is wrong")
        raise Stop("keytool could not open the keystore: " + (out.strip().splitlines() or ["?"])[-1])
    return [line.split(",")[0].strip() for line in out.splitlines() if re.search(r",\s*\w+,\s*PrivateKeyEntry", line)]


def check_key_password(tool, keystore, alias, store_password, key_password):
    """True when the key opens with key_password (it is copied into a throw-away keystore, which is then deleted)."""
    work = tempfile.mkdtemp(prefix="piso-ks-")
    try:
        rc, _out = keytool(tool, ["-importkeystore", "-noprompt", "-srckeystore", keystore, "-srcalias", alias,
                                  "-srcstorepass:env", "PISO_SP", "-srckeypass:env", "PISO_KP",
                                  "-destkeystore", os.path.join(work, "check.p12"), "-deststoretype", "PKCS12",
                                  "-deststorepass:env", "PISO_DP", "-destkeypass:env", "PISO_DP"],
                           {"PISO_SP": store_password, "PISO_KP": key_password, "PISO_DP": secrets.token_urlsafe(24)})
        return rc == 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def qr_checksum(sha_hex):
    """The certificate hash as the QR setup and app.json write it (URL-safe base64, no padding)."""
    return base64.urlsafe_b64encode(bytes.fromhex(sha_hex)).decode().rstrip("=")


def published_checksum():
    try:
        with open(os.path.join(ROOT, "website", "update", "app.json")) as f:
            return json.load(f).get("signatureChecksum")
    except (OSError, ValueError):
        return None


def new_keystore(tool, path):
    """A new signing key: RSA 4096, valid 30 years (Google Play asks for 25+), PKCS12, one random password for both."""
    if os.path.exists(path):
        raise Stop(f"{path} exists already: choose another file name (an existing keystore is never overwritten)")
    password = secrets.token_urlsafe(32)          # 43 characters, 256 bits
    alias = "pisophone"
    rc, out = keytool(tool, ["-genkeypair", "-keystore", path, "-storetype", "PKCS12", "-alias", alias, "-keyalg", "RSA",
                             "-keysize", "4096", "-sigalg", "SHA256withRSA", "-validity", "10950",
                             "-dname", "CN=PisoPhone, O=PisoPhone", "-storepass:env", "PISO_SP", "-keypass:env", "PISO_SP"],
                      {"PISO_SP": password})
    if rc != 0:
        raise Stop("keytool could not make the keystore: " + out.strip())
    if os.name != "nt":
        os.chmod(path, 0o600)
    return alias, password


# ---- the services --------------------------------------------------------------------------------------------------------

def http(method, url, headers, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=dict(headers, **({"Content-Type": "application/json"} if data else {})))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode() or "{}"
            return r.status, json.loads(text)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}
    except urllib.error.URLError as e:
        raise Stop(f"could not reach {urllib.parse.urlsplit(url).netloc}: {e.reason}")


def check_github_token(token, repo, label, allow_classic=False):
    if not token.startswith("github_pat_"):
        if token.startswith(("ghp_", "gho_")) and not allow_classic:
            raise Stop(f"the {label} is a classic token, which reaches every repository you have. Make a fine-grained "
                       f"token limited to {repo} (docs/CI.md), or run again with --allow-classic-token")
        if not token.startswith(("ghp_", "gho_")):
            raise Stop(f"the {label} does not look like a GitHub token (they start with github_pat_)")
    status, body = http("GET", f"{GITHUB_API}/repos/{repo}", {"Authorization": f"Bearer {token}",
                                                             "Accept": "application/vnd.github+json"})
    if status != 200:
        raise Stop(f"the {label} cannot open {repo} (GitHub answered {status}): check its repository access")
    return body


def circleci(token):
    return {"Circle-Token": token, "Accept": "application/json"}


def project_slug():
    return "gh/" + REPO


def check_circleci(token, slug):
    status, body = http("GET", f"{CIRCLECI_API}/project/{slug}", circleci(token))
    if status == 401:
        raise Stop("CircleCI did not accept that API token")
    if status != 200:
        raise Stop(f"CircleCI has no project {slug} for that token ({status}): set up the project first (docs/CI.md, steps 1-3)")
    return body


def upload(token, slug, values):
    for name, value in values.items():
        status, body = http("POST", f"{CIRCLECI_API}/project/{slug}/envvar", circleci(token), {"name": name, "value": value})
        if status not in (200, 201):
            raise Stop(f"CircleCI refused {name} ({status}: {body.get('message', '')})")
        say(f"  set {name}")
    status, body = http("GET", f"{CIRCLECI_API}/project/{slug}/envvar", circleci(token))
    present = {item.get("name") for item in body.get("items", [])} if status == 200 else set()
    missing = [n for n in values if n not in present]
    if missing:
        raise Stop("CircleCI does not list " + ", ".join(missing) + " after setting it")


# ---- the whole run -------------------------------------------------------------------------------------------------------

def run(args, read=input, read_secret=getpass.getpass):
    say("PisoPhone CI secrets. Nothing you type is shown, saved or put on a command line.")
    tool = find_keytool()
    values = {}

    if args.new_keystore:
        say()
        say("A NEW signing key: phones that have the app already cannot update to APKs signed with it (they must remove the")
        say("app or be factory reset). Use this only before the first release to real phones.")
        if read("Type NEW KEY to go on: ").strip() != "NEW KEY":
            raise Stop("cancelled")
        path = os.path.abspath(read("File name for the new keystore [pisophone-release.p12]: ").strip() or "pisophone-release.p12")
        alias, password = new_keystore(tool, path)
        store_password = key_password = password
        say()
        say("=================== WRITE THIS DOWN NOW (it is shown once) ===================")
        say(f"  keystore file:   {path}")
        say(f"  alias:           {alias}")
        say(f"  password:        {password}      (the same for the keystore and the key)")
        say("  Keep the file and the password in your password manager AND in a second, offline place.")
        say("===============================================================================")
        if read_secret("Type the password back to confirm you saved it (not shown): ") != password:
            raise Stop(f"that does not match. Nothing was uploaded; the keystore stays in {path}")
        keystore = path
    else:
        keystore = os.path.abspath(read("Your signing keystore file (.jks or .p12): ").strip().strip('"'))
        if not os.path.isfile(keystore):
            raise Stop(f"no file {keystore}")
        store_password = ask_secret("Keystore password (not shown): ", read_secret)
        found = aliases(tool, keystore, store_password, strict=True)
        default = found[0] if len(found) == 1 else ""
        alias = read(f"Key alias [{default}]: ").strip() or default
        if not ALIAS_RE.fullmatch(alias or ""):
            raise Stop("a key alias is needed (" + (", ".join(found) or "none found") + ")")
        key_password = ask_secret("Key password (Enter: the same as the keystore password; not shown): ", read_secret) or store_password

    sha = cert_sha256(tool, keystore, alias, store_password)
    if not check_key_password(tool, keystore, alias, store_password, key_password):
        raise Stop("the key password is wrong (the keystore opened, the key in it did not)")
    checksum, published = qr_checksum(sha), published_checksum()
    say(f"Signing certificate SHA-256: {sha}")
    if args.new_keystore:
        say("This is a new key: the next APK starts a new line of updates.")
    elif published and checksum != published:
        raise Stop(f"this key is NOT the one the published app is signed with ({checksum} here, {published} published). "
                   "Phones would refuse every update built with it. Find the original keystore; nothing was uploaded")
    elif published:
        say("It is the key the published app is signed with: phones will take the updates.")
    else:
        say("WARNING: website/update/app.json has no signatureChecksum to compare with (run this from the repository folder).")
    with open(keystore, "rb") as f:
        values["KEYSTORE_BASE64"] = base64.b64encode(f.read()).decode()
    values.update(STORE_PASSWORD=store_password, KEY_ALIAS=alias, KEY_PASSWORD=key_password)

    say()
    say(f"GitHub token for CI's commits: a fine-grained token, repository {REPO} only, Contents and Issues: Read and write.")
    say("(GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens)")
    token = ask_secret("GITHUB_PUSH_TOKEN (not shown): ", read_secret)
    check_github_token(token, REPO, "GITHUB_PUSH_TOKEN", args.allow_classic_token)
    values["GITHUB_PUSH_TOKEN"] = token
    say("  it opens the repository.")
    releases = ask_secret(f"RELEASES_TOKEN for {RELEASES_REPO} (Enter: none, the APK stays on the website; not shown): ", read_secret)
    if releases:
        check_github_token(releases, RELEASES_REPO, "RELEASES_TOKEN", args.allow_classic_token)
        values["RELEASES_TOKEN"] = releases
        say("  it opens the releases repository.")

    say()
    say("A CircleCI personal API token, used for this run only (CircleCI > User Settings > Personal API Tokens). Revoke it")
    say("there afterwards.")
    cci = ask_secret("CircleCI API token (not shown): ", read_secret)
    slug = project_slug()
    check_circleci(cci, slug)
    say(f"  the project {slug} is there.")
    if args.dry_run:
        say()
        say("Dry run: every check passed; nothing was uploaded. Would set: " + ", ".join(values))
        return 0
    say()
    say("Setting the project's environment variables:")
    upload(cci, slug, values)
    say()
    say("DONE. Push to beta (or Trigger Pipeline in CircleCI) to build. Now revoke the CircleCI API token you used here.")
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="Put the CI secrets into the CircleCI project, checked (docs/CI.md).")
    p.add_argument("--new-keystore", action="store_true", help="make a new signing key (only before the first release to phones)")
    p.add_argument("--dry-run", action="store_true", help="every check, nothing uploaded")
    p.add_argument("--allow-classic-token", action="store_true", help="accept a classic GitHub token (not recommended)")
    args = p.parse_args(argv)
    try:
        return run(args)
    except Stop as e:
        say()
        say(f"STOPPED: {e}")
        return 1
    except (KeyboardInterrupt, EOFError):
        say()
        say("Stopped. Nothing more was uploaded.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
