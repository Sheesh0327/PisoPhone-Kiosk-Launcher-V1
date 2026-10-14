#!/usr/bin/env bash
# Run by .github/workflows/build-apk.yml ("Emulator tests") inside the emulator that android-emulator-runner started:
# the instrumented tests of the app (app/src/androidTest). When they fail, the app's own log lines are printed, because
# that is what says why. Run it by hand against an emulator or a phone with USB debugging:  bash scripts/ci_emulator_tests.sh
set -u
cd "$(dirname "$0")/.." || exit 1
if bash ./gradlew :app:connectedDebugAndroidTest --no-daemon --no-configuration-cache --stacktrace; then
    exit 0
fi
echo "::group::The app's log (last 400 lines)"
adb logcat -d -t 4000 -v time \
    KioskService:V KioskEngine:V KioskHttpServer:V KioskStateManager:V PaymentRepository:V KioskWireFlowTest:V AndroidRuntime:E '*:S' | tail -400
echo "::endgroup::"
exit 1
