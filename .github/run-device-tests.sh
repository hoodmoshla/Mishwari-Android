#!/usr/bin/env bash
# Run inside android-emulator-runner so screenshots are copied before emulator shutdown.
set -uo pipefail
set +e
adb shell rm -f /data/local/tmp/mishwari-redesign-*.png
clean_status=$?
if [ "$clean_status" -ne 0 ]; then
  echo 'Could not clear stale screenshot files before the device test.'
  exit "$clean_status"
fi
./gradlew --no-daemon :app:connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p app/build/outputs/ui-screenshots
pull_status=0
for name in \
  01-home.png 02-errands.png 03-travel.png 04-assistant.png \
  05-settings.png 06-places.png 07-errand-editor.png 08-home-dark.png; do
  output="app/build/outputs/ui-screenshots/$name"
  if ! adb pull "/data/local/tmp/mishwari-redesign-$name" "$output" >/dev/null; then
    echo "Could not retrieve actual UI screenshot: $name"
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
python3 - <<'PY'
from pathlib import Path
import struct
import sys
import zlib

root = Path('app/build/outputs/ui-screenshots')
signature = b'\x89PNG\r\n\x1a\n'
errors = []
for image in sorted(root.glob('*.png')):
    data = image.read_bytes()
    if not data.startswith(signature):
        errors.append(f'{image}: invalid PNG signature')
        continue
    offset = 8
    saw_ihdr = saw_iend = False
    while offset + 12 <= len(data):
        length = struct.unpack_from('>I', data, offset)[0]
        end = offset + 12 + length
        if end > len(data):
            break
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack_from('>I', data, offset + 8 + length)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != expected_crc:
            errors.append(f'{image}: invalid CRC in {kind!r}')
            break
        if kind == b'IHDR':
            width, height = struct.unpack_from('>II', payload) if len(payload) == 13 else (0, 0)
            saw_ihdr = len(payload) == 13 and width > 0 and height > 0
        if kind == b'IEND':
            saw_iend = length == 0
            break
        offset = end
    if not saw_ihdr or not saw_iend:
        errors.append(f'{image}: truncated or structurally invalid PNG')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
count = len(list(root.glob('*.png')))
if count != 8:
    raise SystemExit(f'Expected exactly eight actual app PNG screenshots, found {count}.')
print(f'Validated {count} complete PNG screenshots from the Android emulator.')
PY
image_status=$?
if [ "$image_status" -ne 0 ]; then
  echo 'UI screenshot validation failed; refusing to mark device verification complete.'
  exit "$image_status"
fi
count=$(find app/build/outputs/ui-screenshots -type f -name '*.png' | wc -l)
echo "Preserved $count actual app screenshots from the Android emulator."
