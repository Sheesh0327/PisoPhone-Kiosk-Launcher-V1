#!/usr/bin/env python3
"""Opens the generated test-log page in Chromium with a fake `window.claude` db and checks what a tester does: ticking,
failing with a note, filters, the report, saving and restoring. Run:  python3 tools/checklist/test_page.py   (needs playwright)"""
import glob, json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
tmp = tempfile.mkdtemp()
page = os.path.join(tmp, "log.html")
subprocess.run([sys.executable, os.path.join(HERE, "build.py"), page], check=True, capture_output=True)
checks = failures = 0


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


# a db that keeps its documents in localStorage (so a reload sees them), with the same calls as the real capability
STUB = """
window.claude = { use: async (n) => {
  if (n !== 'db' || window.__NO_DB) return null;
  const key = 'stubdb:';
  const read = p => { try { return JSON.parse(localStorage.getItem(key + p)); } catch (e) { return null; } };
  const deep = (a, b) => { for (const k in b) { const v = b[k]; a[k] = (v && typeof v === 'object' && !Array.isArray(v)) ? deep(a[k] && typeof a[k] === 'object' ? a[k] : {}, v) : v; } return a; };
  window.__writes = [];
  return { doc: p => ({
    get: async () => ({ exists: read(p) !== null, data: () => read(p) }),
    set: async d => { window.__writes.push('set'); localStorage.setItem(key + p, JSON.stringify(d)); },
    update: async d => { const cur = read(p); if (cur === null) throw {code: 'invalid_argument'}; window.__writes.push('update'); localStorage.setItem(key + p, JSON.stringify(deep(cur, d))); },
    onSnapshot: (next) => { setTimeout(() => next({ exists: read(p) !== null, data: () => read(p) }), 20); return () => {}; }
  }) };
} };
"""

from playwright.sync_api import sync_playwright  # noqa: E402

chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") + glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome")) if c), None)
with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()
    ctx = b.new_context(viewport={"width": 400, "height": 800})
    errors = []
    p = ctx.new_page()
    p.on("pageerror", lambda e: errors.append(str(e)))
    p.add_init_script(STUB)
    url = "file://" + page

    def stored():
        raw = p.evaluate("localStorage.getItem('stubdb:run/main')")
        return json.loads(raw) if raw else None

    p.goto(url)
    p.wait_for_selector(".item")
    check(p.locator(".item").count() == 62, "62 tests are listed")
    check("62" in p.inner_text("#tally") and "left" in p.inner_text("#tally"), "all left at the start: " + p.inner_text("#tally"))
    check(p.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), "no sideways scroll at phone width")

    p.click("#it-B1 [data-act=p]")
    p.wait_for_timeout(200)
    check(p.get_attribute("#it-B1", "data-s") == "p" and "1 pass" in p.inner_text("#tally").replace("\n", " "), "Pass marks the test and the tally follows: " + p.inner_text("#tally"))
    check(stored() and stored()["items"]["B1"]["s"] == "p", "and it is saved in the shared record")
    p.click("#it-B1 [data-act=p]")
    p.wait_for_timeout(200)
    check(p.get_attribute("#it-B1", "data-s") is None and stored()["items"]["B1"]["s"] is None, "tapping Pass again clears it")

    p.click("#it-C2 [data-act=f]")
    check(p.is_visible("#note-C2") and p.is_visible("#it-C2 .sev"), "Fail opens the note and the severity")
    check(p.evaluate("document.activeElement.id") == "note-C2", "and puts the cursor in the note")
    p.fill("#note-C2", "Two coins credited 6 minutes, expected 5")
    p.click("#it-C2 [data-sev=blocker]")
    p.wait_for_timeout(1200)
    st = stored()["items"]["C2"]
    check(st["s"] == "f" and st["sev"] == "blocker" and st["note"].startswith("Two coins"), "the note and severity are saved: " + json.dumps(st))
    p.click("#it-S2 [data-act=k]")
    p.fill("#note-S2", "no packet capture")
    p.click("#it-F1 [data-act=p]")
    p.click("#it-F2 [data-act=p]")
    p.wait_for_timeout(900)

    p.click(".tab[data-pass='1']")
    shown = p.locator(".item:not([hidden])").count()
    check(shown == 23 and p.is_hidden("#it-B4"), f"the Pass 1 tab shows only pass 1 tests ({shown})")
    p.check("#hideDone")
    check(p.locator(".item:not([hidden])").count() == 20 and p.is_hidden("#it-F1"), "Hide done hides the finished ones")
    p.uncheck("#hideDone")
    p.click(".tab[data-pass='0']")

    p.fill("#setup-fw", "3.0.301 esp32-c3-dev")
    p.click("#makeReport")
    rep = p.input_value("#reportText")
    check("Passed 2, failed 1, skipped 1, not tested 58 (of 62)" in rep and "firmware version and board: 3.0.301 esp32-c3-dev".lower() in rep.lower(), "the report has the counts and the setup: " + rep[:300])
    check("C2 [blocker]" in rep and "| 1 | C2 " in rep and "| blocker | | | Two coins credited" in rep and "S2: no packet capture" in rep, "the failure is there as an Issue log row")

    # a reload brings everything back
    p.reload()
    p.wait_for_selector(".item")
    p.wait_for_timeout(300)
    check(p.get_attribute("#it-F1", "data-s") == "p" and p.input_value("#note-C2").startswith("Two coins") and p.input_value("#setup-fw").startswith("3.0.301"), "after a reload the results, notes and setup are back")
    check(p.locator("#tally .f b").inner_text() == "1", "and the tally")

    p.evaluate("window.scrollTo(0, 0)")
    p.click("#it-C2 [data-act=f]"); p.click("#it-C2 [data-act=f]")   # (clear it and fail it again: shows the open note on a screenshot)
    p.wait_for_timeout(300)
    p.locator("#it-C2").scroll_into_view_if_needed()
    p.screenshot(path=os.path.join(tmp, "phone-fail.png"), full_page=False)
    p.evaluate("window.scrollTo(0, 0)")
    p.screenshot(path=os.path.join(tmp, "phone-light.png"), full_page=False)
    p.emulate_media(color_scheme="dark")
    p.screenshot(path=os.path.join(tmp, "phone-dark.png"), full_page=False)
    p.emulate_media(color_scheme="light")

    # clear all needs a second tap
    p.click("#clearAll")
    check(p.get_attribute("#it-F1", "data-s") == "p", "one tap on Clear all changes nothing")
    p.click("#clearAll")
    p.wait_for_timeout(300)
    check(p.get_attribute("#it-F1", "data-s") is None and "62" in p.inner_text("#tally"), "the second tap erases everything")

    # without the shared record: results stay on this device
    p2 = ctx.new_page()
    p2.on("pageerror", lambda e: errors.append(str(e)))
    p2.add_init_script("window.__NO_DB = true;" + STUB)
    p2.goto(url)
    p2.wait_for_selector(".item")
    p2.wait_for_timeout(300)
    check("this device only" in p2.inner_text("#status"), "without the shared record it says results stay on this device")
    p2.click("#it-B2 [data-act=p]")
    p2.reload()
    p2.wait_for_selector(".item")
    check(p2.get_attribute("#it-B2", "data-s") == "p", "and they still survive a reload")
    # a wide screen
    p3 = b.new_context(viewport={"width": 1100, "height": 800}).new_page()
    p3.add_init_script(STUB)
    p3.goto(url)
    p3.wait_for_selector(".item")
    p3.screenshot(path=os.path.join(tmp, "wide.png"))
    check(not errors, "no script errors: " + "; ".join(errors))
    b.close()

print(f"{checks} checks, {failures} failures; screenshots in {tmp}")
sys.exit(1 if failures else 0)
