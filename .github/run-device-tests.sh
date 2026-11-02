#!/usr/bin/env bash
# Run inside android-emulator-runner; reconstruct screenshots only from validated PNG bytes emitted by the instrumented test.
set -uo pipefail
set +e

adb logcat -G 16M
buffer_status=$?
if [ "$buffer_status" -ne 0 ]; then
  echo 'Could not enlarge the emulator log buffer for screenshot transfer.'
  exit "$buffer_status"
fi
# Each CI job starts a fresh emulator, so the log buffer does not need clearing.
rm -rf app/build/outputs/ui-screenshots
mkdir -p app/build/outputs/ui-screenshots

./gradlew --no-daemon :app:connectedDebugAndroidTest --stacktrace
test_status=$?
if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi

python3 - <<'PY'
from pathlib import Path
import base64
import struct
import sys
import zlib

expected = {
    '01-home.png', '02-errands.png', '03-travel.png', '04-assistant.png',
    '05-settings.png', '06-places.png', '07-errand-editor.png', '08-home-dark.png',
}
log_root = Path('app/build/outputs/androidTest-results/connected/debug')
logs = sorted(log_root.rglob('logcat-ye.mishwari.RedesignScreenshotInstrumentedTest-capturesFiveTabsPlacesAndErrandEditor.txt'))
if not logs:
    raise SystemExit('Could not find the instrumented screenshot test logcat output.')

begins = {}
chunks = {}
ended = set()
errors = []
for path in logs:
    for line in path.read_text(errors='replace').splitlines():
        marker = 'MishwariShot:'
        if marker not in line:
            continue
        payload = line.split(marker, 1)[1].strip()
        action, separator, tail = payload.partition(':')
        if action == 'END':
            if separator and tail in expected:
                ended.add(tail)
            continue
        fields = payload.split(':', 3)
        if len(fields) != 4:
            continue
        action, name, value, rest = fields
        if name not in expected:
            continue
        if action == 'BEGIN':
            try:
                metadata = (int(value), int(rest))
            except ValueError:
                errors.append(f'{path}: invalid BEGIN metadata for {name}')
                continue
            if name in begins and begins[name] != metadata:
                errors.append(f'{path}: conflicting BEGIN metadata for {name}')
            begins[name] = metadata
        elif action == 'DATA':
            try:
                index = int(value)
            except ValueError:
                errors.append(f'{path}: invalid DATA index for {name}')
                continue
            previous = chunks.setdefault(name, {}).get(index)
            if previous is not None and previous != rest:
                errors.append(f'{path}: conflicting DATA chunk {index} for {name}')
            chunks[name][index] = rest

root = Path('app/build/outputs/ui-screenshots')
signature = b'\x89PNG\r\n\x1a\n'

def validate_png(image, data):
    if not data.startswith(signature):
        return 'invalid PNG signature'
    offset = 8
    saw_ihdr = saw_iend = False
    while offset + 12 <= len(data):
        length = struct.unpack_from('>I', data, offset)[0]
        end = offset + 12 + length
        if end > len(data):
            return 'truncated PNG chunk'
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack_from('>I', data, offset + 8 + length)[0]
        if zlib.crc32(kind + payload) & 0xffffffff != expected_crc:
            return f'invalid CRC in {kind!r}'
        if kind == b'IHDR':
            if len(payload) != 13:
                return 'invalid IHDR length'
            width, height = struct.unpack_from('>II', payload)
            saw_ihdr = width > 0 and height > 0
        if kind == b'IEND':
            saw_iend = length == 0
            offset = end
            break
        offset = end
    if not saw_ihdr or not saw_iend or offset != len(data):
        return 'PNG is structurally incomplete'
    return None

for name in sorted(expected):
    if name not in begins:
        errors.append(f'missing BEGIN record for {name}')
        continue
    expected_size, total_chunks = begins[name]
    parts = chunks.get(name, {})
    if total_chunks <= 0 or set(parts) != set(range(total_chunks)):
        errors.append(f'incomplete Base64 chunks for {name}: expected {total_chunks}, got {len(parts)}')
        continue
    if name not in ended:
        errors.append(f'missing END record for {name}')
        continue
    try:
        data = base64.b64decode(''.join(parts[i] for i in range(total_chunks)), validate=True)
    except Exception as exc:
        errors.append(f'invalid Base64 for {name}: {exc}')
        continue
    if len(data) != expected_size:
        errors.append(f'wrong byte count for {name}: expected {expected_size}, got {len(data)}')
        continue
    problem = validate_png(name, data)
    if problem:
        errors.append(f'{name}: {problem}')
        continue
    (root / name).write_bytes(data)

if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
if set(p.name for p in root.glob('*.png')) != expected:
    raise SystemExit('Expected exactly the eight named, verified app screenshots.')
print(f'Reconstructed and validated {len(expected)} complete PNG screenshots from the Android emulator logs.')
PY
image_status=$?
if [ "$image_status" -ne 0 ]; then
  echo 'UI screenshot reconstruction or validation failed; refusing to mark device verification complete.'
  exit "$image_status"
fi
count=$(find app/build/outputs/ui-screenshots -type f -name '*.png' | wc -l)
echo "Preserved $count validated screenshots from the Android emulator."
