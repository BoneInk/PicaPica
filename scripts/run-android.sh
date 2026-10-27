#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
task_serial="${1:-emulator-5554}"
"$ANDROID_HOME/platform-tools/adb" -s "$task_serial" get-state
./gradlew :app:assembleDebug --console=plain
"$ANDROID_HOME/platform-tools/adb" -s "$task_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$task_serial" shell am start -n com.boneink.picapica.debug/com.boneink.picapica.MainActivity
