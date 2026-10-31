#!/usr/bin/env python3
"""The voice message on the box's phone list (esp32_firmware/web/portal-core-phones.js, portal.css; feature 056) in a real browser with
the box's answers mocked: a mic on every paired phone (a greyed one when the app is too old, none on an empty slot), hold to record,
a paper plane on release, the 0.5 s accident rule, the 15 s stop, a list that is redrawn every 3 s without disturbing any of it, the
send in two steps with every kind of failure in plain words, the blocked-microphone messages, and the encoder against the vectors
(protocol/fixtures/box_phone_voice_v1.json). Chrome's fake microphone supplies the sound.
Needs Playwright (pip install playwright; playwright install chromium); set CHROME to use a Chromium you already have.
Run: python3 scripts/tests/test_portal_voice_ui.py"""
import json
import os
import re
import sys

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("skipped: playwright is not installed")
    sys.exit(0)

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "..", "..", "esp32_firmware", "web")
VECTORS = os.path.join(HERE, "..", "..", "protocol", "fixtures", "box_phone_voice_v1.json")
sys.path.insert(0, os.path.join(HERE, ".."))
import embed_web  # type: ignore[import-not-found]

PAGE = """<!doctype html><html><head><title>box</title></head><body>
<div id="banners"></div><span id="stat_phones"></span><span id="stat_phones_sub"></span>
<div id="live_devices_container"></div></body></html>"""
OUTER = '<!doctype html><html><body><iframe id="f" src="/box" style="width:900px;height:500px"></iframe></body></html>'

checks = failures = 0
with open(os.path.join(WEB, "portal.css"), encoding="utf-8") as _f:
    CSS = embed_web._css(_f.read(), True)  # (the box with phones serves the rules between /*@phones*/ and /*@end*/)


def check(ok, name):
    global checks, failures
    checks += 1
    if not ok:
        failures += 1
        print("FAIL:", name)


def read(name):
    # (the box with phones serves the shared file and its phone part as one file: scripts/embed_web.py)
    more = {"portal-core.js": ["portal-core-phones.js", "portal-core-end.js"]}.get(name, [])
    text = ""
    for part in [name] + more:
        with open(os.path.join(WEB, part), encoding="utf-8") as f:
            text += f.read() + "\n"
    return text


def device(slot, online=True, voice=True, bound=True, battery=80):
    if not bound:
        return {"slotNum": slot, "isBound": False, "id": "", "ip": "", "name": "", "time": -1, "online": False, "battery": -1, "voice": False}
    return {"slotNum": slot, "isBound": True, "id": f"DEV-{slot}", "ip": f"192.168.1.{40 + slot}", "name": f"Phone {slot}",
            "time": 600 if online else -1, "online": online, "battery": battery, "charging": False, "restarts": 0, "voice": voice}


def status(battery=80):
    return {"devices": [device(1, battery=battery), device(2, online=False), device(3, voice=False), device(4, bound=False)],
            "unassigned_devices": [{"id": "NEW-1", "ip": "192.168.1.99", "name": "New phone", "battery": 50, "charging": False}]}


sent: dict[str, list] = {"ready": [], "upload": []}
answers: dict[str, tuple[int, dict]] = {"ready": (200, {"success": True}), "upload": (200, {"success": True})}


def handle_route(route):
    req = route.request
    path = req.url.split("://", 1)[1].split("/", 1)[1].split("?")[0]

    def respond(code, obj):
        route.fulfill(status=code, content_type="application/json", body=json.dumps(obj))

    if path == "":
        route.fulfill(status=200, content_type="text/html", body=OUTER if os.environ.get("VOICE_FRAME") else PAGE)
    elif path == "box":
        route.fulfill(status=200, content_type="text/html", body=PAGE)
    elif path == "api/voice/ready":
        sent["ready"].append(req.url)
        respond(*answers["ready"])
    elif path == "api/voice":
        sent["upload"].append((req.url, req.headers.get("content-type", ""), req.post_data_buffer))
        respond(*answers["upload"])
    else:
        respond(200, {})


def cell(slot):
    return f'.voice-cell[data-voice-slot="{slot}"]'


with open(VECTORS) as f:
    vec = json.load(f)


def pcm_for(gen):
    n, kind = gen["samples"], gen["kind"]
    if kind == "silence":
        return [0] * n
    if kind == "sawtooth":
        return [(i * 1117) % 20001 - 10000 for i in range(n)]
    out, x = [], gen["seed"]
    for _ in range(n):
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out.append(((x >> 8) % 65536) - 32768)
    return out


