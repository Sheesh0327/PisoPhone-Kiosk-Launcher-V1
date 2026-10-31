#!/usr/bin/env python3
"""The console's Smooth line card in a real browser (headless Chromium), feature 056 groups 5 and 5d: greyed out with its reason and Install now where the router has no
SQM, Test my line fills the two boxes (and says when the router itself was the limit), the live "will limit to" line, Save, a bad number, what is typed surviving the
page's refresh, and the card at phone width. The router side is a fake piso-setup."""
import glob, os, shutil, sys, time
from harness import *

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

CP = 18820
env = Env(offset=240)
tmp = env.tmp
RESULT = f"{tmp}/piso-speedtest.json"
open(f"{tmp}/ubus", "w").write('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{tmp}/ubus", 0o755)
open(f"{tmp}/setup.conf", "w").write("BOX_ADMIN_PASS='box-pass-123'\n")
D = env.data
# a fake piso-setup: apply writes the status file the way the real one does, install makes SQM "installed", test leaves the result the file "mode" asks for
open(f"{tmp}/piso-setup", "w").write(f'''#!/bin/sh
echo "$*" >> "{tmp}/setup-calls"
get() {{ sed -n "s/^$1=//p" "{D}/settings.txt" 2>/dev/null | tail -n 1; }}
case "$1 $2" in
  "sqm apply") sleep 1
    if [ "$(get SQM_ON)" = 1 ]; then
      dk=$(awk -v m="$(get SQM_DOWN_MBPS)" -v p="$(get SQM_PERCENT)" 'BEGIN {{ printf "%d", m * 1000 * (p ? p : 95) / 100 }}'); uk=$(awk -v m="$(get SQM_UP_MBPS)" -v p="$(get SQM_PERCENT)" 'BEGIN {{ printf "%d", m * 1000 * (p ? p : 95) / 100 }}')
      printf 'installed|1\\nqdisc|cake\\nactive|1\\nwan|eth1\\nlimit_down_kbps|%s\\nlimit_up_kbps|%s\\nreason|\\n' "$dk" "$uk" > "{D}/sqm.txt"
    else printf 'installed|1\\nqdisc|cake\\nactive|0\\nwan|\\nlimit_down_kbps|\\nlimit_up_kbps|\\nreason|switched off\\n' > "{D}/sqm.txt"; fi ;;
  "sqm install") sleep 2; printf 'installed|1\\nqdisc|cake\\nactive|0\\nwan|\\nlimit_down_kbps|\\nlimit_up_kbps|\\nreason|switched off\\n' > "{D}/sqm.txt" ;;
  "sqm test") sleep 2; now=$(date +%s)
    case "$(cat "{tmp}/mode" 2>/dev/null)" in
      fail) echo '{{"state":"failed","down":null,"up":null,"host":"","cpu":3,"cpu_limited":false,"time":'$now',"reason":"no speed test server answered (is the router online?)"}}' > "{RESULT}"; exit 1 ;;
      cpu) echo '{{"state":"done","down":312.5,"up":88.0,"host":"speed.cloudflare.com","cpu":97,"cpu_limited":true,"time":'$now',"reason":""}}' > "{RESULT}" ;;
      *) echo '{{"state":"done","down":87.4,"up":18.2,"host":"speed.cloudflare.com","cpu":31,"cpu_limited":false,"time":'$now',"reason":""}}' > "{RESULT}" ;;
    esac ;;
esac
exit 0
''')
os.chmod(f"{tmp}/piso-setup", 0o755)
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{tmp}/ubus", PISO_SETUP_CONF=f"{tmp}/setup.conf", PISO_SETUP_BIN=f"{tmp}/piso-setup",
                 SPEEDTEST_FILE=RESULT, SPEEDTEST_GAP_SECONDS="2")


def settings():
    return open(f"{D}/settings.txt").read() if os.path.exists(f"{D}/settings.txt") else ""


