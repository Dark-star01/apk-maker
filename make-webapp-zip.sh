#!/usr/bin/env bash
# Rebuilds webapp/app.zip from webapp_src/.
# APKMaker picks the NEWEST *.zip inside webapp/, so keep exactly one zip there.
set -euo pipefail
cd "$(dirname "$0")"
rm -f webapp/*.zip
(cd webapp_src && zip -q -r ../webapp/app.zip . -x '.*')
test "$(unzip -Z1 webapp/app.zip | grep -c '^index.html$')" -eq 1 || { echo "index.html must be at the zip root"; exit 1; }
echo "OK: webapp/app.zip ($(unzip -Z1 webapp/app.zip | grep -vc '/$') files)"
