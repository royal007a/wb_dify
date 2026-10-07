#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
JAVA_BIN=${HIFY_JAVA_HOME:?set HIFY_JAVA_HOME to JDK 17}/bin
BUILD_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hify-chunk-characterization.XXXXXX")
SOURCE=backend/hify-knowledge/src/main/java/com/hify/knowledge/application/RecursiveTextChunker.java
COMMIT=e7d43b17882dbf164915d73b271411c439a995da
git -C "$ROOT" show "$COMMIT:$SOURCE" > "$BUILD_DIR/RecursiveTextChunker.java"
ACTUAL=$(shasum -a 256 "$BUILD_DIR/RecursiveTextChunker.java" | awk '{print $1}')
test "$ACTUAL" = b7b34d715ce6ba9e84561435fd191641e13223523ace8e8a2bbfffb1af5737c0
printf 'source=%s\nsha256=%s\n' "$COMMIT:$SOURCE" "$ACTUAL"
"$JAVA_BIN/javac" -J-Xmx128m --release 17 -d "$BUILD_DIR" \
  "$BUILD_DIR/RecursiveTextChunker.java" \
  "$ROOT/docs/research/jikesummary-20261006/chunk-integrity/ChunkProbe.java" \
  "$ROOT/docs/research/jikesummary-20261006/chunk-integrity/ChunkBoundaryMatrix.java"
"$JAVA_BIN/java" -Xmx64m -cp "$BUILD_DIR" ChunkProbe
"$JAVA_BIN/java" -Xmx64m -cp "$BUILD_DIR" ChunkBoundaryMatrix
