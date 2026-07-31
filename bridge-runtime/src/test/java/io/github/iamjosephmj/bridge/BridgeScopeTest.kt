package io.github.iamjosephmj.bridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.iamjosephmj.bridge.dispatch.FakeJobGateway
import io.github.iamjosephmj.bridge.signals.InMemoryTransitionStore
import io.github.iamjosephmj.bridge.store.RunState
import io.github.iamjosephmj.bridge.store.WorkEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.hours

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeScopeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gateway = FakeJobGateway()
    private val clock = FakeClock(1000L)

    @After fun tearDown() = Bridge.reset()

    private fun init() = Bridge.initialize(context) {
        clock = this@BridgeScopeTest.clock
        gateway = this@BridgeScopeTest.gateway
        ioExecutor = Executor { it.run() }
        dbName = "scope-${System.nanoTime()}.db"
        signalSources = emptyList()
        transitionStore = InMemoryTransitionStore()
    }

    private fun runParked() = runBlocking {
        for ((_, payload) in gateway.enqueued.toList()) {
            val state = Bridge.state(payload.workId)
            if (state.runState == RunState.UNKNOWN) continue
            val attempts = Bridge.events(payload.workId)
                .count { it is WorkEvent.Started && it.generation == state.generation } + 1
            io.github.iamjosephmj.bridge.dispatch.BridgeServices.runner!!
                .run(payload.workId, payload.generation, attempts) { false }
        }
    }

    @Test fun `launch reads like a coroutine and survives replay`() {
        init()
        var uploads = 0
        var committed = false
        val launchIt = {
            Bridge.scope().launch("publish-post") {
                val media = step("upload") { uploads++; "media-1" }
                delay(2.hours)
                step("commit") { committed = true; check(media == "media-1") }
            }
        }
        val handle = launchIt()
        runParked()                                   // runs step 1, parks on the timer
        assertThat(uploads).isEqualTo(1)
        assertThat(committed).isFalse()
        assertThat(handle.state()!!.runState).isEqualTo(RunState.ENQUEUED)

        clock.advance(2 * 60 * 60 * 1000L + 1)
        launchIt()                                    // relaunch (post-death path): KEEP + re-register
        runParked()
        assertThat(uploads).isEqualTo(1)              // replayed, not re-executed
        assertThat(committed).isTrue()
        assertThat(handle.state()!!.runState).isEqualTo(RunState.SUCCEEDED)
    }

    @Test fun `launch with constraints and cancel via handle`() {
        init()
        val handle = Bridge.scope().launch("sync", constraints = { charging() }) {
            step("noop") { 1 }
        }
        assertThat(Bridge.state("sync").requiresCharging).isTrue()
        handle.cancel()
        assertThat(handle.state()!!.runState).isEqualTo(RunState.CANCELLED)
    }
}
