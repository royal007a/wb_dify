#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
JAVA_BIN=${HIFY_JAVA_HOME:?set HIFY_JAVA_HOME to JDK 17}/bin
REPOSITORY=${HIFY_M2_REPOSITORY:?set HIFY_M2_REPOSITORY to the local Maven repository}
CP=""
for artifact in \
  org/junit/platform/junit-platform-launcher/1.11.4/junit-platform-launcher-1.11.4.jar \
  org/junit/platform/junit-platform-engine/1.11.4/junit-platform-engine-1.11.4.jar \
  org/junit/platform/junit-platform-commons/1.11.4/junit-platform-commons-1.11.4.jar \
  org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar \
  org/junit/jupiter/junit-jupiter-engine/5.11.4/junit-jupiter-engine-5.11.4.jar \
  org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar \
  org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar \
  org/assertj/assertj-core/3.26.3/assertj-core-3.26.3.jar \
  org/springframework/boot/spring-boot/3.4.5/spring-boot-3.4.5.jar \
  org/springframework/spring-core/6.2.6/spring-core-6.2.6.jar \
  org/springframework/spring-context/6.2.6/spring-context-6.2.6.jar \
  org/springframework/spring-beans/6.2.6/spring-beans-6.2.6.jar \
  org/springframework/spring-expression/6.2.6/spring-expression-6.2.6.jar \
  org/springframework/spring-aop/6.2.6/spring-aop-6.2.6.jar \
  org/springframework/spring-jcl/6.2.6/spring-jcl-6.2.6.jar
do
  test -f "$REPOSITORY/$artifact"
  CP="${CP:+$CP:}$REPOSITORY/$artifact"
done
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hify-startup-detail.XXXXXX")
printf 'Compiled test output: %s\n' "$BUILD_DIR"
"$JAVA_BIN/javac" -J-Xmx128m --release 17 -cp "$CP" -d "$BUILD_DIR" \
  "$ROOT/backend/hify-app/src/test/java/com/hify/api/ShutdownStartupDiagnostics.java" \
  "$ROOT/backend/hify-app/src/test/java/com/hify/api/ShutdownStartupDiagnosticsTest.java" \
  "$ROOT/docs/research/jikesummary-20261006/startup-detail/DiagnosticTestLauncher.java"
"$JAVA_BIN/java" -Xmx128m -cp "$BUILD_DIR:$CP" DiagnosticTestLauncher
