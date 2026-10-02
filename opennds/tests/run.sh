#!/bin/sh
# Runs the OpenNDS integration test (needs python3, curl, openssl, bash).
exec python3 "$(dirname "$0")/test_flow.py"
