package io.github.iamjosephmj.bridge.api

import io.github.iamjosephmj.bridge.exec.BlackBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingBlackBox : BlackBox {
    val stamps = mutableListOf<String>()
    override fun stamp(workId: String, step: String, attempt: Int) { stamps += step }
    override fun clear() { stamps.clear() }
}

class BridgeDispatcherTest {

    @Test fun `stamps every resumption`() = runTest {
        val box = RecordingBlackBox()
        val dispatcher = BridgeDispatcher("w", box, { false },
            StandardTestDispatcher(testScheduler))
        withContext(dispatcher) { yield(); yield() }
        assertTrue("expected >=2 stamps, got ${box.stamps}", box.stamps.size >= 2)
        assertEquals("resume:0", box.stamps.first())
    }

    @Test fun `refuses resumptions once stopped`() = runTest {
        var stopped = false
        val dispatcher = BridgeDispatcher("w", RecordingBlackBox(), { stopped },
            StandardTestDispatcher(testScheduler))
        var cancelled = false
        val job = launch(dispatcher) {
            try { yield(); stopped = true; yield() }
            catch (e: CancellationException) { cancelled = true; throw e }
        }
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled || cancelled)
    }
}
