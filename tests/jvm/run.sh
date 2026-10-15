#!/bin/bash
# Compiles the pure-JVM Kotlin sources (no android.* classes) and runs the tests. Needs kotlinc 1.9.x on PATH or $KOTLINC.
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; SRC="$ROOT/native/android/app/src/main/java/__APP_ID_PATH__"
OUT="$(mktemp -d)"; KC="${KOTLINC:-kotlinc}"
for f in analysis/WaveData.kt render/WaveSettings.kt render/WaveGeometry.kt scene/SceneModel.kt scene/SceneParser.kt scene/TextLayoutEngine.kt scene/TextAnimator.kt scene/WaveShapes.kt; do
  mkdir -p "$OUT/src/$(dirname $f)"; sed 's/__APP_ID__/app/g' "$SRC/$f" > "$OUT/src/$f"; done
cp "$ROOT/tests/jvm/SceneTests.kt" "$OUT/src/"
$KC -nowarn "$OUT"/src/*.kt "$OUT"/src/*/*.kt -include-runtime -d "$OUT/t.jar" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/t.jar" app.scene.SceneTestsKt
