#!/usr/bin/env bash
# Instrumented + e2e tests on an emulator, without touching a real install.
#
#   scripts/test-device.sh                      # every androidTest, first emulator found
#   scripts/test-device.sh -s emulator-5556     # a given device
#   scripts/test-device.sh -c io.lawallet.wallet.e2e.SendE2eTest   # one class (or Class#method)
#
# Builds a side-by-side `.e2e` copy (-PidSuffix=.e2e), so the real io.lawallet.wallet keeps its account, and runs it
# with `am instrument` (connectedDebugAndroidTest would uninstall afterwards). Docs: docs/testing.md.
set -euo pipefail
cd "$(dirname "$0")/.."

serial="" class=""
while getopts "s:c:" o; do
  case $o in
    s) serial=$OPTARG ;;
    c) class=$OPTARG ;;
    *) echo "usage: $0 [-s serial] [-c class[#method]]" >&2; exit 2 ;;
  esac
done
serial=${serial:-$(adb devices | awk '/^emulator-.*\tdevice$/ {print $1; exit}')}
[ -n "$serial" ] || { echo "no emulator running; start one or pass -s <serial>" >&2; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew -PidSuffix=.e2e assembleDebug assembleDebugAndroidTest
adb -s "$serial" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Fresh state every run: only the .e2e copy is cleared.
adb -s "$serial" shell pm clear io.lawallet.wallet.e2e >/dev/null
adb -s "$serial" shell pm grant io.lawallet.wallet.e2e android.permission.POST_NOTIFICATIONS 2>/dev/null || true

start=$(date +%s)
out=$(adb -s "$serial" shell am instrument -w -r ${class:+-e class "$class"} \
  io.lawallet.wallet.e2e.test/androidx.test.runner.AndroidJUnitRunner)
# One line per test (raw mode prints each status block twice: started, then its result code).
echo "$out" | awk '
  /^INSTRUMENTATION_STATUS: class=/ { cls = substr($0, index($0, "=") + 1); sub(/.*\./, "", cls) }
  /^INSTRUMENTATION_STATUS: test=/  { test = substr($0, index($0, "=") + 1) }
  /^INSTRUMENTATION_STATUS_CODE: / {
    code = $2
    r = code == 0 ? "ok" : code == -2 ? "FAILED" : code == -1 ? "ERROR" : code == -3 ? "ignored" : code == -4 ? "skipped (assumption)" : ""
    if (r != "") printf "  %-8s %s#%s\n", r, cls, test
  }'
echo "$out" | grep -E "^OK \(|^Tests run|^INSTRUMENTATION_RESULT: (shortMsg|longMsg)" || true
echo "took $(( $(date +%s) - start ))s"
if ! echo "$out" | grep -q "^OK (" || echo "$out" | grep -q "^INSTRUMENTATION_RESULT: shortMsg"; then
  echo "$out" | grep -A25 "INSTRUMENTATION_STATUS: stack=" | grep -v "^INSTRUMENTATION_STATUS: \(class\|test\|current\|id\|numtests\|stream\)" | head -80 || true
  exit 1
fi
