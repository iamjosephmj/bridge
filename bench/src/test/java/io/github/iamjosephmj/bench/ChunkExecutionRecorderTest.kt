package io.github.iamjosephmj.bench

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exercises the pure replay arithmetic (`computeReplayed`) without a SharedPreferences/Context —
 * the storage half (`recordExecution`/`replayed`) is a thin, un-branching pass-through over
 * Android's SharedPreferences API and isn't worth a Robolectric test on top of this.
 */
class ChunkExecutionRecorderTest {
    @Test fun `no replays when every chunk executed exactly once`() {
        assertThat(ChunkExecutionRecorder.computeReplayed(totalExecutions = 40, distinctCount = 40))
            .isEqualTo(0)
    }

    @Test fun `restart from zero after a kill counts every re-executed chunk as a replay`() {
        // e.g. 5 chunks executed once, then a kill, then all 5 re-executed from scratch:
        // 10 total executions, 5 distinct indexes -> 5 replays.
        assertThat(ChunkExecutionRecorder.computeReplayed(totalExecutions = 10, distinctCount = 5))
            .isEqualTo(5)
    }

    @Test fun `never goes negative even if distinct count is inconsistent with total`() {
        assertThat(ChunkExecutionRecorder.computeReplayed(totalExecutions = 0, distinctCount = 3))
            .isEqualTo(0)
    }
}
