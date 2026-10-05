package com.actionanand.localtell.app.survey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.actionanand.localtell.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TowerSurveyScreen() {
    val context = LocalContext.current
    val db = remember { TowerSurveyDbHelper(context) }
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(TowerSurveyState.summary(context)) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { db.export(context, uri) }
    }
    DisposableEffect(Unit) { onDispose { db.close() } }
    LaunchedEffect(Unit) {
        while (true) {
            summary = TowerSurveyState.summary(context)
            delay(1_000)
        }
    }

    val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val elapsed = summary.startedAt?.let { ((System.currentTimeMillis() - it) / 1_000).let { seconds -> "%d:%02d".format(seconds / 60, seconds % 60) } } ?: "—"
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.survey_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.survey_development), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.survey_privacy))
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (summary.active) stringResource(R.string.survey_active) else stringResource(R.string.survey_stopped), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.survey_elapsed_fixes, elapsed, summary.acceptedFixes))
                Text(stringResource(R.string.survey_observations, summary.observations))
                Text(stringResource(R.string.survey_gps_accuracy, summary.accuracy?.let { "%.1f m".format(it) } ?: stringResource(R.string.survey_waiting_gps)))
                Text(summary.registeredSummary ?: stringResource(R.string.survey_registered_cells_waiting), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasFineLocation && !summary.active, onClick = {
                ContextCompat.startForegroundService(context, Intent(context, TowerSurveyForegroundService::class.java).setAction(TowerSurveyForegroundService.ACTION_START))
            }) { Icon(Icons.Default.PlayArrow, null); Text(stringResource(R.string.survey_start)) }
            OutlinedButton(enabled = summary.active, onClick = {
                context.startService(Intent(context, TowerSurveyForegroundService::class.java).setAction(TowerSurveyForegroundService.ACTION_STOP))
            }) { Icon(Icons.Default.Stop, null); Text(stringResource(R.string.survey_stop)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val name = "localtell-tower-survey-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
                exportLauncher.launch(name)
            }) { Icon(Icons.Default.FileDownload, null); Text(stringResource(R.string.survey_export)) }
            TextButton(enabled = !summary.active, onClick = {
                scope.launch(Dispatchers.IO) { db.clear(); TowerSurveyState.cleared(context) }
            }) { Text(stringResource(R.string.survey_clear)) }
        }
        if (!hasFineLocation) Text(stringResource(R.string.survey_permission), color = MaterialTheme.colorScheme.error)
    }
}
