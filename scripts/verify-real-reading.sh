#!/usr/bin/env bash
# Opt-in live test. Requires a user-entered session. The default work is rated all ages by Naver.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
task_serial="${1:-emulator-5554}"
task_phase="${2:-all}"
if [[ "$task_serial" != emulator-* ]]; then echo "仅限模拟器：此验证会临时关闭网络。" >&2; exit 2; fi
if [[ "$task_phase" != all && "$task_phase" != offline ]]; then echo "阶段只能为 all 或 offline。" >&2; exit 2; fi
task_adb=("$ANDROID_HOME/platform-tools/adb" -s "$task_serial")
task_runner="com.boneink.picapica.debug.test/androidx.test.runner.AndroidJUnitRunner"
run_test() {
  local task_method="$1" task_log="$2"
  shift 2
  "${task_adb[@]}" shell am instrument -w -r -e class "com.boneink.picapica.RealReadingTest#$task_method" "$@" "$task_runner" | tee "$task_log"
  if ! rg -q 'OK \(1 test' "$task_log" || rg -q 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$task_log"; then return 1; fi
}
if [[ "$task_phase" == all ]]; then
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
  "${task_adb[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
  "${task_adb[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  run_test realOnlineReadAndDownload artifacts/real-reading-online.log -e realComicFlow true -e comicId 6a1c5e2e200d604e2a4fed0e -e generalAudienceConfirmed true
fi
task_wifi="$("${task_adb[@]}" shell settings get global wifi_on | tr -d '\r')"
task_data="$("${task_adb[@]}" shell settings get global mobile_data | tr -d '\r')"
restore_network() {
  if [[ "$task_wifi" == 1 ]]; then "${task_adb[@]}" shell svc wifi enable; fi
  if [[ "$task_data" == 1 ]]; then "${task_adb[@]}" shell svc data enable; fi
}
trap restore_network EXIT
"${task_adb[@]}" shell am force-stop com.boneink.picapica.debug
"${task_adb[@]}" shell svc wifi disable
"${task_adb[@]}" shell svc data disable
run_test realOfflineReadAfterProcessRestart artifacts/real-reading-offline-restart.log -e realOffline true
"${task_adb[@]}" pull /sdcard/Android/data/com.boneink.picapica.debug/files/evidence artifacts/
"${task_adb[@]}" pull /sdcard/Android/data/com.boneink.picapica.debug/files/real-reading-manifest.json artifacts/
