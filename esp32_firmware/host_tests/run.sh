#!/bin/sh
# Builds and runs the host tests with the system C++ compiler. No ESP32 or PlatformIO needed.
# cred_crypto_test and fw_manifest_test need mbedtls (libmbedtls-dev) and python3 with the "cryptography" package.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/piso_host_tests"
mkdir -p "$OUT"
python3 gen_cred_fixture.py "$OUT/cred_fixture.h"
python3 gen_fw_fixture.py "$OUT/fw_fixture.h"
python3 gen_protocol_fixture.py "$OUT/protocol_fixture.h"
for t in *_test.cpp; do
    g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -I"$OUT" -o "$OUT/${t%.cpp}" "$t" -lmbedcrypto
    "$OUT/${t%.cpp}"
done
