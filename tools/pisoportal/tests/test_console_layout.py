#!/usr/bin/env python3
"""Feature 053, the console's layout: a grouped sidebar on computers and tablets of 900 px and more, a menu button and a side panel on phones,
the current screen marked and named in the top bar, Esc and focus. What each screen shows is tested by the other console tests.

The router is built the way the brand test builds it (cargo feature test-owner-key) so both share PISO_TEST_TARGET_DIR.

CHROME=/path/to/chromium python3 test_console_layout.py"""
import glob
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed (browser test)")
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
tmp = tempfile.mkdtemp(prefix="console-layout-test-")
target = os.environ.get("PISO_TEST_TARGET_DIR") or os.path.join(tmp, "target")
built = subprocess.run(["cargo", "build", "--release", "--locked", "--features", "test-owner-key", "--target-dir", target], cwd=os.path.join(ROOT, "tools", "pisoportal"), capture_output=True, text=True, check=False)
if built.returncode != 0:
    print(built.stdout[-800:] + built.stderr[-1500:])
    print("FAIL: the test build of the router program failed")
    sys.exit(1)
os.environ["PISOPORTAL"] = os.path.join(target, "release", "pisoportal")
from harness import Env, check, finish

CP = 18720
SCREENS = [("over", "Overview"), ("cli", "Customers"), ("ph", "Rental phones"), ("sales", "Sales"), ("vch", "Vouchers"), ("brand", "Branding"), ("set", "Settings")]
GROUPS = [("Overview", ["over", "sales"]), ("Management", ["cli", "ph", "vch"]), ("System", ["brand", "set"])]
env = Env(offset=215)
pathlib.Path(f"{env.tmp}/ubus").write_text('#!/bin/sh\ncase "$4" in *\\"password\\":\\"router-pass\\"*) echo \'{ "ubus_rpc_session": "abc" }\';; *) echo \'{}\';; esac\n')
os.chmod(f"{env.tmp}/ubus", 0o755)
pathlib.Path(f"{env.tmp}/setup.conf").write_text("BOX_ADMIN_PASS='box-pass-123'\n")
env.extra.update(CONSOLE_PORT=CP, CONSOLE_BIND="127.0.0.1", UBUS=f"{env.tmp}/ubus", PISO_SETUP_CONF=f"{env.tmp}/setup.conf")


def sideways(pg):
    return pg.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")


def sign_in(br, w, h, scheme="light"):
    ctx = br.new_context(viewport={"width": w, "height": h}, color_scheme=scheme)
    pg = ctx.new_page()
    pg.goto(f"http://127.0.0.1:{CP}/")
    pg.wait_for_selector("#login:not(.hide)", timeout=8000)
    check(pg.locator("#side").is_hidden() and pg.locator("#mn").is_hidden(), f"{w} px: before signing in there is no sidebar and no menu button")
    check(sideways(pg) <= 0, f"{w} px: the sign-in card does not scroll sideways")
    pg.fill("#pw", "router-pass")
    pg.click("#gobtn")
    pg.wait_for_selector("#app:not(.hide)", timeout=8000)
    return ctx, pg


