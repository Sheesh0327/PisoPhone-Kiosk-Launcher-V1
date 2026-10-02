#!/usr/bin/env python3
"""Turns protocol/fixtures/box_phone_v1.json into a C++ header for protocol_contract_test.cpp."""
import json, os, sys
src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "protocol", "fixtures", "box_phone_v1.json")
d = json.load(open(src))
q = lambda s: json.dumps(s)  # a JSON string is also a valid C++ string literal for plain ASCII
with open(sys.argv[1], "w") as f:
    f.write("struct EncVec { const char* name; const char* secret; const char* iv; const char* plain; const char* cipher; };\n")
    f.write("struct MacVec { const char* name; const char* secret; const char* message; const char* hmac; };\n")
    f.write("static const EncVec ENC_VECTORS[] = {\n")
    for e in d["encrypt"]:
        f.write(f'    {{{q(e["name"])}, {q(e["secret"])}, {q(e["iv"])}, {q(e["plaintext"])}, {q(e["ciphertext"])}}},\n')
    f.write("};\nstatic const MacVec MAC_VECTORS[] = {\n")
    for m in d["hmac"]:
        f.write(f'    {{{q(m["name"])}, {q(m["secret"])}, {q(m["message"])}, {q(m["hmac"])}}},\n')
    f.write("};\n")
