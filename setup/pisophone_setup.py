#!/usr/bin/env python3
"""PisoPhone router setup, run from your computer: no SSH session to drive and nothing to copy or paste.

    python3 pisophone_setup.py                    set up a factory-reset OpenWrt router (asks a few questions first)
    python3 pisophone_setup.py --branch beta      the same with another branch's setup files (testing)
    python3 pisophone_setup.py --update           new software only, on a router that is set up already

What you need: the router factory reset (OpenWrt), the modem in its WAN port, this computer on one of its LAN ports by
cable (turn this computer's Wi-Fi off for the setup), and the coin box flashed and powered on near the router.
It needs Python 3.8+ and the ssh command (built into Windows 10/11, macOS and Linux); nothing to install.

It asks for the public Wi-Fi name, the site name and the three passwords (Enter generates them), shows them for review and
then does everything the manual steps in setup/README.md do: it finds the router (192.168.1.1 or 10.0.0.1), runs the
one-line installer there in its unattended mode (setup/install.sh --yes, which checks the setup file's sha256), waits for
the router to move to 10.0.0.1, runs the setup with your answers and saves the summary and the printable setup sheet
next to you. The passwords travel over the SSH connection's input, never on a command line. Running it again is safe: a
router that is set up already keeps its settings and passwords (it is then only repaired or finished)."""
import argparse
import getpass
import os
import pathlib
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import webbrowser

SITE_HOST = "pisophone.pages.dev"   # the website (Cloudflare Pages): the installer and the setup file come from there
DEFAULT_BRANCH = "main"
FACTORY_IP = "192.168.1.1"      # OpenWrt's address after a factory reset
TARGET_IP = "10.0.0.1"          # where the PisoPhone setup puts it
KIOSK_SSID = "PisoKiosk"        # fixed names (setup/piso-setup.sh.in)
BOX_SSID = "PisoCoinBox"
MOVE_WAIT = 240                 # seconds to wait for the router at 10.0.0.1 after the move
FORBIDDEN = "'\"\\$`"           # characters the router's shell quoting cannot take
# (a generated password avoids characters that are easily misread: 0/O, 1/l/I)
ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
PASSWORDS = [  # (environment name read by the setup, what it is, min, max, generated length)
    ("ROOT_PASSWORD", "router password (SSH and LuCI login)", 8, 63, 14),
    ("KIOSK_PASSWORD", f"{KIOSK_SSID} Wi-Fi password (for the rental phones' setup)", 8, 63, 12),
    ("BOX_NEW_ADMIN_PASSWORD", "coin box admin password (the box's page; also the phones' admin PIN)", 8, 32, 16),
]


class SetupError(Exception):
    pass


def say(text=""):
    print(text, flush=True)


# ---- what is typed -------------------------------------------------------------------------------------------------------

def password_problem(value, lo, hi):
    """Why the router setup would refuse this password, or None."""
    if not lo <= len(value) <= hi:
        return f"use {lo} to {hi} characters"
    if any(c.isspace() for c in value) or any(c in FORBIDDEN for c in value):
        return "no spaces, quotes, backslashes, $ or backticks"
    if any(ord(c) < 33 or ord(c) > 126 for c in value):
        return "use plain letters, digits and punctuation"
    return None


def name_problem(value, what):
    """Why the setup would refuse this Wi-Fi / site name, or None."""
    if not 1 <= len(value) <= 32:
        return "use 1 to 32 characters"
    if any(c in FORBIDDEN for c in value) or any(ord(c) < 32 for c in value):
        return "no quotes, backslashes, $ or backticks"
    if what == "wifi" and value == KIOSK_SSID:
        return f"{KIOSK_SSID} is the rental phones' network"
    if what == "wifi" and BOX_SSID in value:
        return f"it may not contain {BOX_SSID} (the coin box's network)"
    return None


def generate(length):
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


def ask_text(prompt, default, what, read=input):
    while True:
        value = read(f"{prompt} [{default}]: ").strip() or default
        problem = name_problem(value, what)
        if not problem:
            return value
        say(f"  Not usable: {problem}.")