try:
    check(env.start(), "the portal starts")
    with sync_playwright() as pw:
        chrome = os.environ.get("CHROME") or next((c for c in [shutil.which(x) for x in ("google-chrome", "chromium", "chromium-browser")] + glob.glob("/opt/pw-browsers/chromium-*/chrome-linux*/chrome") if c), None)
        br = pw.chromium.launch(executable_path=chrome, args=["--no-sandbox"]) if chrome else pw.chromium.launch()

        # ---- a computer: the sidebar is always there ----------------------------------------------------------------------------------------
        ctx, pg = sign_in(br, 1280, 800)
        check(pg.locator("#side").is_visible() and pg.locator("#mn").is_hidden(), "1280 px: the sidebar is shown and there is no menu button")
        heads = pg.locator("#side .gh").all_text_contents()
        check(heads == [g for g, _ in GROUPS], f"1280 px: the groups are Overview, Management, System, in that order: {heads}")
        order = pg.eval_on_selector_all("#tabs button", "bs => bs.map(b => b.dataset.t)")
        check(order == ["over", "sales", "cli", "ph", "vch", "brand", "set"], f"1280 px: the screens follow their groups: {order}")
        for g, members in GROUPS:
            got = pg.evaluate("(g) => { const h=[...document.querySelectorAll('#side .gh')].find(x=>x.textContent===g); const r=[]; if (!h) return r; for (let e=h.nextElementSibling; e && !e.classList.contains('gh'); e=e.nextElementSibling) if (e.dataset.t) r.push(e.dataset.t); return r }", g)
            check(got == members, f"1280 px: under {g}: {got}")
        check(pg.get_attribute('#tabs button[data-t=over]', 'aria-current') == "page" and pg.inner_text("#pt") == "Overview", "1280 px: Overview is marked and named in the top bar")
        for t, label in SCREENS:
            pg.click(f'#tabs button[data-t="{t}"]')
            pg.wait_for_timeout(300)
            shown = pg.eval_on_selector_all("main section", "ss => ss.filter(s => !s.classList.contains('hide')).map(s => s.id)")
            marked = pg.eval_on_selector_all("#tabs button[aria-current]", "bs => bs.map(b => b.dataset.t)")
            check(shown == [f"t-{t}"] and marked == [t] and pg.get_attribute(f'#tabs button[data-t="{t}"]', "aria-current") == "page", f"1280 px: {label}: one screen shown, one item marked: {shown} {marked}")
            check(pg.inner_text("#pt") == pg.inner_text(f'#tabs button[data-t="{t}"]').strip(), f"1280 px: {label}: the top bar names the screen: {pg.inner_text('#pt')!r}")
            h = pg.locator(f'#tabs button[data-t="{t}"]').bounding_box()["height"]
            check(h >= 40, f"1280 px: {label}: the sidebar item is {h:.0f} px tall (40 or more)")
            check(sideways(pg) <= 0, f"1280 px: {label}: no sideways scrolling")
        for sel, name in (("#th", "the light/dark button"), ("#adv", "the Advanced link"), ("#out", "Sign out")):
            check(pg.locator(sel).is_visible(), f"1280 px: {name} is in the top bar")
        pg.click("#out")
        pg.wait_for_selector("#login:not(.hide)", timeout=5000)
        check(pg.locator("#side").is_hidden(), "1280 px: signed out, the sidebar is gone again")
        ctx.close()

        # ---- a phone (and a tablet): a menu button and a panel -------------------------------------------------------------------------------
        for w, h in ((390, 844), (768, 1024)):
            ctx, pg = sign_in(br, w, h)
            check(pg.locator("#mn").is_visible() and pg.locator("#side").is_hidden() and pg.get_attribute("#mn", "aria-expanded") == "false", f"{w} px: the menu button is shown and the panel is closed")
            check(pg.locator("#mn").bounding_box()["height"] >= 40, f"{w} px: the menu button is 40 px or more")
            check(pg.inner_text("#pt") == "Overview", f"{w} px: the top bar names the screen")
            # the closed panel is not in the keyboard's way
            pg.focus("#mn")
            pg.keyboard.press("Tab")
            inside = pg.evaluate("!!document.activeElement.closest('#side')")
            check(not inside, f"{w} px: Tab from the menu button does not walk into the closed panel")
            pg.click("#mn")
            pg.wait_for_timeout(350)
            check(pg.locator("#side").is_visible() and pg.get_attribute("#mn", "aria-expanded") == "true", f"{w} px: the menu button opens the panel")
            hs = pg.eval_on_selector_all("#tabs button", "bs => bs.map(b => b.getBoundingClientRect().height)")
            check(min(hs) >= 40, f"{w} px: every item in the panel is 40 px or more: {min(hs):.0f}")
            pg.click('#tabs button[data-t="cli"]')
            pg.wait_for_timeout(350)
            check(pg.locator("#side").is_hidden() and pg.inner_text("#pt") == "Customers" and pg.locator("#t-cli").is_visible(), f"{w} px: choosing a screen closes the panel and shows it")
            pg.click("#mn")
            pg.wait_for_timeout(350)
            pg.mouse.click(w - 10, h - 10)
            pg.wait_for_timeout(350)
            check(pg.locator("#side").is_hidden() and pg.get_attribute("#mn", "aria-expanded") == "false", f"{w} px: a tap outside closes the panel")
            pg.click("#mn")
            pg.wait_for_timeout(350)
            pg.keyboard.press("Escape")
            pg.wait_for_timeout(350)
            check(pg.locator("#side").is_hidden() and pg.evaluate("document.activeElement.id") == "mn", f"{w} px: Esc closes the panel and focus goes back to the menu button")
            for t, label in SCREENS:
                pg.click("#mn")
                pg.wait_for_timeout(300)
                pg.click(f'#tabs button[data-t="{t}"]')
                pg.wait_for_timeout(500)
                check(sideways(pg) <= 0, f"{w} px: {label}: no sideways scrolling")
            ctx.close()
        br.close()
finally:
    env.close()
    shutil.rmtree(tmp, ignore_errors=True)
finish()
