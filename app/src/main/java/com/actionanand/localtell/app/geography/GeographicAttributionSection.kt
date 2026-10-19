package com.actionanand.localtell.app.geography

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actionanand.localtell.app.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings-only attribution for bundled boundaries, not downloadable locality packs. */
@Composable
fun GeographicAttributionSection() {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.geo_sources_title), modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.geo_sources_summary), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.geo_sources_licenses), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.geo_sources_action)) }
    }
    if (showDetails) GeographicSourceDetails(onDismiss = { showDetails = false })
}

@Composable
private fun GeographicSourceDetails(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Read the canonical legal notice only on request and off the UI thread.
    // Organization names, source IDs and legal references remain verbatim.
    val notice by produceState<String?>(initialValue = null, key1 = context) {
        value = withContext(Dispatchers.IO) {
            try { context.assets.open("geography/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            catch (_: IOException) { "" }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.geo_sources_title)) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.geo_sources_modifications), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.geo_sources_limitations), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.geo_sources_notice), modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleSmall)
                    Text(when (val text = notice) {
                        null -> stringResource(R.string.geo_sources_loading)
                        "" -> stringResource(R.string.geo_sources_load_error)
                        else -> text
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.geo_sources_close)) } },
    )
}
