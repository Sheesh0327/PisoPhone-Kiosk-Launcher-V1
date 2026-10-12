#!/usr/bin/env python3
"""PisoPhone Setup: the one program that sets up a whole PisoPhone system, from a bare coin box and a factory-reset router
to working rental phones. It needs no screen: it serves its setup as a web page, so a small headless computer (Armbian, Parrot,
a Raspberry Pi, a laptop) can run it and you open the page on your phone.

    python3 pisophone_setup.py                    the setup page: open the address it prints, type the PIN
    python3 pisophone_setup.py --branch beta      the same with the beta branch's files (testing)
    sudo python3 pisophone_setup.py --install-service   start the setup page by itself whenever the computer boots (Linux)
    python3 pisophone_setup.py --cli              the same in the terminal (no web page)
    python3 pisophone_setup.py --update           new software only, on a router that is set up already (terminal)
    python3 pisophone_setup.py --cli --no-flash   the terminal mode without writing the coin box (it already has the software)

On start it detects the system (Windows, macOS, Linux; Debian, Armbian, Parrot, Ubuntu, Raspberry Pi OS, Fedora, Arch...) and installs
what is missing: ssh from the system's packages (needs root or sudo) and esptool with pip into its own folder
(~/.pisophone, no root needed). --no-install turns that off.

The setup page's steps, in order:
  1. Start        checks this computer (ssh, the website, esptool) and installs what is missing
  2. Router       finds the factory-reset router by itself (192.168.1.1 or 10.0.0.1)
  3. Settings     the shop's name and public Wi-Fi name (asked every time), the country and the passwords (new ones every time)
  4. Install      writes the PisoPhone software to the coin box over USB (esptool, checked against the website's checksums), runs
                  the one-line installer on the router unattended (it checks the setup file's sha256), waits for the router to
                  move to 10.0.0.1, runs the setup, pairs the coin box, saves the summary and the setup sheet
  5. Phones       the link to the phone setup page (Set up a phone), already filled in: open it on a phone or laptop with
                  internet and scan its QR code with the rental phone
  6. Finish       tests a real coin, checks the customer Wi-Fi, offers Telegram alerts; "Set up the next unit" starts over
                  for the next router and coin box
The summary (every password) and the printable setup sheet are saved on this computer, readable only by you, and can be opened
from the page.

The page is served over plain HTTP to the local network and protected by a PIN (printed when it starts, kept in
~/.pisophone/pin; five wrong PINs lock it for a minute). Use --local-only to serve this computer only. It needs Python 3.8+
and the ssh command (built into Windows 10/11, macOS and most Linux systems). The passwords travel over the SSH connection's
input, never on a command line; the router's SSH key is pinned for the run (the same key must answer at 192.168.1.1 and at
10.0.0.1). Running it again is safe: a router that is set up already keeps its names and passwords (it is then only repaired
or finished).

Build a standalone program with Nuitka (no Python needed on the computer that runs it):
    python -m nuitka --onefile --include-package=esptool --include-package=serial setup/pisophone_setup.py"""
import argparse
import base64
import contextlib
import getpass
import glob
import hashlib
import http.server
import importlib
import io
import json
import os
import pathlib
import platform
import re
import secrets
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

APP_NAME = "PisoPhone Setup"
SITE_HOST = "pisophone.pages.dev"   # the website (Cloudflare Pages): the coin box software, the phone setup page, the installer and the setup file
DEFAULT_BRANCH = "main"
FACTORY_IP = "192.168.1.1"      # OpenWrt's address after a factory reset
TARGET_IP = "10.0.0.1"          # where the PisoPhone setup puts it
BOX_IP = "10.0.0.10"            # the coin box, always <LAN network>.10
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
ANSWER_ORDER = [env for env, *_ in PASSWORDS] + ["GUEST_SSID", "SITE_NAME", "COUNTRY"]
COUNTRIES = [  # the Wi-Fi regulatory country (which channels and power the router may use)
    ("PH", "Philippines"), ("ID", "Indonesia"), ("MY", "Malaysia"), ("SG", "Singapore"), ("TH", "Thailand"),
    ("VN", "Vietnam"), ("KH", "Cambodia"), ("MM", "Myanmar"), ("JP", "Japan"), ("KR", "South Korea"), ("TW", "Taiwan"),
    ("HK", "Hong Kong"), ("CN", "China"), ("IN", "India"), ("AE", "United Arab Emirates"), ("SA", "Saudi Arabia"),
    ("AU", "Australia"), ("NZ", "New Zealand"), ("US", "United States"), ("CA", "Canada"), ("GB", "United Kingdom"),
    ("DE", "Germany"),
]
TELEGRAM_TOKEN = re.compile(r"\d{5,12}:[A-Za-z0-9_-]{30,50}")


class SetupError(Exception):
    pass


def say(text=""):
    print(text, flush=True)


def site_url(branch):
    """The website of a branch: main's is the production site, any other branch has its preview site (Cloudflare's branch
    alias, as in setup/install.sh: lowercase, other characters become '-', 28 at most)."""
    if branch == "main":
        return f"https://{SITE_HOST}"
    alias = re.sub(r"[^a-z0-9]", "-", branch.lower())[:28].strip("-")
    return f"https://{alias}.{SITE_HOST}"


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


def country_problem(value):
    return None if re.fullmatch(r"[A-Z]{2}", value or "") else "a two-letter country code, like PH"


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


def check_answers(answers):
    """Raises SetupError when an answer would be refused by the router setup."""
    for env, label, lo, hi, _length in PASSWORDS:
        problem = password_problem(answers[env], lo, hi)
        if problem:
            raise SetupError(f"the {label}: {problem}")
    for key, what in (("GUEST_SSID", "wifi"), ("SITE_NAME", "site")):
        problem = name_problem(answers[key], what)
        if problem:
            raise SetupError(f"{key}: {problem}")
    problem = country_problem(answers.get("COUNTRY", "PH"))
    if problem:
        raise SetupError(f"country: {problem}")


def collect_answers(args, read=input, read_secret=getpass.getpass):
    """The answers the setup needs (environment name -> value), asked here or taken from the options."""
    answers = {"COUNTRY": (getattr(args, "country", None) or "PH").upper()}
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
    check_answers(answers)
    return answers


def kept_answers(country="PH"):
    """The answers sent to a router that was set up before: none (it keeps its own), except the Wi-Fi country."""
    answers = {key: "" for key in ANSWER_ORDER}
    answers["COUNTRY"] = country
    return answers


def review(answers):
    say()
    say("================ PLEASE REVIEW (write the passwords down now) ================")
    say(f"  Public Wi-Fi (customers):  {answers['GUEST_SSID']}  (open, behind the coin payment page)")
    say(f"  Rental-phone Wi-Fi:        {KIOSK_SSID}  (hidden)")
    say(f"  Site name:                 {answers['SITE_NAME']}")
    say(f"  Router address:            {TARGET_IP}   Country: {answers.get('COUNTRY', 'PH')}")
    say(f"  Router password:           {answers['ROOT_PASSWORD']}")
    say(f"  {KIOSK_SSID} Wi-Fi password:  {answers['KIOSK_PASSWORD']}")
    say(f"  Coin box admin password:   {answers['BOX_NEW_ADMIN_PASSWORD']}")
    say("  The router's Wi-Fi networks are replaced and the coin box is paired and set up.")
    say("  (A router that was set up before keeps the names and passwords it already has.)")
    say("==============================================================================")


def parse_summary(text):
    """The passwords and names in the router's summary file (piso-setup's write_summary), as far as they are there."""
    found = {}
    for key, pattern in (("ROOT_PASSWORD", r"^Router \(SSH / LuCI\):.*?password: (\S+)"),
                         ("KIOSK_PASSWORD", r"^Kiosk Wi-Fi:.*?password: (\S+)"),
                         ("GUEST_SSID", r"^PisoWiFi \(customers\):\s+(.+?)\s{2,}\(open"),
                         ("BOX_NEW_ADMIN_PASSWORD", r"^Coin box:.*?admin password: (\S+)")):
        m = re.search(pattern, text or "", re.M)
        if m and not m.group(1).startswith("NOT"):
            found[key] = m.group(1)
    return found


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


def self_command():
    """How to start this program again (the password helper of the window mode runs it)."""
    if "__compiled__" in globals():   # a Nuitka build
        return [os.path.abspath(sys.argv[0])]
    exe = sys.executable
    if os.name == "nt" and os.path.basename(exe).lower() == "pythonw.exe":
        console = os.path.join(os.path.dirname(exe), "python.exe")
        exe = console if os.path.exists(console) else exe
    return [exe, os.path.abspath(__file__)]


