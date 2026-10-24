#!/usr/bin/env python3
"""
Checks that each single-product coin box image really leaves out the other product (esp32_firmware/include/Product.h).

For a product it takes the sources the build compiles (envs/products.ini build_src_filter), resolves the PISO_HAS_GATEWAY /
PISO_HAS_PHONES conditions the way the compiler would, drops the comments, and looks for words that belong to the other product.
Anything found is a trace of it in the binary (a function, a page, a text), so the check fails and prints where.

  python3 scripts/check_product_strip.py            check both single-product images
  python3 scripts/check_product_strip.py --show phone|wifi   print what the product compiles (resolved), for reading
"""

import fnmatch
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "esp32_firmware")

# product -> (PISO_HAS_GATEWAY, PISO_HAS_PHONES, source files left out, words that must not appear, files whose text is checked)
PRODUCTS = {
    "phone": {
        "has": {"PISO_HAS_GATEWAY": 0, "PISO_HAS_PHONES": 1},
        "defined": {"PISO_PRODUCT_PHONE"},
        "skip": [],
        "skip_headers": ["Gateway*.h", "WebServerGateway.h"],   # (not compiled: nothing includes them in this image)
        "words": r"gateway|openwrt|opennds|pisowifi|voucher|captive",
    },
    "wifi": {
        "has": {"PISO_HAS_GATEWAY": 1, "PISO_HAS_PHONES": 0},
        "defined": {"PISO_PRODUCT_WIFI"},
        "skip": [],   # filled from envs/products.ini
        "skip_headers": ["Accounts.h", "AccountProtocol.h", "AccountStorage.h", "PhoneProfiles.h", "PhoneProfileStore.h", "DeviceLabel.h", "SlotLicense.h",
                         "CardCodec.h", "CardPubKey.h", "DeviceManager.h", "WebSocketsUdp.h", "ControllerWebSocket.h",
                         "WebAssetsPhones.h", "WebServerAccounts.h", "WebServerProfiles.h", "WebServerTelemetry.h", "WebServerCoinslot.h", "ReplayCheck.h"],
        "words": r"phone|kiosk|rental|licen[cs]e|slotlic|profile|account|\bpair|android|\bapp\b|trackeddevice|deviceslot|deviceconfig|devicetelemetry|devicecap|websocket|discovery|slotnum|slotactiv|slotcloud",
        # (kiosk_cfg is only the name of the saved-settings area on the chip: boxes of the full product use it too, so the update path and a
        # factory reset keep working the same way; it is not shown anywhere and does not do anything phone related)
        # (revenue counters by coin source: the PisoWiFi-only box keeps the same totals as the full box, so its saved totals and its
        # owner data keep the "phone" counter names (NVS key tot_phone_coins and so on). They count coins; there is no phone code behind them.)
        "allow": [r'\b(totalPhoneCoinsLifetime|lastSavedTotalPhoneCoins|NVS_KEY_TOTAL_PHONE_COINS|unsyncedPhonePesos|phonePesos|outPhone|isPhone|hasPhoneCoins|phoneCoins|K_PHONE_COINS|lifetime_phone_coins|lifetime_phone_pulses|tot_phone_coins)\b', r'\\"phone\\":', r'"kiosk_cfg"', r'pisophone\.pages\.dev', r'\b(TPL|MODAL|ICON)_[A-Z_]+', r'LicensePubKey\.h', r'LICENSE_PUBKEY_\w+', r'LICENSE_PUB_KEY_H', r'pisophone-(admin|fw)-v1'],
        # (the owner's public key file keeps its name, and the signed texts of the owner's tools keep their tags: both are shared by
        # every product, the same signing tools make them)
    },
}

