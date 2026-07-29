package io.github.iamjosephmj.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File

class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "bench.ENQUEUE_BRIDGE" -> {
                // Static CORPUS ids mean a second run against stale chunk-recorder prefs
                // would inflate chunksReplayed with counts left over from a prior run.
                // (Bridge's own journal/event state needs no such reset: Bridge.enqueue KEEPs
                // live work and bumps the generation for terminal work, and
                // BridgeBackend.recordFor slices the event list to the last Enqueued event —
                // repeat runs are already handled there.)
                ChunkExecutionRecorder.reset(context, "bridge")
                BridgeBackend(context).enqueueAll(CORPUS)
            }
            "bench.ENQUEUE_WM" -> {
                // Same static-CORPUS-id problem as above, plus WorkManager has its own
                // self-instrumented timestamp/attempt prefs that must be cleared too, or a
                // repeat run reports stale enqueue/start/complete times and attempt counts.
                ChunkExecutionRecorder.reset(context, "workmanager")
                WmRecorder.reset(context)
                WorkManagerBackend(context).enqueueAll(CORPUS)
            }
            // M2 stall scenario: for every corpus item, print WorkManager's entire answer
            // (WorkInfo.state) next to Bridge's verdict. Written as JSON for run-stall.sh.
            "bench.STALL_REPORT" -> {
                val wm = androidx.work.WorkManager.getInstance(context)
                val entries = CORPUS.joinToString(",\n") { item ->
                    val wmState = try {
                        wm.getWorkInfosForUniqueWork(item.id).get()
                            .firstOrNull()?.state?.name ?: "UNKNOWN"
                    } catch (e: Exception) { "ERROR" }
                    val verdict = io.github.iamjosephmj.bridge.Bridge.whyPending(item.id)
                    val render = verdict?.render(System.currentTimeMillis())
                        ?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.replace("\n", "\\n")
                    """  {"item": "${item.id}",
   "workmanager": {"state": "$wmState"},
   "bridge": {"diagnosis": "${verdict?.diagnosis}",
              "basis": "${verdict?.basis?.name}",
              "render": "$render"}}"""
                }
                val json = """{"scenario": "stall",
 "device": {"model": "${Build.MODEL}", "manufacturer": "${Build.MANUFACTURER}",
            "sdk": ${Build.VERSION.SDK_INT}},
 "items": [
$entries
 ]}"""
                val out = File(context.getExternalFilesDir(null),
                    "report-stall-${System.currentTimeMillis()}.json")
                out.writeText(json)
                Log.i("Bench", "stall report written: ${out.absolutePath}")
            }
            "bench.DUMP_REPORT" -> {
                val backend: Backend = if (intent.getStringExtra("backend") == "workmanager")
                    WorkManagerBackend(context) else BridgeBackend(context)
                val source = if (backend.name == "workmanager") "self-instrumented" else "bridge-events"
                val json = Report.toJson(backend.collect(), mapOf(
                    "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
                    "sdk" to Build.VERSION.SDK_INT.toString(),
                    "source" to source))
                val out = File(context.getExternalFilesDir(null),
                    "report-${backend.name}-${System.currentTimeMillis()}.json")
                out.writeText(json)
                Log.i("Bench", "report written: ${out.absolutePath}")
            }
        }
    }
}
