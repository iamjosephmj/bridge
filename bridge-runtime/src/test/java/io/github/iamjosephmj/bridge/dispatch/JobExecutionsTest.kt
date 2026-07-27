package io.github.iamjosephmj.bridge.dispatch

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.isActive
import org.junit.Test

/**
 * Verifies [JobExecutions] keeps per-jobId state fully isolated: stopping/finishing one job id
 * must never affect another job id's scope, stopped flag, or in-flight map. This is the
 * regression coverage for the "one BridgeJobService component, many concurrent job ids" bug
 * (shared instance fields meant job A's onStopJob could cancel job B's drain and permanently
 * kill the shared scope for all future jobs).
 */
class JobExecutionsTest {

    @Test fun `start assigns independent executions per jobId`() {
        val executions = JobExecutions()
        val a = executions.start(1)
        val b = executions.start(2)
        assertThat(a).isNotSameInstanceAs(b)
        assertThat(a.scope).isNotSameInstanceAs(b.scope)
        assertThat(a.inFlight).isNotSameInstanceAs(b.inFlight)
    }

    @Test fun `stopping one jobId does not cancel or stop another jobId's execution`() {
        val executions = JobExecutions()
        val a = executions.start(1)
        val b = executions.start(2)

        executions.stop(1)

        assertThat(a.stopped.get()).isTrue()
        assertThat(a.scope.isActive).isFalse()
        assertThat(b.stopped.get()).isFalse()
        assertThat(b.scope.isActive).isTrue()
    }

    @Test fun `stop removes only that jobId from the registry`() {
        val executions = JobExecutions()
        executions.start(1)
        executions.start(2)

        executions.stop(1)

        assertThat(executions.get(1)).isNull()
        assertThat(executions.get(2)).isNotNull()
    }

    @Test fun `finish removes the entry without touching other jobIds`() {
        val executions = JobExecutions()
        val a = executions.start(1)
        val b = executions.start(2)

        executions.finish(1)

        assertThat(executions.get(1)).isNull()
        assertThat(executions.get(2)).isNotNull()
        // finish() is the "drained normally" path: it must not cancel the scope itself
        // (the drain coroutine that calls it is still running inside that scope).
        assertThat(a.scope.isActive).isTrue()
        assertThat(b.stopped.get()).isFalse()
    }

    @Test fun `start for an already-registered jobId cancels the stale execution and replaces it`() {
        val executions = JobExecutions()
        val stale = executions.start(1)
        val fresh = executions.start(1)

        assertThat(stale).isNotSameInstanceAs(fresh)
        assertThat(stale.scope.isActive).isFalse()
        assertThat(executions.get(1)).isSameInstanceAs(fresh)
    }

    @Test fun `stop on an unknown jobId is a no-op`() {
        val executions = JobExecutions()
        executions.start(1)

        val result = executions.stop(99)

        assertThat(result).isNull()
        assertThat(executions.get(1)).isNotNull()
    }
}
