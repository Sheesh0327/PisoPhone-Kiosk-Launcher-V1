#!/usr/bin/env python3
"""PisoPhone Setup: the one program that sets up a whole PisoPhone system, from a bare coin box and a factory-reset router
to working rental phones. Nothing to copy, paste or type into a terminal.

    python3 pisophone_setup.py                    the setup assistant (a window that guides you through every step)
    python3 pisophone_setup.py --branch beta      the same with the beta branch's files (testing)
    python3 pisophone_setup.py --cli              the same in the terminal (no window)
    python3 pisophone_setup.py --update           new software only, on a router that is set up already (terminal)

The assistant's steps, in order:
  1. Start        checks this computer (ssh, the website, Chrome or Edge)
  2. Coin box     opens the web flasher in Chrome or Edge (the ESP32 gets its software over USB)
  3. Router       finds the factory-reset router by itself (192.168.1.1 or 10.0.0.1)
  4. Settings     the Wi-Fi names, the country and the passwords (made for you; change what you like)
  5. Install      runs the one-line installer on the router unattended (it checks the setup file's sha256), waits for the
                  router to move to 10.0.0.1, runs the setup, pairs the coin box, saves the summary and the setup sheet
  6. Phones       opens the coin box's phone setup page (Set up a phone) for you, already filled in: scan the QR code
  7. Finish       tests a real coin, checks the customer Wi-Fi, offers Telegram alerts
The summary (every password) and the printable setup sheet are saved on this computer, readable only by you.

It needs Python 3.8+ (with Tk for the window; the terminal mode works without) and the ssh command (built into Windows
10/11, macOS and Linux). The passwords travel over the SSH connection's input, never on a command line; the router's SSH
key is pinned for the run (the same key must answer at 192.168.1.1 and at 10.0.0.1). Running it again is safe: a router
that is set up already keeps its names and passwords (it is then only repaired or finished).

Build a standalone program with Nuitka (no Python needed on the computer that runs it):
    python -m nuitka --onefile --enable-plugin=tk-inter --windows-console-mode=disable setup/pisophone_setup.py"""
import argparse
import base64
import getpass
import glob
import os
import pathlib
import queue
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
SITE_HOST = "pisophone.pages.dev"   # the website (Cloudflare Pages): the flasher, the phone setup page, the installer and the setup file
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
    """Chrome, Edge or Chromium on this computer (the flasher and the USB step need Web Serial and WebUSB: Chromium only)."""
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


def open_provisioning(branch, passwords, slot=1, fetch=fetch_box_link, opener=open_url):
    """Opens the coin box's phone setup page (Set up a phone) in the browser: filled in when the box's admin page can be
    read, else the box's own page (log in, click Set up a phone). Returns ("setup" or "box", a note for the user)."""
    box_password = passwords.get("BOX_NEW_ADMIN_PASSWORD") or ""
    note = "the coin box's admin password is not known here"
    if box_password:
        try:
            link = fetch(BOX_IP, box_password)
            if link["mac"] and link["mac"] != "00:00:00:00:00:00":
                opener(provisioning_url(branch, link, slot, passwords.get("KIOSK_PASSWORD", "")))
                return "setup", ""
            note = "the coin box has no address yet"
        except SetupError as e:
            note = str(e)
    opener(f"http://{BOX_IP}/", chromium=False)
    return "box", note


def box_online(host=BOX_IP, probe=port_open):
    return probe(host, 80)


def preflight(branch, opener=urllib.request.urlopen, find=chromium_path):
    """The checks of the first page: [(what, ok, what to do when not)]. Nothing here changes anything."""
    checks = []
    have_ssh = bool(os.environ.get("PISO_SSH") or shutil.which("ssh"))
    checks.append(("ssh program", have_ssh, "Windows: Settings > Apps > Optional features > add \"OpenSSH Client\". "
                                            "Linux: install openssh-client."))
    try:
        with opener(urllib.request.Request(f"{site_url(branch)}/update/app.json", method="GET"), timeout=8) as r:
            online = r.status == 200
    except (urllib.error.URLError, OSError, ValueError):
        online = False
    checks.append((f"internet and the {urllib.parse.urlsplit(site_url(branch)).netloc} website", online,
                   "This computer needs internet now (the router gets its own from the modem later)."))
    checks.append(("Chrome or Edge", bool(find()), "The flasher needs Chrome or Microsoft Edge (Firefox and Safari cannot "
                                                   "talk to the board over USB). Install one, then start this again."))
    return checks


# ---- the terminal mode ---------------------------------------------------------------------------------------------------

def run_setup(args, ssh, read=input, read_secret=getpass.getpass, probe=port_open, wait=wait_for_move, open_page=open_url):
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


# ---- the window mode -----------------------------------------------------------------------------------------------------

tk = ttk = messagebox = tkfont = None


def _load_tk():
    global tk, ttk, messagebox, tkfont
    import tkinter
    from tkinter import ttk as _ttk, messagebox as _messagebox, font as _tkfont
    tk, ttk, messagebox, tkfont = tkinter, _ttk, _messagebox, _tkfont


def gui_available():
    try:
        __import__("tkinter")   # (only whether it loads)
    except Exception:
        return False
    if os.name != "nt" and sys.platform != "darwin" and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
        return False
    return True


def default_out_dir():
    """Where the window mode saves the summary and the setup sheet: Documents/PisoPhone (or PisoPhone in the home folder)."""
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


class Core:
    """What the window does with the network, the router and the browser; tests replace it with a fake."""

    def __init__(self, branch, workdir):
        self.branch = branch
        self.ssh = Ssh(workdir, hidden=True)
        self.ssh.set_password("")   # never wait for a password on a terminal there is not

    def ssh_missing(self):
        return not os.environ.get("PISO_SSH") and not shutil.which("ssh")

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

    def open_flasher(self):
        return open_url(site_url(self.branch) + "/flash.html")

    def open_provisioning(self, passwords, slot):
        return open_provisioning(self.branch, passwords, slot)

    def box_online(self):
        return box_online()

    def browse(self, url):
        open_url(url, chromium=False)

    def open_path(self, path):
        open_path(path)


# the colours: one neutral palette, one accent
C = {
    "side": "#0f172a", "side_text": "#cbd5e1", "side_muted": "#64748b", "side_line": "#1e293b",
    "bg": "#f8fafc", "card": "#ffffff", "line": "#e2e8f0", "ink": "#0f172a", "muted": "#64748b",
    "accent": "#2563eb", "accent_dark": "#1d4ed8", "accent_soft": "#dbeafe", "accent_off": "#93b4f5",
    "ok": "#15803d", "ok_soft": "#dcfce7", "warn": "#b45309", "warn_soft": "#fef3c7", "bad": "#b91c1c", "bad_soft": "#fee2e2",
    "log_bg": "#0b1220", "log_ink": "#cbd5e1",
}
STEPS = [("welcome", "Start"), ("box", "Coin box"), ("router", "Router"), ("settings", "Settings"), ("install", "Install"),
         ("phones", "Phones"), ("finish", "Finish")]
INSTALL_STAGES = ["Prepare the router", f"Move it to {TARGET_IP}", "Install and set up", "Check everything"]


