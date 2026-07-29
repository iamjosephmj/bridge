package tech.ssemaj.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tech.ssemaj.bridge.ui.Feature
import tech.ssemaj.bridge.ui.HomeScreen
import tech.ssemaj.bridge.ui.screens.ChunkedScreen
import tech.ssemaj.bridge.ui.screens.CompatScreen
import tech.ssemaj.bridge.ui.screens.DeadlineScreen
import tech.ssemaj.bridge.ui.screens.DiagnosticsScreen
import tech.ssemaj.bridge.ui.screens.DurableScreen
import tech.ssemaj.bridge.ui.screens.EnqueueScreen
import tech.ssemaj.bridge.ui.screens.PeriodicScreen
import tech.ssemaj.bridge.ui.theme.BridgeTheme

/**
 * Bridge showcase: a home screen (journal-backed status + feature grid) and one screen
 * per feature. Navigation is a plain saveable state holder — home plus one level deep —
 * so system back (and predictive back) simply pops to home. Each feature screen
 * rehydrates its own work names from the journal on entry. Bridge itself is initialized
 * in [BridgeShowcaseApp].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ShowcaseNavHost()
                }
            }
        }
    }
}

@Composable
private fun ShowcaseNavHost() {
    // null = home. The stack is exactly one level deep, so a nullable Feature is the
    // whole back stack; rememberSaveable keeps the screen across rotation/death.
    var current by rememberSaveable { mutableStateOf<Feature?>(null) }
    BackHandler(enabled = current != null) { current = null }
    Crossfade(targetState = current, label = "screen") { feature ->
        when (feature) {
            null -> HomeScreen(onOpen = { current = it })
            Feature.ENQUEUE -> EnqueueScreen(onBack = { current = null })
            Feature.CHUNKED -> ChunkedScreen(onBack = { current = null })
            Feature.DURABLE -> DurableScreen(onBack = { current = null })
            Feature.DEADLINE -> DeadlineScreen(onBack = { current = null })
            Feature.PERIODIC -> PeriodicScreen(onBack = { current = null })
            Feature.DIAGNOSTICS -> DiagnosticsScreen(onBack = { current = null })
            Feature.COMPAT -> CompatScreen(onBack = { current = null })
        }
    }
}
