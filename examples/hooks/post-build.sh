#!/usr/bin/env bash
# يُشغَّل بعد نجاح بناء APK.  المتغير APK_PATH يشير لملف الـAPK
echo "🪝 post-build: $(ls -l "$APK_PATH" | awk '{print $5}') bytes"
