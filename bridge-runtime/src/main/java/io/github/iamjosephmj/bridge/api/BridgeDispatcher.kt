package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.exec.BlackBox
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/**
 * M5 (minimal tier, spec §1): a dispatcher usable with any coroutine that black-box
 * stamps every resumption and refuses further resumptions once the host signals stop —
 * so a stopped job cannot keep burning CPU past its lease. Thermal-aware parallelism
 * and cost attribution are future work.
 */
class BridgeDispatcher(
    private val workId: String,
    private val blackBox: BlackBox,
    private val isStopped: () -> Boolean,
    private val delegate: CoroutineDispatcher = Dispatchers.Default,
) : CoroutineDispatcher() {
    private var resumptions = 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (isStopped()) {
            // Cancel rather than throw: the continuation observes cancellation at its
            // next suspension point instead of the scheduler eating an exception.
            context[kotlinx.coroutines.Job]?.cancel(
                CancellationException("host signalled stop for $workId"))
        }
        delegate.dispatch(context) {
            blackBox.stamp(workId, "resume:${resumptions++}", 0)
            block.run()
        }
    }
}
