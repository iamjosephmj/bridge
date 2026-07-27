#!/usr/bin/env python3
"""Usage: compare-reports.py <bridge-report.json> <workmanager-report.json>"""
import json, sys

def load(path):
    with open(path) as f:
        d = json.load(f)
    return {r["itemId"]: r for r in d["records"]}, d["device"]

bridge, device = load(sys.argv[1])
wm, _ = load(sys.argv[2])

print(f"device: {device.get('manufacturer')} {device.get('model')} (sdk {device.get('sdk')})")
hdr = f"{'item':28} {'metric':20} {'bridge':>12} {'workmanager':>12}"
print(hdr); print("-" * len(hdr))
for item in sorted(set(bridge) | set(wm)):
    b, w = bridge.get(item, {}), wm.get(item, {})
    for metric in ("timeToFirstStartMs", "timeToCompleteMs", "attempts", "chunksReplayed"):
        bv, wv = b.get(metric), w.get(metric)
        print(f"{item:28} {metric:20} {str(bv):>12} {str(wv):>12}")
lost_b = [i for i, r in bridge.items() if r.get("completedAt") is None]
lost_w = [i for i, r in wm.items() if r.get("completedAt") is None]
print(f"\nincomplete — bridge: {lost_b or 'none'} | workmanager: {lost_w or 'none'}")
