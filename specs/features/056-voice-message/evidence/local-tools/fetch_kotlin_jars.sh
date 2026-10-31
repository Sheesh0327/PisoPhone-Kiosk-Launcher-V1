#!/bin/sh
# Sequential, patient downloads of the Kotlin compiler and JUnit from Maven Central (parallel ones were rate limited: HTTP 429).
cd "$(dirname "$0")/lib"
M=https://repo.maven.apache.org/maven2
for p in \
 org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar \
 org/jetbrains/kotlin/kotlin-script-runtime/2.0.21/kotlin-script-runtime-2.0.21.jar \
 org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar \
 org/jetbrains/kotlin/kotlin-daemon-embeddable/2.0.21/kotlin-daemon-embeddable-2.0.21.jar \
 org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar \
 org/jetbrains/annotations/13.0/annotations-13.0.jar \
 junit/junit/4.13.2/junit-4.13.2.jar \
 org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar \
 org/json/json/20240303/json-20240303.jar \
 org/jetbrains/kotlin/kotlin-compiler-embeddable/2.0.21/kotlin-compiler-embeddable-2.0.21.jar ; do
  f=$(basename $p)
  [ -s "$f" ] && continue
  curl -sS --retry 8 --retry-all-errors --retry-delay 4 -m 300 -o "$f" "$M/$p" || echo "FAILED $f"
  echo "got $f $(wc -c < $f)"
done
echo DONE