class Wizard:
    """The setup assistant: one window, a step list on the left, one step at a time on the right."""

    def __init__(self, root, core, branch=DEFAULT_BRANCH, out_dir=None):
        self.root, self.core, self.branch = root, core, branch
        self.out_dir = out_dir or default_out_dir()
        self.q = queue.Queue()
        self.step = 0
        self.box_done = tk.BooleanVar(value=False)
        self.vars = {
            "GUEST_SSID": tk.StringVar(value="PisoWiFi"), "SITE_NAME": tk.StringVar(value=""),
            "COUNTRY": tk.StringVar(value="Philippines (PH)"),
        }
        for env, _label, _lo, _hi, length in PASSWORDS:
            self.vars[env] = tk.StringVar(value=generate(length))
        for key, var in self.vars.items():
            var.trace_add("write", lambda *_a, k=key: self._validate_field(k))
        self.router_password = tk.StringVar(value="")
        self.tg_token = tk.StringVar(value="")
        self.router = {"host": None, "state": None, "error": None, "login_ok": False}
        self.searching = False
        self.installing = False
        self.result = None          # the installation's result once it has run
        self.install_error = None
        self.log_lines = []
        self.activity = ""
        self.stage = -1
        self.banner = None          # (kind, text) on the install page
        self.telegram = {"running": False, "ok": False, "lines": []}
        self.tg_win = None
        self.field_errors = {}
        self.final = {}             # what the installed system uses: the passwords and names (from the router's summary)
        self.pre = None             # the first page's checks, once they ran
        self.flasher_opened = False
        self.slot = tk.IntVar(value=1)
        self.prov = {"state": None, "note": ""}      # the phone setup page: None, "opening", "setup", "box"
        self.prov_auto = False
        self.box_up = None
        self.coin = {"running": False, "ok": None, "lines": []}
        self.clip_token = ""
        self._fonts()
        self._styles()
        self._layout()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.show(0)
        self.root.after(80, self._poll)

    # -- look --------------------------------------------------------------------------------------------------------------
    def _fonts(self):
        families = set(tkfont.families(self.root))
        family = next((f for f in ("Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Cantarell", "Ubuntu", "Noto Sans",
                                   "DejaVu Sans") if f in families), tkfont.nametofont("TkDefaultFont").actual("family"))
        mono = next((f for f in ("Cascadia Mono", "Consolas", "SF Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono")
                     if f in families), "TkFixedFont")
        self.f = {
            "body": (family, 10), "small": (family, 9), "bold": (family, 10, "bold"), "h1": (family, 18, "bold"),
            "h2": (family, 11, "bold"), "eyebrow": (family, 8, "bold"), "brand": (family, 15, "bold"),
            "step": (family, 10), "step_on": (family, 10, "bold"), "mono": (mono, 9), "mono_big": (mono, 11),
        }
        for name in ("TkDefaultFont", "TkTextFont"):
            tkfont.nametofont(name).configure(family=family, size=10)

    def _styles(self):
        s = ttk.Style(self.root)
        try:
            s.theme_use("clam")
        except tk.TclError:
            pass
        s.configure(".", background=C["bg"], foreground=C["ink"], font=self.f["body"])
        s.configure("Card.TFrame", background=C["card"])
        s.configure("TButton", padding=(14, 7), font=self.f["body"], borderwidth=1, focusthickness=0, relief="solid",
                    background=C["card"], foreground=C["ink"], bordercolor="#cbd5e1", lightcolor=C["card"], darkcolor=C["card"])
        s.map("TButton", background=[("disabled", C["bg"]), ("pressed", C["line"]), ("active", "#f1f5f9")],
              foreground=[("disabled", "#94a3b8")], bordercolor=[("disabled", C["line"]), ("focus", C["accent"])])
        s.configure("Primary.TButton", background=C["accent"], foreground="#ffffff", bordercolor=C["accent"],
                    lightcolor=C["accent"], darkcolor=C["accent"], font=self.f["bold"], padding=(18, 8))
        s.map("Primary.TButton", background=[("disabled", C["accent_off"]), ("pressed", C["accent_dark"]), ("active", C["accent_dark"])],
              foreground=[("disabled", "#eef2ff")], bordercolor=[("disabled", C["accent_off"]), ("focus", C["accent_dark"])],
              lightcolor=[("disabled", C["accent_off"])], darkcolor=[("disabled", C["accent_off"])])
        s.configure("Small.TButton", padding=(8, 3), font=self.f["small"])
        s.configure("TEntry", padding=(8, 6), fieldbackground=C["card"], bordercolor=C["line"], lightcolor=C["line"],
                    darkcolor=C["line"], insertcolor=C["ink"])
        s.map("TEntry", bordercolor=[("focus", C["accent"])], lightcolor=[("focus", C["accent"])])
        s.configure("Bad.TEntry", bordercolor=C["bad"], lightcolor=C["bad"], darkcolor=C["bad"])
        s.configure("TCombobox", padding=(8, 5), fieldbackground=C["card"], background=C["card"], bordercolor=C["line"],
                    arrowcolor=C["muted"], lightcolor=C["line"], darkcolor=C["line"])
        s.map("TCombobox", fieldbackground=[("readonly", C["card"])], bordercolor=[("focus", C["accent"])])
        s.configure("TCheckbutton", background=C["card"], font=self.f["body"], focusthickness=0, indicatorbackground=C["card"],
                    indicatorforeground=C["accent"])
        s.map("TCheckbutton", background=[("active", C["card"])], indicatorbackground=[("selected", C["card"])])
        s.configure("Bg.TCheckbutton", background=C["bg"])
        s.map("Bg.TCheckbutton", background=[("active", C["bg"])])
        s.configure("Horizontal.TProgressbar", troughcolor=C["line"], background=C["accent"], bordercolor=C["line"],
                    lightcolor=C["accent"], darkcolor=C["accent"], thickness=6)
        s.configure("Vertical.TScrollbar", background=C["log_bg"], troughcolor=C["log_bg"], bordercolor=C["log_bg"],
                    arrowcolor=C["side_muted"], lightcolor=C["log_bg"], darkcolor=C["log_bg"])

    def _layout(self):
        r = self.root
        r.title(APP_NAME)
        r.configure(bg=C["bg"])
        r.geometry("1000x700")
        r.minsize(900, 640)
        r.columnconfigure(1, weight=1)
        r.rowconfigure(0, weight=1)
        side = tk.Frame(r, bg=C["side"], width=250)
        side.grid(row=0, column=0, sticky="nsw")
        side.grid_propagate(False)
        tk.Label(side, text="PisoPhone", bg=C["side"], fg="#ffffff", font=self.f["brand"], anchor="w").pack(fill="x", padx=24, pady=(26, 0))
        tk.Label(side, text="System setup", bg=C["side"], fg=C["side_muted"], font=self.f["small"], anchor="w").pack(fill="x", padx=24)
        tk.Frame(side, bg=C["side_line"], height=1).pack(fill="x", padx=24, pady=(20, 14))
        self.step_rows = []
        for i, (_key, title) in enumerate(STEPS):
            row = tk.Frame(side, bg=C["side"])
            row.pack(fill="x", padx=18, pady=3)
            dot = tk.Canvas(row, width=26, height=26, bg=C["side"], highlightthickness=0)
            dot.pack(side="left", padx=(6, 10))
            lab = tk.Label(row, text=title, bg=C["side"], fg=C["side_text"], font=self.f["step"], anchor="w")
            lab.pack(side="left", fill="x")
            self.step_rows.append((row, dot, lab))
        foot = "Setup files: " + self.branch + ("" if self.branch == "main" else "  (testing)")
        tk.Label(side, text=foot, bg=C["side"], fg=C["side_muted"], font=self.f["small"], anchor="w").pack(side="bottom", fill="x", padx=24, pady=18)

        main = tk.Frame(r, bg=C["bg"])
        main.grid(row=0, column=1, sticky="nsew")
        main.columnconfigure(0, weight=1)
        main.rowconfigure(1, weight=1)
        head = tk.Frame(main, bg=C["bg"])
        head.grid(row=0, column=0, sticky="ew", padx=36, pady=(28, 0))
        self.eyebrow = tk.Label(head, bg=C["bg"], fg=C["accent"], font=self.f["eyebrow"], anchor="w")
        self.eyebrow.pack(fill="x")
        self.title = tk.Label(head, bg=C["bg"], fg=C["ink"], font=self.f["h1"], anchor="w")
        self.title.pack(fill="x", pady=(2, 0))
        self.subtitle = tk.Label(head, bg=C["bg"], fg=C["muted"], font=self.f["body"], anchor="w", justify="left", wraplength=640)
        self.subtitle.pack(fill="x", pady=(4, 0))
        self.body = tk.Frame(main, bg=C["bg"])
        self.body.grid(row=1, column=0, sticky="nsew", padx=36, pady=(18, 0))
        tk.Frame(main, bg=C["line"], height=1).grid(row=2, column=0, sticky="ew")
        nav = tk.Frame(main, bg=C["bg"])
        nav.grid(row=3, column=0, sticky="ew", padx=36, pady=14)
        self.nav_note = tk.Label(nav, bg=C["bg"], fg=C["muted"], font=self.f["small"], anchor="w")
        self.nav_note.pack(side="left")
        self.next_btn = ttk.Button(nav, text="Next", style="Primary.TButton", command=self.next)
        self.next_btn.pack(side="right")
        self.back_btn = ttk.Button(nav, text="Back", command=self.back)
        self.back_btn.pack(side="right", padx=(0, 10))
        self.subtitle.bind("<Configure>", lambda e: self.wrap(self.subtitle, e.width - 10, 300))

    def _draw_steps(self):
        for i, (row, dot, lab) in enumerate(self.step_rows):
            dot.delete("all")
            if i < self.step:
                dot.create_oval(2, 2, 24, 24, fill=C["accent"], outline=C["accent"])
                dot.create_line(8, 13, 12, 17, 18, 9, fill="#ffffff", width=2, capstyle="round", joinstyle="round")
                lab.configure(fg=C["side_text"], font=self.f["step"])
            elif i == self.step:
                dot.create_oval(2, 2, 24, 24, fill="#ffffff", outline="#ffffff")
                dot.create_text(13, 13, text=str(i + 1), fill=C["side"], font=self.f["eyebrow"])
                lab.configure(fg="#ffffff", font=self.f["step_on"])
            else:
                dot.create_oval(2, 2, 24, 24, outline=C["side_muted"], width=1.5)
                dot.create_text(13, 13, text=str(i + 1), fill=C["side_muted"], font=self.f["eyebrow"])
                lab.configure(fg=C["side_muted"], font=self.f["step"])

    # -- small building blocks ---------------------------------------------------------------------------------------------
    def card(self, parent, **pack):
        outer = tk.Frame(parent, bg=C["line"])
        outer.pack(fill="x", **dict({"pady": (0, 14)}, **pack))
        inner = tk.Frame(outer, bg=C["card"], padx=20, pady=16)
        inner.pack(fill="both", expand=True, padx=1, pady=1)
        return inner

    def text(self, parent, value, style="body", fg=None, bg=None, **kw):
        bg = bg or parent.cget("bg")
        lab = tk.Label(parent, text=value, bg=bg, fg=fg or C["ink"], font=self.f[style], anchor="w", justify="left", **kw)
        lab.bind("<Configure>", lambda e: self.wrap(lab, e.width - 4, 200))
        return lab

    @staticmethod
    def wrap(label, width, least):
        """Wraps a label's text to the width it was given. Only a real change counts: two columns that grow by one pixel
        every time their text is wrapped again would otherwise never come to rest."""
        width = max(least, width)
        if abs(width - int(float(label.cget("wraplength")))) > 8:
            label.configure(wraplength=width)

    def numbered(self, parent, items):
        for n, item in enumerate(items, 1):
            row = tk.Frame(parent, bg=parent.cget("bg"))
            row.pack(fill="x", pady=4)
            badge = tk.Canvas(row, width=22, height=22, bg=parent.cget("bg"), highlightthickness=0)
            badge.create_oval(1, 1, 21, 21, fill=C["accent_soft"], outline=C["accent_soft"])
            badge.create_text(11, 11, text=str(n), fill=C["accent_dark"], font=self.f["eyebrow"])
            badge.pack(side="left", anchor="n", padx=(0, 12))
            self.text(row, item).pack(side="left", fill="x", expand=True)

    def note(self, parent, kind, value):
        colors = {"ok": (C["ok_soft"], C["ok"]), "warn": (C["warn_soft"], C["warn"]), "bad": (C["bad_soft"], C["bad"]),
                  "info": (C["accent_soft"], C["accent_dark"])}[kind]
        box = tk.Frame(parent, bg=colors[0], padx=14, pady=10)
        box.pack(fill="x", pady=(0, 12))
        tk.Frame(box, bg=colors[1], width=3).pack(side="left", fill="y", padx=(0, 12))
        self.text(box, value, fg=colors[1], bg=colors[0]).pack(side="left", fill="x", expand=True)
        return box

    def copy(self, value):
        self.root.clipboard_clear()
        self.root.clipboard_append(value)
        self.nav_note.configure(text="Copied.")
        self.root.after(1800, lambda: self.nav_note.configure(text=""))

    # -- navigation --------------------------------------------------------------------------------------------------------
    def show(self, n):
        if self.searching and STEPS[n][0] != "router":
            self.searching = False
        self.step = n
        for w in self.body.winfo_children():
            w.destroy()
        self.eyebrow.configure(text=f"STEP {n + 1} OF {len(STEPS)}")
        self.nav_note.configure(text="")
        getattr(self, "page_" + STEPS[n][0])()
        self._draw_steps()
        self.update_nav()

    def next(self):
        key = STEPS[self.step][0]
        if key == "router" and self.router["state"] == "password" and not self.router["login_ok"]:
            return self._check_router_password()
        if key == "box" and not self.box_done.get():
            return
        if self.step == len(STEPS) - 1:
            return self.close()
        self.show(self.step + 1)

    def back(self):
        if self.step > 0 and not self.installing:
            self.show(self.step - 1)

    def can_next(self):
        key = STEPS[self.step][0]
        if key == "box":
            return self.box_done.get()
        if key == "settings":
            return not any(self.settings_problems().values())
        if key == "router":
            if not self.router["host"] or self.router["error"]:
                return False
            return self.router["state"] != "password" or bool(self.router_password.get())
        if key == "install":
            return bool(self.result and self.result["ok"])
        if key == "finish":
            return not (self.coin["running"] or self.telegram["running"])
        return True

    def update_nav(self):
        key = STEPS[self.step][0]
        self.back_btn.configure(state="disabled" if self.step == 0 or self.installing else "normal")
        labels = {"phones": "Next", "finish": "Finish"}
        self.next_btn.configure(text=labels.get(key, "Next"), state="normal" if self.can_next() else "disabled")

    def close(self):
        if self.installing and not messagebox.askyesno(APP_NAME, "The installation is still running. If you close now the "
                                                       "router may be left half set up (running the setup again finishes it).\n\n"
                                                       "Close anyway?", icon="warning", parent=self.root):
            return
        self.searching = False
        try:   # no timer may fire into a window that is gone
            for after_id in self.root.tk.call("after", "info"):
                self.root.after_cancel(after_id)
        except tk.TclError:
            pass
        self.root.destroy()

    # -- talking to the worker threads -------------------------------------------------------------------------------------
    def post(self, *event):
        self.q.put(event)

    def ask(self, func):
        """From a worker thread: runs func on the window's thread and returns its answer."""
        done, box = threading.Event(), []
        self.q.put(("call", func, done, box))
        done.wait()
        return box[0] if box else None

    def _poll(self):
        try:
            while True:
                event = self.q.get_nowait()
                getattr(self, "on_" + event[0])(*event[1:])
        except queue.Empty:
            pass
        if self.root.winfo_exists():
            self.root.after(80, self._poll)

    def on_call(self, func, done, box):
        try:
            box.append(func())
        finally:
            done.set()

    # -- 1. start ----------------------------------------------------------------------------------------------------------
    def page_welcome(self):
        self.title.configure(text="Set up your PisoPhone system")
        self.subtitle.configure(text="This assistant takes you from a bare coin box and router to working rental phones, one step "
                                     "at a time, and does the rest by itself. It takes about 20 minutes. Nothing on the router "
                                     "is changed until you start the installation.")
        cols = tk.Frame(self.body, bg=C["bg"])
        cols.pack(fill="both", expand=True)
        cols.columnconfigure(0, weight=1, uniform="w")
        cols.columnconfigure(1, weight=1, uniform="w")
        left, right = tk.Frame(cols, bg=C["bg"]), tk.Frame(cols, bg=C["bg"])
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        right.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        c = self.card(left)
        self.text(c, "What you need", "h2").pack(fill="x", pady=(0, 8))
        for item in ("The ESP32 coin box board and a USB data cable",
                     "An OpenWrt router (for example a 360T6M), factory reset",
                     "Internet from a modem, by cable to the router's WAN port",
                     "A network (LAN) cable from this computer to the router",
                     "The rental phones, factory reset"):
            self.tick_row(c, item)
        self.checks_card = self.card(right)
        self.text(self.checks_card, "This computer", "h2").pack(fill="x", pady=(0, 8))
        self.checks_box = tk.Frame(self.checks_card, bg=C["card"])
        self.checks_box.pack(fill="x")
        self.note(self.body, "info", f"Your passwords and the printable setup sheet are saved in {self.out_dir} at the end, "
                                     "readable only by you.")
        self._render_checks()
        if self.pre is None and not getattr(self, "pre_running", False):
            self.pre_running = True
            threading.Thread(target=lambda: self.post("pre", self.core.preflight()), daemon=True).start()

    def tick_row(self, parent, item, ok=True):
        row = tk.Frame(parent, bg=C["card"])
        row.pack(fill="x", pady=3)
        mark = tk.Canvas(row, width=18, height=18, bg=C["card"], highlightthickness=0)
        soft, hard = (C["ok_soft"], C["ok"]) if ok else (C["bad_soft"], C["bad"])
        mark.create_oval(1, 1, 17, 17, fill=soft, outline=soft)
        if ok:
            mark.create_line(5, 9, 8, 12, 13, 6, fill=hard, width=2, capstyle="round", joinstyle="round")
        else:
            mark.create_line(6, 6, 12, 12, fill=hard, width=2, capstyle="round")
            mark.create_line(12, 6, 6, 12, fill=hard, width=2, capstyle="round")
        mark.pack(side="left", anchor="n", padx=(0, 10))
        label = self.text(row, item)
        label.pack(side="left", fill="x", expand=True)
        return label

    def _render_checks(self):
        if STEPS[self.step][0] != "welcome" or not self.checks_box.winfo_exists():
            return
        for w in self.checks_box.winfo_children():
            w.destroy()
        if self.pre is None:
            self.text(self.checks_box, "Checking...", fg=C["muted"]).pack(fill="x")
            return
        for what, ok, hint in self.pre:
            holder = tk.Frame(self.checks_box, bg=C["card"])
            holder.pack(fill="x")
            self.tick_row(holder, what, ok)
            if not ok:
                self.text(holder, hint, "small", fg=C["bad"]).pack(fill="x", padx=(28, 0))

    def on_pre(self, checks):
        self.pre = checks
        self._render_checks()

    # -- 2. coin box -------------------------------------------------------------------------------------------------------
    def page_box(self):
        url = site_url(self.branch) + "/flash.html"
        self.title.configure(text="Install the coin box software")
        self.subtitle.configure(text="The coin box's ESP32 board gets its software from the PisoPhone website, over USB. This "
                                     "is done once per box. The flasher opens in your browser by itself.")
        c = self.card(self.body)
        self.numbered(c, ["Plug the ESP32 board into this computer with a USB data cable (a charge-only cable does not work).",
                          "On the page that opened, click Connect the box and install, and choose the board's port. Keep "
                          "\"Erase everything first\" ticked: a used box then forgets its old Wi-Fi.",
                          "Wait for Done. Then unplug the board and power it from its own supply, near the router."])
        row = tk.Frame(c, bg=C["card"])
        row.pack(fill="x", pady=(12, 0))
        self.flasher_btn = ttk.Button(row, text="Open the flasher again", style="Primary.TButton", command=self.open_flasher)
        self.flasher_btn.pack(side="left")
        self.text(row, url, "small", fg=C["muted"]).pack(side="left", padx=12)
        self.note(self.body, "info", "It does not connect? Hold the board's BOOT button, tap RESET, release BOOT, then try again. "
                                     "The page needs Chrome or Edge.")
        ttk.Checkbutton(self.body, text="The coin box software is installed and the box is powered on (or it was done before)",
                        variable=self.box_done, style="Bg.TCheckbutton", command=self.update_nav).pack(anchor="w", pady=(4, 0))
        if not self.flasher_opened:
            self.flasher_opened = True
            self.root.after(400, self.open_flasher)

    def open_flasher(self):
        self.core.open_flasher()
        self.nav_note.configure(text="Opened the flasher in your browser.")
        self.root.after(3000, lambda: self.nav_note.configure(text="") if self.nav_note.winfo_exists() else None)

    # -- 3. settings -------------------------------------------------------------------------------------------------------
    def kept(self):
        """The router was set up before (or has a password): it keeps its names and passwords."""
        return self.router["state"] in ("configured", "password")

    def settings_problems(self):
        v = {k: var.get() for k, var in self.vars.items()}
        if self.kept():
            return {"COUNTRY": country_problem(self.country_code())}
        problems = {"GUEST_SSID": name_problem(v["GUEST_SSID"], "wifi"),
                    "SITE_NAME": name_problem(v["SITE_NAME"], "site") if v["SITE_NAME"] else None,
                    "COUNTRY": country_problem(self.country_code())}
        for env, _label, lo, hi, _length in PASSWORDS:
            problems[env] = password_problem(v[env], lo, hi)
        return problems

    def country_code(self):
        m = re.search(r"\(([A-Za-z]{2})\)\s*$", self.vars["COUNTRY"].get()) or re.fullmatch(r"\s*([A-Za-z]{2})\s*", self.vars["COUNTRY"].get())
        return m.group(1).upper() if m else ""

    def answers(self):
        a = {k: var.get().strip() if k in ("GUEST_SSID", "SITE_NAME") else var.get() for k, var in self.vars.items()}
        a["SITE_NAME"] = a["SITE_NAME"] or a["GUEST_SSID"]
        a["COUNTRY"] = self.country_code()
        return a

    def page_settings(self):
        self.field_errors = {}
        if self.kept():
            self.title.configure(text="This router keeps its settings")
            self.subtitle.configure(text="It was set up before, so its Wi-Fi names and passwords stay as they are (they are in "
                                         "the summary the earlier setup saved). The installation finishes or repairs it.")
            c = self.card(self.body)
            self.text(c, "Country", "h2").pack(fill="x", pady=(0, 4))
            self.text(c, "Decides which Wi-Fi channels the router may use.", "small", fg=C["muted"]).pack(fill="x")
            combo = ttk.Combobox(c, textvariable=self.vars["COUNTRY"], values=[f"{n} ({cc})" for cc, n in COUNTRIES], height=12)
            combo.pack(fill="x", pady=(6, 0))
            combo.bind("<<ComboboxSelected>>", lambda e: self.update_nav())
            self._error_label(c, "COUNTRY")
            self._validate_field("COUNTRY")
            return
        self.title.configure(text="Your network and passwords")
        self.subtitle.configure(text="The passwords below were made for you. Keep them, or type your own. They are saved "
                                     "on this computer at the end and printed on the setup sheet.")
        cols = tk.Frame(self.body, bg=C["bg"])
        cols.pack(fill="both", expand=True)
        cols.columnconfigure(0, weight=1, uniform="c")
        cols.columnconfigure(1, weight=1, uniform="c")
        left, right = tk.Frame(cols, bg=C["bg"]), tk.Frame(cols, bg=C["bg"])
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        right.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        names = self.card(left)
        self.text(names, "Names", "h2").pack(fill="x", pady=(0, 6))
        self.field(names, "GUEST_SSID", "Public Wi-Fi name", "What customers see in their Wi-Fi list.")
        self.field(names, "SITE_NAME", "Shop / site name", "On the setup sheet and in alerts. Empty: the Wi-Fi name.")
        lab = tk.Frame(names, bg=C["card"])
        lab.pack(fill="x", pady=(8, 2))
        self.text(lab, "Country", "bold").pack(fill="x")
        self.text(lab, "Decides which Wi-Fi channels the router may use.", "small", fg=C["muted"]).pack(fill="x")
        combo = ttk.Combobox(names, textvariable=self.vars["COUNTRY"], values=[f"{n} ({c})" for c, n in COUNTRIES], height=12)
        combo.pack(fill="x", pady=(2, 0))
        combo.bind("<<ComboboxSelected>>", lambda e: self.update_nav())
        self._error_label(names, "COUNTRY")
        pw = self.card(right)
        self.text(pw, "Passwords", "h2").pack(fill="x", pady=(0, 6))
        hints = {"ROOT_PASSWORD": ("Router password", "Logs in to the router (SSH and its web page)."),
                 "KIOSK_PASSWORD": (f"{KIOSK_SSID} Wi-Fi password", "The hidden network of the rental phones."),
                 "BOX_NEW_ADMIN_PASSWORD": ("Coin box admin password", "The box's admin page; also the phones' admin PIN.")}
        for env, *_ in PASSWORDS:
            self.field(pw, env, hints[env][0], hints[env][1], secret=True)
        for key in self.vars:
            self._validate_field(key)

    def field(self, parent, key, label, hint, secret=False):
        box = tk.Frame(parent, bg=C["card"])
        box.pack(fill="x", pady=(8, 0))
        self.text(box, label, "bold").pack(fill="x")
        self.text(box, hint, "small", fg=C["muted"]).pack(fill="x")
        row = tk.Frame(box, bg=C["card"])
        row.pack(fill="x", pady=(3, 0))
        entry = ttk.Entry(row, textvariable=self.vars[key], show="•" if secret else "", font=self.f["mono_big"] if secret else self.f["body"])
        entry.pack(side="left", fill="x", expand=True)
        if secret:
            length = next(n for env, *_x, n in PASSWORDS if env == key)

            def toggle(btn):
                hidden = entry.cget("show") != ""
                entry.configure(show="" if hidden else "•")
                btn.configure(text="Hide" if hidden else "Show")
            show = ttk.Button(row, text="Show", style="Small.TButton", width=5)
            show.configure(command=lambda b=show: toggle(b))
            show.pack(side="left", padx=(6, 0))
            ttk.Button(row, text="New", style="Small.TButton", width=4,
                       command=lambda: self.vars[key].set(generate(length))).pack(side="left", padx=(4, 0))
        self.field_errors[key] = (entry, None)
        self._error_label(box, key)

    def _error_label(self, parent, key):
        lab = tk.Label(parent, text="", bg=C["card"], fg=C["bad"], font=self.f["small"], anchor="w")
        lab.pack(fill="x")
        entry = self.field_errors.get(key, (None, None))[0]
        self.field_errors[key] = (entry, lab)

    def _validate_field(self, key):
        if STEPS[self.step][0] != "settings" or key not in self.field_errors:
            return
        entry, lab = self.field_errors[key]
        problem = self.settings_problems().get(key)
        try:
            if lab is not None and lab.winfo_exists():
                lab.configure(text=f"{problem[0].upper()}{problem[1:]}." if problem else "")
            if entry is not None and entry.winfo_exists():
                entry.configure(style="Bad.TEntry" if problem else "TEntry")
        except tk.TclError:
            pass
        self.update_nav()

    # -- 4. router ---------------------------------------------------------------------------------------------------------
    def page_router(self):
        self.title.configure(text="Connect the router")
        self.subtitle.configure(text="The router is set up over a network cable from this computer. This assistant finds it "
                                     "by itself as soon as it is connected.")
        c = self.card(self.body)
        self.numbered(c, ["Factory reset the router (hold its reset button about 10 seconds) and wait 2 minutes for it to start.",
                          "Plug the modem into the router's WAN (internet) port.",
                          "Plug this computer into one of the router's LAN ports with a network cable.",
                          "Turn this computer's Wi-Fi off until the setup is done (so it talks only to the router)."])
        self.router_status = tk.Frame(self.body, bg=C["bg"])
        self.router_status.pack(fill="x")
        self._render_router_status()
        if not self.router["host"] or self.router["error"]:
            self._start_search()

    def _render_router_status(self):
        if STEPS[self.step][0] != "router":
            return
        for w in self.router_status.winfo_children():
            w.destroy()
        r = self.router
        if self.core.ssh_missing():
            self.note(self.router_status, "bad", "The ssh program was not found on this computer (see the first step).")
            return
        if r["error"]:
            self.note(self.router_status, "bad", f"Found something at {r['host']}, but: {r['error']}.")
        elif not r["host"]:
            box = self.note(self.router_status, "info", "Looking for the router at 192.168.1.1 and 10.0.0.1...")
            bar = ttk.Progressbar(box, mode="indeterminate", length=120)
            bar.pack(side="right")
            bar.start(12)
        elif r["state"] == "factory":
            self.note(self.router_status, "ok", f"Router found at {r['host']}. It is ready for the installation.")
        else:
            self.note(self.router_status, "warn", f"Router found at {r['host']}. It was set up before: it keeps the names and "
                                                  "passwords it has, and the installation finishes or repairs it. Your "
                                                  "settings from the previous step are not applied to it (the country is).")
            if r["state"] == "password" and r.get("login_ok"):
                self.note(self.router_status, "ok", "Logged in with the router password saved by the previous setup.")
            elif r["state"] == "password":
                c = self.card(self.router_status)
                self.text(c, "Router password", "bold").pack(fill="x")
                self.text(c, "This router has a password. It is in the summary saved by the previous setup, and none of the "
                             "summaries saved on this computer works.", "small",
                          fg=C["muted"]).pack(fill="x")
                e = ttk.Entry(c, textvariable=self.router_password, show="•", font=self.f["mono_big"])
                e.pack(fill="x", pady=(4, 0))
                e.bind("<KeyRelease>", lambda ev: self.update_nav())
                e.bind("<Return>", lambda ev: self.next())
                if r.get("login_failed"):
                    tk.Label(c, text="That password did not work.", bg=C["card"], fg=C["bad"], font=self.f["small"],
                             anchor="w").pack(fill="x", pady=(4, 0))
        row = tk.Frame(self.router_status, bg=C["bg"])
        row.pack(fill="x")
        if r["host"] or r["error"]:
            ttk.Button(row, text="Search again", command=self._search_again).pack(side="left")
        self.update_nav()

    def _search_again(self):
        self.router.update(host=None, state=None, error=None, login_ok=False, login_failed=False)
        self._render_router_status()
        self._start_search()

    def _start_search(self):
        if self.searching:
            return
        self.searching = True

        def work():
            while self.searching:
                host = self.core.find_router()
                if host:
                    try:
                        state = self.core.probe(host)
                        saved = self.core.saved_login(host, self.out_dir) if state == "password" else None
                        self.post("router", host, state, None, saved)
                    except SetupError as e:
                        self.post("router", host, None, str(e))
                    except Exception as e:  # (an unexpected ssh failure: shown, the search can be repeated)
                        self.post("router", host, None, f"unexpected error: {e}")
                    return
                for _ in range(15):
                    if not self.searching:
                        return
                    time.sleep(0.2)
        threading.Thread(target=work, daemon=True).start()

    def on_router(self, host, state, error, saved=None):
        self.searching = False
        if saved:
            self.router_password.set(saved)   # the password saved by the earlier setup worked: nothing to type
        self.router.update(host=host, state=state, error=error, login_ok=state != "password" or bool(saved), login_failed=False)
        self._render_router_status()
        self.update_nav()

    def _check_router_password(self):
        self.next_btn.configure(state="disabled")
        self.nav_note.configure(text="Checking the password...")
        host, password = self.router["host"], self.router_password.get()
        threading.Thread(target=lambda: self.post("login", self.core.login(host, password)), daemon=True).start()

    def on_login(self, ok):
        self.router.update(login_ok=ok, login_failed=not ok)
        self.nav_note.configure(text="")
        if ok:
            self.show(self.step + 1)
        else:
            self._render_router_status()

    # -- 5. install --------------------------------------------------------------------------------------------------------
    def page_install(self):
        self.title.configure(text="Install")
        kept = self.router["state"] in ("configured", "password")
        a = self.answers()
        if self.result and self.result["ok"]:
            self.subtitle.configure(text="Everything is installed and checked.")
        else:
            self.subtitle.configure(text="The installation takes about 5 to 10 minutes. Keep the coin box powered on (the "
                                         "router pairs with it) and this computer's cable plugged in.")
        top = tk.Frame(self.body, bg=C["bg"])
        top.pack(fill="x")
        top.columnconfigure(0, weight=1, uniform="t")
        top.columnconfigure(1, weight=1, uniform="t")
        rev = self.card(tk.Frame(top, bg=C["bg"]))
        rev.master.master.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        self.text(rev, "Settings", "h2").pack(fill="x", pady=(0, 6))
        rows = [("Public Wi-Fi", "kept as it is" if kept else a["GUEST_SSID"]), ("Site name", "kept as it is" if kept else a["SITE_NAME"]),
                ("Country", a["COUNTRY"]), ("Router", f"{self.router['host'] or '?'} → {TARGET_IP}" if self.router["host"] == FACTORY_IP else TARGET_IP),
                ("Passwords", "kept as they are" if kept else "the three you chose")]
        for k, v in rows:
            row = tk.Frame(rev, bg=C["card"])
            row.pack(fill="x", pady=1)
            self.text(row, k, "small", fg=C["muted"], width=14).pack(side="left")
            self.text(row, v, "body").pack(side="left", fill="x", expand=True)
        prog = self.card(tk.Frame(top, bg=C["bg"]))
        prog.master.master.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        self.text(prog, "Progress", "h2").pack(fill="x", pady=(0, 6))
        self.stage_rows = []
        for i, name in enumerate(INSTALL_STAGES):
            row = tk.Frame(prog, bg=C["card"])
            row.pack(fill="x", pady=2)
            dot = tk.Canvas(row, width=16, height=16, bg=C["card"], highlightthickness=0)
            dot.pack(side="left", padx=(0, 10))
            lab = tk.Label(row, text=name, bg=C["card"], font=self.f["body"], anchor="w")
            lab.pack(side="left")
            self.stage_rows.append((dot, lab))
        self.activity_label = self.text(prog, self.activity, "small", fg=C["muted"])
        self.activity_label.pack(fill="x", pady=(8, 0))
        self.bar = ttk.Progressbar(prog, mode="indeterminate")
        self.bar.pack(fill="x", pady=(6, 0))
        self.banner_box = tk.Frame(self.body, bg=C["bg"])
        self.banner_box.pack(fill="x")
        actions = tk.Frame(self.body, bg=C["bg"])
        actions.pack(fill="x", pady=(0, 10))
        self.start_btn = ttk.Button(actions, text="Start the installation", style="Primary.TButton", command=self.start_install)
        if not (self.result and self.result["ok"]):
            self.start_btn.pack(side="left")
        self.details_btn = ttk.Button(actions, text="Hide details", command=self._toggle_log)
        self.details_btn.pack(side="right")
        logf = tk.Frame(self.body, bg=C["log_bg"])
        logf.pack(fill="both", expand=True, pady=(0, 6))
        self.logf = logf
        self.log = tk.Text(logf, bg=C["log_bg"], fg=C["log_ink"], insertbackground=C["log_ink"], font=self.f["mono"], height=8,
                           relief="flat", padx=12, pady=10, wrap="word", highlightthickness=0, borderwidth=0)
        sb = ttk.Scrollbar(logf, orient="vertical", command=self.log.yview)
        self.log.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.log.pack(side="left", fill="both", expand=True)
        self.log.tag_configure("err", foreground="#fca5a5")
        self.log.tag_configure("ok", foreground="#86efac")
        self.log.tag_configure("tip", foreground="#93c5fd")
        self.log.tag_configure("step", foreground="#ffffff", font=(self.f["mono"][0], self.f["mono"][1], "bold"))
        for line in self.log_lines[-2000:]:
            self._log_insert(line)
        self.log.configure(state="disabled")
        self._render_install_state()

    def _toggle_log(self):
        if self.logf.winfo_ismapped():
            self.logf.pack_forget()
            self.details_btn.configure(text="Show details")
        else:
            self.logf.pack(fill="both", expand=True, pady=(0, 6))
            self.details_btn.configure(text="Hide details")

    def _render_install_state(self):
        if STEPS[self.step][0] != "install":
            return
        done_all = bool(self.result and self.result["ok"])
        for i, (dot, lab) in enumerate(self.stage_rows):
            dot.delete("all")
            if done_all or i < self.stage:
                dot.create_oval(1, 1, 15, 15, fill=C["ok"], outline=C["ok"])
                dot.create_line(4, 8, 7, 11, 12, 5, fill="#ffffff", width=2)
                lab.configure(fg=C["ink"], font=self.f["body"])
            elif i == self.stage and self.installing:
                dot.create_oval(1, 1, 15, 15, fill=C["accent"], outline=C["accent"])
                dot.create_oval(5, 5, 11, 11, fill="#ffffff", outline="#ffffff")
                lab.configure(fg=C["ink"], font=self.f["bold"])
            else:
                dot.create_oval(2, 2, 14, 14, outline=C["line"], width=2)
                lab.configure(fg=C["muted"], font=self.f["body"])
        if self.installing:
            self.bar.configure(mode="indeterminate")
            self.bar.start(12)
            self.start_btn.configure(state="disabled", text="Installing...")
        else:
            self.bar.stop()
            self.bar.configure(mode="determinate", value=100 if done_all else 0)
            self.start_btn.configure(state="normal", text="Try again" if (self.install_error or self.result) else "Start the installation")
            if done_all:
                self.start_btn.pack_forget()   # nothing left to start
        self.activity_label.configure(text=self.activity)
        for w in self.banner_box.winfo_children():
            w.destroy()
        if done_all:
            self.note(self.banner_box, "ok", "Installation complete: all checks passed. Your passwords and the setup sheet are "
                                             f"saved in {self.out_dir}. Opening the phone setup page...")
        elif self.install_error:
            self.note(self.banner_box, "bad", self.install_error[0].upper() + self.install_error[1:])
        elif self.banner:
            self.note(self.banner_box, self.banner[0], self.banner[1])
        self.update_nav()

    def _log_insert(self, line):
        tag = None
        low = line.lower()
        if line.startswith("== "):
            tag = "step"
        elif low.startswith(("error", "fail")) or "warning" in low:
            tag = "err"
        elif tip_text(line):
            tag = "tip"
        elif line.startswith(("PASS", "SETUP COMPLETE")):
            tag = "ok"
        self.log.insert("end", line + "\n", tag)

    def start_install(self):
        if self.installing:
            return
        self.installing, self.result, self.install_error, self.banner = True, None, None, None
        self.stage, self.activity = 0, "Starting..."
        self.log_lines = []
        if self.log.winfo_exists():
            self.log.configure(state="normal")
            self.log.delete("1.0", "end")
            self.log.configure(state="disabled")
        a = self.answers()
        answers = kept_answers(a["COUNTRY"]) if self.kept() else a
        out_dir = self.out_dir

        def work():
            try:
                host = self.core.find_router() or self.router["host"]
                if not host:
                    raise SetupError("the router is not found any more. Check the network cable, then try again.")
                result = self.core.install(host, answers, out_dir, lambda line: self.post("log", line),
                                           lambda n: self.post("stage", n), lambda msg: self.post("notify", msg))
                self.post("installed", result, None)
            except SetupError as e:
                self.post("installed", None, str(e))
            except Exception as e:
                self.post("installed", None, f"unexpected error: {e}")
        self._render_install_state()
        threading.Thread(target=work, daemon=True).start()

    def on_log(self, line):
        self.log_lines.append(line)
        if line.startswith("== "):
            self.activity = line[3:]
        advice = tip_text(line)
        if advice and (self.banner is None or self.banner[0] == "info"):
            self.banner = ("info", "Tip: " + advice)   # the latest tip stays visible above the log
            if STEPS[self.step][0] == "install":
                self._render_install_state()
        if STEPS[self.step][0] == "install" and self.log.winfo_exists():
            self.log.configure(state="normal")
            self._log_insert(line)
            self.log.see("end")
            self.log.configure(state="disabled")
            self.activity_label.configure(text=self.activity)

    def on_stage(self, n):
        self.stage = n
        self.activity = ["Downloading the setup to the router", f"Waiting for the router at {TARGET_IP}",
                         "Installing on the router", "Checking"][n]
        self.banner = None if n != 1 else self.banner
        self._render_install_state()

    def on_notify(self, msg):
        self.on_log(msg)
        if "Unplug" in msg:
            self.banner = ("warn", "Unplug this computer's network cable from the router, wait 5 seconds and plug it back in. "
                                   "The installation continues by itself.")
            self._render_install_state()

    def on_installed(self, result, error):
        self.installing = False
        self.result = result
        if result and result["host"]:
            self.router.update(host=result["host"])
        if result and not result["ok"]:
            error = failure_message(result)
        self.install_error = error
        if result and result["ok"]:
            self.stage = len(INSTALL_STAGES)
            self.activity = "Done."
            found = parse_summary(result.get("summary_text", ""))
            self.final = dict(self.answers(), **found) if not self.kept() else found
            if self.final.get("ROOT_PASSWORD"):
                self.router_password.set(self.final["ROOT_PASSWORD"])
            self.banner = ("ok", "Installed and checked. Opening the phone setup page...")
        else:
            self.activity = "Stopped."
        self._render_install_state()
        if result and result["ok"]:
            self.root.after(2500, self._auto_to_phones)
        elif STEPS[self.step][0] == "install":
            self.root.bell()

    def _auto_to_phones(self):
        """The installation is done: on to the phones, which opens the coin box's phone setup page by itself."""
        if STEPS[self.step][0] == "install" and self.result and self.result["ok"]:
            self.show(self.step + 1)

    # -- 6. phones ---------------------------------------------------------------------------------------------------------
    def passwords(self):
        """What the installed system uses (from the router's summary): the names and passwords."""
        return dict(self.final or {})

    def page_phones(self):
        self.title.configure(text="Set up the rental phones")
        self.subtitle.configure(text="The coin box's phone setup page opens in your browser, already filled in. Each phone is "
                                     "set up once with a QR code: Android installs PisoPhone, locks the phone as a kiosk and "
                                     "connects it to its coin box slot.")
        cols = tk.Frame(self.body, bg=C["bg"])
        cols.pack(fill="both", expand=True)
        cols.columnconfigure(0, weight=3, uniform="p")
        cols.columnconfigure(1, weight=2, uniform="p")
        left, right = tk.Frame(cols, bg=C["bg"]), tk.Frame(cols, bg=C["bg"])
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        right.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        c = self.card(left)
        self.text(c, "For each phone", "h2").pack(fill="x", pady=(0, 6))
        self.numbered(c, ["Factory reset the phone. On its first welcome screen tap the same spot 6 times: a QR reader opens.",
                          "On the page in your browser click Show the setup code and scan it with the phone.",
                          "Accept the phone's screens until PisoPhone shows One last step, and do what it says: on most "
                          "phones switch on Allow display over other apps; an Android Go phone asks for USB (plug it in and "
                          "click Finish over USB on the page).",
                          "The page then opens the coin box's page to pair the slot. The kiosk is running."])
        row = tk.Frame(c, bg=C["card"])
        row.pack(fill="x", pady=(12, 0))
        self.text(row, "Slot", "bold").pack(side="left", padx=(0, 8))
        spin = ttk.Spinbox(row, from_=1, to=6, width=3, textvariable=self.slot, state="readonly")
        spin.pack(side="left")
        self.open_btn = ttk.Button(row, text="Open the setup page", style="Primary.TButton", command=self.open_phone_page)
        self.open_btn.pack(side="left", padx=(12, 0))
        self.prov_box = tk.Frame(left, bg=C["bg"])
        self.prov_box.pack(fill="x")
        self.text(left, "One slot per phone: after a phone is done, choose the next slot and open the page again. A coin box "
                        "has 10 slots.", "small", fg=C["muted"]).pack(fill="x")
        self.status_card = self.card(right)
        self.text(self.status_card, "Coin box", "h2").pack(fill="x", pady=(0, 6))
        self.box_status = self.text(self.status_card, "Checking...", fg=C["muted"])
        self.box_status.pack(fill="x")
        p = self.card(right)
        self.text(p, "Your passwords", "h2").pack(fill="x", pady=(0, 6))
        final = self.passwords()
        for key, label in (("BOX_NEW_ADMIN_PASSWORD", "Coin box admin (user admin)"), ("KIOSK_PASSWORD", f"{KIOSK_SSID} Wi-Fi"),
                           ("ROOT_PASSWORD", "Router (user root)")):
            value = final.get(key)
            box = tk.Frame(p, bg=C["card"])
            box.pack(fill="x", pady=(4, 2))
            self.text(box, label, "small", fg=C["muted"]).pack(fill="x")
            line = tk.Frame(box, bg=C["card"])
            line.pack(fill="x")
            self.text(line, value or "in the saved summary", "mono_big" if value else "small",
                      fg=C["ink"] if value else C["muted"]).pack(side="left", fill="x", expand=True)
            if value:
                ttk.Button(line, text="Copy", style="Small.TButton", command=lambda v=value: self.copy(v)).pack(side="right")
        self._render_prov()
        threading.Thread(target=lambda: self.post("box_up", self.core.box_online()), daemon=True).start()
        if not self.prov_auto:
            self.prov_auto = True
            self.root.after(500, self.open_phone_page)

    def on_box_up(self, up):
        self.box_up = up
        if STEPS[self.step][0] == "phones" and self.box_status.winfo_exists():
            self.box_status.configure(text=f"Online at {BOX_IP}" if up else f"Does not answer at {BOX_IP}. Is it powered on, "
                                      "near the router?", fg=C["ok"] if up else C["bad"])

    def open_phone_page(self):
        """Opens the phone setup page for the chosen slot (the coin box's secret read from its admin page, the Wi-Fi password
        passed in the address's # part)."""
        if self.prov["state"] == "opening":
            return
        self.prov = {"state": "opening", "note": ""}
        self._render_prov()
        slot, passwords = self.slot.get(), self.passwords()

        def work():
            try:
                self.post("prov", *self.core.open_provisioning(passwords, slot))
            except Exception as e:
                self.post("prov", "box", f"the page could not be opened ({e})")
        threading.Thread(target=work, daemon=True).start()

    def on_prov(self, kind, note):
        self.prov = {"state": kind, "note": note}
        if kind == "box" and self.passwords().get("BOX_NEW_ADMIN_PASSWORD"):
            self.copy_temporarily(self.passwords()["BOX_NEW_ADMIN_PASSWORD"])
        self._render_prov()

    def _render_prov(self):
        if STEPS[self.step][0] != "phones" or not self.prov_box.winfo_exists():
            return
        for w in self.prov_box.winfo_children():
            w.destroy()
        state, note = self.prov["state"], self.prov["note"]
        if state == "opening":
            self.note(self.prov_box, "info", "Opening the setup page in your browser...")
        elif state == "setup":
            self.note(self.prov_box, "ok", f"The setup page for slot {self.slot.get()} is open in your browser. Scan its QR "
                                           "code with the phone.")
        elif state == "box":
            self.note(self.prov_box, "warn", f"Opened the coin box's own page ({note}). Log in as admin (the password is "
                                             "copied: paste it), then click Set up a phone.")
        self.open_btn.configure(state="disabled" if state == "opening" else "normal")

    def copy_temporarily(self, value):
        """To the clipboard, and out of it again after a minute (when it is still there)."""
        self.copy(value)
        self.clip_token = value

        def clear():
            try:
                if self.root.winfo_exists() and self.clip_token == value and self.root.clipboard_get() == value:
                    self.root.clipboard_clear()
            except tk.TclError:
                pass
        self.root.after(60000, clear)

    # -- 7. finish ---------------------------------------------------------------------------------------------------------
    def page_finish(self):
        self.title.configure(text="Check that it all works")
        self.subtitle.configure(text="A real coin through the box, and a customer's phone on the public Wi-Fi: when both work, "
                                     "your PisoPhone system is running.")
        cols = tk.Frame(self.body, bg=C["bg"])
        cols.pack(fill="both", expand=True)
        cols.columnconfigure(0, weight=1, uniform="f")
        cols.columnconfigure(1, weight=1, uniform="f")
        left, right = tk.Frame(cols, bg=C["bg"]), tk.Frame(cols, bg=C["bg"])
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        right.grid(row=0, column=1, sticky="nsew", padx=(8, 0))
        c = self.card(left)
        self.text(c, "1. Test a coin", "h2").pack(fill="x", pady=(0, 4))
        self.text(c, "Click the button, then insert one coin when the box beeps (within 30 seconds).", fg=C["muted"]).pack(fill="x")
        self.coin_btn = ttk.Button(c, text="Test a coin", style="Primary.TButton", command=self.start_coin_test)
        self.coin_btn.pack(anchor="w", pady=(10, 0))
        self.coin_box = tk.Frame(c, bg=C["card"])
        self.coin_box.pack(fill="x", pady=(8, 0))
        c2 = self.card(left)
        self.text(c2, "2. Try it as a customer", "h2").pack(fill="x", pady=(0, 4))
        guest = self.passwords().get("GUEST_SSID") or self.vars["GUEST_SSID"].get()
        self.numbered(c2, [f"With any phone join the Wi-Fi {guest}. The payment page opens by itself.",
                           "Pick a plan, tap Insert Coin, insert a coin: it shows at once. Tap Done: you are online."])
        c3 = self.card(right)
        self.text(c3, "Alerts on your phone (optional)", "h2").pack(fill="x", pady=(0, 4))
        self.text(c3, "Telegram messages when the coin box goes offline or the revenue does not add up, plus a daily report. "
                      "You can also do it later.", fg=C["muted"]).pack(fill="x")
        self.tg_btn_main = ttk.Button(c3, text="Set up Telegram alerts", command=self.open_telegram_dialog)
        self.tg_btn_main.pack(anchor="w", pady=(10, 0))
        self.tg_state = self.text(c3, "Connected." if self.telegram["ok"] else "", fg=C["ok"])
        self.tg_state.pack(fill="x", pady=(6, 0))
        f = self.card(right)
        self.text(f, "Saved on this computer", "h2").pack(fill="x", pady=(0, 6))
        saved = (self.result or {}).get("saved") or []
        for label, path in saved:
            ttk.Button(f, text="Open the " + label.split(" (")[0].lower(), command=lambda pth=path: self.core.open_path(pth)).pack(fill="x", pady=2)
        ttk.Button(f, text="Open the folder", command=lambda: self.core.open_path(self.out_dir)).pack(fill="x", pady=2)
        self.text(f, "Keep the summary private: it holds every password. Day to day, the router has: piso-setup status.", "small",
                  fg=C["muted"]).pack(fill="x", pady=(6, 0))
        self._render_coin()

    def start_coin_test(self):
        if self.coin["running"]:
            return
        host, password = self.router["host"] or TARGET_IP, self.router_password.get()
        self.coin = {"running": True, "ok": None, "lines": []}
        self._render_coin()
        self.update_nav()

        def work():
            try:
                self.core.run(host, password, "piso-setup test-coin", lambda line: self.post("coin_line", line))
            except Exception as e:
                self.post("coin_line", f"RESULT: the test could not run ({e})")
            self.post("coin_done")
        threading.Thread(target=work, daemon=True).start()

    def on_coin_line(self, line):
        self.coin["lines"].append(line)
        if line.startswith("RESULT: the box counted"):
            self.coin["ok"] = True
        elif line.startswith("RESULT:"):
            self.coin["ok"] = False
        self._render_coin()

    def on_coin_done(self):
        self.coin["running"] = False
        if self.coin["ok"] is None:
            self.coin["ok"] = False
        self._render_coin()
        self.update_nav()

    def _render_coin(self):
        if STEPS[self.step][0] != "finish" or not self.coin_box.winfo_exists():
            return
        for w in self.coin_box.winfo_children():
            w.destroy()
        c = self.coin
        if c["running"] and not c["lines"]:
            self.text(self.coin_box, "Arming the coin slot...", fg=C["muted"]).pack(fill="x")
        elif c["running"]:
            self.text(self.coin_box, c["lines"][-1], fg=C["accent_dark"]).pack(fill="x")
        elif c["ok"] is True:
            self.note(self.coin_box, "ok", self._sentence([x for x in c["lines"] if x.startswith("RESULT:")][-1][len("RESULT: "):]))
        elif c["ok"] is False:
            result = [x for x in c["lines"] if x.startswith("RESULT:")]
            self.note(self.coin_box, "bad", (self._sentence(result[-1][len("RESULT: "):]) if result else "The test did not finish.")
                      + " You can run it again.")
        self.coin_btn.configure(state="disabled" if c["running"] else "normal",
                                text="Test again" if c["ok"] is not None and not c["running"] else "Test a coin")

    @staticmethod
    def _sentence(text):
        return text[:1].upper() + text[1:]

    # -- Telegram (a dialog on the last page) -----------------------------------------------------------------------------
    def open_telegram_dialog(self):
        if self.tg_win is not None and self.tg_win.winfo_exists():
            self.tg_win.lift()
            return
        win = tk.Toplevel(self.root)
        self.tg_win = win
        win.title("Telegram alerts")
        win.configure(bg=C["bg"])
        win.transient(self.root)
        self.root.update_idletasks()
        win.geometry(f"520x420+{self.root.winfo_rootx() + 240}+{self.root.winfo_rooty() + 100}")
        win.resizable(False, False)
        body = tk.Frame(win, bg=C["bg"], padx=24, pady=20)
        body.pack(fill="both", expand=True)
        self.text(body, "Alerts on your phone", "h1").pack(fill="x")
        c = self.card(body, pady=(10, 10))
        self.numbered(c, ["In Telegram, open @BotFather (button below), send /newbot and choose a name for your bot.",
                          "Copy the token BotFather gives you (it looks like 123456789:AAH...) and paste it here.",
                          "Click Connect, then open your new bot in Telegram and send it any message, like /start."])
        row = tk.Frame(c, bg=C["card"])
        row.pack(fill="x", pady=(8, 0))
        ttk.Button(row, text="Open @BotFather", command=lambda: self.core.browse("https://t.me/BotFather")).pack(side="left")
        form = tk.Frame(c, bg=C["card"])
        form.pack(fill="x", pady=(12, 0))
        self.text(form, "Bot token", "bold").pack(fill="x")
        row2 = tk.Frame(form, bg=C["card"])
        row2.pack(fill="x", pady=(3, 0))
        ttk.Entry(row2, textvariable=self.tg_token, font=self.f["mono_big"]).pack(side="left", fill="x", expand=True)
        self.tg_btn = ttk.Button(row2, text="Connect", style="Primary.TButton", command=self.start_telegram)
        self.tg_btn.pack(side="left", padx=(8, 0))
        self.tg_status = tk.Frame(body, bg=C["bg"])
        self.tg_status.pack(fill="x")
        ttk.Button(body, text="Close", command=win.destroy).pack(side="bottom", anchor="e")
        self._render_telegram()

    def _render_telegram(self):
        win = self.tg_win
        if win is None or not win.winfo_exists():
            return
        for w in self.tg_status.winfo_children():
            w.destroy()
        t = self.telegram
        if t["ok"]:
            self.note(self.tg_status, "ok", "Telegram is connected. A message was sent to your chat; send /help to your bot "
                                            "for the commands.")
        elif t["running"]:
            self.note(self.tg_status, "info", "Now open your new bot in Telegram and send it any message (for example "
                                              "/start). Waiting up to 2 minutes...")
        elif t.get("error"):
            self.note(self.tg_status, "bad", t["error"])
        self.tg_btn.configure(state="disabled" if t["running"] or t["ok"] else "normal")
        if STEPS[self.step][0] == "finish" and self.tg_state.winfo_exists():
            self.tg_state.configure(text="Connected." if t["ok"] else "")
        self.update_nav()

    def start_telegram(self):
        token = self.tg_token.get().strip()
        if not TELEGRAM_TOKEN.fullmatch(token):
            self.telegram["error"] = "That does not look like a bot token (numbers, a colon, then letters, like 123456789:AAH...)."
            return self._render_telegram()
        self.telegram.update(running=True, error=None, lines=[])
        self._render_telegram()
        host, password = self.router["host"] or TARGET_IP, self.router_password.get()
        site = self.passwords().get("SITE_NAME") or self.answers()["SITE_NAME"]

        def confirm(chat):
            return self.ask(lambda: messagebox.askyesno(APP_NAME, f"{chat}\n\nIs that you? Alerts and commands are then "
                                                                  "accepted only from this chat.", parent=self.tg_win or self.root))

        def work():
            try:
                ok = self.core.telegram(host, password, token, site, lambda line: self.post("tg_line", line), confirm)
                self.post("tg_done", ok, None if ok else "Telegram was not connected. Try again, or do it later with "
                                                         "piso-setup telegram on the router.")
            except Exception as e:
                self.post("tg_done", False, f"Telegram was not connected: {e}")
        threading.Thread(target=work, daemon=True).start()

    def on_tg_line(self, line):
        self.telegram["lines"].append(line)

    def on_tg_done(self, ok, error):
        self.telegram.update(running=False, ok=ok, error=error)
        self._render_telegram()


