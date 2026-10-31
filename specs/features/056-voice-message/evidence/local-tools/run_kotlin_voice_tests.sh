#!/bin/sh
# Local check of the pure Kotlin voice classes and the repo's own tests for them (no Android SDK in this container).
# The Robolectric runner the tests name is a one-line stand-in (shim/); org.json is the real library.
SP="${KT_WORK:-/tmp/kt_voice}"   # where fetch_kotlin_jars.sh put lib/ (it uses its own folder: copy it there)
REPO="${REPO:-$(git rev-parse --show-toplevel)}"
LIB=$SP/lib; OUT=$SP/out
export JAVA_TOOL_OPTIONS=
rm -rf "$OUT"; mkdir -p "$OUT"
KC="$LIB/kotlin-compiler-embeddable-2.0.21.jar:$LIB/kotlin-stdlib-2.0.21.jar:$LIB/kotlin-script-runtime-2.0.21.jar:$LIB/kotlin-reflect-1.6.10.jar:$LIB/kotlin-daemon-embeddable-2.0.21.jar:$LIB/trove4j-1.0.20200330.jar:$LIB/annotations-13.0.jar:$LIB/kotlinx-coroutines-core-jvm-1.5.0.jar"
CP="$LIB/kotlin-stdlib-2.0.21.jar:$LIB/junit-4.13.2.jar:$LIB/hamcrest-core-1.3.jar:$LIB/json-20240303.jar"
MAIN=$REPO/app/src/main/java/com/pisophone/kiosk/voice
TEST=$REPO/app/src/test/java/com/pisophone/kiosk/voice
SRC="$SP/shim/org/robolectric/RobolectricTestRunner.kt $(find $TEST -name '*.kt') $( [ -d $MAIN ] && find $MAIN -name '*.kt')"
java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp "$CP" -d "$OUT" $SRC 2>&1 | grep -v "^warning: .*JAVA_TOOL" | head -${LINES_MAX:-40}
[ "${PIPESTATUS:-0}" ] 
CLASSES=$(cd $TEST && ls *Test.kt | sed 's/\.kt$//; s/^/com.pisophone.kiosk.voice./' | tr '\n' ' ')
cd $REPO/app && java -cp "$OUT:$CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | head -${LINES_MAX:-60}
