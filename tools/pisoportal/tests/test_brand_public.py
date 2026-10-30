#!/usr/bin/env python3
"""The public side of shop branding (feature 049, group 2): /brand.json, /brand/logo and /brand/picture, read with no sign-in, the way a guest's
phone or the owner's print tools read them. The branding files are laid out by hand here in the format brand.rs and brandpack.rs keep
(the Rust tests make them with the real code), so a change of that format shows up on both sides."""
import base64
import hashlib
import json
import os
import urllib.error
import urllib.request

from harness import HERE, Env, check, finish

TINY_PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
GOLDEN = os.path.join(HERE, "golden", "page_states.json")


def get(base, path, method="GET"):
    try:
        r = urllib.request.urlopen(urllib.request.Request(base + path, method=method), timeout=5)
        return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


env = Env(offset=150)
try:
    check(env.start(), "the portal starts")
    base = f"http://127.0.0.1:{env.PP}"
    brand_dir = f"{env.data}/brand"
    with open(GOLDEN) as f:
        today = json.load(f)["page_sha256"]

    def page_is_todays():
        return hashlib.sha256(get(base, "/?fas=ABC")[2]).hexdigest() == today

    # nothing published: the portal says so, and serves no picture
    s, h, b = get(base, "/brand.json")
    check(s == 200 and b == b'{"v":1,"branded":false}', f"unbranded: {b!r}")
    check(h["Content-Type"].startswith("application/json") and h["X-Content-Type-Options"] == "nosniff", "as JSON, never sniffed")
    check(get(base, "/brand/logo")[0] == 404 and get(base, "/brand/picture")[0] == 404, "no picture on an unbranded router")
    s, h, b = get(base, "/brand.json", "HEAD")
    check(s == 200 and b == b"" and int(h["Content-Length"]) == len(b'{"v":1,"branded":false}'), "HEAD answers with the length and no body")

    # a logo installed but nothing published: still unbranded, still no picture
    sha = hashlib.sha256(TINY_PNG).hexdigest()
    name = f"logo-{sha[:12]}.png"
    os.makedirs(brand_dir)
    with open(f"{brand_dir}/{name}", "wb") as f:
        f.write(TINY_PNG)
    with open(f"{brand_dir}/images.txt", "w") as f:
        f.write(f"logo {name} {sha}\n")
    check(get(base, "/brand.json")[2] == b'{"v":1,"branded":false}' and get(base, "/brand/logo")[0] == 404, "a logo alone changes nothing")

    # published: the shop's data, escaped, and the logo
    with open(f"{brand_dir}/published.txt", "w") as f:
        f.write("name=Aling \"Nena\" Net Cafe\npalette=dagat\nlayout=banner\naccent=#ff7a1a\ncontact_en=0917 123 4567\nrules_en=No downloads\\nBe \"kind\" \\\\ ok\npromo_fil=Happy hour na!\n")
    s, h, b = get(base, "/brand.json")
    j = json.loads(b)
    check(s == 200 and j["v"] == 1 and j["branded"] is True and j["name"] == 'Aling "Nena" Net Cafe', f"branded: {j}")
    check(j["palette"] == "dagat" and j["layout"] == "banner" and j["accent"] == "#ff7a1a" and j["logo"] is True and j["picture"] is False, f"the settings and the logo: {j}")
    check(j["texts"]["rules"] == {"en": 'No downloads\nBe "kind" \\ ok', "fil": ""} and j["texts"]["promo"] == {"en": "", "fil": "Happy hour na!"}, "texts, with a line break and quotes, come back whole")
    check(list(j["texts"]) == ["tagline", "welcome", "contact", "rules", "hours", "promo"], "always in the same order")
    s, h, b = get(base, "/brand/logo")
    check(s == 200 and b == TINY_PNG and h["Content-Type"] == "image/png", "the logo is served as it was installed")
    check(h["X-Content-Type-Options"] == "nosniff" and "default-src 'none'" in h["Content-Security-Policy"], "with nosniff and a CSP that lets it do nothing but be a picture")
    check(get(base, "/brand/picture")[0] == 404, "no picture was installed")
    check(not page_is_todays() and b"Aling &quot;Nena&quot; Net Cafe" in get(base, "/?fas=ABC")[2], "the guest page is the branded one now (test_page_branded.py looks at it)")

    # a damaged picture file is never served
    with open(f"{brand_dir}/{name}", "wb") as f:
        f.write(b"changed")
    check(get(base, "/brand/logo")[0] == 404 and json.loads(get(base, "/brand.json")[2])["logo"] is False, "a logo that no longer matches its checksum is not served")
    with open(f"{brand_dir}/{name}", "wb") as f:
        f.write(TINY_PNG)
    # images.txt cannot point outside the brand folder
    with open(f"{brand_dir}/images.txt", "w") as f:
        f.write(f"logo ../published.txt {sha}\n")
    check(get(base, "/brand/logo")[0] == 404, "a path in images.txt is never followed")
    with open(f"{brand_dir}/images.txt", "w") as f:
        f.write(f"logo {name} {sha}\n")
    check(get(base, "/brand/logo")[0] == 200, "and the proper line works again")

    # restore default look: back to unbranded at once, the logo stays installed but is not served
    os.remove(f"{brand_dir}/published.txt")
    check(get(base, "/brand.json")[2] == b'{"v":1,"branded":false}' and get(base, "/brand/logo")[0] == 404, "restore default look")
    check(os.path.exists(f"{brand_dir}/{name}"), "the logo file is still there")
    check(page_is_todays(), "the page is today's")
finally:
    env.stop()
finish()