with sync_playwright() as p:
    browser = p.chromium.launch(executable_path=os.environ.get("CHROME") or None,
                                args=["--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"])

    def open_page():
        page = browser.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.route("**/*", handle_route)
        page.goto("http://localhost/")  # (localhost counts as a secure context, like the box page with Chrome's one-time setting)
        page.add_style_tag(content=CSS)
        page.add_script_tag(content=read("portal-core.js"))
        page.evaluate("() => pollTimers.forEach(clearInterval)")  # the test feeds the list itself
        return page, errors

    def feed(page, data=None):
        page.evaluate("d => window.statusHooks.forEach(h => h(d))", data or status())

    def phase(page, slot):
        return page.eval_on_selector(cell(slot), "el => el.dataset.phase")

    def hold(page, slot, ms):
        box = page.locator(cell(slot) + " .mic").bounding_box()
        page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
        page.mouse.down()
        page.wait_for_function("s => document.querySelector('.voice-cell[data-voice-slot=\"' + s + '\"]').dataset.phase === 'recording'", arg=slot)
        page.wait_for_timeout(ms)
        page.mouse.up()

    def wait_phase(page, slot, want, timeout=20000):
        page.wait_for_function("a => document.querySelector('.voice-cell[data-voice-slot=\"' + a[0] + '\"]').dataset.phase === a[1]",
                               arg=[slot, want], timeout=timeout)

    # ---- the rows ----
    page, errors = open_page()
    feed(page)
    check(len(page.query_selector_all(".voice-cell")) == 3, "a mic cell on each of the three paired phones: online, offline and old app")
    check(page.query_selector(cell(2) + " .mic") is not None and phase(page, 2) == "idle", "an offline phone has a working mic too")
    check(page.query_selector(cell(4)) is None and page.query_selector(".row.pending .voice-cell") is None,
          "no mic on an empty slot or on a phone that wants to connect")
    check("off" in page.get_attribute(cell(3) + " .mic", "class") and phase(page, 3) == "off", "a phone with an old app has a greyed mic")
    page.click(cell(3) + " .mic")
    check("Update the app on this phone" in page.inner_text(cell(3) + " .voice-note"), "the greyed mic says to update the app")
    check(phase(page, 3) == "off", "and records nothing")

    # ---- hold, release, plane ----
    hold(page, 1, 1200)
    wait_phase(page, 1, "recorded")
    check(page.is_visible(cell(1) + " .plane") and not page.is_visible(cell(1) + " .mic"), "released: the mic has become a paper plane")
    check(page.is_visible(cell(1) + " .cross"), "with a cross to throw it away")
    check(re.fullmatch(r"\d+ s", page.inner_text(cell(1) + " .voice-len")) is not None, "the plane shows the length")
    clip_len = page.evaluate("() => window.voiceState[1].clip.length")
    secs = page.evaluate("() => window.voiceState[1].lengthSec")
    samples = round(secs * 8000)
    blocks = -(-samples // 505)
    expected = 8 + 256 * (blocks - 1) + 4 + (samples - 505 * (blocks - 1)) // 2
    check(1.0 <= secs <= 2.6 and clip_len == expected, f"a clip of about a second and a half whose length fits its samples ({secs} s, {clip_len} bytes, want {expected})")
    check(page.evaluate("() => window.voiceState[1].clip[0] === 1 && window.voiceState[1].clip[1] === 1"), "the clip starts with version 1, codec 1")

    # ---- the list is redrawn every 3 s: the plane, the length and a second clip survive ----
    hold(page, 2, 900)
    wait_phase(page, 2, "recorded")
    before = page.inner_text(cell(1) + " .voice-len")
    feed(page, status(battery=55))
    check(phase(page, 1) == "recorded" and phase(page, 2) == "recorded", "after a redraw both rows still hold their clip")
    check(page.inner_text(cell(1) + " .voice-len") == before and page.is_visible(cell(1) + " .plane"), "and the plane still shows its length")
    check("55%" in page.inner_text("#live_devices_container"), "while the redraw really did refresh the list")
    page.click(cell(1) + " .cross")
    check(phase(page, 1) == "idle" and page.is_visible(cell(1) + " .mic"), "the cross throws the recording away and brings the mic back")
    check(phase(page, 2) == "recorded", "the other phone's clip is not touched")
    page.click(cell(2) + " .cross")

    # ---- an accident: a press under half a second keeps nothing ----
    box = page.locator(cell(1) + " .mic").bounding_box()
    page.mouse.move(box["x"] + 5, box["y"] + 5)
    page.mouse.down()
    page.mouse.up()
    page.wait_for_function("() => document.querySelector('.voice-cell[data-voice-slot=\"1\"] .voice-note').textContent.length > 0")
    check("Hold the mic while you speak" in page.inner_text(cell(1) + " .voice-note"), "a quick tap says to hold the mic")
    check(phase(page, 1) == "idle" and page.evaluate("() => window.voiceState[1].clip") is None, "and keeps nothing")

    # ---- while recording the redraw leaves the button under the finger alone ----
    box = page.locator(cell(1) + " .mic").bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.mouse.down()
    page.wait_for_function("() => document.querySelector('.voice-cell[data-voice-slot=\"1\"]').dataset.phase === 'recording'")
    handle = page.query_selector(cell(1) + " .mic")
    page.wait_for_timeout(1100)
    feed(page, status(battery=33))
    feed(page, status(battery=32))
    check(page.evaluate("el => el.isConnected", handle) and phase(page, 1) == "recording", "a redraw during a recording keeps the same button and the recording")
    check(re.fullmatch(r"\d+ s", page.inner_text(cell(1) + " .voice-count")) is not None, "the counter runs")
    page.mouse.up()
    wait_phase(page, 1, "recorded")
    check("32%" in page.inner_text("#live_devices_container"), "the list is redrawn when the recording ends")
    check(not page.evaluate("() => window.voiceIsRecording()"), "nothing is left recording")

    # ---- send: a question that is answered at once, then the clip ----
    sent["ready"].clear()
    sent["upload"].clear()
    page.click(cell(1) + " .plane")
    wait_phase(page, 1, "sent")
    check(len(sent["ready"]) == 1 and "ip=192.168.1.41" in sent["ready"][0], "the early question names the phone")
    check(len(sent["upload"]) == 1, "then one upload")
    url, ctype, body = sent["upload"][0]
    check("ip=192.168.1.41" in url and re.search(r"len=\d+", url) is not None, "the upload names the phone and the clip length")
    check(ctype.startswith("multipart/form-data") and b'name="clip"' in body, "as a file in a multipart form (the box takes it in pieces)")
    found = re.search(r"len=(\d+)", url)
    announced = int(found.group(1)) if found else -1
    check(sent["ready"][0].endswith(f"len={announced}"), "and the same length as the upload")
    check(len(body) > announced and len(body) < announced + 400, f"carrying the whole clip ({announced} bytes announced, {len(body)} sent)")
    check(page.is_visible(cell(1) + " .voice-sent"), "'Sent' shows")
    page.wait_for_function("() => document.querySelector('.voice-cell[data-voice-slot=\"1\"]').dataset.phase === 'idle'", timeout=5000)
    check(page.evaluate("() => window.voiceState[1].clip") is None and page.is_visible(cell(1) + " .mic"), "then the mic is back and the clip is gone")

    # ---- every kind of failure keeps the plane and says why ----
    cases = [
        ("ready", 502, "no_answer", "Phone 1 did not answer"),
        ("ready", 429, "busy", "The box is busy"),
        ("ready", 503, "low_memory", "The box is busy"),
        ("ready", 409, "old_app", "Update the app"),
        ("ready", 404, "unknown_phone", "not paired"),
        ("upload", 502, "cut_off", "The message was cut off"),
        ("upload", 502, "phone_refused", "The message was cut off"),
        ("upload", 400, "bad_clip", "Throw it away and record it again"),
    ]
    for step, code, word, text in cases:
        answers["ready"] = (200, {"success": True})
        answers["upload"] = (200, {"success": True})
        answers[step] = (code, {"success": False, "error": word})
        hold(page, 1, 700)
        wait_phase(page, 1, "recorded")
        page.click(cell(1) + " .plane")
        wait_phase(page, 1, "failed")
        note = page.inner_text(cell(1) + " .voice-note")
        check(text in note, f"{word}: the page says '{text}' (got '{note}')")
        check(page.is_visible(cell(1) + " .plane") and page.evaluate("() => window.voiceState[1].clip !== null"),
              f"{word}: the plane stays and the clip is kept")
        if word == "no_answer":
            # the same plane, tapped again once the phone is back, sends the same clip
            answers["ready"] = (200, {"success": True})
            page.click(cell(1) + " .plane")
            wait_phase(page, 1, "sent")
            page.wait_for_function("() => document.querySelector('.voice-cell[data-voice-slot=\"1\"]').dataset.phase === 'idle'", timeout=5000)
        else:
            page.click(cell(1) + " .cross")
    answers["ready"] = (200, {"success": True})
    answers["upload"] = (200, {"success": True})
    check(not errors, f"no script error on the page: {errors}")
    page.close()

    # ---- the signed-out box and a box that cannot be reached ----
    page, errors = open_page()
    feed(page)
    hold(page, 1, 700)
    wait_phase(page, 1, "recorded")
    answers["ready"] = (401, {"error": "Unauthorized"})
    page.click(cell(1) + " .plane")
    wait_phase(page, 1, "failed")
    check("signed out" in page.inner_text(cell(1) + " .voice-note") and page.evaluate("() => window.voiceState[1].clip !== null"),
          "signed out: it says so and keeps the clip")
    answers["ready"] = (200, {"success": True})
    page.route("**/api/voice/ready*", lambda r: r.abort())  # (the route added last is asked first)
    page.click(cell(1) + " .plane")
    wait_phase(page, 1, "failed")
    check("Could not reach the box" in page.inner_text(cell(1) + " .voice-note"), "a box that does not answer at all is named")
    page.close()

    # ---- the microphone cannot be used ----
    page, errors = open_page()
    feed(page)
    page.evaluate("() => { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true }); }")
    page.click(cell(1) + " .mic")
    note = page.inner_text(cell(1) + " .voice-note")
    check("chrome://flags/#unsafely-treat-insecure-origin-as-secure" in note and "http://localhost" in note and "iPhone" in note,
          f"a plain http:// page: the Chrome setting, this address and the iPhone are named ({note})")
    check(phase(page, 1) == "idle" and not page.evaluate("() => window.voiceIsRecording()"), "and nothing is recorded")
    page.close()
    for err_name, text in (("NotAllowedError", "blocked for this page"), ("NotFoundError", "No microphone was found"),
                           ("NotReadableError", "used by another app")):
        page, errors = open_page()
        feed(page)
        page.evaluate("n => { navigator.mediaDevices.getUserMedia = () => Promise.reject(Object.assign(new Error('x'), { name: n })); }", err_name)
        box = page.locator(cell(1) + " .mic").bounding_box()
        page.mouse.move(box["x"] + 5, box["y"] + 5)
        page.mouse.down()
        page.wait_for_function("() => document.querySelector('.voice-cell[data-voice-slot=\"1\"] .voice-note').textContent.length > 0")
        page.mouse.up()
        check(text in page.inner_text(cell(1) + " .voice-note"), f"{err_name}: the page says '{text}'")
        check(phase(page, 1) == "idle" and not page.evaluate("() => window.voiceIsRecording()"), f"{err_name}: nothing is recorded")
        page.close()

    # ---- the box page inside the router console's frame ----
    os.environ["VOICE_FRAME"] = "1"
    page = browser.new_page()
    page.route("**/*", handle_route)
    page.goto("http://localhost/")
    frame = page.frame_locator("#f")
    page.wait_for_selector("#f")
    inner = next(f for f in page.frames if f.url.endswith("/box"))
    inner.add_style_tag(content=CSS)
    inner.add_script_tag(content=read("portal-core.js"))
    inner.evaluate("() => pollTimers.forEach(clearInterval)")
    inner.evaluate("d => window.statusHooks.forEach(h => h(d))", status())
    inner.evaluate("() => { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true }); }")
    inner.click(cell(1) + " .mic")
    check("inside the router console" in inner.inner_text(cell(1) + " .voice-note") and "on its own" in inner.inner_text(cell(1) + " .voice-note"),
          "inside the router console's frame the page says to open the box page on its own")
    del os.environ["VOICE_FRAME"]
    page.close()

    # ---- the encoder against the vectors (the same bytes as the Python reference, the box's tests and the phone's) ----
    page, errors = open_page()
    for c in vec["clips"]:
        pcm = pcm_for(c["generator"])
        got = page.evaluate("p => Array.from(window.voiceEncode(Int16Array.from(p)))", pcm)
        check(bytes(got).hex() == c["hex"], "the page's encoder makes the vector's bytes for {}".format(c["name"]))
    check(not errors, f"no script error while encoding: {errors}")
    page.close()

    # ---- the 15 s limit ----
    page, errors = open_page()
    feed(page)
    box = page.locator(cell(1) + " .mic").bounding_box()
    page.mouse.move(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2)
    page.mouse.down()  # never let go
    wait_phase(page, 1, "recorded", timeout=25000)
    secs = page.evaluate("() => window.voiceState[1].lengthSec")
    n = page.evaluate("() => window.voiceState[1].clip.length")
    check(14.0 <= secs <= 15.0, f"held for ever: the recording stops by itself at 15 s (got {secs:.2f} s)")
    check(n <= vec["constants"]["max_clip_bytes"], f"and the clip fits the longest allowed ({n} bytes)")
    page.mouse.up()
    check(phase(page, 1) == "recorded", "letting go afterwards changes nothing")
    page.close()
    browser.close()

# ---- the PisoWiFi-only box carries none of it ----
for name, ctype, ident, raw in embed_web.assets_for(False):
    check(b"voice" not in raw.lower(), f"the PisoWiFi-only {name} has no voice code")
check(any(b"voice-cell" in raw for name, ctype, ident, raw in embed_web.assets_for(True)), "the box with phones does carry it")

print(f"{checks} checks, {failures} failures")
sys.exit(1 if failures else 0)
