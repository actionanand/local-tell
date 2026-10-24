package com.actionanand.localtell.app.backup

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency
import com.actionanand.localtell.app.dropbox.DropboxBackupSchedule
import com.actionanand.localtell.app.dropbox.DropboxBackupStatus
import com.actionanand.localtell.app.dropbox.DropboxBackupUiState
import com.actionanand.localtell.app.dropbox.DropboxScheduleState
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Off / Manual / Daily / Weekly rows with calendar-based next-backup details and per-row sync icons. */
@Composable
internal fun DropboxScheduleSection(
    state: DropboxBackupUiState,
    scheduledQueued: Boolean,
    syncEnabled: Boolean,
    onSelect: (DropboxBackupFrequency) -> Unit,
    onWeekday: (DayOfWeek) -> Unit,
    onSyncNow: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // Keeps "Today"/"Tomorrow" and the overdue state current while the screen stays open.
    val tick by produceState(0) {
        while (true) {
            delay(30_000)
            value++
        }
    }
    val now = remember(tick, state) { Instant.now() }
    val zone = remember(tick) { ZoneId.systemDefault() }
    val syncing = state.status == DropboxBackupStatus.SYNCING
    val preferredTime = formatTime(context, DropboxBackupSchedule.PREFERRED_TIME)
    Text(stringResource(R.string.dropbox_backup_mode), fontWeight = FontWeight.SemiBold)
    Column(Modifier.selectableGroup()) {
        DropboxBackupFrequency.entries.forEach { mode ->
            val selected = state.frequency == mode
            val scheduled = mode == DropboxBackupFrequency.DAILY || mode == DropboxBackupFrequency.WEEKLY
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = !syncing, role = Role.RadioButton, onClick = { onSelect(mode) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = !syncing)
                Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (mode) {
                            DropboxBackupFrequency.DAILY -> stringResource(R.string.dropbox_daily_rule, preferredTime)
                            DropboxBackupFrequency.WEEKLY -> stringResource(
                                R.string.dropbox_weekly_rule,
                                state.weeklyDay.getDisplayName(TextStyle.FULL, locale),
                                preferredTime,
                            )
                            else -> stringResource(checkNotNull(mode.descriptionRes()))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DropboxBackupSchedule.activeNextBackup(state.frequency, mode, state.weeklyDay, now, zone)?.let { next ->
                        Text(
                            stringResource(R.string.dropbox_next_backup, describeOccurrence(context, locale, next, now, zone)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (scheduled) {
                    val label = stringResource(R.string.dropbox_sync_now_icon)
                    IconButton(
                        onClick = onSyncNow,
                        enabled = syncEnabled && !syncing,
                        modifier = Modifier.semantics { contentDescription = label },
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = null)
                        }
                    }
                }
            }
            if (mode == DropboxBackupFrequency.WEEKLY && selected) {
                WeekdaySelector(state.weeklyDay, locale, enabled = !syncing, onWeekday = onWeekday)
            }
        }
    }
    if (state.frequency == DropboxBackupFrequency.DAILY || state.frequency == DropboxBackupFrequency.WEEKLY) {
        val overdue = DropboxBackupSchedule.overdue(
            state.frequency, state.weeklyDay, now, zone, state.scheduleSince, state.coveredThrough,
        )
        val display = DropboxBackupSchedule.displayState(
            state.status, overdue, state.scheduledBlockedOccurrence, scheduledQueued,
        )
        Text(
            stringResource(R.string.dropbox_schedule_status, stringResource(display.labelRes())),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (display == DropboxScheduleState.FAILED || display == DropboxScheduleState.OVERDUE)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (display == DropboxScheduleState.OVERDUE || display == DropboxScheduleState.QUEUED ||
            display == DropboxScheduleState.WAITING_FOR_INTERNET) {
            Text(stringResource(R.string.dropbox_schedule_overdue), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.dropbox_schedule_helper, preferredTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekdaySelector(selected: DayOfWeek, locale: Locale, enabled: Boolean, onWeekday: (DayOfWeek) -> Unit) {
    val first = WeekFields.of(locale).firstDayOfWeek
    Column(Modifier.padding(start = 48.dp, bottom = 4.dp)) {
        Text(stringResource(R.string.dropbox_backup_day), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0L until 7L).map { first.plus(it) }.forEach { day ->
                val fullName = day.getDisplayName(TextStyle.FULL, locale)
                FilterChip(
                    selected = day == selected,
                    enabled = enabled,
                    onClick = { onWeekday(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    modifier = Modifier.semantics { contentDescription = fullName },
                )
            }
        }
    }
}

private fun formatTime(context: Context, time: LocalTime): String {
    // A fixed January date keeps the label free of any DST gap on the current day.
    val sample = LocalDate.of(2001, 1, 8).atTime(time).atZone(ZoneId.systemDefault()).toInstant()
    return android.text.format.DateFormat.getTimeFormat(context).format(Date.from(sample))
}

/** "Today, 2:00 AM", "Tomorrow, 2:00 AM" or a short weekday/date, always in local time. */
@Composable
private fun describeOccurrence(context: Context, locale: Locale, at: Instant, now: Instant, zone: ZoneId): String {
    val date = Date.from(at)
    val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
    val today = now.atZone(zone).toLocalDate()
    return when (at.atZone(zone).toLocalDate()) {
        today -> stringResource(R.string.dropbox_when_today, time)
        today.plusDays(1) -> stringResource(R.string.dropbox_when_tomorrow, time)
        else -> stringResource(
            R.string.dropbox_when_date,
            SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(date),
            time,
        )
    }
}

private fun DropboxScheduleState.labelRes(): Int = when (this) {
    DropboxScheduleState.SCHEDULED -> R.string.dropbox_schedule_scheduled
    DropboxScheduleState.QUEUED -> R.string.dropbox_schedule_queued
    DropboxScheduleState.SYNCING -> R.string.dropbox_schedule_syncing
    DropboxScheduleState.WAITING_FOR_INTERNET -> R.string.dropbox_schedule_waiting
    DropboxScheduleState.OVERDUE -> R.string.dropbox_schedule_overdue_state
    DropboxScheduleState.FAILED -> R.string.dropbox_schedule_failed
    DropboxScheduleState.PAUSED -> R.string.dropbox_schedule_paused
}