def ask_password(label, lo, hi, length, read=getpass.getpass):
    say()
    say(f"Choose the {label}: {lo} to {hi} characters, no spaces or quotes. Press Enter to have one generated.")
    while True:
        value = read("  Password (not shown): ")
        if not value:
            value = generate(length)
            say(f"  Generated: {value}")
            return value
        problem = password_problem(value, lo, hi)
        if problem:
            say(f"  Not usable: {problem}.")
            continue
        if read("  Type it again: ") != value:
            say("  The two entries differ. Try again.")
            continue
        return value


def collect_answers(args, read=input, read_secret=getpass.getpass):
    """The answers the setup needs (environment name -> value), asked here or taken from the options."""
    answers = {}
    if args.yes:
        answers["GUEST_SSID"] = args.guest_ssid or "PisoWiFi"
        answers["SITE_NAME"] = args.site_name or answers["GUEST_SSID"]
        for env, _label, _lo, _hi, length in PASSWORDS:
            answers[env] = generate(length)
    else:
        say()
        say("A few questions first. Nothing on the router is changed until you have reviewed your answers.")
        say()
        answers["GUEST_SSID"] = ask_text("Name of the public customer Wi-Fi", args.guest_ssid or "PisoWiFi", "wifi", read)
        answers["SITE_NAME"] = ask_text("Name of the shop / site (on the setup sheet and in alerts)",
                                        args.site_name or answers["GUEST_SSID"], "site", read)
        for env, label, lo, hi, length in PASSWORDS:
            answers[env] = ask_password(label, lo, hi, length, read_secret)
    for env, label, lo, hi, _length in PASSWORDS:
        problem = password_problem(answers[env], lo, hi)
        if problem:
            raise SetupError(f"the {label}: {problem}")
    for key, what in (("GUEST_SSID", "wifi"), ("SITE_NAME", "site")):
        problem = name_problem(answers[key], what)
        if problem:
            raise SetupError(f"{key}: {problem}")
    return answers


def review(answers):
    say()
    say("================ PLEASE REVIEW (write the passwords down now) ================")
    say(f"  Public Wi-Fi (customers):  {answers['GUEST_SSID']}  (open, behind the coin payment page)")
    say(f"  Rental-phone Wi-Fi:        {KIOSK_SSID}  (hidden)")
    say(f"  Site name:                 {answers['SITE_NAME']}")
    say(f"  Router address:            {TARGET_IP}")
    say(f"  Router password:           {answers['ROOT_PASSWORD']}")
    say(f"  {KIOSK_SSID} Wi-Fi password:  {answers['KIOSK_PASSWORD']}")
    say(f"  Coin box admin password:   {answers['BOX_NEW_ADMIN_PASSWORD']}")
    say("  The router's Wi-Fi networks are replaced and the coin box is paired and set up.")
    say("  (A router that was set up before keeps the names and passwords it already has.)")
    say("==============================================================================")


# ---- reaching the router -------------------------------------------------------------------------------------------------

