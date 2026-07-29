package tech.ssemaj.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn back arrow so the app needs no icon artifact (the current Compose BOM no
 * longer bundles material-icons with material3).
 */
@Composable
private fun BackArrowIcon() {
    val color = MaterialTheme.colorScheme.onBackground
    Canvas(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = "Back" },
    ) {
        val stroke = 2.dp.toPx()
        val midY = size.height / 2f
        val left = size.width * 0.12f
        drawLine(color, Offset(left, midY), Offset(size.width * 0.92f, midY), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, midY), Offset(size.width * 0.48f, midY - size.height * 0.34f), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, midY), Offset(size.width * 0.48f, midY + size.height * 0.34f), stroke, StrokeCap.Round)
    }
}

/**
 * Chrome shared by every feature screen: top bar with back navigation, then a scrolling
 * column of (1) plain-English explainer, (2) the live demo — controls plus console —
 * and (3) a collapsible monospace snippet of the exact API call the buttons make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureScreen(
    title: String,
    explainer: String,
    snippet: String,
    onBack: () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { BackArrowIcon() }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("What this is")
            Text(
                text = explainer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            SectionLabel("Try it")
            controls()
            Spacer(Modifier.height(6.dp))
            CodeSnippet(snippet)
        }
    }
}
