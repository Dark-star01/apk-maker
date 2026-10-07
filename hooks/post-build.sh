#!/usr/bin/env bash
# Verifies the built APK really contains the bridge plugin and the web app.
set -e
fail() { echo "❌ MVM guard: $1"; exit 1; }
[ -f "$APK_PATH" ] || fail "APK not found at $APK_PATH"
T=$(mktemp -d); unzip -q -o "$APK_PATH" -d "$T"
cat "$T"/classes*.dex | strings | grep -q "MvmBridgePlugin" || fail "MvmBridgePlugin is not inside the APK dex"
cat "$T"/classes*.dex | strings | grep -q "pickMediaResult"  || fail "pickMediaResult missing from the APK dex"
cat "$T"/classes*.dex | strings | grep -q "MvmDiagPlugin"     || fail "MvmDiagPlugin missing from the APK dex"
cat "$T"/classes*.dex | strings | grep -q "AudioEngine"     || fail "AudioEngine missing from the APK dex"
cat "$T"/classes*.dex | strings | grep -q "MediaManager"     || fail "MediaManager missing from the APK dex"
[ -f "$T/assets/public/index.html" ] || fail "assets/public/index.html missing"
grep -q 'js/main.js' "$T/assets/public/index.html" || fail "packaged index.html is not the app"
[ -f "$T/assets/public/js/core/bridge.js" ] || fail "bridge.js missing in APK assets"
echo "✅ MVM guard: APK contains MvmBridgePlugin + MediaManager + web app"
