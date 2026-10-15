#!/bin/bash
# Type-checks and smoke-runs the Android-facing drawing code against stand-in android.graphics stubs (NOT the real SDK).
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; SRC="$ROOT/native/android/app/src/main/java/__APP_ID_PATH__"
OUT="$(mktemp -d)"; KC="${KOTLINC:-kotlinc}"
for f in analysis/WaveData.kt effects/*.kt render/WaveSettings.kt render/WaveGeometry.kt render/WaveOverlay.kt render/FrameComposer.kt render/FrameGeometry.kt render/Renderer.kt scene/*.kt; do
  for g in $SRC/$f; do rel="${g#$SRC/}"; mkdir -p "$OUT/src/$(dirname $rel)"; sed 's/__APP_ID__/app/g' "$g" > "$OUT/src/$rel"; done; done
cp "$ROOT/tests/jvm/stubs/AndroidGraphicsStubs.kt" "$ROOT/tests/jvm/SmokeTest.kt" "$OUT/src/"
$KC -nowarn "$OUT"/src/*.kt "$OUT"/src/*/*.kt -include-runtime -d "$OUT/s.jar" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/s.jar" app.scene.SmokeTestKt
