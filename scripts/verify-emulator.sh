#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
task_serial="${1:-emulator-5554}"
if [[ "$task_serial" != emulator-* ]]; then
  echo "此脚本仅用于模拟器：包含关闭网络与重启测试。" >&2
  exit 2
fi
task_adb=("$ANDROID_HOME/platform-tools/adb" -s "$task_serial")
task_package="com.boneink.picapica.debug"
task_runner="$task_package.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p artifacts
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
"${task_adb[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${task_adb[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
task_wifi_state="$("${task_adb[@]}" shell settings get global wifi_on | tr -d '\r')"
task_data_state="$("${task_adb[@]}" shell settings get global mobile_data | tr -d '\r')"
restore_radios() {
  if [[ "$task_wifi_state" == 1 ]]; then "${task_adb[@]}" shell svc wifi enable; fi
  if [[ "$task_data_state" == 1 ]]; then "${task_adb[@]}" shell svc data enable; fi
}
trap restore_radios EXIT
run_instrumentation() {
  local task_class="$1" task_log="$2"
  shift 2
  "${task_adb[@]}" shell am instrument -w -r -e class "$task_class" "$@" "$task_runner" | tee "$task_log"
  if ! rg -q 'OK \([0-9]+ test' "$task_log" || rg -q 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$task_log"; then
    echo "Instrumentation failed: $task_class" >&2
    exit 1
  fi
}
run_instrumentation com.boneink.picapica.LoginFailureTest artifacts/login-failure.log
run_instrumentation com.boneink.picapica.ShortcutsAndDeepRepliesTest artifacts/shortcuts-deep-replies-0.11.0.log
run_instrumentation com.boneink.picapica.BlockingAndInlineTest artifacts/blocking-inline-0.10.0.log
run_instrumentation com.boneink.picapica.MetadataMigrationTest artifacts/metadata-migration-0.10.0.log
run_instrumentation com.boneink.picapica.SettingsTest artifacts/settings-ui-0.9.0.log
"${task_adb[@]}" shell am force-stop "$task_package"
run_instrumentation com.boneink.picapica.SettingsRestartTest artifacts/settings-restart-0.9.0.log
run_instrumentation com.boneink.picapica.SearchFiltersTest artifacts/search-filters-ui-0.8.0.log
run_instrumentation com.boneink.picapica.UiLayoutTest artifacts/ui-layout-0.9.0.log
run_instrumentation com.boneink.picapica.BoardNavigationTest artifacts/board-navigation-ui-0.6.0.log
run_instrumentation com.boneink.picapica.CommentThreadsTest artifacts/comment-threads-native-0.5.0.log
run_instrumentation com.boneink.picapica.UserPagesTest artifacts/user-pages-ui-0.4.0.log
run_instrumentation com.boneink.picapica.CommentsReaderTest artifacts/comments-reader-ui-0.3.0.log
run_instrumentation com.boneink.picapica.DiscoveryFlowTest artifacts/discovery-flow.log
run_instrumentation com.boneink.picapica.DownloadIntegrityTest artifacts/download-integrity.log
run_instrumentation com.boneink.picapica.CoverBackfillTest artifacts/cover-backfill-0.3.0.log
run_instrumentation com.boneink.picapica.CoreFlowTest artifacts/core-flow.log
"${task_adb[@]}" shell am force-stop "$task_package"
"${task_adb[@]}" shell svc wifi disable
"${task_adb[@]}" shell svc data disable
run_instrumentation com.boneink.picapica.OfflineRestartTest artifacts/offline-restart.log -e offlineRestart true
"${task_adb[@]}" pull "/sdcard/Android/data/$task_package/files/evidence" artifacts/
echo "已验证：长按屏蔽与撤销、封面内标题作者、分类导航与排行榜入口、深层楼中楼原地展开和定向回复、内容屏蔽与元数据展示、最近评论内展开回复、数据库升级保留数据、官方评论区、评论与作品往返、楼中楼与历史直达、用户资料、限流和协议错误提示、防重复登录、评论分页与回复、发送与点赞、横竖切换与缩放手势、离线封面、升级补封面、登录、搜索、阅读、下载、中断重试、文件修复、关闭网络后重启续读（本地联调数据）。"