def strip_comments(text):
    """The text without C++ comments (what is inside a string, raw string or character literal is kept: that is in the binary)."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('R"', i):
            m = re.match(r'R"([^(\s]*)\(', text[i:])
            if m:
                end = text.find(")" + m.group(1) + '"', i + m.end())
                end = n if end < 0 else end + len(m.group(1)) + 2
                out.append(text[i:end])
                i = end
                continue
        if c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read_skip(product):
    """The files a product leaves out, from build_src_filter in envs/products.ini."""
    text = open(os.path.join(ROOT, "envs", "products.ini"), encoding="utf-8").read()
    section = {"phone": "phone_only", "wifi": "wifi_only"}[product]
    m = re.search(r"\[" + section + r"\](.*?)(?=\n\[|\Z)", text, re.S)
    if not m:
        return []
    f = re.search(r"build_src_filter\s*=\s*(.*)", m.group(1))
    return re.findall(r"-<([^>]+)>", f.group(1)) if f else []


def eval_cond(expr, has, defined):
    """True/False when the condition only uses the product macros, else None (the compiler's other conditions stay as they are)."""
    e = expr.strip()
    for name in list(has) + list(defined):
        pass
    unknown = [False]

    def dfn(m):
        return "1" if m.group(1) in defined else ("0" if m.group(1).startswith("PISO_PRODUCT") else "?")
    e = re.sub(r"defined\s*\(\s*(\w+)\s*\)", dfn, e)
    e = re.sub(r"defined\s+(\w+)", dfn, e)

    def ident(m):
        n = m.group(0)
        if n in has:
            return str(has[n])
        if n.isdigit():
            return n
        unknown[0] = True
        return "0"
    e2 = re.sub(r"[A-Za-z_]\w*|\d+", ident, e)
    if unknown[0] or "?" in e2:
        return None
    e2 = e2.replace("&&", " and ").replace("||", " or ")
    e2 = re.sub(r"!(?!=)", " not ", e2)
    try:
        return bool(eval(e2, {"__builtins__": {}}))
    except Exception:
        return None


def resolve(text, has, defined):
    """The text with the conditions on the product macros resolved; the lines of the branch that is not compiled are dropped."""
    out = []
    stack = []   # each: [active_before, taken, this_branch_active, known]
    active = True
    for line in text.split("\n"):
        s = line.strip()
        m = re.match(r"#\s*(ifdef|ifndef|if|elif|else|endif)\b\s*(.*)", s)
        if not m:
            if active:
                out.append(line)
            continue
        d, rest = m.group(1), m.group(2)
        if d in ("if", "ifdef", "ifndef"):
            if d == "ifdef":
                rest = f"defined({rest.strip()})"
            elif d == "ifndef":
                rest = f"!defined({rest.strip()})"
            val = eval_cond(rest, has, defined)
            if val is None:
                stack.append(["unknown", active])
                if active:
                    out.append(line)
            else:
                stack.append(["known", active, val, val])
                active = active and val
        elif d == "elif":
            top = stack[-1]
            if top[0] == "unknown":
                if top[1]:
                    out.append(line)
            else:
                val = eval_cond(rest, has, defined)
                taken = top[3]
                v = bool(val) and not taken
                top[3] = taken or bool(val)
                top[2] = v
                active = top[1] and v
        elif d == "else":
            top = stack[-1]
            if top[0] == "unknown":
                if top[1]:
                    out.append(line)
            else:
                top[2] = not top[3]
                active = top[1] and top[2]
        else:   # endif
            top = stack.pop()
            if top[0] == "unknown":
                if top[1]:
                    out.append(line)
            else:
                active = top[1]
    return "\n".join(out)


def sources(product):
    cfg = PRODUCTS[product]
    skip = cfg["skip"] or read_skip(product)
    files = []
    for d in ("src", "include"):
        for name in sorted(os.listdir(os.path.join(ROOT, d))):
            if not name.endswith((".cpp", ".h")):
                continue
            if name == "WebAssets.h":
                continue
            if any(fnmatch.fnmatch(name, pat) for pat in skip + cfg.get("skip_headers", [])):
                continue
            if d == "include" and name.endswith(".h"):
                # a header of a file that is left out is not compiled either (nothing includes it); the check below reports it if so
                stem = name[:-2] + ".cpp"
                if stem in skip:
                    continue
            files.append(os.path.join(d, name))
    return files, skip


def view(product):
    cfg = PRODUCTS[product]
    files, skip = sources(product)
    result = {}
    for rel in files:
        text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        result[rel] = resolve(text, cfg["has"], cfg["defined"])
    return result, skip


def check(product):
    cfg = PRODUCTS[product]
    pattern = re.compile(cfg["words"], re.I)
    result, skip = view(product)
    problems = []
    for rel, text in result.items():
        code = strip_comments(text)
        code = re.sub(r"PISO_(HAS|PRODUCT)_\w+", "", code)   # (the product macros are not in the binary)
        for no, line in enumerate(code.split("\n"), 1):
            for ok in cfg.get("allow", []):
                line = re.sub(ok, "", line)
            if pattern.search(line):
                problems.append(f"{product}: {rel}:{no}: {line.strip()[:110]}")
    # the dashboard files the box serves (esp32_firmware/web/ as embed_web.py puts them in this image)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import embed_web
    for name, _ctype, _ident, raw in embed_web.assets_for(cfg["has"]["PISO_HAS_PHONES"] == 1):
        if name.endswith(".png") or name == "jsqr.js":   # (images; the vendored QR reader is not ours)
            continue
        for no, line in enumerate(raw.decode("utf-8").split("\n"), 1):
            for ok in cfg.get("allow", []):
                line = re.sub(ok, "", line)
            if pattern.search(line):
                problems.append(f"{product}: web/{name} (as served):{no}: {line.strip()[:110]}")
    # a file that still includes a header left out of this image would not compile
    left_out = set(cfg.get("skip_headers", [])) | {n[:-4] + ".h" for n in skip if n.endswith(".cpp")}
    for rel, text in result.items():
        for no, line in enumerate(text.split("\n"), 1):
            m = re.match(r'\s*#\s*include\s+"([^"]+)"', line)
            if m and m.group(1) in left_out:
                problems.append(f"{product}: {rel}:{no}: includes {m.group(1)}, which this image leaves out")
    return problems


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "--show":
        result, _skip = view(sys.argv[2])
        for rel, text in result.items():
            print(f"//// {rel}\n{text}")
        return 0
    bad = []
    for product in PRODUCTS:
        found = check(product)
        print(f"{product}: {'no trace of the other product' if not found else str(len(found)) + ' trace(s)'}")
        bad += found
    for line in bad:
        print(line)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
