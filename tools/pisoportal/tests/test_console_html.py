#!/usr/bin/env python3
"""Test console.html and page_html logic for Feature 056 (PisoWiFi vs PisoHub console tabs)."""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PORTAL_SRC = os.path.join(HERE, "..", "src")
HTML_PATH = os.path.join(PORTAL_SRC, "console.html")

PHTAB_BOTH = '<button data-t="ph"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18.5h2"/></svg>Rental phones</button>'
PHTAB_WIFI = '<button data-t="ph" class="dis" aria-disabled="true"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>Rental phones (Locked)</button>'

PHSEC_BOTH = r'''<section id="t-ph" class="hide"><p class="msg hide" id="phnote"></p><iframe id="boxf" title="Rental phones: the coin box" allow=""></iframe><p class="s">This is the coin box's own page, brought in through the router. <a id="boxdirect" href="#">Open it on its own</a></p></section>'''
PHSEC_WIFI = '<section id="t-ph" class="hide"><div class="card" style="margin-top:16px"><div style="display:flex;align-items:center;gap:12px;margin-bottom:8px"><svg viewBox="0 0 24 24" aria-hidden="true" style="width:24px;height:24px;stroke:var(--gold,#d97706);fill:none;stroke-width:2"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg><h2 style="margin:0">PisoPhone Management Disabled</h2></div><p class="s">This router is configured for PisoWiFi only. Rental phone management, prepaid time cards, and phone slot settings are disabled. To manage rental phones, configure the shop product as \'both\' during router setup.</p></div></section>'

def page_html_py(product: str) -> str:
    ui = open(HTML_PATH, "r", encoding="utf-8").read()
    if product == "wifi":
        return ui.replace("@PHTAB_BTN@", PHTAB_WIFI).replace("@PH_SECTION@", PHSEC_WIFI).replace("@PHTAB@", "Rental phones (Locked)")
    else:
        return ui.replace("@PHTAB_BTN@", PHTAB_BOTH).replace("@PH_SECTION@", PHSEC_BOTH).replace("@PHTAB@", "Rental phones")

def test_feature_056_console():
    wifi = page_html_py("wifi")
    both = page_html_py("both")

    failures = []
    # 1. On wifi, button data-t="ph" should have class "dis" and aria-disabled="true"
    if 'data-t="ph" class="dis" aria-disabled="true"' not in wifi:
        failures.append("wifi does not have disabled lock button for data-t='ph'")
    if "Rental phones (Locked)" not in wifi:
        failures.append("wifi does not contain 'Rental phones (Locked)'")
    if 'id="boxf"' in wifi:
        failures.append("wifi should not contain iframe id='boxf'")
    if "PisoPhone Management Disabled" not in wifi:
        failures.append("wifi should contain 'PisoPhone Management Disabled'")

    # 2. On both, button data-t="ph" should be normal
    if 'data-t="ph"' not in both or 'class="dis"' in both:
        failures.append("both has disabled button")
    if "Rental phones" not in both or "(Locked)" in both:
        failures.append("both should have 'Rental phones' without '(Locked)'")
    if 'id="boxf"' not in both:
        failures.append("both must contain iframe id='boxf'")

    # 3. No unresolved template variables remain
    for var in ("@PHTAB_BTN@", "@PH_SECTION@", "@PHTAB@"):
        if var in wifi:
            failures.append(f"wifi still contains {var}")
        if var in both:
            failures.append(f"both still contains {var}")

    if failures:
        print("FAILURES:\n" + "\n".join(f" - {f}" for f in failures))
        sys.exit(1)
    print("PASS: Feature 056 console HTML requirements satisfied.")

if __name__ == "__main__":
    test_feature_056_console()
