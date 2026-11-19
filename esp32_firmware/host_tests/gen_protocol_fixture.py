#!/usr/bin/env python3
"""Turns protocol/fixtures/box_phone_v1.json and gateway_v1.json into a C++ header for host tests."""
import json, os, sys
fixtures = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "protocol", "fixtures")
d = json.load(open(os.path.join(fixtures, "box_phone_v1.json")))
gw = json.load(open(os.path.join(fixtures, "gateway_v1.json")))
q = lambda s: json.dumps(s)  # a JSON string is also a valid C++ string literal for plain ASCII
with open(sys.argv[1], "w") as f:
    f.write("struct EncVec { const char* name; const char* secret; const char* iv; const char* plain; const char* cipher; };\n")
    f.write("struct MacVec { const char* name; const char* secret; const char* message; const char* hmac; };\n")
    f.write("struct AcctVec { const char* name; const char* secret; const char* op; const char* device; const char* ts; const char* bound; const char* message; const char* hmac; };\n")
    f.write("struct GwReqVec { const char* name; const char* key; const char* action; const char* session; const char* nonce; const char* sig; };\n")
    f.write("struct GwRespVec { const char* name; const char* key; const char* action; const char* session; const char* nonce; const char* facts; const char* rsig; };\n")
    f.write("struct GwEventVec { const char* name; const char* key; const char* session; const char* wid; unsigned long seq; const char* type; int pulses; const char* body; const char* line; };\n")
    f.write("static const EncVec ENC_VECTORS[] = {\n")
    for e in d["encrypt"]:
        f.write(f'    {{{q(e["name"])}, {q(e["secret"])}, {q(e["iv"])}, {q(e["plaintext"])}, {q(e["ciphertext"])}}},\n')
    f.write("};\nstatic const MacVec MAC_VECTORS[] = {\n")
    for m in d["hmac"]:
        f.write(f'    {{{q(m["name"])}, {q(m["secret"])}, {q(m["message"])}, {q(m["hmac"])}}},\n')
    f.write("};\n")
    f.write("static const AcctVec ACCT_VECTORS[] = {\n")
    for a in d["accounts"]:
        f.write(f'    {{{q(a["name"])}, {q(a["secret"])}, {q(a["op"])}, {q(a["device"])}, {q(a["ts"])}, {q(a["bound"])}, {q(a["message"])}, {q(a["hmac"])}}},\n')
    f.write("};\n")
    f.write("static const GwReqVec GW_REQ_VECTORS[] = {\n")
    for r in gw["requests"]:
        f.write(f'    {{{q(r["name"])}, {q(r["key"])}, {q(r["action"])}, {q(r["session"])}, {q(r["nonce"])}, {q(r["sig"])}}},\n')
    f.write("};\n")
    f.write("static const GwRespVec GW_RESP_VECTORS[] = {\n")
    for r in gw["responses"]:
        f.write(f'    {{{q(r["name"])}, {q(r["key"])}, {q(r["action"])}, {q(r["session"])}, {q(r["nonce"])}, {q(r["facts"])}, {q(r["rsig"])}}},\n')
    f.write("};\n")
    f.write("static const GwEventVec GW_EVENT_VECTORS[] = {\n")
    for ev in gw["events"]:
        f.write(f'    {{{q(ev["name"])}, {q(ev["key"])}, {q(ev["session"])}, {q(ev["wid"])}, {ev["seq"]}UL, {q(ev["type"])}, {ev["pulses"]}, {q(ev["body"])}, {q(ev["line"])}}},\n')
    f.write("};\n")
