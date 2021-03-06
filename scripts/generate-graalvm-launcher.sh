#!/usr/bin/env bash
set -eu

BASE="$(dirname "${BASH_SOURCE[0]}")"

ASSEMBLY="coursier-assembly"

cleanup() {
  rm -f "$ASSEMBLY"
}

trap cleanup EXIT INT TERM


OUTPUT="$ASSEMBLY" "$BASE/generate-launcher.sh" --assembly

OUTPUT="coursier-graalvm"

"$GRAALVM_HOME/bin/native-image" \
  --enable-http \
  --enable-https \
  -H:ReflectionConfigurationFiles="$BASE/reflection.json" \
  --no-fallback \
  --no-server \
  -jar "$ASSEMBLY" \
  "$OUTPUT"

echo "Generate graalvm native image $OUTPUT."
echo "Run like"
echo "  coursier-graalvm -Djava.library.path=\$GRAALVM_HOME/jre/lib …args…"
