#!/bin/sh
# Repository security rules, run by CI (.github/workflows/quality.yml). Exit 1 on any violation.
# Run it locally from the repository root: sh scripts/check_security_rules.sh
cd "$(dirname "$0")/.." || exit 1
fail=0
bad() { echo "RULE VIOLATION: $1"; fail=1; }

CODE_DIRS="app esp32_firmware website scripts router setup tools/pisoportal/src tools/pisoportal/tests protocol .github"
# website/js/yume-chan-bundle.js is a vendored third-party bundle; it is never scanned. ($EXCLUDE is split into words on
# purpose wherever it is used.)
EXCLUDE='--exclude-dir=.git --exclude-dir=build --exclude-dir=target --exclude-dir=node_modules --exclude-dir=__pycache__ --exclude=yume-chan-bundle.js --exclude=check_security_rules.sh'

# 1. No private keys anywhere in the repository (the signing keys live offline). Binary files are skipped (-I): a firmware image
# contains the mbedTLS parser's own text "-----BEGIN EC PRIVATE KEY-----", which is not a key.
if grep -rEIl $EXCLUDE -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' . ; then
    bad "a private key is committed (files listed above)"
fi

# 2. The shared master secret may only remain in the two files still being migrated off it.
#    Any new use fails the build; the list shrinks to nothing when S3 lands. Source files only (-I): the firmware images
#    built from Config.cpp for the setup program (website/flash/*.bin) carry its string like any build of it.
ALLOWED_MASTER="app/src/main/java/com/pisophone/kiosk/security/KioskSecurity.kt esp32_firmware/src/Config.cpp"
for f in $(grep -rlI $EXCLUDE 'PISOPHONE_HMAC_MASTER_KEY' $CODE_DIRS 2>/dev/null); do
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

# 5. The box serves its coin-slot API (arm, unarm, ack, status) to signed calls only: an unsigned ack could clear a phone's
#    paid coins. The transition switch must stay on in the source (a build for old phones may turn it off explicitly).
if ! grep -qE '^#define PISO_REQUIRE_SIGNED_COINSLOT 1[[:space:]]*$' esp32_firmware/src/WebServerCoinslot.cpp; then
    bad "the coin-slot API no longer requires signed calls by default (PISO_REQUIRE_SIGNED_COINSLOT)"
fi

# 6. runBlocking on app code blocks threads and risks ANRs. The count may only go down.
MAX_RUN_BLOCKING=2
n=$(grep -rn 'runBlocking' app/src/main 2>/dev/null | wc -l | tr -d ' ')
if [ "$n" -gt "$MAX_RUN_BLOCKING" ]; then
    bad "runBlocking count is $n, above the allowed $MAX_RUN_BLOCKING"
elif [ "$n" -lt "$MAX_RUN_BLOCKING" ]; then
    echo "note: runBlocking count is $n; lower MAX_RUN_BLOCKING in scripts/check_security_rules.sh to $n"
fi

# 7. Router updates: the program a router runs is never built with the test owner key, and the setup never runs a downloaded
#    file before the router program has verified the owner's signature on it (and only over https).
if grep -rn 'test-owner-key' .github/workflows/router-program.yml tools/build_piso_setup.py setup/piso-setup.sh.in; then
    bad "the router program or its workflow mentions the test owner key (it is for tests only)"
fi
if ! grep -q 'update-verify "\$_work/router.json" "\$_work/new.sh"' setup/piso-setup.sh.in || ! grep -q 'https://\*) ;;' setup/piso-setup.sh.in; then
    bad "the router setup no longer verifies a downloaded update (update-verify) or no longer insists on https"
fi
n=$(grep -c 'sh "\$_work/new.sh"' setup/piso-setup.sh.in)
if [ "$n" -ne 1 ]; then
    bad "the downloaded setup file may be run in exactly one place, after its verification (found $n)"
fi

# 8. Source folders hold source. A pile of Python packages once got committed into app/src (135 MB, never in the APK); nothing
#    but code and resources may sit next to the Kotlin and C++ files, and no file over 5 MB may be tracked outside the folders
#    CI publishes builds into (website/ and tools/pisoportal/bin/).
bad_files=$(git ls-files app/src/main/java app/src/test/java app/src/androidTest/java esp32_firmware/src esp32_firmware/include 2>/dev/null | grep -vE '\.(kt|java|cpp|h|hpp|c|ino)$')
if [ -n "$bad_files" ]; then
    echo "$bad_files"
    bad "files that are not source sit in a source folder (listed above)"
fi
for f in $(git ls-files); do
    case "$f" in website/*|tools/pisoportal/bin/*|setup/piso-setup*) continue ;; esac
    [ -f "$f" ] || continue
    if [ "$(wc -c < "$f")" -gt 5000000 ]; then
        bad "$f is over 5 MB; build outputs and packages do not belong in the repository"
    fi
done

# 9. A log line never carries the admin PIN or the box secret (logcat is readable over USB debugging). A line that names one of
#    them and interpolates a value ($x or ${x}) in the same call is a violation; "SecretSet=${!secret.isNullOrBlank()}" style
#    booleans and lengths are fine (they do not contain the value).
if grep -rnE --include='*.kt' -e 'Log\.[a-z]\(.*([Pp]in|PIN|[Ss]ecret).*\$\{?(it|pin|adminPin|currentPin|newPin|secret|boxSecret)\b[^.!(]*["}]' app/src/main/java; then
    bad "a log line interpolates the admin PIN or a secret (lines above)"
fi

# 10. A signing or publishing step in a workflow runs from a copy of the approved commit, never from a tree that `git reset --hard`
#     moved to a newer one (spec 01 CI-1, CI-2): a push after the owner's approval must not be able to change what gets signed.
if ! python3 scripts/tests/test_workflow_signing.py; then
    bad "a workflow runs a signing script after git reset --hard (lines above)"
fi

[ "$fail" -eq 0 ] && echo "security rules: all passed"
exit "$fail"
