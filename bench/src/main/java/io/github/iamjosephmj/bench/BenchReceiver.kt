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
            "bench.ENQUEUE_DURABLE" -> {
                context.getSharedPreferences("durable-fs-counters", Context.MODE_PRIVATE)
                    .edit().clear().commit()
                io.github.iamjosephmj.bridge.Bridge.durable("durable-fs")
            }
            "bench.DURABLE_REPORT" -> {
                val prefs = context.getSharedPreferences("durable-fs-counters", Context.MODE_PRIVATE)
                val events = io.github.iamjosephmj.bridge.Bridge.events("durable-fs")
                val json = """{"scenario": "durable-force-stop",
 "device": {"model": "${Build.MODEL}", "sdk": ${Build.VERSION.SDK_INT}},
 "state": "${io.github.iamjosephmj.bridge.Bridge.state("durable-fs")?.runState}",
 "firstStepExecutions": ${prefs.getInt("first", 0)},
 "secondStepExecutions": ${prefs.getInt("second", 0)},
 "stepEventsJournaled": ${events.count { it is io.github.iamjosephmj.bridge.store.WorkEvent.StepCompleted && !it.name.startsWith("${'$'}sys") }},
 "parks": ${events.count { it is io.github.iamjosephmj.bridge.store.WorkEvent.Stopped && it.stopReason == io.github.iamjosephmj.bridge.store.StopReason.PARKED.code }},
 "deaths": ${events.count { it is io.github.iamjosephmj.bridge.store.WorkEvent.Died }}}"""
                val out = File(context.getExternalFilesDir(null),
                    "report-durable-fs-${System.currentTimeMillis()}.json")
                out.writeText(json)
                Log.i("Bench", "durable report written: ${out.absolutePath}")
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
