#!/usr/bin/env bash
# M2 stall scenario: same work on both backends, device demoted so neither runs,
# then both APIs asked "why?". WorkManager's whole answer is one enum; Bridge's is a verdict.
#
# Usage: ./run-stall.sh [observation_seconds] [--restricted]
#   --restricted additionally denies RUN_ANY_IN_BACKGROUND (the harsher variant).
set -euo pipefail

PKG="io.github.iamjosephmj.bench"
OBS="${1:-120}"
VARIANT="${2:-}"

cleanup() {
  echo "[stall] restoring device state"
  adb shell am set-standby-bucket "$PKG" active || true
  adb shell cmd appops reset "$PKG" || true
}
trap cleanup EXIT

echo "[stall] NOT priming — demoting instead"
adb shell am set-standby-bucket "$PKG" rare
if [ "$VARIANT" = "--restricted" ]; then
  adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND deny
fi

echo "[stall] enqueueing corpus on both backends"
adb shell am broadcast -a bench.ENQUEUE_WM -n "$PKG/.BenchReceiver"
adb shell am broadcast -a bench.ENQUEUE_BRIDGE -n "$PKG/.BenchReceiver"

echo "[stall] observing for ${OBS}s (work should NOT run)"
sleep "$OBS"

echo "[stall] collecting both answers"
adb shell am broadcast -a bench.STALL_REPORT -n "$PKG/.BenchReceiver"
sleep 3

DEVICE_DIR="/sdcard/Android/data/$PKG/files"
LATEST=$(adb shell ls -t "$DEVICE_DIR" | tr -d '\r' | grep '^report-stall-' | head -1)
mkdir -p "$(dirname "$0")/reports"
adb pull "$DEVICE_DIR/$LATEST" "$(dirname "$0")/reports/" >/dev/null
echo "[stall] pulled: reports/$LATEST"

python3 - "$(dirname "$0")/reports/$LATEST" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
d = r["device"]
print(f"\nstall scenario — {d['manufacturer']} {d['model']} (API {d['sdk']})")
print(f"{'item':<18}{'workmanager':<14}bridge")
for it in r["items"]:
    print(f"{it['item']:<18}{it['workmanager']['state']:<14}"
          f"{it['bridge']['diagnosis']} [{it['bridge']['basis']}]")
EOF
