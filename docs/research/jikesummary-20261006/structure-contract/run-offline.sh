#!/bin/sh
set -eu
# No network or Maven; require already available, explicit test-library versions.
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
  org/assertj/assertj-core/3.26.3/assertj-core-3.26.3.jar
do
  test -f "$REPOSITORY/$artifact"
  CP="${CP:+$CP:}$REPOSITORY/$artifact"
done
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hify-structure-contract.XXXXXX")
# Keep this small compile output for diagnosis; never clean a project directory.
printf 'Compiled test output: %s\n' "$BUILD_DIR"
"$JAVA_BIN/javac" -J-Xmx128m --release 17 -cp "$CP" -d "$BUILD_DIR" \
  "$ROOT/backend/hify-app/src/test/java/com/hify/api/ModuleStructureContract.java" \
  "$ROOT/backend/hify-app/src/test/java/com/hify/api/ModuleStructureContractTest.java" \
  "$ROOT/backend/hify-app/src/test/java/com/hify/api/MavenStructureTest.java" \
  "$ROOT/docs/research/jikesummary-20261006/structure-contract/StructureTestLauncher.java"
cd "$ROOT/backend/hify-app"
"$JAVA_BIN/java" -Xmx192m -cp "$BUILD_DIR:$CP" StructureTestLauncher
