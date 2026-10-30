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
import time

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
from harness import Env, check, finish, legacy_batch

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


now = int(time.time())
rows = ""
for d in range(30):
    for i, (plan, p, m, k) in enumerate([("hyper", 5, 30, "new"), ("hyper", 10, 60, "new"), ("endurance", 20, 1440, "new"), ("hyper", 10, 60, "vbuy")][: 2 + d % 3]):
        rows += f"{now - d * 86400 - i * 3600},{plan},{p},{m},{k},x\n"
open(f"{env.data}/revenue.csv", "w").write(rows)
env.set_box(wifi_counter=True, lifetime_base=1247)

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

        # ---- the Overview, Customers and Sales screens (group 2) -----------------------------------------------------------------------------
        def open_screen(pg, w, t):
            if w < 900:
                pg.click("#mn")
                pg.wait_for_timeout(300)
            pg.click(f'#tabs button[data-t="{t}"]')
            pg.wait_for_timeout(900)

        for w, h in ((1280, 800), (390, 844)):
            for f in glob.glob(f"{env.nds}/*"):
                if len(os.path.basename(f)) == 12:  # (a customer file is the MAC without colons; calls.log is not one)
                    os.remove(f)
            ctx, pg = sign_in(br, w, h)
            open_screen(pg, w, "cli")
            e = pg.locator("#cl .empty")
            check(e.count() == 1 and "Nobody is connected right now" in e.inner_text() and e.is_visible(), f"{w} px: Customers: with nobody connected there is a clear empty state")
            for n, (st, left) in enumerate([("Authenticated", 1800), ("Authenticated", 5400), ("Authenticated", 240), ("Preauthenticated", 0)], start=1):
                env.nds_client(f"aa:bb:cc:00:00:0{n}", STATE=st, SESSION_END=int(time.time()) + left, DL=2048 * n, UL=512 * n)
            open_screen(pg, w, "over")
            tiles = pg.locator("#tiles .card")
            check(tiles.count() >= 5 and pg.locator("#tiles .card .k svg").count() == tiles.count(), f"{w} px: Overview: every tile has an icon ({pg.locator('#tiles .card .k svg').count()} of {tiles.count()})")
            check(pg.locator("#tiles .card .k svg").first.bounding_box()["width"] <= 20, f"{w} px: Overview: the icons are small")
            xs = pg.eval_on_selector_all("#tiles .card", "cs => cs.map(c => Math.round(c.getBoundingClientRect().width))")
            check(min(xs) >= (150 if w < 900 else 190), f"{w} px: Overview: tiles are wide enough to read: {xs}")
            hw = pg.evaluate("[document.querySelector('#t-over .hero').getBoundingClientRect().width, document.querySelector('#t-over #tiles').getBoundingClientRect().width]")
            check(abs(hw[0] - hw[1]) <= 2, f"{w} px: Overview: the money card is as wide as the tiles: {hw}")
            check(sideways(pg) <= 0, f"{w} px: Overview: no sideways scrolling")
            open_screen(pg, w, "cli")
            pg.wait_for_selector("#cl button.b", timeout=8000)
            check(pg.locator("#cl .empty").count() == 0 and pg.locator("#cl button.b").count() == 3, f"{w} px: Customers: three customers online, each with Disconnect")
            bb = pg.eval_on_selector_all("#cl button.b", "bs => bs.map(b => { const r=b.getBoundingClientRect(); return [Math.round(r.right), Math.round(r.height)] })")
            check(all(r <= w for r, _ in bb) and all(hh >= (40 if w < 900 else 32) for _, hh in bb), f"{w} px: Customers: every Disconnect button is fully on the screen and tall enough: {bb}")
            check(pg.inner_text("#clc") != "" , f"{w} px: Customers: the card says how many are online: {pg.inner_text('#clc')!r}")
            if w < 900:
                shown = pg.eval_on_selector_all("#cl tr:first-child td", "ts => ts.filter(t => t.offsetParent).length")
                check(shown == 4, f"{w} px: Customers: a phone row shows the name, the plan, the time left and Disconnect, nothing else: {shown} cells")
            check(sideways(pg) <= 0, f"{w} px: Customers: no sideways scrolling")
            check(pg.evaluate("getComputedStyle(document.querySelector('#t-cli thead th')).position") == "sticky", f"{w} px: Customers: the table header stays in view")
            for days in ("7", "30", "90"):
                open_screen(pg, w, "sales")
                pg.select_option("#rng", days)
                pg.wait_for_timeout(900)
                labels = pg.eval_on_selector_all("#sd div", "ds => ds.filter(d => d.textContent).map(d => { const r = d.getBoundingClientRect(), t = document.createRange(); t.selectNodeContents(d); const q = t.getBoundingClientRect(); return [Math.round(q.left), Math.round(q.right)] })")
                card = pg.eval_on_selector(".days", "d => { const r = d.closest('.card').getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right)] }")
                inside = all(a >= card[0] and b <= card[1] for a, b in labels)
                apart = all(labels[i][1] <= labels[i + 1][0] for i in range(len(labels) - 1))
                check(len(labels) >= 3 and inside and apart, f"{w} px: Sales, {days} days: {len(labels)} day labels, inside the card and not touching each other")
                check(sideways(pg) <= 0, f"{w} px: Sales, {days} days: no sideways scrolling")
            check(pg.locator("#st .card .k svg").count() >= 3, f"{w} px: Sales: the totals have icons")
            check(pg.evaluate("getComputedStyle(document.querySelector('#shd th')).position") == "sticky", f"{w} px: Sales: the table header stays in view")
            hh = [pg.locator(x).bounding_box()["height"] for x in ("#rng", "#csv")]
            check(min(hh) >= 40, f"{w} px: Sales: the period list and the CSV button are 40 px or more: {hh}")
            ctx.close()

        # ---- Vouchers, Settings with Prices, Rental phones and Branding (group 3) --------------------------------------------------------------
        for w, h in ((1280, 800), (390, 844)):
            ctx, pg = sign_in(br, w, h)
            for f in ("voucher_batches.txt", "voucher_codes.txt"):
                if os.path.exists(f"{env.data}/{f}"):
                    os.remove(f"{env.data}/{f}")
            open_screen(pg, w, "vch")
            e = pg.locator("#vl2 .empty")
            check(e.count() == 1 and "No vouchers yet" in e.inner_text() and e.is_visible(), f"{w} px: Vouchers: with none issued there is a clear empty state")
            legacy_batch(env.data, 60, 10, "hyper", 4, 30)
            open_screen(pg, w, "cli")
            open_screen(pg, w, "vch")
            pg.wait_for_selector("#vl2 button[data-v]", timeout=8000)
            bb = pg.eval_on_selector_all("#vl2 button.b", "bs => bs.map(b => { const r=b.getBoundingClientRect(); return [Math.round(r.right), Math.round(r.height)] })")
            check(bb and all(r <= w for r, _ in bb) and all(hh >= (40 if w < 900 else 32) for _, hh in bb), f"{w} px: Vouchers: the Cancel button is fully on the screen and tall enough: {bb}")
            cells = pg.eval_on_selector_all("#vl2 tr:first-child td", "ts => ts.filter(t => t.offsetParent).map(t => Math.round(t.getBoundingClientRect().right))")
            check(cells and max(cells) <= w, f"{w} px: Vouchers: every part of a voucher row is on the screen: {cells}")
            check(sideways(pg) <= 0, f"{w} px: Vouchers: no sideways scrolling")

            open_screen(pg, w, "set")
            pg.wait_for_selector("#rows-h input", timeout=8000)
            sw = [pg.locator(x).bounding_box() for x in ("#vcs", "#fss")]
            check(all(b and b["x"] + b["width"] <= w and b["width"] >= (300 if w < 900 else 400) for b in sw), f"{w} px: Settings: the two choices are wide enough to read: {[round(b['width']) for b in sw]}")
            pb = pg.eval_on_selector_all("#prc button, #prc input", "es => es.filter(e => e.offsetParent).map(e => { const r=e.getBoundingClientRect(); return [Math.round(r.right), Math.round(r.height), e.tagName === 'INPUT' && e.scrollWidth > e.clientWidth + 1, e.tagName] })")
            check(all(r <= w for r, _, _, _ in pb), f"{w} px: Prices: every field and button is on the screen")
            check(not any(c for _, _, c, _ in pb), f"{w} px: Prices: no number is cut off inside its field")
            check(all(hh >= (36 if t == "INPUT" else 32) for _, hh, _, t in pb), f"{w} px: Prices: fields are 36 px or more and buttons 32 px or more")
            if w < 900:
                check(pg.locator("#prs").bounding_box()["height"] >= 40 and all(hh >= 40 for _, hh, _, _ in pb), f"{w} px: Prices: touch targets are 40 px or more")
            check(sideways(pg) <= 0, f"{w} px: Settings: no sideways scrolling")

            open_screen(pg, w, "ph")
            fr = pg.locator("#boxf").bounding_box()
            check(fr["x"] >= 0 and fr["x"] + fr["width"] <= w and fr["height"] >= 400, f"{w} px: Rental phones: the coin box page fits the screen: {fr}")
            check(sideways(pg) <= 0, f"{w} px: Rental phones: no sideways scrolling")
            ctx.close()
        for w, h in ((1280, 900), (1000, 800), (390, 844)):
            ctx, pg = sign_in(br, w, h)
            open_screen(pg, w, "brand")
            pg.wait_for_selector("#bpal button", timeout=8000)
            fb, pv = pg.locator("#bform").bounding_box(), pg.locator("#bprev").bounding_box()
            check(fb["width"] >= (300 if w < 500 else 480), f"{w} px: Branding: the form is wide enough: {fb['width']:.0f}")
            check(sideways(pg) <= 0, f"{w} px: Branding: no sideways scrolling")
            if w >= 1180:
                check(pv["x"] >= fb["x"] + fb["width"], f"{w} px: Branding: the preview is beside the form")
            else:
                check(pv["y"] >= fb["y"] + fb["height"] - 1, f"{w} px: Branding: the preview is below the form (not squeezed beside it)")
            check(pg.locator("#bpub").bounding_box()["height"] >= 40, f"{w} px: Branding: Publish is 40 px or more")
            ctx.close()
        br.close()
finally:
    env.close()
    shutil.rmtree(tmp, ignore_errors=True)
finish()
