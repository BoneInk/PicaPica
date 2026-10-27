#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/android-env.sh
if [[ $# -eq 0 ]]; then set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug; fi
./gradlew "$@"
