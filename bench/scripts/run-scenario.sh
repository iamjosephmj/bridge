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

# Real-device notes (Pixel 6 Pro, API 36 — Task 16 acceptance run):
#  1. A freshly installed/never-launched app sits in the package-manager "stopped" state,
#     in which even an explicit broadcast to a manifest receiver is dropped (and, separately,
#     shell (uid 2000) cannot reach a non-exported manifest receiver at all on this OS
#     build). `pm unstop` clears the stopped bit; the bench app's receiver was made
#     exported="true" (Task 16) so shell can address it explicitly.
#  2. A never-launched app also sits in the "NEVER" app-standby bucket, under which
#     JobScheduler's WITHIN_QUOTA constraint is never satisfied — Bridge's jobs (which route
#     every run through a real dispatched JobScheduler job, by design) then simply never get
#     execution quota within the scenario window. WorkManager's GreedyScheduler can mask
#     this because it runs work in-process while the app happens to be alive, which is not
#     the comparison this benchmark is trying to make. Force the bucket to ACTIVE so both
#     backends get a fair, unthrottled shot at running.
prime() {
  $ADB shell pm unstop "$PKG" >/dev/null 2>&1 || true
  $ADB shell am set-standby-bucket "$PKG" active >/dev/null 2>&1 || true
}
prime

$ADB shell am broadcast --receiver-foreground --include-stopped-packages -n "$PKG/.BenchReceiver" -a "$ACTION" >/dev/null
echo "enqueued corpus on $BACKEND"

case "$SCENARIO" in
  baseline)
    sleep 120 ;;
  force-stop)
    sleep 20
    $ADB shell am force-stop "$PKG"
    echo "force-stopped; relaunching in 10s"
    sleep 10
    # `monkey -p $PKG 1` (launch by LAUNCHER category) is a no-op here — the bench app is
    # headless and declares no launcher Activity ("No activities found to run, monkey
    # aborted"). force-stop also re-enters the "stopped"/"NEVER"-bucket state, so re-prime
    # and send an explicit, --include-stopped-packages broadcast to relaunch the process
    # instead; this runs BenchApp.onCreate -> Bridge.initialize, which is where
    # reconciliation happens.
    prime
    $ADB shell am broadcast --receiver-foreground --include-stopped-packages \
      -n "$PKG/.BenchReceiver" -a bench.WAKE >/dev/null 2>&1 || true
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

prime
$ADB shell am broadcast --receiver-foreground --include-stopped-packages -n "$PKG/.BenchReceiver" \
  -a bench.DUMP_REPORT --es backend "$BACKEND" >/dev/null
sleep 2
REMOTE=$($ADB shell "ls -t /sdcard/Android/data/$PKG/files/report-$BACKEND-*.json | head -1" | tr -d '\r')
OUT="reports/$(basename "$REMOTE" .json)-$SCENARIO.json"
mkdir -p reports
$ADB pull "$REMOTE" "$OUT" >/dev/null
echo "report: $OUT"
