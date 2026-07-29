package io.github.iamjosephmj.bridge.diagnostics

import io.github.iamjosephmj.bridge.signals.SignalKind
import io.github.iamjosephmj.bridge.signals.SignalValue
import io.github.iamjosephmj.bridge.signals.Trigger
import io.github.iamjosephmj.bridge.store.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictRenderTest {

    @Test fun `renders diagnosis, basis, contributing, evidence`() {
        val fourHours12m = 4 * 60 * 60 * 1000L + 12 * 60 * 1000L
        val v = Verdict(
            workId = "photo-backup", state = RunState.ENQUEUED,
            diagnosis = Diagnosis.DeferredByStandbyBucket(40),
            contributing = listOf(Diagnosis.DeferredByDoze(deep = true)),
            evidence = listOf(Evidence(SignalKind.STANDBY_BUCKET, SignalValue.Bucket(40),
                at = 1000L, trigger = Trigger.BROADCAST)),
            basis = Basis.INFERRED,
            pendingSinceMs = 0L,
        )
        val out = v.render(now = fourHours12m)
        assertTrue(out, out.startsWith("ENQUEUED 4h 12m — DeferredByStandbyBucket(RARE) [INFERRED]"))
        assertTrue(out, out.contains("contributing: DeferredByDoze(deep)"))
        assertTrue(out, out.contains("STANDBY_BUCKET"))
        assertTrue(out, out.contains("Bucket(RARE)"))
        assertTrue(out, out.contains("BROADCAST"))
    }

    @Test fun `notes render, terminal states skip duration`() {
        val v = Verdict("x", RunState.SUCCEEDED, Diagnosis.Finished, emptyList(), emptyList(),
            Basis.INFERRED, pendingSinceMs = null, notes = listOf("SignalHistoryUnavailable"))
        val out = v.render(now = 99999L)
        assertTrue(out, out.startsWith("SUCCEEDED — Finished [INFERRED]"))
        assertTrue(out, out.contains("note: SignalHistoryUnavailable"))
    }

    @Test fun `bucket names`() {
        assertEquals("WORKING_SET", Verdict.bucketName(20))
        assertEquals("BUCKET_77", Verdict.bucketName(77))
    }
}
