#!/bin/sh
# Repository security rules, run by CI (.github/workflows/quality.yml). Exit 1 on any violation.
# Run it locally from the repository root: sh scripts/check_security_rules.sh
cd "$(dirname "$0")/.." || exit 1
fail=0
bad() { echo "RULE VIOLATION: $1"; fail=1; }

CODE_DIRS="esp32_firmware website scripts opennds .github"
# website/js/yume-chan-bundle.js is a vendored third-party bundle; it is never scanned.
EXCLUDE='--exclude-dir=.git --exclude-dir=build --exclude-dir=__pycache__ --exclude=yume-chan-bundle.js --exclude=check_security_rules.sh'

# 1. No private keys anywhere in the repository (the signing keys live offline).
if grep -rEl $EXCLUDE -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' . ; then
    bad "a private key is committed (files listed above)"
fi

# 2. The shared master secret may only remain in the two files still being migrated off it.
#    Any new use fails the build; the list shrinks to nothing when S3 lands.
ALLOWED_MASTER="app/src/main/java/com/pisophone/kiosk/security/KioskSecurity.kt esp32_firmware/src/Config.cpp"
for f in $(grep -rl $EXCLUDE 'PISOPHONE_HMAC_MASTER_KEY' $CODE_DIRS 2>/dev/null); do
    case " $ALLOWED_MASTER " in *" $f "*) ;; *) bad "shared master secret referenced in $f" ;; esac
done

# 3. No factory passwords in code: every box and phone generates its own.
if grep -rnE $EXCLUDE -e 'Admin@123|AdminSetup|superadmin123' $CODE_DIRS; then
    bad "a factory password is hard-coded (lines above)"
fi

# 4. TLS verification may only be skipped where the payload is signed, and the line must say so.
if grep -rn $EXCLUDE 'setInsecure' $CODE_DIRS | grep -v 'piso-allow-insecure'; then
    bad "setInsecure() without a 'piso-allow-insecure: <reason>' marker on the same line"
fi


[ "$fail" -eq 0 ] && echo "security rules: all passed"
exit "$fail"
