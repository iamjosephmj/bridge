package tech.ssemaj.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.ssemaj.bridge.ui.sections.ChunkedSection
import tech.ssemaj.bridge.ui.sections.CompatSection
import tech.ssemaj.bridge.ui.sections.DeadlineSection
import tech.ssemaj.bridge.ui.sections.DiagnosticsSection
import tech.ssemaj.bridge.ui.sections.DurableSection
import tech.ssemaj.bridge.ui.sections.EnqueueSection
import tech.ssemaj.bridge.ui.sections.PeriodicSection
import tech.ssemaj.bridge.ui.sections.StatusSection
import tech.ssemaj.bridge.ui.theme.BridgeTheme

/**
 * Bridge showcase: one scrolling screen, seven self-contained demos. Each section lives
 * in ui/sections/ and pairs a button with a live console showing what the library did.
 * Bridge itself is initialized in [BridgeShowcaseApp].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ShowcaseScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ShowcaseScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bridge showcase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Background work with a causal ledger. Press a button, watch the journal.",
            style = MaterialTheme.typography.bodyMedium,
        )
        StatusSection()         // 0: journal-backed report — persists across restarts
        EnqueueSection()        // 1: plain + constrained enqueue
        ChunkedSection()        // 2: chunks(10), resumable progress
        DurableSection()        // 3: Bridge.scope().launch durable coroutine
        DeadlineSection()       // 4: mustCompleteBy(now + 2 min)
        PeriodicSection()       // 5: periodic(15 min) + cancel
        DiagnosticsSection()    // 6: whyPending / ledger / report / GlassBox
        CompatSection()         // 7: WorkManager-shaped chain via bridge-compat
    }
}
