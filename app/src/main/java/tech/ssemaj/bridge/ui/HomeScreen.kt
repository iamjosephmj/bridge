package tech.ssemaj.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.iamjosephmj.bridge.Bridge

/** The seven feature destinations, in showcase order. */
enum class Feature(
    val title: String,
    val hook: String,
    val deviceVerified: Boolean,
) {
    ENQUEUE(
        "Enqueue + constraints",
        "One call to schedule work; constraints that explain themselves.",
        deviceVerified = false,
    ),
    CHUNKED(
        "Chunked resumption",
        "Kill the app mid-transfer; it resumes at the chunk it reached, not from zero.",
        deviceVerified = true,
    ),
    DURABLE(
        "Durable coroutines",
        "A suspend block that survives process death by journaled replay.",
        deviceVerified = true,
    ),
    DEADLINE(
        "Deadlines",
        "Work that escalates its urgency as the finish-by time approaches.",
        deviceVerified = false,
    ),
    PERIODIC(
        "Periodic",
        "Repeating work as one continuous, cancellable history per name.",
        deviceVerified = false,
    ),
    DIAGNOSTICS(
        "Diagnostics",
        "Ask why work hasn't run and get a causal verdict, not a state enum.",
        deviceVerified = true,
    ),
    COMPAT(
        "Compat",
        "WorkManager-shaped code with Bridge's journal running underneath.",
        deviceVerified = false,
    ),
}

/**
 * Home: the journal-backed status panel (the persistence proof — rendered from disk on
 * every launch) above a two-column grid of feature cards.
 */
@Composable
fun HomeScreen(onOpen: (Feature) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bridge", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Background work with a causal ledger. Press a button, watch the journal.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        StatusPanel()
        Spacer(Modifier.height(4.dp))
        SectionLabel("Features")
        FeatureGrid(onOpen)
    }
}

/**
 * The persistence proof, kept from the original StatusSection: Bridge.report() rendered
 * from the persisted journal on every launch, plus a manual Refresh.
 */
@Composable
private fun StatusPanel() {
    var report by remember { mutableStateOf("") }
    val refresh = { report = Bridge.report().render(System.currentTimeMillis()) }
    // Render once on first composition — i.e. on every app launch. Initialization runs
    // async (Bridge.initializeAsync in BridgeShowcaseApp), so wait for readiness before
    // the first render; report() itself degrades gracefully pre-ready, and the manual
    // Refresh button keeps its synchronous contract.
    LaunchedEffect(Unit) {
        Bridge.awaitReady()
        refresh()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Current status")
                TextButton(onClick = refresh) { Text("Refresh") }
            }
            Text(
                "Rendered from the persisted journal at launch. Kill the app and come " +
                    "back: everything below is still here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConsolePanel(report, placeholder = "No work recorded yet.")
        }
    }
}

@Composable
private fun FeatureGrid(onOpen: (Feature) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Feature.entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { feature ->
                    FeatureCard(
                        feature = feature,
                        onClick = { onOpen(feature) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureCard(feature: Feature, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(feature.title, style = MaterialTheme.typography.titleMedium)
            Text(
                feature.hook,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (feature.deviceVerified) DeviceVerifiedTag()
        }
    }
}
