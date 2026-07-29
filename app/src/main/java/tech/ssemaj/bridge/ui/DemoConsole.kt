package tech.ssemaj.bridge.ui

import androidx.compose.runtime.Composable
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
 * Tiny per-section "console": a button starts a suspend block off the main thread and the
 * block appends lines that render live in the section's [ResultText]. Starting a new run
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
}

@Composable
fun rememberDemoConsole(): DemoConsole {
    val scope = rememberCoroutineScope()
    return remember { DemoConsole(scope) }
}
