#!/usr/bin/env bash
# Fails the build early if the native bridge was not really applied (APKMaker skips silently otherwise).
set -e
APP_ID=$(jq -r '.appId' config.json)
DIR="android/app/src/main/java/$(echo "$APP_ID" | tr . /)"
fail() { echo "❌ MVM guard: $1"; exit 1; }
[ -f "$DIR/MainActivity.kt" ]            || fail "MainActivity.kt missing in $DIR (native/android not applied?)"
[ -f "$DIR/bridge/MvmBridgePlugin.kt" ]  || fail "bridge/MvmBridgePlugin.kt missing"
[ ! -f "$DIR/MainActivity.java" ]        || fail "default MainActivity.java still present (would shadow Kotlin one)"
grep -q "registerPlugin(MvmBridgePlugin" "$DIR/MainActivity.kt" || fail "plugin not registered in MainActivity.kt"
grep -q '@CapacitorPlugin(name = "MvmBridge")' "$DIR/bridge/MvmBridgePlugin.kt" || fail "@CapacitorPlugin missing"
if grep -rl "__APP_ID" "$DIR" >/dev/null 2>&1; then fail "unreplaced __APP_ID placeholder in Kotlin files"; fi
grep -q "^package $APP_ID" "$DIR/MainActivity.kt" || fail "MainActivity package != appId"
[ -f www/index.html ] && grep -q 'js/main.js' www/index.html || fail "www/index.html is not the Music Video Maker app"
echo "✅ MVM guard: native bridge files are in place ($(find "$DIR" -name '*.kt' | wc -l) Kotlin files)"
[ -f "$DIR/bridge/MvmDiagPlugin.kt" ] && [ -f "$DIR/bridge/BootDiag.kt" ] || { echo "❌ MVM guard: diag plugin files missing"; exit 1; }
[ -f "$DIR/audio/AudioEngine.kt" ] || { echo "❌ MVM guard: audio/AudioEngine.kt missing"; exit 1; }
for f in AudioAnalyzer PcmAnalyzer WaveData; do [ -f "$DIR/analysis/$f.kt" ] || { echo "❌ MVM guard: analysis/$f.kt missing"; exit 1; }; done
for f in Renderer RenderLoop PreviewController FrameGeometry FrameComposer PlaybackClock BackgroundLoader; do [ -f "$DIR/render/$f.kt" ] || { echo "❌ MVM guard: render/$f.kt missing"; exit 1; }; done
for f in EffectEngine EffectSettings EffectState AudioEnvelope BackgroundMotion DeterministicNoise Presets; do [ -f "$DIR/effects/$f.kt" ] || { echo "❌ MVM guard: effects/$f.kt missing"; exit 1; }; done
