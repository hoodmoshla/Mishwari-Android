#!/usr/bin/env bash
# Run inside android-emulator-runner so screenshots are copied before emulator shutdown.
set -uo pipefail
set +e
./gradlew --no-daemon :app:connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p app/build/outputs/ui-screenshots
adb pull /sdcard/Android/data/ye.mishwari/files/screenshots app/build/outputs/ui-screenshots/
pull_status=$?
if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi
if [ "$pull_status" -ne 0 ]; then
  echo 'UI screenshot collection failed; refusing to mark device verification complete.'
  exit "$pull_status"
fi
count=$(find app/build/outputs/ui-screenshots -type f -name '*.png' | wc -l)
if [ "$count" -lt 5 ]; then
  echo "Expected at least five actual app screenshots, found $count."
  exit 1
fi
echo "Preserved $count actual app screenshots from the Android emulator."
