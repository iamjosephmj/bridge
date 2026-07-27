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
            "bench.ENQUEUE_BRIDGE" -> BridgeBackend(context).enqueueAll(CORPUS)
            "bench.ENQUEUE_WM" -> WorkManagerBackend(context).enqueueAll(CORPUS)
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
