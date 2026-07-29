package tech.ssemaj.bridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.iamjosephmj.bridge.Bridge
import io.github.iamjosephmj.bridge.store.RunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tiny per-screen "console": a button starts a suspend block off the main thread and the
 * block appends lines that render live in the screen's [ConsolePanel]. Starting a new run
 * cancels the previous one so buttons stay idempotent.
 */
class DemoConsole(private val scope: CoroutineScope) {
    var text by mutableStateOf("")
        private set
    private var job: Job? = null

    fun run(block: suspend DemoConsole.() -> Unit) {
        job?.cancel()
        text = ""
        job = scope.launch(Dispatchers.Default) {
            try {
                block()
            } catch (e: Exception) {
                log("error: ${e.message}")
            }
        }
    }

    fun log(line: String) {
        text = if (text.isEmpty()) line else "$text\n$line"
    }

    /**
     * Polls the journal's fold of this work item and logs every state change until it
     * reaches a terminal state (or the timeout). This is pure observation — all facts
     * come from [Bridge.state], i.e. the event journal.
     */
    suspend fun watchState(name: String, timeoutMs: Long = 20_000, everyMs: Long = 500) {
        val terminal = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED)
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            val st = Bridge.state(name)
            val line = st?.let {
                buildString {
                    append(it.runState.name).append("  gen=").append(it.generation)
                    append(" attempt=").append(it.attempt)
                    if (it.chunkCount > 0) append("  chunk ${it.nextChunk}/${it.chunkCount}")
                }
            } ?: "no such work"
            if (line != last) { log(line); last = line }
            if (st != null && st.runState in terminal) return
            delay(everyMs)
        }
        log("(still ${Bridge.state(name)?.runState} after ${timeoutMs / 1000}s — " +
            "the OS decides exactly when constrained work runs)")
    }

    /**
     * Rehydrates the console from the journal after a process restart: for every name
     * the journal knows, seed a summary line (state fold + last ledger outcome) so the
     * section isn't blank, and if a run is still live, restart the [watchState] poller
     * so a run that spans the restart visibly continues. No-op for unknown names.
     */
    fun rehydrate(vararg names: String) {
        val live = names.filter { seedFromJournal(it) }
        if (live.isEmpty()) return
        job = scope.launch(Dispatchers.Default) {
            live.forEach { name -> launch { watchState(name) } }
        }
    }

    /** Logs what the journal remembers about [name]; returns true if the run is live. */
    private fun seedFromJournal(name: String): Boolean {
        val st = Bridge.state(name) ?: return false
        log(buildString {
            append("[journal] '").append(name).append("': ").append(st.runState.name)
            append("  gen=").append(st.generation)
            if (st.chunkCount > 0) append("  chunk ${st.nextChunk}/${st.chunkCount}")
        })
        Bridge.ledger(name).runs.lastOrNull()?.let {
            log("[journal] last run: gen=${it.generation} attempt=${it.attempt}  ${it.outcome}")
        }
        val isLive = st.runState in setOf(
            RunState.ENQUEUED, RunState.DISPATCHED, RunState.RUNNING)
        if (isLive) log("[journal] still live — resuming watch")
        return isLive
    }
}

/**
 * A console for one demo section. Any [rehydrateFrom] work names are looked up in the
 * journal on first composition, so state survives app restarts visibly.
 */
@Composable
fun rememberDemoConsole(vararg rehydrateFrom: String): DemoConsole {
    val scope = rememberCoroutineScope()
    val console = remember { DemoConsole(scope) }
    LaunchedEffect(Unit) { console.rehydrate(*rehydrateFrom) }
    return console
}
