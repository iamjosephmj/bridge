package io.github.iamjosephmj.bridge.api

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkRequestDslTest {
    @Test fun `builder applies all fields with sane defaults`() {
        val r = workRequest(name = "photo-backup", workerName = "upload") {
            importance(Importance.LOW)
            charging()
            unmetered()
            chunks(count = 40, estimatedUpBytes = 200_000_000L)
            maxAttempts(5)
        }
        assertThat(r.importance).isEqualTo(Importance.LOW)
        assertThat(r.requiresCharging).isTrue()
        assertThat(r.requiresUnmetered).isTrue()
        assertThat(r.chunkCount).isEqualTo(40)
        assertThat(r.maxAttempts).isEqualTo(5)

        val plain = workRequest("ping", "pinger")
        assertThat(plain.importance).isEqualTo(Importance.DEFAULT)
        assertThat(plain.chunkCount).isEqualTo(0)
        assertThat(plain.maxAttempts).isEqualTo(3)
    }

    @Test fun `registry creates registered workers and rejects unknown names`() {
        val registry = WorkerRegistry()
        registry.register("upload") { object : BridgeWorker {
            override suspend fun run(ctx: RunContext) = RunResult.Success
        } }
        assertThat(registry.create("upload")).isNotNull()
        assertThrows(IllegalArgumentException::class.java) { registry.create("nope") }
    }
}