try:
    check(env.start(), "the portal starts")
    chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
    with sync_playwright() as pw:
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"])
        pg = br.new_page(viewport={"width": 1280, "height": 900})
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.goto(f"http://127.0.0.1:{CP}/")
        pg.wait_for_selector("#login:not(.hide)", timeout=8000)
        pg.fill("#pw", "router-pass")
        pg.click("#gobtn")
        pg.wait_for_selector("#app:not(.hide)", timeout=8000)
        pg.click('#tabs button[data-t="set"]')
        pg.wait_for_selector("#sqc", timeout=8000)

        # ---- a router without SQM: greyed out, with its reason and Install now ----------------------------------------------------------------------------------
        pg.wait_for_function("document.getElementById('sqn').textContent.length > 0", timeout=8000)
        check("off" in (pg.get_attribute("#sqc", "class") or "").split(), "no SQM: the card is greyed out")
        check(all(pg.is_disabled(f"#{i}") for i in ("sqd", "squ", "sqp", "sqs", "sqk", "sqt")), "and its fields and buttons are off")
        check(pg.is_visible("#sqi") and pg.is_enabled("#sqi"), "but Install now is offered, because trying again could work")
        check("not" in pg.text_content("#sqn").lower(), "and the reason is in plain words: " + pg.text_content("#sqn"))

        pg.click("#sqi")
        pg.wait_for_function("document.getElementById('sqn').textContent.indexOf('Installing') >= 0", timeout=6000)
        check(pg.is_disabled("#sqi"), "while it installs the button is off and the card says so: " + pg.text_content("#sqn"))
        pg.wait_for_function("document.getElementById('sqc').className.indexOf('off') < 0", timeout=15000)
        check(not pg.is_visible("#sqi") and pg.is_enabled("#sqd") and pg.is_enabled("#sqk"), "installed: the card is on and Install now is gone")
        check("off" in pg.text_content("#sqn").lower() and "Line speed not set" in pg.text_content("#sql"), f"smoothing is off and no speed is set: {pg.text_content('#sqn')} / {pg.text_content('#sql')}")
        check(pg.input_value("#sqp") == "95" and pg.input_value("#sqs") == "off", "the margin starts at 95% and the switch at Off")

        # ---- Test my line fills the boxes and saves nothing ----------------------------------------------------------------------------------------------------
        check(pg.is_enabled("#sqt"), "the test button is on on a quiet router")
        pg.click("#sqt")
        pg.wait_for_function("document.getElementById('sqb').textContent.indexOf('Testing') >= 0", timeout=6000)
        check(pg.is_disabled("#sqt"), "while it runs the button is off")
        pg.wait_for_function("document.getElementById('sqd').value !== ''", timeout=15000)
        check(pg.input_value("#sqd") == "87.4" and pg.input_value("#squ") == "18.2", f"the boxes are filled with the measured speeds: {pg.input_value('#sqd')} / {pg.input_value('#squ')}")
        check("87.4" in pg.text_content("#sqb") and "Save" in pg.text_content("#sqb"), "and the card says what was measured and that Save is next: " + pg.text_content("#sqb"))
        check("83.03" in pg.text_content("#sql") and "17.29" in pg.text_content("#sql") and "95%" in pg.text_content("#sql"), "the live line says what SQM will use: " + pg.text_content("#sql"))
        check("SQM_DOWN_MBPS" not in settings(), "nothing is saved until the owner presses Save")

        # ---- typed numbers survive the refresh; a bad number is refused, nothing is saved ---------------------------------------------------------------------------
        pg.fill("#sqd", "70")
        pg.wait_for_timeout(4800)
        check(pg.input_value("#sqd") == "70" and "58.5" not in pg.text_content("#sql") and "66.5" in pg.text_content("#sql"), "what is typed is not overwritten by the page's refresh, and the live line follows: " + pg.text_content("#sql"))
        pg.fill("#sqd", "abc")
        pg.click("#sqk")
        pg.wait_for_function("document.getElementById('sqm2').textContent.length > 0", timeout=4000)
        check("number" in pg.text_content("#sqm2").lower() and "SQM_DOWN_MBPS" not in settings(), "a bad number is refused with the reason, nothing is saved: " + pg.text_content("#sqm2"))
        pg.fill("#sqp", "40")
        pg.fill("#sqd", "87.4")
        pg.click("#sqk")
        pg.wait_for_function("document.getElementById('sqm2').textContent.indexOf('50') >= 0", timeout=4000)
        check("SQM_DOWN_MBPS" not in settings(), "a margin outside 50 to 100 is refused too: " + pg.text_content("#sqm2"))
        pg.fill("#sqp", "95")

        # ---- Save, off: the speeds are kept; Save, on: the router limits the line -------------------------------------------------------------------------------------
        pg.click("#sqk")
        pg.wait_for_function("document.getElementById('sqm2').textContent.indexOf('Saved') === 0", timeout=4000)
        check("SQM_DOWN_MBPS=87.4\n" in settings() and "SQM_UP_MBPS=18.2\n" in settings() and "SQM_ON=0\n" in settings(), "saved with the switch Off: " + settings())
        pg.select_option("#sqs", "on")
        pg.click("#sqk")
        pg.wait_for_function("document.getElementById('sqn').textContent.indexOf('Smoothing is on') >= 0", timeout=10000)
        check("83.03 Mbps down" in pg.text_content("#sqn") and "17.29 Mbps up" in pg.text_content("#sqn"), "on: the card says what the router limits the line to: " + pg.text_content("#sqn"))
        check("SQM_ON=1\n" in settings(), "and the switch is kept")

        # ---- the router itself was the limit: the number is shown, not offered as the line speed ------------------------------------------------------------------------
        open(f"{tmp}/mode", "w").write("cpu")
        pg.wait_for_timeout(2200)
        pg.click("#sqt")
        pg.wait_for_function("document.getElementById('sqb').textContent.indexOf('may be the limit') >= 0", timeout=20000)
        check(pg.input_value("#sqd") == "87.4" and "97%" in pg.text_content("#sqb"), "a busy router's number is not put in the boxes, and the warning names the processor load: " + pg.text_content("#sqb"))

        # ---- the test cannot reach any host --------------------------------------------------------------------------------------------------------------------------
        open(f"{tmp}/mode", "w").write("fail")
        pg.wait_for_timeout(2200)
        pg.click("#sqt")
        pg.wait_for_function("document.getElementById('sqb').textContent.indexOf('answered') >= 0", timeout=20000)
        check(pg.input_value("#sqd") == "87.4" and pg.input_value("#squ") == "18.2", "a failed test says why and leaves the boxes as they were: " + pg.text_content("#sqb"))

        # ---- a customer is online: the button is off with the reason ---------------------------------------------------------------------------------------------------
        env.nds_client("aa:bb:cc:00:00:01", STATE="Authenticated", SESSION_END=int(time.time()) + 900, DL=100, UL=10)
        pg.wait_for_function("document.getElementById('sqt').disabled", timeout=8000)
        check("online" in pg.text_content("#sqb").lower(), "a customer online: Test my line is off and says why: " + pg.text_content("#sqb"))

        # ---- a phone: no sideways scrolling, buttons big enough to touch, dark mode ----------------------------------------------------------------------------------
        for scheme in ("light", "dark"):
            ph = br.new_page(viewport={"width": 360, "height": 740}, color_scheme=scheme)
            ph.on("pageerror", lambda e: errors.append(str(e)))
            ph.goto(f"http://127.0.0.1:{CP}/")
            ph.wait_for_selector("#login:not(.hide)", timeout=8000)
            ph.fill("#pw", "router-pass")
            ph.click("#gobtn")
            ph.wait_for_selector("#app:not(.hide)", timeout=8000)
            ph.evaluate("document.querySelector('#tabs button[data-t=\"set\"]') ? document.querySelector('#tabs button[data-t=\"set\"]').click() : 0")
            ph.wait_for_selector("#sqc", state="attached", timeout=8000)
            ph.wait_for_function("document.getElementById('sqn').textContent.length > 0", timeout=8000)
            check(ph.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), f"{scheme}, 360 px wide: no sideways scrolling")
            tall = ph.evaluate("['sqk','sqt'].every(function(i){return document.getElementById(i).getBoundingClientRect().height >= 40})")
            check(tall, f"{scheme}, 360 px wide: the buttons are at least 40 px tall")
            ph.close()
        check(errors == [], f"no script errors: {errors}")
        br.close()
finally:
    env.close()
finish()
