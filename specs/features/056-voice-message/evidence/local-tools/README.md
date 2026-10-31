# Running the pure-Kotlin voice tests without the Android SDK

The Android SDK is not in the cloud container, so `./gradlew testDebugUnitTest` cannot run there. The voice classes in
`app/src/main/java/com/pisophone/kiosk/voice/` are pure Kotlin, so they can be compiled and tested alone:

1. `mkdir -p /tmp/kt_voice/lib /tmp/kt_voice/shim/org/robolectric && cp fetch_kotlin_jars.sh /tmp/kt_voice/ && sh /tmp/kt_voice/fetch_kotlin_jars.sh`
   (Maven Central answers HTTP 429 to fast repeats: the script retries; a jar of under 1 KB is an error body, delete it and run again. It also needs
   `kotlinx-coroutines-core-jvm-1.5.0.jar` from `org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.5.0/`.)
2. Put this one-line stand-in at `/tmp/kt_voice/shim/org/robolectric/RobolectricTestRunner.kt`:
   `package org.robolectric` / `class RobolectricTestRunner(klass: Class<*>) : org.junit.runners.BlockJUnit4ClassRunner(klass)`
3. `sh run_kotlin_voice_tests.sh` compiles `voice/` and its tests and runs them with plain JUnit 4 and the real org.json.

This checks the logic only. Robolectric, the server route, the audio and ktlint run in CI (`build-apk.yml`, `quality.yml`).