def port_open(host, port=22, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def find_router(probe=port_open):
    """The router's address: 10.0.0.1 (moved already) or 192.168.1.1 (factory), or None."""
    for host in (TARGET_IP, FACTORY_IP):
        if probe(host):
            return host
    return None


class Ssh:
    """The system's ssh client. The router's host key is trusted the first time and then pinned for this run (a file of our
    own, so a router that was reset since the last setup gives no "host key changed" error). PISO_SSH replaces the ssh
    command (tests)."""

    def __init__(self, workdir):
        self.command = shlex.split(os.environ.get("PISO_SSH", "")) or ["ssh"]
        known = os.path.join(workdir, "known_hosts").replace(os.sep, "/")
        self.options = [
            "-o", f'UserKnownHostsFile="{known}"', "-o", "StrictHostKeyChecking=accept-new",
            "-o", "HostKeyAlias=pisophone-router",      # the same key at 192.168.1.1 and at 10.0.0.1
            "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=12",
            "-o", "IdentitiesOnly=yes", "-o", "LogLevel=ERROR",
        ]

    def argv(self, host, remote, *extra):
        return [*self.command, *self.options, *extra, f"root@{host}", remote]

    def run(self, host, remote, *extra, **kw):
        return subprocess.run(self.argv(host, remote, *extra), **kw)

    def popen(self, host, remote, *extra, **kw):
        return subprocess.Popen(self.argv(host, remote, *extra), **kw)


def probe_router(ssh, host):
    """Logs in without asking for a password (a factory-reset OpenWrt router has none): 'factory', 'configured' (it was set
    up before), 'password' (it has a password: probably set up before), or raises when it is not an OpenWrt router."""
    remote = "[ -f /etc/openwrt_release ] && echo @@OPENWRT; [ -s /etc/piso-setup.conf ] && echo @@CONFIGURED; true"
    try:
        r = ssh.run(host, remote, "-T", "-o", "BatchMode=yes", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        raise SetupError(f"the router at {host} does not answer SSH logins")
    if r.returncode == 0:
        if "@@OPENWRT" not in r.stdout:
            raise SetupError(f"the device at {host} is not an OpenWrt router (is this computer's Wi-Fi still on?)")
        return "configured" if "@@CONFIGURED" in r.stdout else "factory"
    if "permission denied" in r.stderr.lower():
        return "password"
    raise SetupError(f"could not log in to {host}: {r.stderr.strip() or 'ssh failed'}")


def site_url(branch):
    """The website of a branch: main's is the production site, any other branch has its preview site (Cloudflare's branch
    alias, as in setup/install.sh: lowercase, other characters become '-', 28 at most)."""
    if branch == "main":
        return f"https://{SITE_HOST}"
    alias = re.sub(r"[^a-z0-9]", "-", branch.lower())[:28].strip("-")
    return f"https://{alias}.{SITE_HOST}"


def installer_command(branch, *args):
    """The remote command: download the one-line installer of this branch and run it. Downloaded to a file first, so a
    failed download is reported (a pipe into sh would run nothing and report success)."""
    url = f"{site_url(branch)}/install.sh"
    run = " ".join(shlex.quote(a) for a in ("--branch", branch, "--yes", *args))
    return (f"rm -f /tmp/piso-install.sh; if ! wget -q -T 60 -O /tmp/piso-install.sh {shlex.quote(url)}; then "
            f"echo 'ERROR: the router could not download the installer. Is the modem plugged into its WAN port, with internet?'; "
            f"exit 3; fi; sh /tmp/piso-install.sh {run}")


def setup_command(branch):
    """The remote command for the setup itself: the answers are read from the connection's input (never on a command line,
    where any process on the router could read them), then the installer runs the setup, then the results are sent back
    between markers."""
    names = [env for env, *_ in PASSWORDS] + ["GUEST_SSID", "SITE_NAME"]
    reads = "; ".join(f"IFS= read -r {n}" for n in names)
    return (f"{reads}; export {' '.join(names)}; {installer_command(branch)}; rc=$?; echo; echo \"@@PISO-RC $rc\"; "
            f"echo \"@@PISO-STATE $(cat /tmp/piso-setup.state 2>/dev/null)\"; "
            f"if [ -r /root/piso-setup-summary.txt ]; then echo @@PISO-SUMMARY; cat /root/piso-setup-summary.txt; fi; "
            f"if [ -r /root/piso-handout.html ]; then echo @@PISO-HANDOUT; cat /root/piso-handout.html; fi; echo @@PISO-END")


def stream(proc, out=None):
    """Shows the router's output as it comes and returns the sections after the markers (rc, state, summary, handout)."""
    out = out or sys.stdout
    result = {"rc": None, "state": "", "summary": [], "handout": []}
    section = None
    for raw in iter(proc.stdout.readline, b""):
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("@@PISO-RC "):
            result["rc"] = int(line.split()[1]) if line.split()[1:] and line.split()[1].isdigit() else None
            section = "tail"
            continue
        if line.startswith("@@PISO-STATE"):
            result["state"] = line[len("@@PISO-STATE"):].strip()
            continue
        if line in ("@@PISO-SUMMARY", "@@PISO-HANDOUT"):
            section = line[7:].lower()
            continue
        if line == "@@PISO-END":
            section = "end"
            continue
        if section in ("summary", "handout"):
            result[section].append(line)
        elif section is None:
            out.write(line + "\n")
            out.flush()
    proc.wait()
    return result


def save_private(path, text):
    """Writes a file only this user can read (it holds passwords)."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def wait_for_move(probe=port_open, wait=MOVE_WAIT, sleep=time.sleep, now=time.monotonic, renew=None):
    """After the move: waits until the router answers at 10.0.0.1 (this computer needs a new address in 10.0.0.x)."""
    say()
    say(f"The router is moving to {TARGET_IP}. Waiting for it (this computer needs a new address on the router's network)...")
    start, hinted, renewed = now(), False, False
    sleep(5)
    while now() - start < wait:
        if probe(TARGET_IP):
            say(f"The router answers at {TARGET_IP}.")
            return True
        elapsed = now() - start
        if elapsed > 20 and renew and not renewed:
            renewed = True
            renew()
        if elapsed > 30 and not hinted:
            hinted = True
            say("Still waiting. Unplug this computer's network cable from the router, wait 5 seconds and plug it back in.")
        sleep(3)
    return False


def renew_address():
    """Windows: ask for a new address at once (other systems do it when the cable is replugged)."""
    if os.name == "nt":
        say("Asking Windows for a new network address (ipconfig /renew)...")
        try:
            subprocess.run(["ipconfig", "/renew"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90, check=False)
        except (OSError, subprocess.SubprocessError):
            pass


# ---- the whole run -------------------------------------------------------------------------------------------------------

def run_setup(args, ssh, read=input, read_secret=getpass.getpass, probe=port_open, wait=wait_for_move, open_page=webbrowser.open):
    host = args.router or find_router(probe)
    if not host:
        raise SetupError(
            f"no router found at {TARGET_IP} or {FACTORY_IP}. Check that this computer is plugged into one of the router's LAN "
            "ports (not WAN), that the router has finished starting (about 2 minutes after power-on) and was factory reset, and "
            "turn this computer's Wi-Fi off.")
    say(f"Router found at {host}.")
    state = probe_router(ssh, host)
    if args.update:
        if state == "factory":
            raise SetupError("this router has not been set up yet: run this script without --update")
        say("Installing the current PisoPhone software (you are asked for the router password)...")
        r = ssh.run(host, installer_command(args.branch, "update"), "-T", stdin=subprocess.DEVNULL)
        if r.returncode != 0:
            raise SetupError("the update did not finish (see the messages above). It is safe to run it again.")
        say()
        say("UPDATE DONE.")
        return 0

    if state in ("configured", "password"):
        # (the router's own settings win over new answers, so none are asked: they would not be used)
        say("This router was set up before (or has a password): it keeps the names and passwords it has, and the setup")
        say("finishes or repairs it. Anything it does not have yet is generated and shown in the summary.")
        if state == "password":
            say("Type the router password when ssh asks for it (it is in your saved summary; a router that was just")
            say("factory reset has none: press Enter).")
        if not args.yes and read("Continue? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1
        answers = {env: "" for env, *_ in PASSWORDS}
        answers.update(GUEST_SSID="", SITE_NAME="")
    else:
        answers = collect_answers(args, read, read_secret)
        review(answers)
        if not args.yes and read("Apply these settings to the router? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1

    if host == FACTORY_IP:
        say()
        say(f"Step 1 of 2: downloading the setup to the router and moving it to {TARGET_IP}...")
        r = ssh.run(host, installer_command(args.branch), "-T", stdin=subprocess.DEVNULL)
        if r.returncode != 0:
            raise SetupError("the router could not be prepared (see the messages above). Nothing else was changed; run this again.")
        if not wait(probe=probe, renew=renew_address):
            raise SetupError(
                f"the router does not answer at {TARGET_IP}. Unplug and replug this computer's network cable (or restart the "
                "computer's network), then run this script again: it continues where it stopped.")
        host = TARGET_IP
    else:
        say()
        say("Step 1 of 2: the router is at its address already.")

    say()
    say("Step 2 of 2: the setup runs on the router (about 3 to 8 minutes; it waits for the coin box to join, so keep the")
    say("box powered on). Do not close this window.")
    say()
    values = "".join(answers[env] + "\n" for env in [e for e, *_ in PASSWORDS] + ["GUEST_SSID", "SITE_NAME"])
    proc = ssh.popen(host, setup_command(args.branch), "-T", stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    try:
        proc.stdin.write(values.encode())
        proc.stdin.close()
    except OSError:
        pass   # ssh failed before reading: its message and exit status say why
    result = stream(proc)

    saved = []
    stamp = time.strftime("%Y%m%d-%H%M")
    os.makedirs(args.out, exist_ok=True)
    if result["summary"]:
        path = os.path.join(args.out, f"pisophone-summary-{stamp}.txt")
        save_private(path, "\n".join(result["summary"]) + "\n")
        saved.append(("Summary (every password)", path))
    if result["handout"]:
        path = os.path.join(args.out, f"pisophone-setup-sheet-{stamp}.html")
        save_private(path, "\n".join(result["handout"]) + "\n")
        saved.append(("Printable setup sheet", path))

    say()
    if result["rc"] is None:
        raise SetupError("the connection to the router was lost before the setup finished. Run this script again: it continues "
                         "where it stopped (the router keeps what is done).")
    ok = result["rc"] == 0 and result["state"].startswith("DONE all checks passed")
    for label, path in saved:
        say(f"{label} saved to: {os.path.abspath(path)}  (keep it private)")
    if ok:
        say()
        say("SETUP COMPLETE: all checks passed.")
        say(f"Next: set up the rental phones from the coin box's page (http://{TARGET_IP.rsplit('.', 1)[0]}.10/), Install & Provision.")
        sheet = [path for label, path in saved if path.endswith(".html")]
        if sheet and not args.no_browser:
            try:
                open_page(pathlib.Path(os.path.abspath(sheet[0])).as_uri())
            except Exception:
                pass   # (no browser: the path is shown above)
        if not args.yes and read("Connect Telegram alerts now? You need a bot token from @BotFather. [y/N] ").strip().lower() in ("y", "yes"):
            say("Type the router password when ssh asks for it (it is in the summary above).")
            ssh.run(host, "piso-setup telegram", "-t")
        return 0
    if result["state"].startswith("DONE"):
        raise SetupError(f"the setup finished, but some checks failed ({result['state']}). Read the messages above; after fixing "
                         "the cause run this script again, or log in and run: piso-setup status")
    raise SetupError("the setup stopped: " + (result["state"].replace("FAILED ", "", 1) if result["state"] else "see the messages above")
                     + ". Fix that and run this script again: it continues where it stopped.")


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    p = argparse.ArgumentParser(description="Set up a PisoPhone router from this computer (no typed commands).")
    p.add_argument("--branch", default=DEFAULT_BRANCH, help=f"the repository branch whose setup files are used (default {DEFAULT_BRANCH})")
    p.add_argument("--update", action="store_true", help="install new software on a router that is set up already")
    p.add_argument("--router", help=f"the router's address (default: found by itself, {TARGET_IP} or {FACTORY_IP})")
    p.add_argument("--guest-ssid", help="the public Wi-Fi name (default PisoWiFi)")
    p.add_argument("--site-name", help="the shop / site name (default: the public Wi-Fi name)")
    p.add_argument("--yes", action="store_true", help="ask nothing: defaults, generated passwords, no review")
    p.add_argument("--out", default=".", help="the folder for the saved summary and setup sheet (default: the current folder)")
    p.add_argument("--no-browser", action="store_true", help="do not open the setup sheet when done")
    args = p.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.branch):
        p.error(f"not a branch name: {args.branch}")
    if args.router and not re.fullmatch(r"[A-Za-z0-9.:-]+", args.router):
        p.error(f"not an address: {args.router}")
    if not os.environ.get("PISO_SSH") and not shutil.which("ssh"):
        say("ERROR: the ssh command was not found. Windows: Settings > Apps > Optional features > add 'OpenSSH Client'. "
            "Linux: install openssh-client.")
        return 2
    say("PisoPhone router setup")
    say("Before you start: the router is factory reset and on, the modem is in its WAN port, this computer is in a LAN")
    say("port by cable (turn its Wi-Fi off), and the coin box is flashed and powered on near the router.")
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        return run_setup(args, Ssh(workdir))
    except SetupError as e:
        say()
        say(f"ERROR: {e}")
        return 1
    except KeyboardInterrupt:
        say()
        say("Stopped. Running this script again is safe: the router keeps what is done.")
        return 130
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
