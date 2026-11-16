#!/usr/bin/env python3
"""Turns protocol/fixtures/gateway_v1.json into a C++ header for gateway_protocol_test.cpp."""
import json, os, sys

src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "protocol", "fixtures", "gateway_v1.json")
gw = json.load(open(src))
q = lambda s: json.dumps(s)

with open(sys.argv[1], "w") as f:
    f.write("struct GwReqVec { const char* name; const char* key; const char* action; const char* session; const char* nonce; const char* sig; };\n")
    f.write("struct GwRespVec { const char* name; const char* key; const char* action; const char* session; const char* nonce; const char* facts; const char* rsig; };\n")
    f.write("struct GwEventVec { const char* name; const char* key; const char* session; const char* wid; unsigned long seq; const char* type; int pulses; const char* body; const char* line; };\n")
    f.write("static const GwReqVec GW_REQ_VECTORS[] = {\n")
    for r in gw["requests"]:
        f.write(f'    {{{q(r["name"])}, {q(r["key"])}, {q(r["action"])}, {q(r["session"])}, {q(r["nonce"])}, {q(r["sig"])}}},\n')
    f.write("};\nstatic const GwRespVec GW_RESP_VECTORS[] = {\n")
    for r in gw["responses"]:
        f.write(f'    {{{q(r["name"])}, {q(r["key"])}, {q(r["action"])}, {q(r["session"])}, {q(r["nonce"])}, {q(r["facts"])}, {q(r["rsig"])}}},\n')
    f.write("};\nstatic const GwEventVec GW_EVENT_VECTORS[] = {\n")
    for ev in gw["events"]:
        f.write(f'    {{{q(ev["name"])}, {q(ev["key"])}, {q(ev["session"])}, {q(ev["wid"])}, {ev["seq"]}UL, {q(ev["type"])}, {ev["pulses"]}, {q(ev["body"])}, {q(ev["line"])}}},\n')
    f.write("};\n")
