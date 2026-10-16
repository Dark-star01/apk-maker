#!/bin/bash
# Runs the web UI flow test (needs Node 20+ and `npm i jsdom` in a folder of your choice: JSDOM_DIR, default ./node_modules).
HERE="$(cd "$(dirname "$0")" && pwd)"; D="${JSDOM_DIR:-$HERE}"
cp "$HERE/ui-flow.mjs" "$D/ui-flow.run.mjs" && WEBAPP_ROOT="$HERE/../../webapp_src" node "$D/ui-flow.run.mjs"
