#!/usr/bin/env bash
# M5 device gap-closer: durable block force-stopped mid-delay(20s), relaunched after the
# timer. Passes iff each step executed exactly once across the death and the work SUCCEEDED.
set -euo pipefail

PKG="io.github.iamjosephmj.bench"

prime() {
  adb shell pm unstop "$PKG" >/dev/null 2>&1 || true
  adb shell am set-standby-bucket "$PKG" active >/dev/null 2>&1 || true
}
prime

echo "[durable-fs] enqueueing durable block"
adb shell am broadcast --receiver-foreground --include-stopped-packages \
  -n "$PKG/.BenchReceiver" -a bench.ENQUEUE_DURABLE >/dev/null

echo "[durable-fs] +6s: force-stop (block should be parked mid-delay)"
sleep 6
adb shell am force-stop "$PKG"

echo "[durable-fs] +30s: relaunch (timer elapsed while dead); reconcile replays"
sleep 24
prime
adb shell am broadcast --receiver-foreground --include-stopped-packages \
  -n "$PKG/.BenchReceiver" -a bench.WAKE >/dev/null 2>&1 || true

echo "[durable-fs] waiting up to 90s for completion"
for i in $(seq 1 18); do
  sleep 5
  adb shell am broadcast --receiver-foreground -n "$PKG/.BenchReceiver" \
    -a bench.DURABLE_REPORT >/dev/null
  sleep 1
  DEVICE_DIR="/sdcard/Android/data/$PKG/files"
  LATEST=$(adb shell ls -t "$DEVICE_DIR" 2>/dev/null | tr -d '\r' | grep '^report-durable-fs-' | head -1)
  [ -z "$LATEST" ] && continue
  adb pull "$DEVICE_DIR/$LATEST" /tmp/durable-fs.json >/dev/null 2>&1
  STATE=$(python3 -c "import json;print(json.load(open('/tmp/durable-fs.json'))['state'])")
  [ "$STATE" = "SUCCEEDED" ] && break
done

mkdir -p "$(dirname "$0")/reports"
cp /tmp/durable-fs.json "$(dirname "$0")/reports/$LATEST"
python3 - "$(dirname "$0")/reports/$LATEST" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
print(f"\ndurable force-stop — {r['device']['model']} (API {r['device']['sdk']})")
for k in ("state","firstStepExecutions","secondStepExecutions",
          "stepEventsJournaled","parks","deaths"):
    print(f"  {k}: {r[k]}")
ok = (r["state"] == "SUCCEEDED" and r["firstStepExecutions"] == 1
      and r["secondStepExecutions"] == 1 and r["parks"] >= 1)
print("\nPASS" if ok else "\nFAIL")
sys.exit(0 if ok else 1)
EOF
