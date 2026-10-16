#!/bin/bash
# JS -> Kotlin wire contract: the scene JSON the web app produces is parsed field by field and rendered by the real Renderer (stub canvas).
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; SRC="$ROOT/native/android/app/src/main/java/__APP_ID_PATH__"
OUT="$(mktemp -d)"; KC="${KOTLINC:-kotlinc}"
node "$ROOT/tests/web/dump-payloads.mjs" "$OUT/payloads.json"
for f in analysis/WaveData.kt effects/*.kt render/WaveSettings.kt render/WaveGeometry.kt render/WaveOverlay.kt render/FrameComposer.kt render/FrameGeometry.kt render/Renderer.kt scene/*.kt; do
  for g in $SRC/$f; do rel="${g#$SRC/}"; mkdir -p "$OUT/src/$(dirname $rel)"; sed 's/__APP_ID__/app/g' "$g" > "$OUT/src/$rel"; done; done
cp "$ROOT/tests/jvm/stubs/AndroidGraphicsStubs.kt" "$ROOT/tests/jvm/WireTest.kt" "$OUT/src/"
$KC -nowarn "$OUT"/src/*.kt "$OUT"/src/*/*.kt -include-runtime -d "$OUT/w.jar" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/w.jar" app.scene.WireTestKt "$OUT/payloads.json"
