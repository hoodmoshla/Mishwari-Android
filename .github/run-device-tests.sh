#!/usr/bin/env bash
# Run inside android-emulator-runner so screenshots are copied before emulator shutdown.
set -uo pipefail
set +e
./gradlew --no-daemon :app:connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p app/build/outputs/ui-screenshots
pull_status=0
for name in \
  01-home.png 02-errands.png 03-travel.png 04-assistant.png \
  05-settings.png 06-places.png 07-errand-editor.png 08-home-dark.png; do
  output="app/build/outputs/ui-screenshots/$name"
  if ! adb exec-out run-as ye.mishwari cat "cache/redesign-screenshots/$name" > "$output"; then
    echo "Could not retrieve actual UI screenshot: $name"
    pull_status=1
  elif [ ! -s "$output" ]; then
    echo "Retrieved screenshot is empty: $name"
    pull_status=1
  fi
done
if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi
if [ "$pull_status" -ne 0 ]; then
  echo 'UI screenshot collection failed; refusing to mark device verification complete.'
  exit "$pull_status"
fi
count=$(find app/build/outputs/ui-screenshots -type f -name '*.png' -size +0c | wc -l)
if [ "$count" -lt 8 ]; then
  echo "Expected eight actual app screenshots, found $count."
  exit 1
fi
echo "Preserved $count actual app screenshots from the Android emulator."
