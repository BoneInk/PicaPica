#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
task_serial="${1:-emulator-5554}"
task_adb=("$ANDROID_HOME/platform-tools/adb" -s "$task_serial")
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
"${task_adb[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${task_adb[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"${task_adb[@]}" shell am instrument -w -r -e class com.boneink.picapica.LiveEndpointTest -e liveProbe true \
  com.boneink.picapica.debug.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/live-device-probe.log
rg -q 'OK \(1 test' artifacts/live-device-probe.log
! rg -q 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=' artifacts/live-device-probe.log