def run_gui(args, core=None):
    _load_tk()
    if os.name == "nt":
        try:   # crisp text on high-resolution screens
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    workdir = tempfile.mkdtemp(prefix="pisophone-")
    try:
        root = tk.Tk()
        Wizard(root, core or Core(args.branch, workdir), args.branch, None if args.out == "." else args.out)
        root.mainloop()
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    return 0


# ---- start ---------------------------------------------------------------------------------------------------------------

def main(argv=None):
    if os.environ.get("PISO_ASKPASS_MODE"):
        # started by ssh as its password helper (Ssh.set_password): the answer is the password, nothing else
        os.write(1, (os.environ.get("PISO_ASKPASS_PW", "") + "\n").encode())
        return 0
    if sys.stdout is not None and hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    p = argparse.ArgumentParser(description="Set up a PisoPhone system: the coin box, the router and the rental phones.")
    p.add_argument("--branch", default=DEFAULT_BRANCH, help=f"the repository branch whose setup files are used (default {DEFAULT_BRANCH})")
    p.add_argument("--cli", action="store_true", help="run in the terminal instead of a window")
    p.add_argument("--update", action="store_true", help="install new software on a router that is set up already (terminal)")
    p.add_argument("--router", help=f"the router's address (default: found by itself, {TARGET_IP} or {FACTORY_IP})")
    p.add_argument("--guest-ssid", help="the public Wi-Fi name (default PisoWiFi)")
    p.add_argument("--site-name", help="the shop / site name (default: the public Wi-Fi name)")
    p.add_argument("--country", default="PH", help="the Wi-Fi country code (default PH)")
    p.add_argument("--yes", action="store_true", help="ask nothing: defaults, generated passwords, no review (terminal)")
    p.add_argument("--out", default=".", help="the folder for the saved summary and setup sheet (default: the current folder; "
                                              "the window uses Documents/PisoPhone)")
    p.add_argument("--no-browser", action="store_true", help="do not open the setup sheet when done (terminal)")
    args = p.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.branch):
        p.error(f"not a branch name: {args.branch}")
    if args.router and not re.fullmatch(r"[A-Za-z0-9.:-]+", args.router):
        p.error(f"not an address: {args.router}")
    args.country = args.country.upper()
    if country_problem(args.country):
        p.error(f"not a country code: {args.country}")
    if not (args.cli or args.update or args.yes or args.router) and gui_available():
        return run_gui(args)
    if not os.environ.get("PISO_SSH") and not shutil.which("ssh"):
        say("ERROR: the ssh command was not found. Windows: Settings > Apps > Optional features > add 'OpenSSH Client'. "
            "Linux: install openssh-client.")
        return 2
    say(APP_NAME)
    say("Before you start: the coin box is flashed (" + site_url(args.branch) + "/flash.html) and powered on near the router,")
    say("the router is factory reset and on, the modem is in its WAN port, and this computer is in a LAN port by cable")
    say("(turn its Wi-Fi off).")
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
