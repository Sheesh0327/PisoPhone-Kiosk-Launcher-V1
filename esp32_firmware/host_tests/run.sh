#!/bin/sh
# Builds and runs the host tests with the system C++ compiler. No ESP32 or PlatformIO needed.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/piso_host_tests"
mkdir -p "$OUT"
for t in *_test.cpp; do
    g++ -std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -o "$OUT/${t%.cpp}" "$t"
    "$OUT/${t%.cpp}"
done
