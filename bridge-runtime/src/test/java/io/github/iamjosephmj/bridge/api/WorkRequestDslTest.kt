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

    @Test fun `backoff sets pacing, defaults exponential, floors at 10s`() {
        val r = workRequest("sync", "upload") { backoff(60_000L, BackoffPolicy.LINEAR) }
        assertThat(r.backoffMs).isEqualTo(60_000L)
        assertThat(r.backoffPolicy).isEqualTo(BackoffPolicy.LINEAR)

        val exp = workRequest("sync2", "upload") { backoff(15_000L) }
        assertThat(exp.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)

        assertThat(workRequest("plain", "upload").backoffMs).isEqualTo(0L)
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("bad", "upload") { backoff(5_000L) }
        }
    }

    @Test fun `backoff rejects deviceIdle and periodic in either order`() {
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("a", "w") { backoff(30_000L); deviceIdle() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("b", "w") { deviceIdle(); backoff(30_000L) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("c", "w") { backoff(30_000L); periodic(30 * 60_000L) }
        }
    }

    @Test fun `contentTrigger accumulates uris, flags, and delays`() {
        val r = workRequest("photos", "upload") {
            contentTrigger("content://media/photos", descendants = true,
                updateDelayMs = 500L, maxDelayMs = 5_000L)
            contentTrigger("content://media/videos")
        }
        assertThat(r.contentUris)
            .containsExactly("content://media/photos", "content://media/videos").inOrder()
        assertThat(r.contentDescendants).isTrue()
        assertThat(r.contentUpdateDelayMs).isEqualTo(500L)
        assertThat(r.contentMaxDelayMs).isEqualTo(5_000L)

        val plain = workRequest("ping", "pinger")
        assertThat(plain.contentUris).isEmpty()
        assertThat(plain.contentDescendants).isFalse()
    }

    @Test fun `periodic and contentTrigger are mutually exclusive both ways`() {
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("bad", "w") {
                periodic(30 * 60_000L)
                contentTrigger("content://media/photos")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("bad2", "w") {
                contentTrigger("content://media/photos")
                periodic(30 * 60_000L)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            workRequest("bad3", "w") { contentTrigger() }
        }
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