class Ssh:
    """The system's ssh client. The router's host key is trusted the first time and then pinned for this run (a file of our
    own, so a router that was reset since the last setup gives no "host key changed" error). PISO_SSH replaces the ssh
    command (tests). In the window there is no terminal for ssh to ask a password on: set_password() makes ssh take it from
    a small helper (SSH_ASKPASS) instead, and `hidden` keeps ssh from opening a console window on Windows."""

    def __init__(self, workdir, hidden=False):
        self.workdir = workdir
        self.hidden = hidden
        self.env = None
        self.command = shlex.split(os.environ.get("PISO_SSH", "")) or ["ssh"]
        known = os.path.join(workdir, "known_hosts").replace(os.sep, "/")
        self.options = [
            "-o", f'UserKnownHostsFile="{known}"', "-o", "StrictHostKeyChecking=accept-new",
            "-o", "HostKeyAlias=pisophone-router",      # the same key at 192.168.1.1 and at 10.0.0.1
            "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=12",
            "-o", "IdentitiesOnly=yes", "-o", "LogLevel=ERROR",
        ]

    def set_password(self, password):
        """From now on ssh answers its password question with this (an empty one fails at once instead of waiting)."""
        if os.name == "nt":
            helper = os.path.join(self.workdir, "askpass.cmd")
            with open(helper, "w") as f:
                f.write("@" + " ".join(f'"{p}"' for p in self_command()) + "\r\n")
        else:
            helper = os.path.join(self.workdir, "askpass.sh")
            with open(helper, "w") as f:
                f.write("#!/bin/sh\nprintf '%s\\n' \"$PISO_ASKPASS_PW\"\n")
            os.chmod(helper, 0o700)
        self.env = dict(os.environ, SSH_ASKPASS=helper, SSH_ASKPASS_REQUIRE="force", PISO_ASKPASS_PW=password,
                        PISO_ASKPASS_MODE="1", DISPLAY=os.environ.get("DISPLAY") or ":0")

    def argv(self, host, remote, *extra):
        if self.env is not None:
            extra = ("-o", "NumberOfPasswordPrompts=1", *extra)
        return [*self.command, *self.options, *extra, f"root@{host}", remote]

    def _kw(self, kw):
        if self.env is not None:
            kw.setdefault("env", self.env)
        if self.hidden and os.name == "nt":
            kw.setdefault("creationflags", getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return kw

    def run(self, host, remote, *extra, **kw):
        return subprocess.run(self.argv(host, remote, *extra), **self._kw(kw))

    def popen(self, host, remote, *extra, **kw):
        return subprocess.Popen(self.argv(host, remote, *extra), **self._kw(kw))


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


def check_login(ssh, host):
    """True when ssh logs in (with the password given to set_password, if any)."""
    try:
        r = ssh.run(host, "echo @@LOGIN-OK", "-T", capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return False
    return "@@LOGIN-OK" in r.stdout


# The router password is saved on this computer: in the summary of a finished setup, and (written before the setup changes it,
# so an interrupted run can be repeated) in a small file of its own. A re-run logs in with it by itself.
SAVED_PASSWORD_FILES = ("pisophone-summary-*.txt", "pisophone-router-password.txt")


def saved_router_password(*folders):
    """(password, path) from the newest file with a saved router password in these folders, or (None, None)."""
    best = None
    for folder in dict.fromkeys(os.path.abspath(f) for f in folders if f):
        for pattern in SAVED_PASSWORD_FILES:
            for path in glob.glob(os.path.join(glob.escape(folder), pattern)):
                try:
                    with open(path, encoding="utf-8", errors="replace") as f:
                        password = parse_summary(f.read()).get("ROOT_PASSWORD")
                    stamp = os.path.getmtime(path)
                except OSError:
                    continue
                if password and (best is None or stamp > best[0]):
                    best = (stamp, password, path)
    return (best[1], best[2]) if best else (None, None)


def save_router_password(out_dir, password, host=None):
    """Keeps the router password the setup is about to set (in the summary's format), before the router changes."""
    if not password:
        return None
    path = os.path.join(out_dir, "pisophone-router-password.txt")
    os.makedirs(out_dir, exist_ok=True)
    save_private(path, f"Router (SSH / LuCI):   root@{host or TARGET_IP}        password: {password}\n")
    return path


def use_saved_password(ssh, host, folders, notify=say):
    """Logs in to a router that has a password with the one saved on this computer. Returns the password when it worked (ssh
    keeps using it); otherwise None, and ssh is as it was (a terminal ssh asks the person)."""
    password, path = saved_router_password(*folders)
    if not password:
        return None
    before = ssh.env
    ssh.set_password(password)
    if check_login(ssh, host):
        notify(f"Logged in to the router with the password saved in {path}.")
        return password
    ssh.env = before
    notify(f"The router password saved in {path} was not accepted (was it changed since?).")
    return None


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
    reads = "; ".join(f"IFS= read -r {n}" for n in ANSWER_ORDER)
    return (f"{reads}; export {' '.join(ANSWER_ORDER)}; {installer_command(branch)}; rc=$?; echo; echo \"@@PISO-RC $rc\"; "
            f"echo \"@@PISO-STATE $(cat /tmp/piso-setup.state 2>/dev/null)\"; "
            f"if [ -r /root/piso-setup-summary.txt ]; then echo @@PISO-SUMMARY; cat /root/piso-setup-summary.txt; fi; "
            f"if [ -r /root/piso-handout.html ]; then echo @@PISO-HANDOUT; cat /root/piso-handout.html; fi; echo @@PISO-END")


def tip_text(line):
    """The advice in a "TIP:" line of the router's output, or None."""
    m = re.match(r"\s*TIP: (.+)$", line or "")
    return m.group(1).strip() if m else None


def stream(proc, emit):
    """Passes the router's output on line by line as it comes (emit) and returns the sections after the markers (rc, state,
    summary, handout)."""
    result = {"rc": None, "state": "", "summary": [], "handout": []}
    section = None
    for raw in iter(proc.stdout.readline, b""):
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        if line.startswith("@@PISO-RC "):
            word = line.split()[1] if len(line.split()) > 1 else ""
            result["rc"] = int(word) if word.isdigit() else None
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
            emit(line)
    proc.wait()
    result["exit"] = proc.returncode
    return result


def run_remote(ssh, host, remote, emit, data=None):
    """Runs a remote command, its output (ssh's own messages included) passed to emit line by line."""
    proc = ssh.popen(host, remote, "-T", stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if data is not None:
        try:
            proc.stdin.write(data.encode())
            proc.stdin.close()
        except OSError:
            pass   # ssh failed before reading: its message and exit status say why
    return stream(proc, emit)


def save_private(path, text):
    """Writes a file only this user can read (it holds passwords)."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def wait_for_move(probe=port_open, wait=MOVE_WAIT, sleep=time.sleep, now=time.monotonic, renew=None, notify=say):
    """After the move: waits until the router answers at 10.0.0.1 (this computer needs a new address in 10.0.0.x)."""
    notify(f"The router is moving to {TARGET_IP}. Waiting for it (this computer needs a new address on the router's network)...")
    start, hinted, renewed = now(), False, False
    sleep(5)
    while now() - start < wait:
        if probe(TARGET_IP):
            notify(f"The router answers at {TARGET_IP}.")
            return True
        elapsed = now() - start
        if elapsed > 20 and renew and not renewed:
            renewed = True
            renew()
        if elapsed > 30 and not hinted:
            hinted = True
            notify("Still waiting. Unplug this computer's network cable from the router, wait 5 seconds and plug it back in.")
        sleep(3)
    return False


def renew_address():
    """Windows: ask for a new address at once (other systems do it when the cable is replugged)."""
    if os.name == "nt":
        try:
            subprocess.run(["ipconfig", "/renew"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90, check=False,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.SubprocessError):
            pass


def install(ssh, host, answers, branch, out_dir, emit, wait_move, stage=lambda n: None):
    """The installation: on a factory router the installer first moves it to 10.0.0.1 (wait_move() waits for that), then
    the setup runs with the answers. Returns the result (ok, state, saved files, summary); raises SetupError when it could
    not run to its end."""
    if host == FACTORY_IP:
        stage(0)
        emit(f"Downloading the setup to the router and moving it to {TARGET_IP}...")
        r = run_remote(ssh, host, installer_command(branch), emit)
        if r["exit"] != 0:
            raise SetupError("the router could not be prepared (see the messages above). Nothing else was changed; try again.")
        stage(1)
        if not wait_move():
            raise SetupError(
                f"the router does not answer at {TARGET_IP}. Unplug and replug this computer's network cable (or restart the "
                "computer's network), then try again: it continues where it stopped.")
        host = TARGET_IP
    stage(2)
    values = "".join(answers.get(key, "") + "\n" for key in ANSWER_ORDER)
    save_router_password(out_dir, answers.get("ROOT_PASSWORD"), host)   # before the router changes it: a repeat can log in
    result = run_remote(ssh, host, setup_command(branch), emit, data=values)

    saved = []
    stamp = time.strftime("%Y%m%d-%H%M")
    os.makedirs(out_dir, exist_ok=True)
    if result["summary"]:
        path = os.path.join(out_dir, f"pisophone-summary-{stamp}.txt")
        save_private(path, "\n".join(result["summary"]) + "\n")
        saved.append(("Summary (every password)", os.path.abspath(path)))
    if result["handout"]:
        path = os.path.join(out_dir, f"pisophone-setup-sheet-{stamp}.html")
        save_private(path, "\n".join(result["handout"]) + "\n")
        saved.append(("Printable setup sheet", os.path.abspath(path)))
    if result["rc"] is None:
        raise SetupError("the connection to the router was lost before the setup finished. Try again: it continues where it "
                         "stopped (the router keeps what is done).")
    stage(3)
    result.update(host=host, saved=saved, ok=result["rc"] == 0 and result["state"].startswith("DONE all checks passed"))
    result["summary_text"] = "\n".join(result["summary"])
    return result


def failure_message(result):
    """What went wrong, for a result that is not ok."""
    state = result["state"]
    if state.startswith("DONE"):
        return (f"the setup finished, but some checks failed ({state}). Read the messages above; after fixing the cause run "
                "it again (it continues where it stopped).")
    reason = state.replace("FAILED ", "", 1) if state else "see the messages above"
    more = ""
    if "box" in reason.lower():
        more = (" The report above (ending in a line that starts with \"verdict:\") says where the link to the coin box breaks; "
                f"you can get it again any time with: ssh root@{TARGET_IP} piso-setup box-diag.")
    return f"the setup stopped: {reason}.{more} Fix that and run it again: it continues where it stopped."


def update_router(ssh, host, branch, emit):
    r = run_remote(ssh, host, installer_command(branch, "update"), emit)
    if r["exit"] != 0:
        raise SetupError("the update did not finish (see the messages above). It is safe to run it again.")


def connect_telegram(ssh, host, token, site, emit, confirm):
    """Connects the Telegram bot (piso-setup telegram): the router waits for the first message sent to the bot, then
    confirm(<that line>) decides whether that chat is the owner's. True when connected."""
    proc = ssh.popen(host, "piso-setup telegram", "-T", stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        proc.stdin.write(f"{token}\n{site}\n".encode())
        proc.stdin.flush()
    except OSError:
        pass
    buf, chat, answered, done = "", "", False, False
    fd = proc.stdout.fileno()
    while True:
        chunk = os.read(fd, 4096)
        if not chunk:
            break
        buf += chunk.decode("utf-8", "replace")
        while "\n" in buf:
            line, buf = buf.split("\n", 1)
            line = line.rstrip("\r")
            if line.startswith("Got a message from chat"):
                chat = line
            if line.startswith("Done."):
                done = True
            emit(line)
        if not answered and "Is that you" in buf:
            answered = True
            ok = confirm(chat or "a chat")
            emit(buf.strip() + (" yes" if ok else " no"))
            buf = ""
            try:
                proc.stdin.write(b"y\n" if ok else b"n\n")
                proc.stdin.close()
            except OSError:
                pass
    if buf.strip():
        emit(buf.strip())
    proc.wait()
    return proc.returncode == 0 and done


# ---- the browser, the coin box's page, the checks ------------------------------------------------------------------------

def chromium_path():
    """Chrome, Edge or Chromium on this computer (the phone setup page needs WebUSB to set up a phone over a cable: Chromium only)."""
    candidates = []
    if os.name == "nt":
        for base in (os.environ.get("PROGRAMFILES"), os.environ.get("PROGRAMFILES(X86)"), os.environ.get("LOCALAPPDATA")):
            if base:
                candidates += [os.path.join(base, "Google", "Chrome", "Application", "chrome.exe"),
                               os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe")]
    elif sys.platform == "darwin":
        candidates = ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                      "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                      "/Applications/Chromium.app/Contents/MacOS/Chromium"]
    for path in candidates:
        if os.path.isfile(path):
            return path
    for name in ("google-chrome", "google-chrome-stable", "chrome", "microsoft-edge", "microsoft-edge-stable", "msedge",
                 "chromium", "chromium-browser"):
        found = shutil.which(name)
        if found:
            return found
    return None


def open_url(url, chromium=True, find=chromium_path, launch=subprocess.Popen, fallback=webbrowser.open):
    """Opens a page in Chrome or Edge when this computer has one (a new tab of the running browser), else in the default
    browser. Returns "chromium" or "default"."""
    exe = find() if chromium else None
    if exe:
        try:
            launch([exe, url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return "chromium"
        except OSError:
            pass
    fallback(url)
    return "default"


def fetch_box_link(host, password, opener=urllib.request.urlopen, timeout=8):
    """The coin box's MAC and secret, read from its admin page the way its Set up a phone button does (the page holds
    them as window.PISO_CFG). Logs in as admin with the coin box admin password."""
    req = urllib.request.Request(f"http://{host}/")
    req.add_header("Authorization", "Basic " + base64.b64encode(f"admin:{password}".encode()).decode())
    try:
        with opener(req, timeout=timeout) as r:
            page = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise SetupError("the coin box did not accept the admin password")
        raise SetupError(f"the coin box answered with an error (HTTP {e.code})")
    except (urllib.error.URLError, OSError) as e:
        raise SetupError(f"the coin box at {host} does not answer ({getattr(e, 'reason', e)})")
    m = re.search(r'PISO_CFG\s*=\s*\{\s*mac:\s*"([^"]*)"\s*,\s*secret:\s*"([^"]*)"', page)
    if not m:
        raise SetupError(f"{host} did not show the coin box's admin page")
    return {"mac": m.group(1), "secret": m.group(2)}


def provisioning_url(branch, link, slot, wifi_pass, box_ip=BOX_IP):
    """The website's phone setup page for this box, filled in. Everything goes after the # (a fragment is never sent to a web
    server, its logs or a Referer); the page reads it, shows the QR code and removes it from the address bar."""
    values = {"mac": link["mac"], "secret": link["secret"], "slot": slot, "name": f"PisoPhone {slot}", "ip": box_ip,
              "wifi_pass": wifi_pass}
    return f"{site_url(branch)}/#" + urllib.parse.urlencode({k: v for k, v in values.items() if v not in ("", None)})


def provisioning_target(branch, passwords, slot=1, fetch=fetch_box_link):
    """Where the phone setup page is: ("setup", the website's page, filled in, "") when the coin box's admin page can be read,
    else ("box", the coin box's own page, why not)."""
    box_password = passwords.get("BOX_NEW_ADMIN_PASSWORD") or ""
    note = "the coin box's admin password is not known here"
    if box_password:
        try:
            link = fetch(BOX_IP, box_password)
            if link["mac"] and link["mac"] != "00:00:00:00:00:00":
                return "setup", provisioning_url(branch, link, slot, passwords.get("KIOSK_PASSWORD", "")), ""
            note = "the coin box has no address yet"
        except SetupError as e:
            note = str(e)
    return "box", f"http://{BOX_IP}/", note


def open_provisioning(branch, passwords, slot=1, fetch=fetch_box_link, opener=open_url):
    """Opens the coin box's phone setup page (Set up a phone) in the browser: filled in when the box's admin page can be
    read, else the box's own page (log in, click Set up a phone). Returns ("setup" or "box", a note for the user)."""
    kind, url, note = provisioning_target(branch, passwords, slot, fetch)
    if kind == "setup":
        opener(url)
    else:
        opener(url, chromium=False)
    return kind, note


def box_online(host=BOX_IP, probe=port_open):
    return probe(host, 80)


# ---- the coin box: its software goes in over USB ----------------------------------------------------------------------------

FLASH_BAUD = 460800   # fast, and still reliable on the cheap USB-serial chips of most boards
# the USB-serial chips on ESP32 boards: Espressif's own (C3 and S3 boards), CH340, CP210x, FTDI, Prolific
USB_BOARD_IDS = {0x303A: "Espressif", 0x1A86: "CH340", 0x10C4: "CP210x", 0x0403: "FTDI", 0x067B: "Prolific"}
CHIP_RE = re.compile(r"(?:Chip is|Chip type:|Connected to)\s*(ESP32(?:-[CSHP]\d)?)", re.I)
PROGRESS_RE = re.compile(r"\((\d+) ?%\)")


def esptool_module():
    """Espressif's flashing tool (pip install esptool; the standalone program carries it), or None."""
    try:
        import esptool
        return esptool
    except ImportError:
        return None


def esptool_major(tool):
    try:
        return int(str(tool.__version__).split(".")[0])
    except (AttributeError, ValueError):
        return 4


def usb_boards(comports=None):
    """[(port, description)] of the USB serial adapters coin box boards use, sorted. Empty when none is plugged in (or
    pyserial, which esptool brings, is missing)."""
    if comports is None:
        try:
            from serial.tools.list_ports import comports
        except ImportError:
            return []
    found = []
    for port in comports():
        if getattr(port, "vid", None) in USB_BOARD_IDS:
            found.append((port.device, port.description or USB_BOARD_IDS[port.vid]))
    return sorted(found)


class _Lines(io.TextIOBase):
    """Collects what esptool prints and hands it on line by line (its progress lines end in a carriage return)."""

    def __init__(self, emit):
        self.emit, self.buf = emit, ""

    def writable(self):
        return True

    def write(self, text):
        self.buf += text
        parts = re.split(r"[\r\n]", self.buf)
        self.buf = parts.pop()
        for part in parts:
            if part.strip():
                self.emit(part.rstrip())
        return len(text)

    def flush(self):
        if self.buf.strip():
            self.emit(self.buf.rstrip())
        self.buf = ""


def run_esptool(tool, argv, emit):
    """Runs one esptool command in this process. Raises SetupError (with the tool's last words) when it fails."""
    seen = []

    def take(line):
        seen.append(line)
        emit(line)
    out = _Lines(take)
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            tool.main(argv)
    except SystemExit as e:
        if e.code not in (0, None):
            out.flush()
            raise SetupError("the flashing tool stopped: " + (seen[-1] if seen else str(e.code)))
    except Exception as e:   # esptool's own errors (FatalError, serial problems)
        out.flush()
        raise SetupError("the flashing tool failed: " + re.sub(r"\s+", " ", str(e)).strip())
    out.flush()
    return seen


def board_advice(message):
    """What to try when the board did not answer (esptool says 'Failed to connect', 'Permission denied' ...)."""
    low = message.lower()
    if "permission" in low or "access is denied" in low:
        return ("This computer may not use the USB port. Linux: add your user to the 'dialout' group and log in again. "
                "Another program (a serial monitor) may also be using the port.")
    if "failed to connect" in low or "timed out" in low or "no serial data" in low:
        return ("The board did not answer. Use a USB DATA cable, hold the board's BOOT button, tap RESET, release BOOT, and try "
                "again. Some boards need their USB driver (CH340 or CP210x) installed once.")
    return ""


def with_advice(error, evidence):
    """An error's text, one sentence, with what to try when the board's trouble is a known one."""
    text = str(error).rstrip(". ")
    advice = board_advice(evidence)
    return f"{text}. {advice}" if advice else text + "."


def flash_manifest(branch, cache_dir, opener=urllib.request.urlopen, emit=say):
    """The list of firmware images of this branch (flash/manifest.json on the website). Offline, the copy kept on this
    computer from the last time is used."""
    path = os.path.join(cache_dir, "manifest.json")
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/manifest.json"), timeout=15) as r:
            text = r.read().decode("utf-8")
        manifest = json.loads(text)
        os.makedirs(cache_dir, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
    except (urllib.error.URLError, OSError, ValueError):
        try:
            with open(path, encoding="utf-8") as f:
                manifest = json.load(f)
            emit("The website could not be reached: using the coin box software kept from the last time.")
        except (OSError, ValueError):
            raise SetupError("the coin box software list could not be downloaded. This computer needs internet for this step.")
    if not isinstance(manifest.get("builds"), list) or not manifest["builds"]:
        raise SetupError("the website has no coin box software for this branch.")
    return manifest


def choose_build(manifest, chip):
    """The image for a chip ("ESP32-C3", "ESP32"), or None."""
    name = (chip or "").upper()
    return next((b for b in manifest["builds"] if str(b.get("chip", "")).upper() == name), None)


def fetch_image(branch, build, cache_dir, opener=urllib.request.urlopen):
    """The path of the image, downloaded and checked against the manifest's size and sha256 (kept for next time)."""
    path = os.path.join(cache_dir, f"{build['sha256'][:16]}-{build['file']}")
    if re.search(r"[/\\]", build["file"]):
        raise SetupError("the website's software list is not valid.")

    def good(data):
        return len(data) == build["size"] and hashlib.sha256(data).hexdigest() == build["sha256"]
    try:
        with open(path, "rb") as f:
            if good(f.read()):
                return path
    except OSError:
        pass
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/flash/{build['file']}"), timeout=60) as r:
            data = r.read()
    except (urllib.error.URLError, OSError) as e:
        raise SetupError(f"the coin box software could not be downloaded ({e}).")
    if not good(data):
        raise SetupError("the downloaded coin box software does not match its checksum. Try again in a minute.")
    os.makedirs(cache_dir, exist_ok=True)
    with open(path + ".part", "wb") as f:
        f.write(data)
    os.replace(path + ".part", path)
    return path


def flash_box(branch, port, emit, cache_dir, opener=urllib.request.urlopen, tool=None):
    """Writes the PisoPhone software to the board on `port`: detects the chip, takes the image for it (checked), erases the
    whole flash (a used box then forgets its old Wi-Fi), writes and verifies it, restarts the board. Returns (chip, version)."""
    tool = tool or esptool_module()
    if tool is None:
        raise SetupError("the flashing tool is missing: run  pip install esptool  and start this again.")
    major = esptool_major(tool)

    def name(text):   # esptool 5 spells its commands and options with hyphens (4 with underscores)
        return text.replace("_", "-") if major >= 5 else text
    base = ["--port", port, "--baud", str(FLASH_BAUD)]
    emit(f"== Looking at the board on {port}")
    seen = []
    try:
        run_esptool(tool, base + [name("chip_id")], seen.append)
    except SetupError as e:
        raise SetupError(with_advice(e, ' '.join(seen) + ' ' + str(e)))
    match = CHIP_RE.search("\n".join(seen))
    if not match:
        raise SetupError("the board did not say which chip it has. " + board_advice("failed to connect"))
    chip = match.group(1).upper()
    manifest = flash_manifest(branch, cache_dir, opener, emit)
    build = choose_build(manifest, chip)
    if not build:
        raise SetupError(f"this board is an {chip}; PisoPhone software exists for "
                         f"{' and '.join(b['chip'] for b in manifest['builds'])}.")
    emit(f"{chip} found. Software version {manifest.get('version', '?')} ({build['size'] / 1048576:.2f} MB).")
    image = fetch_image(branch, build, cache_dir, opener)
    emit("== Writing the coin box software (erasing the board first; about a minute)")
    last = [-10]

    def progress(line):
        found = PROGRESS_RE.search(line)
        if found:
            pct = int(found.group(1))
            if pct // 10 == last[0] // 10 and pct < 100:
                return
            last[0] = pct
        emit(line)
    args = base + [name("write_flash"), "-e", "-z", name("--flash_mode"), "keep", name("--flash_freq"), "keep",
                   name("--flash_size"), "keep", hex(build["offset"]), image]
    try:
        run_esptool(tool, args, progress)
    except SetupError as e:
        raise SetupError(with_advice(e, str(e)))
    emit("Written and verified. The board restarts by itself; keep it powered (the USB cable does that) while the router is set up.")
    return chip, manifest.get("version", "?")


def preflight(branch, opener=urllib.request.urlopen, tool=esptool_module, which=shutil.which):
    """The checks of the first page: [(what, ok, what to do when not)]. Nothing here changes anything."""
    checks = []
    have_ssh = bool(os.environ.get("PISO_SSH") or which("ssh"))
    checks.append(("ssh program", have_ssh, "Install it with the button below (Linux), or: Windows: Settings > Apps > Optional "
                                            "features > add \"OpenSSH Client\"."))
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/update/app.json", method="GET"), timeout=8) as r:
            online = r.status == 200
    except (urllib.error.URLError, OSError, ValueError):
        online = False
    checks.append((f"internet and the {urllib.parse.urlsplit(site_url(branch)).netloc} website", online,
                   "This computer needs internet now (the router gets its own from the modem later)."))
    checks.append(("USB flashing tool (esptool)", tool() is not None,
                   "Install it with the button below, or run  python3 -m pip install esptool."))
    return checks


# ---- the terminal mode ---------------------------------------------------------------------------------------------------

def flash_in_terminal(args, read, board_finder, flasher):
    """Writes the coin box's software when a board is on USB. Without one, goes on (the box may have it already)."""
    boards = board_finder()
    if not boards:
        say("No coin box board found on USB: going on without writing it (it must run PisoPhone software already; plug it in and")
        say("run this again to write it, or add --no-flash to skip this check).")
        return
    if len(boards) > 1:
        raise SetupError("several USB boards are connected (" + ", ".join(p for p, _d in boards) + "): unplug all but the coin box.")
    port, description = boards[0]
    say(f"Coin box board found: {description} on {port}.")
    if not args.yes and read("Write the PisoPhone software to it? This erases the board. [Y/n] ").strip().lower() in ("n", "no"):
        say("Skipped writing the coin box.")
        return
    flasher(port, say)


def run_setup(args, ssh, read=input, read_secret=getpass.getpass, probe=port_open, wait=wait_for_move, open_page=open_url,
              board_finder=usb_boards, flasher=None):
    if flasher is None:
        def flasher(port, emit):
            return flash_box(args.branch, port, emit, flash_cache_dir())
    host = args.router or find_router(probe)
    if not host:
        raise SetupError(
            f"no router found at {TARGET_IP} or {FACTORY_IP}. Check that this computer is plugged into one of the router's LAN "
            "ports (not WAN), that the router has finished starting (about 2 minutes after power-on) and was factory reset, and "
            "turn this computer's Wi-Fi off.")
    say(f"Router found at {host}.")
    state = probe_router(ssh, host)
    auto_login = use_saved_password(ssh, host, [args.out, default_out_dir()]) if state == "password" else None
    if args.update:
        if state == "factory":
            raise SetupError("this router has not been set up yet: run this script without --update")
        say("Installing the current PisoPhone software (you are asked for the router password)...")
        update_router(ssh, host, args.branch, say)
        say()
        say("UPDATE DONE.")
        return 0

    if state in ("configured", "password"):
        # (the router's own settings win over new answers, so none are asked: they would not be used)
        say("This router was set up before (or has a password): it keeps the names and passwords it has, and the setup")
        say("finishes or repairs it. Anything it does not have yet is generated and shown in the summary.")
        if state == "password" and not auto_login:
            say("Type the router password when ssh asks for it (it is in your saved summary; a router that was just")
            say("factory reset has none: press Enter).")
        if not args.yes and read("Continue? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1
        answers = kept_answers((getattr(args, "country", None) or "PH").upper())
    else:
        answers = collect_answers(args, read, read_secret)
        review(answers)
        if not args.yes and read("Apply these settings to the router? [y/N] ").strip().lower() not in ("y", "yes"):
            say("Cancelled. Nothing was changed.")
            return 1

    if not getattr(args, "no_flash", False):
        flash_in_terminal(args, read, board_finder, flasher)
    say()
    if host == FACTORY_IP:
        say(f"Step 1 of 2: downloading the setup to the router and moving it to {TARGET_IP}...")
    else:
        say("Step 1 of 2: the router is at its address already.")

    def stage(n):
        if n == 2:
            say()
            say("Step 2 of 2: the setup runs on the router (about 3 to 8 minutes; it waits for the coin box to join, so keep the")
            say("box powered on). Do not close this window.")
            say()

    result = install(ssh, host, answers, args.branch, args.out, say, lambda: wait(probe=probe, renew=renew_address), stage)
    host = result["host"]
    say()
    for label, path in result["saved"]:
        say(f"{label} saved to: {path}  (keep it private)")
    if not result["ok"]:
        raise SetupError(failure_message(result))
    say()
    say("SETUP COMPLETE: all checks passed.")
    found = dict(answers, **parse_summary(result.get("summary_text", "")))
    if not args.no_browser:
        try:
            kind, note = open_provisioning(args.branch, found, opener=open_page)
            say("Opened the phone setup page in your browser: scan its QR code with a factory-reset phone (6 taps on the welcome"
                " screen)." if kind == "setup" else
                f"Opened the coin box's page ({note}): log in as admin and click Set up a phone.")
        except Exception:
            say(f"Next: set up the rental phones from the coin box's page (http://{BOX_IP}/), Set up a phone.")
    else:
        say(f"Next: set up the rental phones from the coin box's page (http://{BOX_IP}/), Set up a phone.")
    if not args.yes and read("Connect Telegram alerts now? You need a bot token from @BotFather. [y/N] ").strip().lower() in ("y", "yes"):
        if found.get("ROOT_PASSWORD"):
            ssh.set_password(found["ROOT_PASSWORD"])   # the setup has set it: ssh needs no typing
        else:
            say("Type the router password when ssh asks for it (it is in the summary above).")
        ssh.run(host, "piso-setup telegram", "-t")
    return 0


# ---- this computer: what it is, what it needs --------------------------------------------------------------------------------

def pisophone_home():
    """The folder for what this program keeps (the PIN, downloaded libraries): ~/.pisophone, or $PISOPHONE_HOME (the service)."""
    return os.environ.get("PISOPHONE_HOME") or os.path.join(os.path.expanduser("~"), ".pisophone")


def private_lib_dir():
    """Where esptool is installed when the system's Python does not have it (pip --target: no root, no venv, works on
    Debian-family systems whose Python is 'externally managed')."""
    return os.path.join(pisophone_home(), f"pylib-{sys.version_info[0]}.{sys.version_info[1]}")


def use_private_libs():
    lib = private_lib_dir()
    if os.path.isdir(lib) and lib not in sys.path:
        sys.path.insert(0, lib)


def read_os_release(path="/etc/os-release"):
    values = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                key, sep, value = line.strip().partition("=")
                if sep:
                    values[key] = value.strip().strip('"')
    except OSError:
        pass
    return values


def package_family(os_release):
    """The package manager family of a Linux system, from /etc/os-release (ID and ID_LIKE): Armbian, Parrot, Kali, Ubuntu,
    Raspberry Pi OS and Debian are all 'debian'."""
    ids = set((os_release.get("ID", "") + " " + os_release.get("ID_LIKE", "")).lower().split())
    for family, names in (("debian", {"debian", "ubuntu", "armbian", "parrot", "kali", "raspbian", "linuxmint", "pop"}),
                          ("redhat", {"fedora", "rhel", "centos", "rocky", "almalinux"}), ("arch", {"arch", "manjaro"}),
                          ("alpine", {"alpine"}), ("suse", {"suse", "opensuse", "opensuse-leap"})):
        if ids & names:
            return family
    return ""


def detect_system(os_release=None, system=None, machine=None, is_root=None, which=shutil.which):
    """What this computer is: {kind: linux|windows|macos, name, family, arch, root, sudo}. 'sudo' means sudo works without a
    password (so packages can be installed unattended)."""
    system = system or platform.system()
    kind = {"Windows": "windows", "Darwin": "macos"}.get(system, "linux")
    info = {"kind": kind, "family": "", "arch": machine or platform.machine(), "root": False, "sudo": False}
    if kind == "linux":
        release = os_release if os_release is not None else read_os_release()
        info["family"] = package_family(release)
        info["name"] = release.get("PRETTY_NAME") or release.get("NAME") or "Linux"
        info["root"] = (os.geteuid() == 0) if is_root is None and hasattr(os, "geteuid") else bool(is_root)
        if not info["root"] and which("sudo"):
            try:
                info["sudo"] = subprocess.run(["sudo", "-n", "true"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                              timeout=10).returncode == 0
            except (OSError, subprocess.SubprocessError):
                pass
    else:
        info["name"] = "Windows" if kind == "windows" else "macOS " + platform.mac_ver()[0]
    return info


SYSTEM_PACKAGES = {   # what to install per package family
    "ssh": {"debian": "openssh-client", "redhat": "openssh-clients", "arch": "openssh", "alpine": "openssh-client", "suse": "openssh"},
    "pip": {"debian": "python3-pip", "redhat": "python3-pip", "arch": "python-pip", "alpine": "py3-pip", "suse": "python3-pip"},
}
INSTALLERS = {"debian": ["apt-get", "install", "-y", "-q"], "redhat": ["dnf", "install", "-y"], "arch": ["pacman", "-S", "--noconfirm"],
              "alpine": ["apk", "add"], "suse": ["zypper", "--non-interactive", "install"]}


def run_command(argv, emit, timeout=900, env=None):
    """Runs a command, hands its output on line by line. Returns its exit status (127 when it cannot be started)."""
    try:
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, universal_newlines=True,
                                errors="replace", creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except OSError as e:
        emit(f"{argv[0]}: {e}")
        return 127
    timer = threading.Timer(timeout, proc.kill)
    timer.start()
    try:
        for line in proc.stdout:
            if line.strip():
                emit(line.rstrip())
        return proc.wait()
    finally:
        timer.cancel()


def install_system_package(what, system, emit, run=run_command):
    """Installs a system package ('ssh', 'pip') with the system's package manager, when this program may (root, or sudo
    without a password). Returns True when the command succeeded."""
    family = system.get("family", "")
    package = SYSTEM_PACKAGES.get(what, {}).get(family)
    if not package or family not in INSTALLERS:
        emit(f"This system's packages are not known here: install {what} yourself.")
        return False
    if not (system.get("root") or system.get("sudo")):
        emit(f"Installing {package} needs administrator rights: run  sudo {' '.join(INSTALLERS[family])} {package}  and start this again.")
        return False
    argv = ([] if system.get("root") else ["sudo", "-n"]) + INSTALLERS[family] + [package]
    env = dict(os.environ, DEBIAN_FRONTEND="noninteractive")
    if family == "debian":
        run((["sudo", "-n"] if not system.get("root") else []) + ["apt-get", "update", "-q"], emit, 600, env)
    emit(f"Installing {package}...")
    return run(argv, emit, 900, env) == 0


def pip_available(run=run_command):
    return run([sys.executable, "-m", "pip", "--version"], lambda line: None, 60) == 0


def ensure_esptool(emit, system, run=run_command):
    """Makes `import esptool` work: already there, or installed into this program's own folder with pip (and pip itself from
    the system's packages when it is missing). Returns True when esptool can be used."""
    use_private_libs()
    if esptool_module():
        return True
    if not pip_available(run):
        emit("pip is missing.")
        if system["kind"] == "linux":
            install_system_package("pip", system, emit, run)
        if not pip_available(run):
            emit("Without pip esptool cannot be installed here. Debian family: sudo apt install python3-pip (or: sudo apt install esptool).")
            return False
    lib = private_lib_dir()
    os.makedirs(lib, exist_ok=True)
    emit("Installing esptool (the tool that writes the coin box)...")
    lines = []

    def keep(line):
        lines.append(line)
        emit(line)
    argv = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--quiet", "--upgrade", "--target", lib, "esptool"]
    code = run(argv, keep, 900)
    if code != 0 and any("externally-managed" in line for line in lines):
        code = run(argv + ["--break-system-packages"], keep, 900)   # (only into the folder above)
    if code != 0:
        emit("esptool could not be installed (is the internet on? Armbian / Debian: sudo apt install esptool is the alternative).")
        return False
    importlib.invalidate_caches()
    use_private_libs()
    return esptool_module() is not None


def ensure_ssh(emit, system, run=run_command, which=shutil.which):
    """The ssh command: present, or installed from the system's packages on Linux. Returns True when it can be used."""
    if os.environ.get("PISO_SSH") or which("ssh"):
        return True
    if system["kind"] == "linux":
        install_system_package("ssh", system, emit, run)
        return bool(which("ssh"))
    if system["kind"] == "windows":
        emit("Windows: Settings > Apps > Optional features > add 'OpenSSH Client'.")
    else:
        emit("ssh is missing: it is part of macOS; check the Command Line Tools.")
    return False


def ensure_dependencies(emit, system=None, run=run_command, which=shutil.which):
    """Installs what this program needs and the system lacks (ssh, esptool). Returns {name: ok}."""
    system = system or detect_system()
    emit(f"This computer: {system['name']} ({system['arch']}){', running as administrator' if system['root'] else ''}.")
    result = {"ssh": ensure_ssh(emit, system, run, which), "esptool": ensure_esptool(emit, system, run)}
    for name, ok in result.items():
        emit(f"{name}: {'ready' if ok else 'MISSING'}")
    return result


def serial_access_fix(port, system, emit, run=run_command):
    """Linux: a USB serial port is usually owned by root and group 'dialout'. Makes the port usable for this run when the
    user may (sudo without a password); otherwise says what to do. Returns True when the port can be opened."""
    if system["kind"] != "linux" or os.access(port, os.R_OK | os.W_OK):
        return True
    if system.get("sudo") and run(["sudo", "-n", "chmod", "a+rw", port], emit, 30) == 0:
        emit(f"Allowed this program to use {port}.")
        return True
    user = os.environ.get("USER") or "your-user"
    emit(f"This account may not use {port}: run  sudo usermod -aG dialout {user}  and log in again (or start this as root).")
    return False


def flash_cache_dir():
    """Where the downloaded coin box software is kept, so the next unit does not download it again."""
    return os.path.join(default_out_dir(), "firmware-cache")


def default_out_dir():
    """Where the summary and the setup sheet are saved: Documents/PisoPhone (or PisoPhone in the home folder), or the
    service's own folder."""
    if os.environ.get("PISOPHONE_HOME"):
        return os.path.join(os.environ["PISOPHONE_HOME"], "saved")
    home = pathlib.Path.home()
    docs = home / "Documents"
    return str((docs if docs.is_dir() else home) / "PisoPhone")


def open_path(path):
    """Opens a file or folder with the system's program for it."""
    try:
        if os.name == "nt":
            os.startfile(path)   # noqa: S606 (a file this program saved)
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        webbrowser.open(pathlib.Path(path).as_uri())


# ---- the setup page: a small web server on this computer ----------------------------------------------------------------------
# The setup is a web page served by this program (standard library only), so it needs no screen on the computer that runs it:
# open the address on any phone or laptop in the same network. A PIN keeps everyone else out.

STEPS = [("welcome", "Start"), ("router", "Router"), ("settings", "Settings"), ("install", "Install"), ("phones", "Phones"),
         ("finish", "Finish")]
INSTALL_STAGES = ["Write the coin box software (USB)", "Prepare the router", f"Move it to {TARGET_IP}", "Install and set up",
                  "Check everything"]
STAGE_ACTIVITY = ["Writing the coin box software", "Downloading the setup to the router", f"Waiting for the router at {TARGET_IP}",
                  "Installing on the router", "Checking"]
PIN_RE = re.compile(r"[A-Za-z0-9]{6,32}")
DEFAULT_PORT = 8765
LOG_KEEP = 3000


class Core:
    """What the setup does with the network, the router and the coin box; tests replace it with a fake."""

    def __init__(self, branch, workdir):
        self.branch = branch
        self.ssh = Ssh(workdir, hidden=True)
        self.ssh.set_password("")   # never wait for a password on a terminal there is not

    def preflight(self):
        return preflight(self.branch)

    def find_router(self):
        return find_router()

    def probe(self, host):
        return probe_router(self.ssh, host)

    def saved_login(self, host, out_dir):
        """The router password saved on this computer, when the router accepts it (else None)."""
        return use_saved_password(self.ssh, host, [out_dir, default_out_dir()], notify=lambda text: None)

    def login(self, host, password):
        self.ssh.set_password(password)
        return check_login(self.ssh, host)

    def usb_boards(self):
        return usb_boards()

    def flash_box(self, port, emit):
        system = detect_system()
        if not serial_access_fix(port, system, emit):
            raise SetupError("this account may not use the USB port (see above).")
        return flash_box(self.branch, port, emit, flash_cache_dir())

    def install(self, host, answers, out_dir, emit, stage, notify):
        return install(self.ssh, host, answers, self.branch, out_dir, emit,
                       lambda: wait_for_move(renew=renew_address, notify=notify), stage)

    def run(self, host, password, command, emit):
        """A command on the router (the root password is known by now): its output line by line; returns its exit status."""
        self.ssh.set_password(password)
        return run_remote(self.ssh, host, command, emit)["exit"]

    def telegram(self, host, password, token, site, emit, confirm):
        self.ssh.set_password(password)
        return connect_telegram(self.ssh, host, token, site, emit, confirm)

    def provisioning_target(self, passwords, slot):
        return provisioning_target(self.branch, passwords, slot)

    def box_online(self):
        return box_online()


class Session:
    """The state of the setup, for the web page: one unit after the other. Worker threads change it, the page reads it."""

    def __init__(self, core, branch=DEFAULT_BRANCH, out_dir=None, system=None, install_deps=None):
        self.core, self.branch = core, branch
        self.out_dir = out_dir or default_out_dir()
        self.system = system or detect_system()
        self.install_deps = install_deps or (lambda emit: ensure_dependencies(emit, self.system))
        self.lock = threading.RLock()
        self.unit = 0
        self.checks = None
        self.deps = {"running": False, "lines": []}
        self.country = "PH"
        self.reset_unit()
        self.run_checks()

    # -- state ---------------------------------------------------------------------------------------------------------------
    def reset_unit(self):
        with self.lock:
            self.unit += 1
            self.step = 0 if self.unit == 1 else 1
            self.values = {"GUEST_SSID": "", "SITE_NAME": ""}
            for env, _label, _lo, _hi, length in PASSWORDS:
                self.values[env] = generate(length)
            self.router = {"host": None, "state": None, "error": None, "login_ok": False, "login_failed": False, "searching": False}
            self.router_password = ""
            self.usb = None            # None: not looked yet, else [(port, description)]
            self.skip_flash = False
            self.flashed = False
            self.install = {"running": False, "stage": -1, "activity": "", "error": None, "banner": None, "ok": False, "lines": []}
            self.result = None
            self.final = {}
            self.saved = []
            self.prov = {"state": None, "note": "", "url": "", "slot": 1}
            self.prov_auto = False
            self.box_up = None
            self.coin = {"running": False, "ok": None, "lines": []}
            self.telegram = {"running": False, "ok": False, "error": None, "lines": [], "confirm": None}
            self._tg_answer = None
            self._entered = {self.step}
            if self.step == 1:
                self._enter(1)

    def spawn(self, fn):
        threading.Thread(target=fn, daemon=True).start()

    def log(self, line):
        with self.lock:
            lines = self.install["lines"]
            lines.append(line)
            if len(lines) > LOG_KEEP:
                del lines[:len(lines) - LOG_KEEP]
            if line.startswith("== "):
                self.install["activity"] = line[3:]
            advice = tip_text(line)
            if advice and (self.install["banner"] is None or self.install["banner"][0] == "info"):
                self.install["banner"] = ("info", "Tip: " + advice)

    # -- the checks of this computer ---------------------------------------------------------------------------------------------
    def run_checks(self):
        def work():
            try:
                checks = self.core.preflight()
            except Exception as e:
                checks = [("checks", False, str(e))]
            with self.lock:
                self.checks = checks
        self.spawn(work)

    def do_recheck(self, a):
        with self.lock:
            self.checks = None
        self.run_checks()

    def do_install_deps(self, a):
        with self.lock:
            if self.deps["running"]:
                return
            self.deps = {"running": True, "lines": []}

        def emit(line):
            with self.lock:
                self.deps["lines"].append(line)

        def work():
            try:
                self.install_deps(emit)
            except Exception as e:
                emit(f"failed: {e}")
            with self.lock:
                self.deps["running"] = False
                self.checks = None
            self.run_checks()
        self.spawn(work)

    # -- the settings --------------------------------------------------------------------------------------------------------
    def kept(self):
        return self.router["state"] in ("configured", "password")

    def settings_problems(self):
        v = self.values
        if self.kept():
            return {"COUNTRY": country_problem(self.country)}
        problems = {"GUEST_SSID": name_problem(v["GUEST_SSID"], "wifi"), "SITE_NAME": name_problem(v["SITE_NAME"], "site"),
                    "COUNTRY": country_problem(self.country)}
        for env, _label, lo, hi, _length in PASSWORDS:
            problems[env] = password_problem(v[env], lo, hi)
        return problems

    def answers(self):
        a = {k: v.strip() if k in ("GUEST_SSID", "SITE_NAME") else v for k, v in self.values.items()}
        a["COUNTRY"] = self.country
        return a

    def do_set(self, a):
        with self.lock:
            for key, value in (a.get("values") or {}).items():
                if key in self.values and isinstance(value, str) and len(value) < 200:
                    self.values[key] = value
            country = a.get("country")
            if isinstance(country, str) and re.fullmatch(r"[A-Za-z]{2}", country):
                self.country = country.upper()

    # -- moving between the steps -----------------------------------------------------------------------------------------------
    def can_next(self):
        key = STEPS[self.step][0]
        if key == "settings":
            return not any(self.settings_problems().values())
        if key == "router":
            if not self.router["host"] or self.router["error"]:
                return False
            return self.router["state"] != "password" or bool(self.router_password)
        if key == "install":
            return bool(self.result and self.result["ok"])
        if key == "finish":
            return False
        return True

    def do_next(self, a):
        with self.lock:
            key = STEPS[self.step][0]
            if key == "router" and self.router["state"] == "password" and not self.router["login_ok"]:
                return self._check_password()
            if not self.can_next() or self.install["running"]:
                return
            self.step += 1
            self._enter(self.step)

    def do_back(self, a):
        with self.lock:
            if self.step > 0 and not self.install["running"]:
                self.router["searching"] = False
                self.step -= 1
                self._enter(self.step)

    def _enter(self, n):
        key = STEPS[n][0]
        if key == "router" and (not self.router["host"] or self.router["error"]):
            self.do_search({})
        elif key == "install" and self.usb is None:
            self.do_refresh_usb({})
        elif key == "phones":
            self.do_recheck_box({})
            if not self.prov_auto and self.final:
                self.prov_auto = True
                self.do_open_phone({"slot": self.prov["slot"]})

    # -- the router ----------------------------------------------------------------------------------------------------------
    def do_search(self, a):
        with self.lock:
            if self.router["searching"]:
                return
            self.router.update(host=None, state=None, error=None, login_ok=False, login_failed=False, searching=True)

        def work():
            while True:
                with self.lock:
                    if not self.router["searching"]:
                        return
                host = self.core.find_router()
                if host:
                    try:
                        state = self.core.probe(host)
                        saved = self.core.saved_login(host, self.out_dir) if state == "password" else None
                        error = None
                    except SetupError as e:
                        state, saved, error = None, None, str(e)
                    except Exception as e:
                        state, saved, error = None, None, f"unexpected error: {e}"
                    with self.lock:
                        if saved:
                            self.router_password = saved   # the password saved by the earlier setup worked: nothing to type
                        self.router.update(host=host, state=state, error=error, searching=False,
                                           login_ok=state != "password" or bool(saved), login_failed=False)
                    return
                time.sleep(3)
        self.spawn(work)

    def do_router_password(self, a):
        password = a.get("password")
        with self.lock:
            if isinstance(password, str) and len(password) < 200:
                self.router_password = password
                self.router["login_failed"] = False

    def _check_password(self):
        host, password = self.router["host"], self.router_password
        if not password:
            return

        def work():
            try:
                ok = self.core.login(host, password)
            except Exception:
                ok = False
            with self.lock:
                self.router.update(login_ok=ok, login_failed=not ok)
                if ok and STEPS[self.step][0] == "router":
                    self.step += 1
                    self._enter(self.step)
        self.spawn(work)

    # -- the coin box on USB and the installation ---------------------------------------------------------------------------------
    def do_refresh_usb(self, a):
        with self.lock:
            self.usb = None

        def work():
            try:
                boards = self.core.usb_boards()
            except Exception:
                boards = []
            with self.lock:
                self.usb = boards
        self.spawn(work)

    def do_skip_flash(self, a):
        with self.lock:
            self.skip_flash = bool(a.get("value"))

    def do_start_install(self, a):
        with self.lock:
            if self.install["running"] or STEPS[self.step][0] != "install":
                return
            self.install.update(running=True, stage=0, activity="Starting...", error=None, banner=None, ok=False, lines=[])
            self.result = None
            answers = kept_answers(self.country) if self.kept() else self.answers()
            write_box = not (self.skip_flash or self.flashed)
            host_hint = self.router["host"]

        def stage(n):
            with self.lock:
                self.install["stage"] = n
                self.install["activity"] = STAGE_ACTIVITY[n]
                if n != 2:
                    self.install["banner"] = None

        def notify(msg):
            self.log(msg)
            if "Unplug" in msg:
                with self.lock:
                    self.install["banner"] = ("warn", "Unplug this computer's network cable from the router, wait 5 seconds and "
                                                      "plug it back in. The installation continues by itself.")

        def work():
            error, result = None, None
            try:
                host = self.core.find_router() or host_hint
                if not host:
                    raise SetupError("the router is not found any more. Check the network cable, then try again.")
                if write_box:
                    boards = self.core.usb_boards()
                    if not boards:
                        raise SetupError("no coin box board found on USB. Plug it in with a USB data cable and try again, or "
                                         "tick that it already has the PisoPhone software.")
                    if len(boards) > 1:
                        raise SetupError("several USB boards are connected: unplug all but the coin box.")
                    stage(0)
                    self.core.flash_box(boards[0][0], self.log)
                    with self.lock:
                        self.flashed = True
                result = self.core.install(host, answers, self.out_dir, self.log, lambda n: stage(n + 1), notify)
            except SetupError as e:
                error = str(e)
            except Exception as e:
                error = f"unexpected error: {e}"
            self._finish_install(result, error)
        self.spawn(work)

    def _finish_install(self, result, error):
        with self.lock:
            self.install["running"] = False
            self.result = result
            if result and result["host"]:
                self.router["host"] = result["host"]
            if result and not result["ok"]:
                error = failure_message(result)
            self.install["error"] = error
            if result and result["ok"]:
                self.install.update(ok=True, stage=len(INSTALL_STAGES), activity="Done.", banner=("ok", "Installed and checked."))
                found = parse_summary(result.get("summary_text", ""))
                self.final = dict(self.answers(), **found) if not self.kept() else found
                self.saved = [[label, path] for label, path in result.get("saved", [])]
                if self.final.get("ROOT_PASSWORD"):
                    self.router_password = self.final["ROOT_PASSWORD"]
            else:
                self.install["activity"] = "Stopped."

    # -- the phones ------------------------------------------------------------------------------------------------------------
    def passwords(self):
        return dict(self.final or {})

    def do_recheck_box(self, a):
        def work():
            try:
                up = self.core.box_online()
            except Exception:
                up = False
            with self.lock:
                self.box_up = up
        self.spawn(work)

    def do_open_phone(self, a):
        try:
            slot = max(1, min(10, int(a.get("slot", 1))))
        except (TypeError, ValueError):
            slot = 1
        with self.lock:
            if self.prov["state"] == "opening":
                return
            self.prov = {"state": "opening", "note": "", "url": "", "slot": slot}
            passwords = self.passwords()

        def work():
            try:
                kind, url, note = self.core.provisioning_target(passwords, slot)
            except Exception as e:
                kind, url, note = "box", f"http://{BOX_IP}/", f"the page could not be prepared ({e})"
            with self.lock:
                self.prov = {"state": kind, "note": note, "url": url, "slot": slot}
        self.spawn(work)

    # -- the last checks ---------------------------------------------------------------------------------------------------------
    def do_coin_test(self, a):
        with self.lock:
            if self.coin["running"]:
                return
            self.coin = {"running": True, "ok": None, "lines": []}
            host, password = self.router["host"] or TARGET_IP, self.router_password

        def line(text):
            with self.lock:
                self.coin["lines"].append(text)
                if text.startswith("RESULT: the box counted"):
                    self.coin["ok"] = True
                elif text.startswith("RESULT:"):
                    self.coin["ok"] = False

        def work():
            try:
                self.core.run(host, password, "piso-setup test-coin", line)
            except Exception as e:
                line(f"RESULT: the test could not run ({e})")
            with self.lock:
                self.coin["running"] = False
                if self.coin["ok"] is None:
                    self.coin["ok"] = False
        self.spawn(work)

    def do_telegram(self, a):
        token = str(a.get("token", "")).strip()
        with self.lock:
            if self.telegram["running"]:
                return
            if not TELEGRAM_TOKEN.fullmatch(token):
                self.telegram["error"] = "That does not look like a bot token (numbers, a colon, then letters, like 123456789:AAH...)."
                return
            self.telegram.update(running=True, error=None, lines=[], confirm=None)
            host, password = self.router["host"] or TARGET_IP, self.router_password
            site = self.passwords().get("SITE_NAME") or self.answers()["SITE_NAME"]
            self._tg_answer = threading.Event()
            self._tg_yes = False

        def confirm(chat):
            with self.lock:
                self.telegram["confirm"] = chat
                event = self._tg_answer
            event.wait(600)
            with self.lock:
                self.telegram["confirm"] = None
                return self._tg_yes

        def line(text):
            with self.lock:
                self.telegram["lines"].append(text)

        def work():
            try:
                ok = self.core.telegram(host, password, token, site, line, confirm)
                error = None if ok else ("Telegram was not connected. Try again, or do it later with piso-setup telegram on the router.")
            except Exception as e:
                ok, error = False, f"Telegram was not connected: {e}"
            with self.lock:
                self.telegram.update(running=False, ok=ok, error=error, confirm=None)
        self.spawn(work)

    def do_telegram_answer(self, a):
        with self.lock:
            if self.telegram.get("confirm") is None or self._tg_answer is None:
                return
            self._tg_yes = bool(a.get("yes"))
            self._tg_answer.set()

    def do_next_unit(self, a):
        with self.lock:
            if self.install["running"] or self.coin["running"] or self.telegram["running"]:
                return
            self.reset_unit()

    # -- what the page reads -------------------------------------------------------------------------------------------------------
    def act(self, name, args):
        if not re.fullmatch(r"[a-z_]{2,24}", name or "") or not hasattr(self, "do_" + name):
            raise ValueError("unknown action")
        getattr(self, "do_" + name)(args if isinstance(args, dict) else {})

    def snapshot(self):
        with self.lock:
            inst = dict(self.install, lines=list(self.install["lines"][-600:]), total=len(self.install["lines"]))
            problems = {k: v for k, v in self.settings_problems().items() if v}
            return {
                "unit": self.unit, "step": self.step, "steps": [label for _key, label in STEPS],
                "keys": [key for key, _label in STEPS], "can_next": self.can_next(),
                "system": {"name": self.system["name"], "kind": self.system["kind"], "arch": self.system["arch"],
                           "root": self.system["root"]},
                "checks": None if self.checks is None else [list(c) for c in self.checks],
                "deps": dict(self.deps, lines=list(self.deps["lines"][-60:])),
                "router": dict(self.router, has_password=bool(self.router_password)),
                "settings": {"values": dict(self.values), "country": self.country, "problems": problems, "kept": self.kept(),
                             "countries": [[c, n] for c, n in COUNTRIES]},
                "usb": {"boards": self.usb, "skip": self.skip_flash, "flashed": self.flashed},
                "install": inst, "stages": INSTALL_STAGES, "final": self.passwords(), "saved": [[l, os.path.basename(p)] for l, p in self.saved],
                "review": {"public_wifi": self.answers()["GUEST_SSID"], "site": self.answers()["SITE_NAME"], "country": self.country,
                           "router": f"{self.router['host'] or '?'} -> {TARGET_IP}" if self.router["host"] == FACTORY_IP else TARGET_IP},
                "phones": dict(self.prov, box_up=self.box_up, box_ip=BOX_IP, kiosk_ssid=KIOSK_SSID),
                "coin": dict(self.coin), "telegram": dict(self.telegram),
            }

    def saved_file(self, index):
        """(name, path) of a file the installation saved, or None. Only those files can be fetched by the page."""
        with self.lock:
            if 0 <= index < len(self.saved):
                path = self.saved[index][1]
                return os.path.basename(path), path
        return None


def local_addresses():
    """This computer's addresses on its networks (not loopback): what to type on a phone."""
    found = []
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("10.255.255.255", 1))   # (no packet is sent: it only picks the outgoing interface)
        found.append(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    return [ip for ip in dict.fromkeys(found) if not ip.startswith("127.") and not ip.startswith("0.")]


def load_pin(path=None, wanted=None):
    """The PIN of the setup page: --pin, else the one saved from the last run, else a new random one (saved, owner only)."""
    path = path or os.path.join(pisophone_home(), "pin")
    if wanted:
        if not PIN_RE.fullmatch(wanted):
            raise SetupError("the PIN must be 6 to 32 letters or digits")
        pin = wanted
    else:
        try:
            with open(path, encoding="utf-8") as f:
                pin = f.read().strip()
        except OSError:
            pin = ""
        if PIN_RE.fullmatch(pin):
            return pin
        pin = "".join(secrets.choice("0123456789") for _ in range(6))
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    save_private(path, pin + "\n")
    return pin


class Web:
    """The page's server state: the PIN, who has logged in, who guessed wrong."""

    def __init__(self, session, pin, allowed_hosts=()):
        self.session, self.pin = session, pin
        self.tokens = set()
        self.fails = []
        self.lock = threading.Lock()
        self.allowed_hosts = {h.lower() for h in allowed_hosts}

    def host_ok(self, header):
        """Rejects a Host header that is not this computer's address or name: a web page of another site cannot then reach the
        setup through the visitor's browser (DNS rebinding)."""
        host = (header or "").lower()
        if host.startswith("["):
            host = host[1:host.find("]")]
        elif host.count(":") == 1:
            host = host.split(":")[0]
        if host in ("localhost", "") or host in self.allowed_hosts or host.endswith(".local") or ":" in host:
            return True
        return bool(re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host))

    def login(self, pin):
        now = time.monotonic()
        with self.lock:
            self.fails = [t for t in self.fails if now - t < 60]
            if len(self.fails) >= 5:
                return None, "Too many wrong PINs: wait a minute."
            if secrets.compare_digest(str(pin), self.pin):
                token = secrets.token_urlsafe(24)
                self.tokens.add(token)
                return token, None
            self.fails.append(now)
            return None, "Wrong PIN."

    def valid(self, token):
        with self.lock:
            return token in self.tokens


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "PisoSetup"
    protocol_version = "HTTP/1.0"

    def log_message(self, *args):
        pass

    @property
    def web(self):
        return self.server.web

    def _send(self, code, body, ctype="application/json", extra=()):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                                                    "connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'")
        for key, value in extra:
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def _json(self, code, value, extra=()):
        self._send(code, json.dumps(value), extra=extra)

    def _token(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            key, _sep, value = part.strip().partition("=")
            if key == "piso":
                return value
        return ""

    def _guard(self, post=False):
        if not self.web.host_ok(self.headers.get("Host")):
            self._json(403, {"error": "unknown host name: use this computer's address"})
            return False
        if post and self.headers.get("X-Piso") != "1":
            self._json(403, {"error": "forbidden"})
            return False
        return True

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path)
        if not self._guard():
            return
        if path.path == "/":
            return self._send(200, PAGE, "text/html; charset=utf-8")
        if path.path == "/favicon.ico":
            return self._send(204, b"")
        if not self.web.valid(self._token()):
            return self._json(401, {"error": "login"})
        if path.path == "/api/state":
            return self._json(200, self.web.session.snapshot())
        if path.path == "/api/saved":
            try:
                found = self.web.session.saved_file(int(urllib.parse.parse_qs(path.query).get("i", ["-1"])[0]))
            except ValueError:
                found = None
            if not found:
                return self._json(404, {"error": "no such file"})
            with open(found[1], "rb") as f:
                body = f.read()
            kind = "text/html; charset=utf-8" if found[0].endswith(".html") else "text/plain; charset=utf-8"
            return self._send(200, body, kind, [("Content-Disposition", f'inline; filename="{found[0]}"')])
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._guard(post=True):
            return
        try:
            size = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(min(size, 20000)).decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            return self._json(400, {"error": "bad request"})
        if not isinstance(body, dict):
            return self._json(400, {"error": "bad request"})
        if self.path == "/api/login":
            token, error = self.web.login(body.get("pin", ""))
            if error:
                return self._json(403, {"error": error})
            return self._json(200, {"ok": True}, [("Set-Cookie", f"piso={token}; HttpOnly; SameSite=Strict; Path=/")])
        if not self.web.valid(self._token()):
            return self._json(401, {"error": "login"})
        if self.path == "/api/do":
            try:
                self.web.session.act(body.get("action"), body)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
            return self._json(200, self.web.session.snapshot())
        self._json(404, {"error": "not found"})


def make_server(session, pin, host="0.0.0.0", port=DEFAULT_PORT):
    """The setup page's server (port 0: any free one). Tries the next ports when the first is taken."""
    last = None
    for candidate in ([port] if port == 0 else range(port, port + 10)):
        try:
            server = http.server.ThreadingHTTPServer((host, candidate), Handler)
            break
        except OSError as e:
            last = e
    else:
        raise SetupError(f"no port is free from {port} on ({last})")
    server.daemon_threads = True
    server.web = Web(session, pin, {socket.gethostname()})
    return server


def has_display():
    return os.name == "nt" or sys.platform == "darwin" or bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def service_unit(script, port):
    """The systemd unit that starts the setup page when the computer boots."""
    return (f"[Unit]\nDescription=PisoPhone setup page\nAfter=network-online.target\nWants=network-online.target\n\n"
            f"[Service]\nType=simple\nEnvironment=PISOPHONE_HOME=/var/lib/pisophone-setup\nEnvironment=PYTHONUNBUFFERED=1\n"
            f"ExecStart={sys.executable} {script} --port {port} --no-open\nRestart=on-failure\nRestartSec=5\n\n"
            f"[Install]\nWantedBy=multi-user.target\n")


def install_service(args, unit_path="/etc/systemd/system/pisophone-setup.service", state_dir="/var/lib/pisophone-setup",
                    run=run_command, supported=None):
    """Linux: makes the setup page start by itself at boot (as root: it writes to the USB port and installs packages)."""
    if supported is None:
        supported = sys.platform.startswith("linux") and bool(shutil.which("systemctl"))
    if not supported:
        raise SetupError("--install-service needs a Linux system with systemd")
    if hasattr(os, "geteuid") and os.geteuid() != 0:
        raise SetupError("run it as administrator:  sudo python3 " + os.path.abspath(sys.argv[0]) + " --install-service")
    os.environ["PISOPHONE_HOME"] = state_dir
    os.makedirs(state_dir, mode=0o700, exist_ok=True)
    pin = load_pin(wanted=args.pin)
    with open(unit_path, "w", encoding="utf-8") as f:
        f.write(service_unit(os.path.abspath(sys.argv[0]), args.port))
    for command in (["systemctl", "daemon-reload"], ["systemctl", "enable", "--now", "pisophone-setup"]):
        if run(command, say, 60) != 0:
            raise SetupError("systemctl failed (see above)")
    say("The setup page now starts by itself when this computer starts.")
    for ip in local_addresses() or ["<this computer's address>"]:
        say(f"  Open  http://{ip}:{args.port}  on your phone (same network), PIN {pin}")
    say("  Stop it with: sudo systemctl disable --now pisophone-setup")
    return 0


def run_web(args, core=None, system=None):
    """The default mode: the setup page. Installs what is missing, serves the page, prints where to open it."""
    use_private_libs()
    system = system or detect_system()
    say(APP_NAME)
    if not args.no_install:
        say("Checking what this computer needs (the first time it installs ssh and esptool; this can take a minute)...")
        ensure_dependencies(say, system)
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        session = Session(core or Core(args.branch, workdir), args.branch, None if args.out == "." else args.out, system)
        pin = load_pin(wanted=args.pin)
        server = make_server(session, pin, "127.0.0.1" if args.local_only else "0.0.0.0", args.port)
        port = server.server_address[1]
        say()
        say("The setup page is running. Open it in a browser:")
        say(f"  on this computer:  http://127.0.0.1:{port}")
        if not args.local_only:
            for ip in local_addresses():
                say(f"  on your phone:     http://{ip}:{port}   (same network as this computer)")
        say(f"  PIN: {pin}")
        say("Leave this running until the last unit is done (Ctrl+C stops it).")
        if not args.no_open and has_display():
            try:
                webbrowser.open(f"http://127.0.0.1:{port}")
            except Exception:
                pass
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            say()
            say("Stopped. Running this again is safe: the router keeps what is done.")
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PisoPhone Setup</title>
<style>
:root{--bg:#f8fafc;--card:#fff;--line:#e2e8f0;--ink:#0f172a;--mut:#64748b;--acc:#2563eb;--accd:#1d4ed8;--accs:#dbeafe;--ok:#15803d;--oks:#dcfce7;--warn:#b45309;--warns:#fef3c7;--bad:#b91c1c;--bads:#fee2e2;--log:#0b1220;--logi:#cbd5e1}
@media(prefers-color-scheme:dark){:root{--bg:#0b1220;--card:#111a2e;--line:#22304a;--ink:#e5e9f2;--mut:#93a1ba;--acc:#4f8bff;--accd:#6b9dff;--accs:#14284d;--ok:#4ade80;--oks:#0f2c1c;--warn:#fbbf24;--warns:#33280c;--bad:#f87171;--bads:#3a1414;--log:#050912}}
*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header{padding:14px 16px;border-bottom:1px solid var(--line);display:flex;gap:10px;align-items:baseline;flex-wrap:wrap}
header b{font-size:18px}header span{color:var(--mut);font-size:14px}
nav{display:flex;gap:6px;padding:10px 16px;overflow-x:auto}
nav div{flex:1 0 auto;text-align:center;padding:6px 10px;border-radius:999px;font-size:13px;background:var(--card);border:1px solid var(--line);color:var(--mut);white-space:nowrap}
nav div.on{background:var(--acc);border-color:var(--acc);color:#fff;font-weight:600}nav div.done{color:var(--ok);border-color:var(--ok)}
main{max-width:760px;margin:0 auto;padding:6px 16px 110px}
h1{font-size:22px;margin:14px 0 4px}h2{font-size:16px;margin:0 0 8px}p{margin:6px 0}.mut{color:var(--mut)}.small{font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px;margin:12px 0}
.note{border-left:4px solid var(--acc);background:var(--accs);padding:10px 12px;border-radius:6px;margin:12px 0}
.note.ok{border-color:var(--ok);background:var(--oks)}.note.warn{border-color:var(--warn);background:var(--warns)}.note.bad{border-color:var(--bad);background:var(--bads)}
button{font:inherit;padding:10px 16px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--ink);cursor:pointer;min-height:44px}
button.primary{background:var(--acc);border-color:var(--acc);color:#fff;font-weight:600}button:disabled{opacity:.45;cursor:default}
button.small{min-height:34px;padding:4px 10px;font-size:13px}
input,select{font:inherit;width:100%;padding:10px 12px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--ink);min-height:44px}
input.bad{border-color:var(--bad)}label{display:block;font-weight:600;margin-top:12px}.hint{font-weight:400;color:var(--mut);font-size:13px}.err{color:var(--bad);font-size:13px;min-height:1em}
ul.steps{padding-left:20px;margin:6px 0}ul.steps li{margin:4px 0}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.grow{flex:1}
.kv{display:flex;gap:10px;padding:3px 0}.kv span:first-child{color:var(--mut);min-width:110px}
pre{background:var(--log);color:var(--logi);padding:10px 12px;border-radius:8px;overflow:auto;max-height:280px;font:12px/1.4 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word;margin:8px 0}
.dot{display:inline-block;width:14px;height:14px;border-radius:50%;border:2px solid var(--line);margin-right:10px;vertical-align:-2px}.dot.on{background:var(--acc);border-color:var(--acc)}.dot.ok{background:var(--ok);border-color:var(--ok)}
.pw{font:15px ui-monospace,Menlo,Consolas,monospace;word-break:break-all}
footer{position:fixed;left:0;right:0;bottom:0;background:var(--bg);border-top:1px solid var(--line);padding:10px 16px;display:flex;gap:10px;justify-content:space-between;max-width:100%}
footer .in{display:flex;gap:10px;justify-content:space-between;width:100%;max-width:760px;margin:0 auto}
#login{position:fixed;inset:0;background:var(--bg);display:none;align-items:center;justify-content:center;padding:20px;z-index:9}#login .card{width:100%;max-width:360px}
a{color:var(--acc)}.hide{display:none!important}
</style></head><body>
<div id="login"><div class="card"><h1>PisoPhone Setup</h1><p class="mut">Type the PIN shown on the computer that runs the setup (in its terminal, or <code>pisophone-setup.service</code>'s log).</p>
<label for="pin">PIN</label><input id="pin" type="password" inputmode="text" autocomplete="off"><div class="err" id="pinErr"></div>
<p><button class="primary" id="pinBtn" style="width:100%">Open the setup</button></p></div></div>
<header><b>PisoPhone Setup</b><span id="unit"></span><span id="sysname"></span></header>
<nav id="nav"></nav>
<main>
<section id="s0">
 <h1>Set up your PisoPhone system</h1>
 <p class="mut">One unit at a time: the coin box gets its software over USB, the router is set up and paired with it, then the rental phones. About 15 minutes. Nothing on the router is changed until you start the installation.</p>
 <div class="card"><h2>What you need</h2><ul class="steps"><li>The ESP32 coin box board and a USB <b>data</b> cable, plugged into this computer</li><li>An OpenWrt router (for example a 360T6M), factory reset</li><li>The modem in the router's WAN port, and a network cable from this computer to a LAN port</li><li>The rental phones, factory reset</li></ul></div>
 <div class="card"><h2>This computer</h2><p class="mut small" id="sysline"></p><div id="checks"><p class="mut">Checking...</p></div>
  <div class="row" style="margin-top:8px"><button id="btnDeps" class="hide">Install what is missing</button><button id="btnRecheck">Check again</button></div>
  <pre id="depsLog" class="hide"></pre></div>
</section>
<section id="s1" class="hide">
 <h1>Connect the router</h1>
 <p class="mut">The router is set up over a network cable from this computer. It is found by itself as soon as it is connected.</p>
 <div class="card"><ul class="steps"><li>Factory reset the router (hold its reset button about 10 seconds) and wait 2 minutes.</li><li>Plug the modem into the router's <b>WAN</b> (internet) port.</li><li>Plug this computer into one of the router's <b>LAN</b> ports.</li><li>Turn this computer's Wi-Fi off if it is also on the router's network.</li></ul></div>
 <div id="unitNote" class="note hide"></div>
 <div id="routerBox" class="note">Looking for the router...</div>
 <div id="pwRow" class="card hide"><label for="routerPw">Router password <span class="hint">this router was set up before and has a password (it is in your saved summary)</span></label><input id="routerPw" type="password" autocomplete="off"><div class="err" id="pwErr"></div></div>
 <button id="btnSearch">Search again</button>
</section>
<section id="s2" class="hide">
 <h1 id="setTitle">This customer's names</h1>
 <p class="mut" id="setSub"></p>
 <div class="card" id="namesCard">
  <label for="GUEST_SSID">Public Wi-Fi name <span class="hint">what customers see in their Wi-Fi list</span></label><input id="GUEST_SSID" data-k="1" autocomplete="off" autocapitalize="off"><div class="err" data-e="GUEST_SSID"></div>
  <label for="SITE_NAME">Shop / site name <span class="hint">on the setup sheet and in alerts</span></label><input id="SITE_NAME" data-k="1" autocomplete="off"><div class="err" data-e="SITE_NAME"></div>
 </div>
 <div class="card"><label for="COUNTRY" style="margin-top:0">Country <span class="hint">decides which Wi-Fi channels the router may use</span></label><select id="COUNTRY"></select><div class="err" data-e="COUNTRY"></div></div>
 <div class="card" id="pwCard"><h2>Passwords <span class="hint">new for every unit; keep them or type your own</span></h2>
  <label for="ROOT_PASSWORD">Router password <span class="hint">SSH and the router's web page</span></label><input id="ROOT_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="ROOT_PASSWORD"></div>
  <label for="KIOSK_PASSWORD">PisoKiosk Wi-Fi password <span class="hint">the hidden network of the rental phones</span></label><input id="KIOSK_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="KIOSK_PASSWORD"></div>
  <label for="BOX_NEW_ADMIN_PASSWORD">Coin box admin password <span class="hint">the box's admin page; also the phones' admin PIN</span></label><input id="BOX_NEW_ADMIN_PASSWORD" data-k="1" autocomplete="off"><div class="err" data-e="BOX_NEW_ADMIN_PASSWORD"></div></div>
</section>
<section id="s3" class="hide">
 <h1>Install</h1>
 <p class="mut" id="instSub">One click: the coin box gets its software over USB, then the router is set up and paired with it. About 10 minutes. Keep the USB cable plugged in (it powers the box) and the network cable too.</p>
 <div class="card"><h2>Settings</h2><div id="review"></div></div>
 <div class="card"><div class="row"><h2 class="grow" style="margin:0">Coin box (USB)</h2><button class="small" id="btnUsb">Look again</button></div><p id="usbText" class="mut"></p>
  <label style="font-weight:400;display:flex;gap:10px;align-items:center;margin-top:8px"><input type="checkbox" id="skipFlash" style="width:auto;min-height:0"> Do not write it: the coin box already has the PisoPhone software</label></div>
 <div class="card"><h2>Progress</h2><div id="stages"></div><p id="activity" class="mut small"></p></div>
 <div id="instBanner"></div>
 <p><button class="primary" id="btnStart">Start the installation</button></p>
 <details open><summary class="mut small">Details</summary><pre id="instLog"></pre></details>
</section>
<section id="s4" class="hide">
 <h1>Set up the rental phones</h1>
 <p class="mut">Each phone is set up once with a QR code: Android installs PisoPhone, locks the phone as a kiosk and connects it to its coin box slot. Open the setup page on a phone or laptop that has internet, and scan its code with the rental phone.</p>
 <div class="card"><h2>For each phone</h2><ul class="steps"><li>Factory reset the phone. On its first welcome screen tap the same spot 6 times: a QR reader opens.</li><li>Open the setup page below, tap <b>Show the setup code</b>, and scan it with the rental phone.</li><li>Accept the screens until PisoPhone shows <b>One last step</b>: on most phones switch on <i>Allow display over other apps</i>. An Android Go phone asks for USB: plug it into a computer and use <b>Finish over USB</b> on the setup page (Chrome or Edge).</li><li>Pair the slot on the coin box's page. The kiosk is running.</li></ul>
  <div class="row" style="margin-top:10px"><label style="margin:0">Slot</label><select id="slot" style="width:90px"></select><button class="primary" id="btnPhone">Make the setup page link</button></div>
  <div id="provBox"></div></div>
 <div class="card"><h2>Coin box</h2><p id="boxUp" class="mut">Checking...</p></div>
 <div class="card"><h2>Your passwords</h2><div id="pwList"></div></div>
</section>
<section id="s5" class="hide">
 <h1>Check that it all works</h1>
 <div class="card"><h2>1. Test a coin</h2><p class="mut">Tap the button, then insert one coin when the box beeps (within 30 seconds).</p><button class="primary" id="btnCoin">Test a coin</button><div id="coinBox"></div></div>
 <div class="card"><h2>2. Try it as a customer</h2><p class="mut" id="custText"></p></div>
 <div class="card"><h2>Alerts on your phone (optional)</h2><p class="mut">Telegram messages when the coin box goes offline or the revenue does not add up, plus a daily report. You need a bot token from @BotFather.</p>
  <input id="tgToken" placeholder="123456789:AAH..." autocomplete="off"><div class="err" id="tgErr"></div><button id="btnTg">Connect Telegram</button><div id="tgBox"></div></div>
 <div class="card"><h2>Saved on this computer</h2><div id="savedList"></div><p class="mut small" id="savedDir"></p></div>
 <div class="card"><h2>Another unit?</h2><p class="mut">Starts over for the next router and coin box: the shop and Wi-Fi names are asked again and new passwords are made. This unit's summary stays saved.</p><button class="primary" id="btnNext">Set up the next unit</button></div>
</section>
</main>
<footer><div class="in"><button id="btnBack">Back</button><button class="primary" id="btnNextStep">Next</button></div></footer>
<script>
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
let S=null,shown=-1,shownUnit=0,dirty=false,pwTimer=0;
const h=(tag,attrs,...kids)=>{const e=document.createElement(tag);for(const k in(attrs||{})){if(k==='class')e.className=attrs[k];else if(k==='onclick')e.onclick=attrs[k];else e.setAttribute(k,attrs[k])}for(const c of kids.flat())if(c!=null)e.append(c.nodeType?c:document.createTextNode(String(c)));return e};
const clear=e=>{while(e.firstChild)e.removeChild(e.firstChild)};
async function api(path,body){const r=await fetch(path,{method:body?'POST':'GET',headers:{'X-Piso':'1','Content-Type':'application/json'},body:body?JSON.stringify(body):undefined,credentials:'same-origin'});if(r.status===401){showLogin();throw new Error('login')}return r.json()}
async function act(action,extra){S=await api('/api/do',Object.assign({action},extra||{}));paint()}
function showLogin(){$('#login').style.display='flex'}
async function login(){const r=await fetch('/api/login',{method:'POST',headers:{'X-Piso':'1','Content-Type':'application/json'},body:JSON.stringify({pin:$('#pin').value}),credentials:'same-origin'});const j=await r.json();if(r.ok){$('#login').style.display='none';$('#pin').value='';poll(true)}else $('#pinErr').textContent=j.error||'Wrong PIN.'}
$('#pinBtn').onclick=login;$('#pin').onkeydown=e=>{if(e.key==='Enter')login()};
async function poll(once){try{S=await api('/api/state');paint()}catch(e){}if(once!==true)setTimeout(poll,document.hidden?4000:1000)}
function copyText(t,btn){const done=()=>{if(btn){const o=btn.textContent;btn.textContent='Copied';setTimeout(()=>btn.textContent=o,1200)}};if(navigator.clipboard&&window.isSecureContext)navigator.clipboard.writeText(t).then(done,()=>{});else{const a=document.createElement('textarea');a.value=t;document.body.append(a);a.select();try{document.execCommand('copy')}catch(e){}a.remove();done()}}
function note(kind,text){return h('div',{class:'note '+kind},text)}
function setPre(pre,lines){const near=pre.scrollTop+pre.clientHeight>=pre.scrollHeight-40;pre.textContent=lines.join('\n');if(near)pre.scrollTop=pre.scrollHeight}
function paint(){
 if(!S)return;
 $('#unit').textContent='Unit '+S.unit;$('#sysname').textContent=S.system.name;
 if(S.unit!==shownUnit){shownUnit=S.unit;shown=-1;fillSettingsOnce()}
 const nav=$('#nav');if(nav.children.length!==S.steps.length){clear(nav);S.steps.forEach(n=>nav.append(h('div',{},n)))}
 [...nav.children].forEach((d,i)=>d.className=i===S.step?'on':i<S.step?'done':'');
 if(shown!==S.step){shown=S.step;$$('main>section').forEach((s,i)=>s.classList.toggle('hide',i!==S.step));window.scrollTo(0,0)}
 $('#btnBack').classList.toggle('hide',S.step===0||S.install.running);
 const last=S.step===S.steps.length-1;$('#btnNextStep').classList.toggle('hide',last);$('#btnNextStep').disabled=!S.can_next&&!(S.step===1&&S.router.state==='password'&&S.router.has_password);
 [paint0,paint1,paint2,paint3,paint4,paint5][S.step]();
}
function paint0(){
 $('#sysline').textContent=S.system.name+' ('+S.system.arch+')'+(S.system.root?', running as administrator':'');
 const c=$('#checks');clear(c);let miss=false;
 if(!S.checks)c.append(h('p',{class:'mut'},'Checking...'));
 else S.checks.forEach(([what,ok,fix])=>{if(!ok)miss=true;c.append(h('div',{class:'kv'},h('span',{style:'color:var(--'+(ok?'ok':'bad')+')'},ok?'OK':'Missing'),h('span',{style:'color:var(--ink)'},what+(ok?'':': '+fix))))});
 $('#btnDeps').classList.toggle('hide',!miss||S.deps.running);$('#btnDeps').disabled=S.deps.running;
 const lg=$('#depsLog');lg.classList.toggle('hide',!S.deps.lines.length);if(S.deps.lines.length)setPre(lg,S.deps.lines);
 $('#btnRecheck').disabled=S.deps.running;
}
function paint1(){
 const r=S.router,b=$('#routerBox');
 $('#unitNote').classList.toggle('hide',S.unit<2);if(S.unit>1)$('#unitNote').textContent='Unit '+S.unit+': unplug the network cable from the last router, connect this computer to the next factory-reset router, and plug the next coin box board into USB.';
 let kind='',text='';
 if(r.error){kind='bad';text=r.error[0].toUpperCase()+r.error.slice(1)}
 else if(r.host){kind='ok';text='Router found at '+r.host+(r.state==='factory'?' (factory reset: ready to be set up).':r.state==='configured'?' (set up before: it keeps its names and passwords and is finished or repaired).':r.state==='password'?' (it has a password).':'.')}
 else{kind='';text='Looking for the router at 192.168.1.1 or 10.0.0.1... Check the cable is in a LAN port (not WAN) and the router has been on for 2 minutes.'}
 b.className='note '+kind;b.textContent=text;
 $('#pwRow').classList.toggle('hide',!(r.host&&r.state==='password'&&!r.login_ok));
 $('#pwErr').textContent=r.login_failed?'That password did not work.':'';
 $('#btnSearch').disabled=r.searching&&!r.host;
}
function fillSettingsOnce(){
 const v=S.settings.values;$$('input[data-k]').forEach(i=>i.value=v[i.id]||'');
 const sel=$('#COUNTRY');clear(sel);S.settings.countries.forEach(([c,n])=>sel.append(h('option',{value:c},n+' ('+c+')')));sel.value=S.settings.country;
}
function paint2(){
 const st=S.settings,kept=st.kept;
 $('#namesCard').classList.toggle('hide',kept);$('#pwCard').classList.toggle('hide',kept);
 $('#setTitle').textContent=kept?'This router keeps its settings':'This customer\'s names and passwords';
 $('#setSub').textContent=kept?'It was set up before, so its Wi-Fi names and passwords stay as they are (they are in the summary the earlier setup saved). The installation finishes or repairs it.':'Type the shop name and public Wi-Fi name for this customer (asked every time). The passwords were made for you, new ones for every unit: keep them or type your own. They are saved on this computer at the end and printed on the setup sheet.';
 $$('.err[data-e]').forEach(e=>{const k=e.dataset.e,p=st.problems[k];const input=document.getElementById(k);const typed=input&&(input.value||k==='COUNTRY');e.textContent=p&&typed?p[0].toUpperCase()+p.slice(1):'';if(input)input.classList.toggle('bad',!!(p&&typed))});
}
$$('input[data-k]').forEach(i=>i.addEventListener('input',()=>{clearTimeout(pwTimer);const values={};$$('input[data-k]').forEach(x=>values[x.id]=x.value);pwTimer=setTimeout(()=>act('set',{values}),250)}));
$('#COUNTRY').onchange=e=>act('set',{country:e.target.value});
$('#routerPw').addEventListener('input',e=>act('router_password',{password:e.target.value}));
function paint3(){
 const rv=S.review,i=S.install,u=S.usb,kept=S.settings.kept,box=$('#review');clear(box);
 [['Public Wi-Fi',kept?'kept as it is':rv.public_wifi],['Site name',kept?'kept as it is':rv.site],['Country',rv.country],['Router',rv.router],['Passwords',kept?'kept as they are':'the three shown before']].forEach(([k,v])=>box.append(h('div',{class:'kv'},h('span',{},k),h('span',{},v))));
 let t,cls='mut';
 if(u.flashed){t='The coin box software is written.';cls='ok'}else if(u.skip){t='Not written: the box is used as it is.'}else if(u.boards===null)t='Looking for the board...';
 else if(!u.boards.length){t='No board found. Plug the coin box board into this computer with a USB data cable (a charge-only cable does not work), then tap Look again.';cls='warn'}
 else if(u.boards.length>1){t='Several USB boards are connected ('+u.boards.map(b=>b[0]).join(', ')+'). Unplug all but the coin box.';cls='warn'}
 else{t='Found: '+u.boards[0][1]+' on '+u.boards[0][0]+'. It is erased and gets the PisoPhone software.';cls='ok'}
 const ut=$('#usbText');ut.textContent=t;ut.style.color=cls==='mut'?'':'var(--'+cls+')';
 $('#skipFlash').checked=u.skip;$('#skipFlash').disabled=i.running;$('#btnUsb').disabled=i.running;
 const st=$('#stages');clear(st);S.stages.forEach((n,k)=>{const done=i.ok||k<i.stage,on=k===i.stage&&i.running;st.append(h('div',{style:'margin:4px 0'},h('span',{class:'dot '+(done?'ok':on?'on':'')}),h('span',{style:on?'font-weight:600':''},n)))});
 $('#activity').textContent=i.activity;
 const bn=$('#instBanner');clear(bn);
 if(i.ok)bn.append(note('ok','Installation complete: all checks passed. Your passwords and the setup sheet are saved. Tap Next for the phones.'));
 else if(i.error)bn.append(note('bad',i.error[0].toUpperCase()+i.error.slice(1)));
 else if(i.banner)bn.append(note(i.banner[0],i.banner[1]));
 const sb=$('#btnStart');sb.classList.toggle('hide',i.ok);sb.disabled=i.running;sb.textContent=i.running?'Installing...':(i.error||S.install.total)?'Try again':'Start the installation';
 setPre($('#instLog'),i.lines);
}
$('#btnUsb').onclick=()=>act('refresh_usb');$('#skipFlash').onchange=e=>act('skip_flash',{value:e.target.checked});$('#btnStart').onclick=()=>act('start_install');
function paint4(){
 const sel=$('#slot');if(!sel.children.length){for(let n=1;n<=10;n++)sel.append(h('option',{value:n},String(n)))}
 const p=S.phones,box=$('#provBox');clear(box);$('#btnPhone').disabled=p.state==='opening';
 if(p.state==='opening')box.append(note('','Preparing the setup page...'));
 else if(p.state==='setup')box.append(note('ok','The setup page for slot '+p.slot+' is ready.'),h('p',{},h('a',{href:p.url,target:'_blank',rel:'noopener'},'Open the phone setup page'),' ',h('button',{class:'small',onclick:ev=>copyText(p.url,ev.target)},'Copy link')),h('p',{class:'mut small'},'The link holds this box\'s key: open it only on your own phone or laptop.'));
 else if(p.state==='box')box.append(note('warn','The phone setup page could not be filled in ('+p.note+'). Open the coin box\'s own page from a computer on the router\'s network, log in as admin with the admin password below, and tap Set up a phone: http://'+p.box_ip+'/'));
 const bu=$('#boxUp');bu.textContent=p.box_up===null?'Checking...':p.box_up?'Online at '+p.box_ip:'Does not answer at '+p.box_ip+'. Is it powered on, near the router?';bu.style.color=p.box_up===null?'':p.box_up?'var(--ok)':'var(--bad)';
 const list=$('#pwList'),f=S.final;clear(list);
 [['BOX_NEW_ADMIN_PASSWORD','Coin box admin (user admin)'],['KIOSK_PASSWORD',p.kiosk_ssid+' Wi-Fi'],['ROOT_PASSWORD','Router (user root)']].forEach(([k,l])=>{const v=f[k];list.append(h('div',{style:'margin:8px 0'},h('div',{class:'mut small'},l),h('div',{class:'row'},h('span',{class:'pw grow'},v||'in the saved summary'),v?h('button',{class:'small',onclick:ev=>copyText(v,ev.target)},'Copy'):null)))});
}
$('#btnPhone').onclick=()=>act('open_phone',{slot:+$('#slot').value});
function paint5(){
 const c=S.coin,cb=$('#coinBox');clear(cb);$('#btnCoin').disabled=c.running;
 if(c.lines.length)cb.append(h('pre',{},c.lines.join('\n')));
 if(c.ok===true)cb.append(note('ok','The box counted the coin: the box and the portal work.'));else if(c.ok===false&&!c.running)cb.append(note('bad','The coin test did not pass. Read the lines above, then try again.'));
 $('#custText').textContent='With any phone join the Wi-Fi "'+(S.final.GUEST_SSID||S.review.public_wifi)+'". The payment page opens by itself. Pick a plan, tap Insert Coin, insert a coin: it shows at once. Tap Done: you are online.';
 const t=S.telegram,tb=$('#tgBox');clear(tb);$('#btnTg').disabled=t.running;$('#tgErr').textContent=t.error||'';
 if(t.ok)tb.append(note('ok','Telegram is connected.'));
 if(t.lines.length)tb.append(h('pre',{},t.lines.join('\n')));
 if(t.confirm)tb.append(note('warn',t.confirm+' Is that you? Alerts and commands are then accepted only from this chat.'),h('div',{class:'row'},h('button',{class:'primary',onclick:()=>act('telegram_answer',{yes:true})},'Yes, that is me'),h('button',{onclick:()=>act('telegram_answer',{yes:false})},'No')));
 const sl=$('#savedList');clear(sl);S.saved.forEach(([l,n],i)=>sl.append(h('p',{},h('a',{href:'/api/saved?i='+i,target:'_blank'},l.split(' (')[0]),' ',h('span',{class:'mut small'},n))));
 if(!S.saved.length)sl.append(h('p',{class:'mut'},'Nothing saved yet.'));
 $('#savedDir').textContent='Keep the summary private: it holds every password. Day to day, the router has: piso-setup status.';
}
$('#btnCoin').onclick=()=>act('coin_test');$('#btnTg').onclick=()=>act('telegram',{token:$('#tgToken').value});$('#btnNext').onclick=()=>act('next_unit');
$('#btnBack').onclick=()=>act('back');$('#btnNextStep').onclick=()=>act('next');
$('#btnDeps').onclick=()=>act('install_deps');$('#btnRecheck').onclick=()=>act('recheck');$('#btnSearch').onclick=()=>act('search');
poll();
</script></body></html>
"""


# ---- start ---------------------------------------------------------------------------------------------------------------

def main(argv=None):
    if os.environ.get("PISO_ASKPASS_MODE"):
        # started by ssh as its password helper (Ssh.set_password): the answer is the password, nothing else
        os.write(1, (os.environ.get("PISO_ASKPASS_PW", "") + "\n").encode())
        return 0
    if sys.stdout is not None and hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    use_private_libs()
    p = argparse.ArgumentParser(description="Set up a PisoPhone system: the coin box, the router and the rental phones.")
    p.add_argument("--branch", default=DEFAULT_BRANCH, help=f"the repository branch whose setup files are used (default {DEFAULT_BRANCH})")
    p.add_argument("--cli", action="store_true", help="run in the terminal instead of the setup page")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"the setup page's port (default {DEFAULT_PORT})")
    p.add_argument("--pin", help="the PIN that opens the setup page (6 to 32 letters or digits; kept for next time; default: a random one)")
    p.add_argument("--local-only", action="store_true", help="serve the setup page to this computer only (not to a phone)")
    p.add_argument("--no-open", action="store_true", help="do not open a browser on this computer")
    p.add_argument("--no-install", action="store_true", help="do not install the programs this one needs (ssh, esptool)")
    p.add_argument("--install-service", action="store_true", help="Linux: start the setup page by itself when the computer boots (run with sudo)")
    p.add_argument("--update", action="store_true", help="install new software on a router that is set up already (terminal)")
    p.add_argument("--router", help=f"the router's address (default: found by itself, {TARGET_IP} or {FACTORY_IP})")
    p.add_argument("--guest-ssid", help="the public Wi-Fi name (default PisoWiFi)")
    p.add_argument("--site-name", help="the shop / site name (default: the public Wi-Fi name)")
    p.add_argument("--country", default="PH", help="the Wi-Fi country code (default PH)")
    p.add_argument("--yes", action="store_true", help="ask nothing: defaults, generated passwords, no review (terminal)")
    p.add_argument("--out", default=".", help="the folder for the saved summary and setup sheet (default: the current folder; "
                                              "the setup page uses Documents/PisoPhone)")
    p.add_argument("--no-browser", action="store_true", help="do not open the setup sheet when done (terminal)")
    p.add_argument("--no-flash", action="store_true", help="do not write the coin box software over USB (terminal; it has it already)")
    args = p.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.branch):
        p.error(f"not a branch name: {args.branch}")
    if args.router and not re.fullmatch(r"[A-Za-z0-9.:-]+", args.router):
        p.error(f"not an address: {args.router}")
    args.country = args.country.upper()
    if country_problem(args.country):
        p.error(f"not a country code: {args.country}")
    if not 1 <= args.port <= 65535:
        p.error("not a port")
    try:
        if args.install_service:
            return install_service(args)
        if not (args.cli or args.update or args.yes or args.router):
            return run_web(args)
    except SetupError as e:
        say(f"ERROR: {e}")
        return 1
    use_private_libs()
    if not args.no_install:
        system = detect_system()
        ensure_ssh(say, system)
        if not (args.update or args.no_flash):
            ensure_esptool(say, system)
    if not os.environ.get("PISO_SSH") and not shutil.which("ssh"):
        say("ERROR: the ssh command was not found. Windows: Settings > Apps > Optional features > add 'OpenSSH Client'. "
            "Linux: install openssh-client.")
        return 2
    say(APP_NAME)
    say("Before you start: the coin box board is plugged into this computer's USB port (its software is written now), the router")
    say("is factory reset and on, the modem is in its WAN port, and this computer is in a LAN port by cable (turn its Wi-Fi")
    say("off).")
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        return run_setup(args, Ssh(workdir))
    except SetupError as e:
        say()
        say(f"ERROR: {e}")
        return 1
    except KeyboardInterrupt:
        say()
        say("Stopped. Running this again is safe: the router keeps what is done.")
        return 130
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
