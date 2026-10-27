#!/usr/bin/env bash
# Source this file or invoke scripts/build.sh. No shell profiles are changed.
set -euo pipefail
task_toolchain="${PICAPICA_TOOLCHAIN:-$HOME/.local/share/bone-drop-toolchain}"
if [[ -z "${JAVA_HOME:-}" && -d "$task_toolchain/jdk-17.0.20.1+1/Contents/Home" ]]; then
  export JAVA_HOME="$task_toolchain/jdk-17.0.20.1+1/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$task_toolchain/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
