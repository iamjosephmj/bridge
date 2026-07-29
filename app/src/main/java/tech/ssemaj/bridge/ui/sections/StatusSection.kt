package tech.ssemaj.bridge.ui.sections

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.iamjosephmj.bridge.Bridge
import tech.ssemaj.bridge.ui.DemoSection
import tech.ssemaj.bridge.ui.ResultText

/**
 * Pinned status panel — the persistence proof. Rendered straight from the journal on
 * every launch: every work item Bridge has ever seen shows up here with its current
 * state and diagnosis, so closing and reopening the app visibly loses nothing.
 */
@Composable
fun StatusSection() {
    var report by remember { mutableStateOf("") }
    val refresh = { report = Bridge.report().render(System.currentTimeMillis()) }
    // Render once on first composition — i.e. on every app launch.
    LaunchedEffect(Unit) { refresh() }
    DemoSection(
        title = "Current status",
        description = "Bridge.report() rendered from the persisted journal at launch. " +
            "Kill the app and come back: everything below is still here.",
    ) {
        Button(onClick = refresh) { Text("Refresh") }
        ResultText(report)
    }
}
