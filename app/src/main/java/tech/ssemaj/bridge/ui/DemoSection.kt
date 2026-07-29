package tech.ssemaj.bridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared scaffolding for every demo: a card with a title, a one-paragraph explanation of
 * what the demo shows, the demo's buttons, and a monospace result area.
 */
@Composable
fun DemoSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

/** Monospace output area; empty until the demo's button has been pressed. */
@Composable
fun ResultText(text: String) {
    if (text.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}
