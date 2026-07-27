package io.github.iamjosephmj.bench

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReportTest {
    @Test fun `report json contains records and device info`() {
        val json = Report.toJson(
            listOf(RunRecord("ping-none", "bridge", 100L, 150L, 200L,
                attempts = 1, chunksReplayed = 0)),
            deviceInfo = mapOf("model" to "TestDevice", "sdk" to "34"))
        assertThat(json).contains("\"itemId\":\"ping-none\"")
        assertThat(json).contains("\"model\":\"TestDevice\"")
        assertThat(json).contains("\"timeToFirstStartMs\":50")
        assertThat(json).contains("\"timeToCompleteMs\":100")
    }
}
