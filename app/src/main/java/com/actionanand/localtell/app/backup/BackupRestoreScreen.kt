package com.actionanand.localtell.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.saved.*

@Composable
fun BackupRestoreScreen(vm: AppBackupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.saved_backup_share)
    val host = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(state.messageSequence) { if (message != null) host.showSnackbar(message) }
    val export = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri == null) vm.cancelBackup() else vm.export(uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::inspectBackup) }
    val device = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::inspectBackup) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.backup_included))
                Text(stringResource(R.string.backup_counts, state.savedCount, AppLimits.current.maxSavedPlaces,
                    state.journeyCount, AppLimits.current.maxJourneyPoints))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = state.loaded && !state.busy && state.savedCount + state.journeyCount > 0,
                        onClick = vm::prepareBackup) { Text(stringResource(R.string.saved_export)) }
                    OutlinedButton(enabled = !state.busy, onClick = {
                        runCatching { restore.launch("*/*") }.onFailure { vm.restorePickerUnavailable() }
                    }) { Text(stringResource(R.string.saved_restore)) }
                    TextButton(enabled = !state.busy, onClick = {
                        runCatching { device.launch(arrayOf("*/*")) }.onFailure { vm.restorePickerUnavailable() }
                    }) { Text(stringResource(R.string.saved_restore_device_file)) }
                    if (!state.loaded) TextButton(enabled = !state.busy, onClick = vm::refresh) { Text(stringResource(R.string.saved_retry)) }
                }
                if (state.loaded && state.savedCount + state.journeyCount == 0) Text(stringResource(R.string.backup_empty))
                Text(stringResource(R.string.backup_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.saved_restore_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
    state.preparedBackup?.takeIf { state.chooseBackupDestination }?.let { prepared ->
        AlertDialog(onDismissRequest = vm::cancelBackup,
            title = { Text(stringResource(R.string.saved_backup_ready)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(prepared.filename)
                    Text(stringResource(R.string.backup_privacy), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.saved_backup_folder_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !state.busy, onClick = {
                        vm.destinationPickerStarted()
                        runCatching { export.launch(prepared.filename) }.onFailure { vm.backupDestinationUnavailable() }
                    }) { Text(stringResource(R.string.saved_backup_local)) }
                    OutlinedButton(enabled = !state.busy, onClick = {
                        vm.shareBackup { backup -> sharePreparedBackup(context, backup, shareTitle) }
                    }) { Text(stringResource(R.string.saved_backup_share)) }
                }
            }, confirmButton = { TextButton(onClick = vm::cancelBackup) { Text(stringResource(R.string.cancel)) } })
    }
    state.preview?.let { preview ->
        AlertDialog(onDismissRequest = vm::dismissRestore, title = { Text(stringResource(R.string.saved_restore)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_preview_saved, state.restore?.savedPlaces?.size ?: 0,
                        preview.saved.duplicates, preview.saved.added, preview.saved.total, AppLimits.current.maxSavedPlaces))
                    Text(stringResource(R.string.backup_preview_journey, state.restore?.journeys?.size ?: 0,
                        preview.journey.duplicates, preview.journey.additions.size, preview.journey.removed,
                        preview.journey.total, AppLimits.current.maxJourneyPoints))
                    Text(stringResource(if (preview.saved.fits) R.string.backup_restore_confirm else R.string.backup_capacity_overflow))
                }
            },
            confirmButton = { if (preview.saved.fits) TextButton(enabled = !state.busy, onClick = vm::restore) { Text(stringResource(R.string.saved_restore)) } },
            dismissButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.cancel)) } })
    }
}
