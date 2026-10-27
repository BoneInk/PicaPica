#!/usr/bin/env bash
# Foreground emulator launcher; does not change macOS or Android global proxy settings.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
task_avd="${PICAPICA_AVD:-PicaPica_API35}"
task_proxy="${PICAPICA_EMULATOR_PROXY:-}"
if [[ -z "$task_proxy" && "$(uname -s)" == Darwin ]]; then
  task_proxy="$(python3 - <<'PY'
import re, subprocess
settings = subprocess.check_output(['scutil', '--proxy'], text=True)
def read(key):
    match = re.search(r'^\s*' + key + r'\s*:\s*(\S+)', settings, re.M)
    return match.group(1) if match else ''
if read('HTTPSEnable') == '1' and read('HTTPSProxy') and read('HTTPSPort').isdigit():
    print('http://' + read('HTTPSProxy') + ':' + read('HTTPSPort'))
PY
)"
fi
# Software rendering avoids the observed gfxstream/Vulkan color-buffer failure on this Mac.
# The renderer can still be selected explicitly for other hosts.
task_gpu="${PICAPICA_EMULATOR_GPU:-software}"
task_args=(-avd "$task_avd" -no-snapshot -no-audio -gpu "$task_gpu" -no-metrics -no-boot-anim -scale 0.75)
if [[ "$task_gpu" == software ]]; then task_args+=(-feature -Vulkan); fi
if [[ -n "$task_proxy" ]]; then task_args+=(-http-proxy "$task_proxy"); fi
# Shell proxies may differ from the user's current macOS proxy. Use the explicit choice above.
exec env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy \
  "$ANDROID_HOME/emulator/emulator" "${task_args[@]}"
