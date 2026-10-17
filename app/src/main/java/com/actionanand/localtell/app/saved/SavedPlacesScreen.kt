package com.actionanand.localtell.app.saved

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.actionanand.localtell.app.R
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedPlacesFeedback(onManageSavedPlaces: () -> Unit, vm: SavedPlacesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(state.messageSequence) { if (message != null) host.showSnackbar(message) }
    SnackbarHost(host, Modifier.padding(12.dp))
    if (state.limitReached) AlertDialog(
        onDismissRequest = vm::dismissLimit,
        title = { Text(stringResource(R.string.saved_limit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.saved_limit_count, MAX_SAVED_PLACES, MAX_SAVED_PLACES), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.saved_limit_message))
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissLimit(); onManageSavedPlaces() }) { Text(stringResource(R.string.saved_manage)) } },
        dismissButton = { TextButton(onClick = vm::dismissLimit) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun BookmarkButton(place: SavedPlace?, vm: SavedPlacesViewModel = viewModel()) {
    if (place == null) return
    val places by vm.places.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val existing = places.firstOrNull { SavedPlaceIdentity.matches(it, place) }
    var removalId by rememberSaveable(place.latitude, place.longitude) { mutableStateOf<String?>(null) }
    IconButton(enabled = state.loaded && !state.busy, onClick = {
        if (existing == null) vm.save(place) else removalId = existing.id
    }) {
        Icon(
            if (existing == null) Icons.Outlined.BookmarkBorder else Icons.Filled.Bookmark,
            stringResource(if (existing == null) R.string.saved_save_location else R.string.saved_remove_bookmark),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    removalId?.let { id ->
        SavedRemovalDialog(1, false, onDismiss = { removalId = null }, onConfirm = {
            vm.delete(setOf(id)); removalId = null
        })
    }
}

@Composable
fun SavedPlacesScreen(onOpen: (SavedPlace) -> Unit, vm: SavedPlacesViewModel = viewModel()) {
    val places by vm.places.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.saved_backup_share)
    var selection by rememberSaveable { mutableStateOf(SavedPlacesSelection()) }
    val selectedIds = selection.selected.filter { id -> places.any { it.id == id } }.toSet()
    val selecting = selection.selected.isNotEmpty()
    BackHandler(selecting) { selection = selection.cancelSelection() }
    val export = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri == null) vm.cancelBackup() else vm.export(uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::inspectBackup)
    }
    fun saveBackup(localOnly: Boolean) {
        val prepared = state.preparedBackup ?: return
        vm.destinationPickerStarted()
        runCatching { export.launch(BackupDestination(prepared.filename, localOnly)) }
            .onFailure { vm.documentUnavailable(); vm.backupShareFallback() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.saved_count, places.size, MAX_SAVED_PLACES), style = MaterialTheme.typography.bodyMedium)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!state.loaded && !state.busy) TextButton(onClick = vm::refresh) { Text(stringResource(R.string.saved_retry)) }
        }
        item {
            if (selecting) {
                Text(stringResource(R.string.saved_selected_count, selectedIds.size), fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !state.busy, onClick = { selection = selection.selectAll(places.map { it.id }) }) { Text(stringResource(R.string.saved_select_all)) }
                    TextButton(onClick = { selection = selection.cancelSelection() }) { Text(stringResource(R.string.cancel)) }
                    OutlinedButton(enabled = !state.busy && selectedIds.isNotEmpty(), onClick = {
                        selection = selection.requestRemoval(selectedIds.toList())
                    }) { Text(stringResource(R.string.saved_delete_selected)) }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = state.loaded && !state.busy && places.isNotEmpty(), onClick = {
                        vm.prepareBackup()
                    }) { Text(stringResource(R.string.saved_export)) }
                    OutlinedButton(enabled = state.loaded && !state.busy, onClick = {
                        // Extensions are not reliable MIME types. Validate DISPLAY_NAME and content afterward.
                        runCatching { restore.launch(arrayOf("*/*")) }.onFailure { vm.documentUnavailable() }
                    }) { Text(stringResource(R.string.saved_restore)) }
                    TextButton(enabled = !state.busy && places.isNotEmpty(), onClick = {
                        selection = selection.requestRemoval(places.map { it.id }, all = true)
                    }) { Text(stringResource(R.string.saved_remove_all)) }
                }
                Text(stringResource(R.string.saved_backup_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.loaded && places.isEmpty()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.saved_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.saved_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(places, key = SavedPlace::id) { place ->
            val checked = place.id in selectedIds
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        enabled = !state.busy,
                        role = Role.Button,
                        onClickLabel = stringResource(if (selecting) R.string.saved_toggle_selection else R.string.saved_open),
                        onLongClickLabel = stringResource(R.string.saved_select),
                        onLongClick = { selection = selection.select(place.id) },
                        onClick = {
                            if (selecting) selection = selection.toggle(place.id)
                            else onOpen(place)
                        },
                    ).semantics { if (selecting) this.selected = checked }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting) Checkbox(checked, onCheckedChange = null)
                    else Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(place.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val hierarchy = listOfNotNull(place.subDistrict, place.district, place.state, place.countryCode)
                            .filterNot { it == place.displayName }.distinct()
                        if (hierarchy.isNotEmpty()) Text(hierarchy.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        val savedDate = remember(place.savedAt, locale) {
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(place.savedAt))
                        }
                        Text(stringResource(R.string.saved_date, savedDate), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!selecting) IconButton(enabled = !state.busy, onClick = { selection = selection.requestRemoval(listOf(place.id)) }) {
                        Icon(Icons.Default.DeleteOutline, stringResource(R.string.saved_remove_bookmark))
                    }
                }
            }
        }
    }
    state.preparedBackup?.takeIf { state.chooseBackupDestination || state.cloudBackupOptions }?.let { prepared ->
        AlertDialog(
            onDismissRequest = vm::cancelBackup,
            title = { Text(stringResource(if (state.cloudBackupOptions) R.string.saved_backup_options else R.string.saved_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(prepared.filename, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.saved_backup_choose))
                    if (state.chooseBackupDestination) {
                        OutlinedButton(enabled = !state.busy, onClick = { saveBackup(true) }) { Text(stringResource(R.string.saved_backup_local)) }
                        OutlinedButton(enabled = !state.busy, onClick = vm::backupShareFallback) { Text(stringResource(R.string.saved_backup_cloud)) }
                    } else {
                        OutlinedButton(enabled = !state.busy, onClick = { saveBackup(false) }) { Text(stringResource(R.string.saved_backup_provider)) }
                        TextButton(enabled = !state.busy, onClick = {
                            runCatching { sharePreparedBackup(context, prepared, shareTitle) }
                                .onSuccess { vm.backupSharingOpened() }.onFailure { vm.documentUnavailable() }
                        }) { Text(stringResource(R.string.saved_backup_share)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelBackup) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (selection.pendingRemoval.isNotEmpty()) SavedRemovalDialog(selection.pendingRemoval.size, selection.removeAll, onDismiss = { selection = selection.cancelRemoval() }, onConfirm = {
        val (approvedIds, next) = selection.confirmRemoval()
        vm.delete(approvedIds); selection = next
    })
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = vm::dismissRestore,
            title = { Text(stringResource(R.string.saved_restore)) },
            text = { Text(stringResource(if (preview.fits) R.string.saved_restore_preview else R.string.saved_restore_overflow, preview.added, preview.duplicates, preview.total)) },
            confirmButton = { if (preview.fits) TextButton(enabled = !state.busy, onClick = vm::restore) { Text(stringResource(R.string.saved_restore)) } },
            dismissButton = { TextButton(onClick = vm::dismissRestore) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SavedRemovalDialog(count: Int, all: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (all) R.string.saved_remove_all else R.string.saved_remove_bookmark)) },
        text = { Text(stringResource(if (all) R.string.saved_remove_all_confirm else R.string.saved_remove_confirm, count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.saved_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
