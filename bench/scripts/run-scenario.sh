#!/usr/bin/env bash
# Usage: ./run-scenario.sh <bridge|workmanager> <baseline|force-stop|doze> [serial]
set -euo pipefail
BACKEND="$1"; SCENARIO="$2"; SERIAL="${3:-}"
ADB="adb ${SERIAL:+-s $SERIAL}"
PKG="io.github.iamjosephmj.bench"

case "$BACKEND" in
  bridge) ACTION="bench.ENQUEUE_BRIDGE" ;;
  workmanager) ACTION="bench.ENQUEUE_WM" ;;
  *) echo "backend must be bridge|workmanager"; exit 1 ;;
esac

$ADB shell am broadcast --receiver-foreground -n "$PKG/.BenchReceiver" -a "$ACTION" >/dev/null
echo "enqueued corpus on $BACKEND"

case "$SCENARIO" in
  baseline)
    sleep 120 ;;
  force-stop)
    sleep 20
    $ADB shell am force-stop "$PKG"
    echo "force-stopped; relaunching in 10s"
    sleep 10
    $ADB shell monkey -p "$PKG" 1 >/dev/null 2>&1 || true   # relaunch → Bridge reconciles
    sleep 120 ;;
  doze)
    sleep 20
    $ADB shell dumpsys deviceidle force-idle
    echo "forced deep idle for 60s"
    sleep 60
    $ADB shell dumpsys deviceidle unforce
    $ADB shell dumpsys battery reset
    sleep 120 ;;
  *) echo "scenario must be baseline|force-stop|doze"; exit 1 ;;
esac

$ADB shell am broadcast --receiver-foreground -n "$PKG/.BenchReceiver" \
  -a bench.DUMP_REPORT --es backend "$BACKEND" >/dev/null
sleep 2
REMOTE=$($ADB shell "ls -t /sdcard/Android/data/$PKG/files/report-$BACKEND-*.json | head -1" | tr -d '\r')
OUT="reports/$(basename "$REMOTE" .json)-$SCENARIO.json"
mkdir -p reports
$ADB pull "$REMOTE" "$OUT" >/dev/null
echo "report: $OUT"
