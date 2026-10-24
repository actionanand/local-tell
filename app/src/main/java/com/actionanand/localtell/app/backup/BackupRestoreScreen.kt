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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.saved.*
import com.actionanand.localtell.app.dropbox.DropboxConnectionStatus
import com.actionanand.localtell.app.dropbox.DropboxConnectionViewModel
import com.actionanand.localtell.app.dropbox.DropboxConnectionFailure
import com.actionanand.localtell.app.dropbox.DropboxConnectionNotice
import com.actionanand.localtell.app.dropbox.DropboxRetryAction
import com.actionanand.localtell.app.dropbox.DropboxBackupViewModel
import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency
import com.actionanand.localtell.app.dropbox.DropboxBackupStatus
import com.actionanand.localtell.app.dropbox.DropboxBackupError
import com.actionanand.localtell.app.dropbox.DropboxReviewDialog
import com.actionanand.localtell.app.dropbox.BackupScreenSection
import com.actionanand.localtell.app.dropbox.backupScreenSections
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BackupRestoreScreen(
    vm: AppBackupViewModel = viewModel(),
    onConnectDropbox: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Cloud backup is a build-time option; its ViewModels are only created when the section is shown.
    val sections = remember { backupScreenSections() }
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
            if (BackupScreenSection.CLOUD_BACKUP in sections) {
                item { DropboxBackupCard(busy = state.busy, onConnectDropbox = onConnectDropbox) }
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

@Composable
private fun DropboxBackupCard(
    busy: Boolean,
    onConnectDropbox: () -> Unit,
    dropboxVm: DropboxConnectionViewModel = viewModel(),
    dropboxBackupVm: DropboxBackupViewModel = viewModel(),
) {
    val dropboxState by dropboxVm.state.collectAsStateWithLifecycle()
    val backupState by dropboxBackupVm.state.collectAsStateWithLifecycle()
    val savedPlacesExcess by dropboxBackupVm.savedPlacesExcess.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var showSavedLimitDialog by remember { mutableStateOf(false) }
    LaunchedEffect(dropboxState.status, dropboxState.accountIdHash) {
        when {
            dropboxState.status == DropboxConnectionStatus.CONNECTED ->
                dropboxBackupVm.selectAccount(dropboxState.accountIdHash?.takeIf(String::isNotBlank))
            dropboxState.status == DropboxConnectionStatus.NOT_CONNECTED ||
                dropboxState.status == DropboxConnectionStatus.REAUTHORIZATION_REQUIRED ->
                dropboxBackupVm.selectAccount(null)
        }
    }
    LaunchedEffect(backupState.reviewDialog) {
        if (backupState.reviewDialog != null) showConflictDialog = true
    }
    LaunchedEffect(savedPlacesExcess > 0) {
        showSavedLimitDialog = savedPlacesExcess > 0
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dropbox_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (dropboxState.status) {
                DropboxConnectionStatus.NOT_CONNECTED -> {
                    Text(stringResource(R.string.dropbox_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy, onClick = onConnectDropbox) { Text(stringResource(R.string.dropbox_connect)) }
                }
                DropboxConnectionStatus.CONNECTING -> {
                    Text(stringResource(R.string.dropbox_connecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                DropboxConnectionStatus.DISCONNECTING -> {
                    Text(stringResource(R.string.dropbox_disconnecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                DropboxConnectionStatus.CONNECTED -> {
                    Text(stringResource(R.string.dropbox_connected, dropboxState.accountName.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.dropbox_location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(
                            when (backupState.status) {
                                DropboxBackupStatus.UP_TO_DATE -> if (backupState.lastSuccessfulAt == null && backupState.lastRestoredAt == null)
                                    R.string.dropbox_status_no_backup else R.string.dropbox_status_up_to_date
                                DropboxBackupStatus.PENDING -> R.string.dropbox_status_pending
                                DropboxBackupStatus.SYNCING -> R.string.dropbox_status_syncing
                                DropboxBackupStatus.FAILED -> R.string.dropbox_status_failed
                                DropboxBackupStatus.CONFLICT -> if (backupState.restoreAvailable)
                                    R.string.dropbox_status_restore_available else R.string.dropbox_status_conflict
                                DropboxBackupStatus.OFFLINE -> R.string.dropbox_status_offline
                                DropboxBackupStatus.REAUTHORIZATION_REQUIRED -> R.string.dropbox_status_reauthorize
                                DropboxBackupStatus.SAVED_LIMIT_EXCEEDED -> R.string.dropbox_status_saved_limit
                                DropboxBackupStatus.INTERRUPTED -> R.string.dropbox_status_interrupted
                            },
                        ),
                        color = if (backupState.reviewDialog == DropboxReviewDialog.CONFLICT || backupState.status in setOf(
                                DropboxBackupStatus.FAILED,
                                DropboxBackupStatus.OFFLINE,
                                DropboxBackupStatus.REAUTHORIZATION_REQUIRED,
                                DropboxBackupStatus.SAVED_LIMIT_EXCEEDED,
                            )
                        ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val dateFormat = remember(context) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    }
                    val lastSuccess = backupState.lastSuccessfulAt
                    Text(
                        if (lastSuccess == null) stringResource(R.string.dropbox_never_backed_up)
                        else stringResource(R.string.dropbox_last_successful, dateFormat.format(Date(lastSuccess))),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    backupState.lastAttemptedAt?.let { attempted ->
                        Text(
                            stringResource(R.string.dropbox_last_attempted, dateFormat.format(Date(attempted))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    backupState.lastRestoredAt?.let { restored ->
                        Text(
                            stringResource(R.string.dropbox_last_restored, dateFormat.format(Date(restored))),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    backupState.error?.let { error ->
                        Text(
                            stringResource(
                                when (error) {
                                    DropboxBackupError.NETWORK -> R.string.dropbox_error_network
                                    DropboxBackupError.AUTHORIZATION -> R.string.dropbox_error_authorization
                                    DropboxBackupError.STORAGE -> R.string.dropbox_error_storage
                                    DropboxBackupError.UNKNOWN -> R.string.dropbox_error_unknown
                                    DropboxBackupError.DROPBOX_API -> R.string.dropbox_error_api
                                    DropboxBackupError.DROPBOX_PATH -> R.string.dropbox_error_path
                                    DropboxBackupError.DROPBOX_PERMISSION -> R.string.dropbox_error_permission
                                    DropboxBackupError.DROPBOX_QUOTA -> R.string.dropbox_error_quota
                                    DropboxBackupError.INVALID_BACKUP -> R.string.dropbox_error_invalid_backup
                                    DropboxBackupError.DROPBOX_TEMPORARY -> R.string.dropbox_error_temporary
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    backupState.diagnostic?.let { diagnostic ->
                        val copyLabel = stringResource(R.string.dropbox_copy_diagnostic)
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText(copyLabel, diagnostic.toSafeText()))
                        }) { Text(copyLabel) }
                    }
                    Text(stringResource(R.string.dropbox_backup_mode), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropboxBackupFrequency.entries.forEach { mode ->
                            FilterChip(
                                selected = backupState.frequency == mode,
                                enabled = backupState.status != DropboxBackupStatus.SYNCING,
                                onClick = { dropboxBackupVm.setFrequency(mode) },
                                label = { Text(stringResource(mode.labelRes())) },
                            )
                        }
                    }
                    Text(
                        stringResource(backupState.frequency.descriptionRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = backupState.frequency.syncAllowed &&
                                backupState.status != DropboxBackupStatus.SYNCING &&
                                savedPlacesExcess == 0,
                            onClick = dropboxBackupVm::syncNow,
                        ) {
                            if (backupState.status == DropboxBackupStatus.SYNCING) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.dropbox_sync_now))
                        }
                        OutlinedButton(
                            enabled = dropboxState.status != DropboxConnectionStatus.DISCONNECTING,
                            onClick = { showDisconnectConfirmation = true },
                        ) { Text(stringResource(R.string.dropbox_disconnect)) }
                    }
                    Text(
                        stringResource(R.string.dropbox_retention),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (savedPlacesExcess > 0) {
                        TextButton(onClick = { showSavedLimitDialog = true }) {
                            Text(stringResource(R.string.dropbox_saved_limit_fix))
                        }
                    }
                    if (backupState.status == DropboxBackupStatus.CONFLICT) {
                        TextButton(onClick = { showConflictDialog = true }) {
                            Text(stringResource(
                                if (backupState.restoreAvailable) R.string.dropbox_restore_backup else R.string.dropbox_resolve_conflict,
                            ))
                        }
                    }
                }
                DropboxConnectionStatus.FAILED -> {
                    Text(
                        stringResource(
                            when (dropboxState.failure) {
                                DropboxConnectionFailure.CREDENTIAL_STORAGE -> R.string.dropbox_credential_storage_failed
                                DropboxConnectionFailure.LOCAL_DISCONNECT -> R.string.dropbox_local_disconnect_failed
                                else -> R.string.dropbox_connection_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        enabled = !busy,
                        onClick = if (dropboxState.retryAction == DropboxRetryAction.VERIFY_STORED) {
                            dropboxVm::retry
                        } else {
                            onConnectDropbox
                        },
                    ) { Text(stringResource(R.string.dropbox_try_again)) }
                }
                DropboxConnectionStatus.REAUTHORIZATION_REQUIRED -> {
                    Text(stringResource(R.string.dropbox_reauthorization_required), color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy, onClick = onConnectDropbox) { Text(stringResource(R.string.dropbox_reauthorize)) }
                }
            }
            if (dropboxState.status == DropboxConnectionStatus.NOT_CONNECTED) {
                Text(stringResource(R.string.dropbox_connection_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            dropboxState.notice?.let {
                Text(
                    stringResource(
                        if (it == DropboxConnectionNotice.DISCONNECTED) {
                            R.string.dropbox_disconnected
                        } else {
                            R.string.dropbox_disconnect_revocation_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showDisconnectConfirmation && dropboxState.status == DropboxConnectionStatus.CONNECTED) {
                AlertDialog(
                    onDismissRequest = { showDisconnectConfirmation = false },
                    title = { Text(stringResource(R.string.dropbox_disconnect_confirm_title)) },
                    text = { Text(stringResource(R.string.dropbox_disconnect_confirm_message)) },
                    confirmButton = {
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            enabled = dropboxState.status != DropboxConnectionStatus.DISCONNECTING,
                            onClick = {
                                showDisconnectConfirmation = false
                                dropboxVm.disconnect()
                            },
                        ) { Text(stringResource(R.string.dropbox_disconnect)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDisconnectConfirmation = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }
            if (showSavedLimitDialog && savedPlacesExcess > 0) {
                AlertDialog(
                    onDismissRequest = { showSavedLimitDialog = false },
                    title = { Text(stringResource(R.string.dropbox_saved_limit_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.dropbox_saved_limit_message,
                                AppLimits.current.maxSavedPlaces + savedPlacesExcess,
                                AppLimits.current.maxSavedPlaces,
                                savedPlacesExcess,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                showSavedLimitDialog = false
                                dropboxBackupVm.removeOldestExtraPlaces()
                            },
                        ) { Text(stringResource(R.string.dropbox_saved_limit_remove)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavedLimitDialog = false }) {
                            Text(stringResource(R.string.dropbox_saved_limit_manual))
                        }
                    },
                )
            }
            val reviewDialog = backupState.reviewDialog
            if (showConflictDialog && reviewDialog == DropboxReviewDialog.RESTORE) {
                AlertDialog(
                    onDismissRequest = { showConflictDialog = false },
                    title = { Text(stringResource(R.string.dropbox_restore_title)) },
                    text = {
                        Text(
                            stringResource(R.string.dropbox_restore_message),
                            Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showConflictDialog = false
                            dropboxBackupVm.restoreDropboxBackup()
                        }) { Text(stringResource(R.string.dropbox_restore_backup)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConflictDialog = false }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
            if (showConflictDialog && reviewDialog == DropboxReviewDialog.CONFLICT) {
                AlertDialog(
                    onDismissRequest = { showConflictDialog = false },
                    title = { Text(stringResource(R.string.dropbox_conflict_title)) },
                    // The text slot shrinks and scrolls first, so the actions stay visible with large fonts.
                    text = {
                        Text(
                            stringResource(R.string.dropbox_conflict_message),
                            Modifier.verticalScroll(rememberScrollState()),
                        )
                    },
                    // One stack keeps all three actions together; the dialog's button flow row would wrap them unevenly.
                    confirmButton = {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = {
                                showConflictDialog = false
                                dropboxBackupVm.useLocalBackup()
                            }) { Text(stringResource(R.string.dropbox_use_local), textAlign = TextAlign.End) }
                            TextButton(onClick = {
                                showConflictDialog = false
                                dropboxBackupVm.mergeDropboxBackup()
                            }) { Text(stringResource(R.string.dropbox_merge_remote), textAlign = TextAlign.End) }
                            TextButton(onClick = { showConflictDialog = false }) {
                                Text(stringResource(R.string.cancel), textAlign = TextAlign.End)
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun DropboxBackupFrequency.labelRes(): Int = when (this) {
    DropboxBackupFrequency.OFF -> R.string.dropbox_mode_off
    DropboxBackupFrequency.MANUAL -> R.string.dropbox_mode_manual
    DropboxBackupFrequency.DAILY -> R.string.dropbox_frequency_daily
    DropboxBackupFrequency.WEEKLY -> R.string.dropbox_frequency_weekly
}

private fun DropboxBackupFrequency.descriptionRes(): Int = when (this) {
    DropboxBackupFrequency.OFF -> R.string.dropbox_mode_off_description
    DropboxBackupFrequency.MANUAL -> R.string.dropbox_mode_manual_description
    DropboxBackupFrequency.DAILY -> R.string.dropbox_mode_daily_description
    DropboxBackupFrequency.WEEKLY -> R.string.dropbox_mode_weekly_description
}
