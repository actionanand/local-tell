package com.actionanand.localtell.app.dropbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxBackupPolicyTest {
    @Test fun fourBackupModesPersistByStablePreferenceValues() {
        assertEquals(listOf("off", "manual", "daily", "weekly"), DropboxBackupFrequency.entries.map { it.preferenceValue })
        DropboxBackupFrequency.entries.forEach { assertEquals(it, DropboxBackupFrequency.fromPreference(it.preferenceValue)) }
    }

    @Test fun missingOrUnknownPreferenceDefaultsToOff() {
        assertEquals(DropboxBackupFrequency.OFF, DropboxBackupFrequency.fromPreference(null))
        assertEquals(DropboxBackupFrequency.OFF, DropboxBackupFrequency.fromPreference("hourly"))
    }

    @Test fun offBlocksAllSyncWhileManualAllowsOnlyTapToSync() {
        assertFalse(DropboxBackupFrequency.OFF.syncAllowed)
        assertTrue(DropboxBackupFrequency.MANUAL.syncAllowed)
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.OFF, connected = true))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.MANUAL, connected = true))
    }

    @Test fun dailyAndWeeklyScheduleOnlyWhileConnected() {
        assertEquals(1L, DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.DAILY, connected = true))
        assertEquals(7L, DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.WEEKLY, connected = true))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.DAILY, connected = false))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.WEEKLY, connected = false))
    }

    @Test fun retentionKeepsNewestThreeIncludingJustUploaded() {
        val files = (1..5).map { DropboxRemoteFile("/localtell-$it.bklt", "LocalTell-$it.bklt", it * 1000L) }
        val obsolete = DropboxBackupRetention.obsolete(files, "/LocalTell-5.bklt")
        assertEquals(listOf("/localtell-2.bklt", "/localtell-1.bklt"), obsolete.map { it.path })
    }

    @Test fun retentionDeletesNothingWithThreeOrFewerSnapshots() {
        val files = (1..3).map { DropboxRemoteFile("/localtell-$it.bklt", "LocalTell-$it.bklt", it * 1000L) }
        assertTrue(DropboxBackupRetention.obsolete(files, "/localtell-3.bklt").isEmpty())
        assertTrue(DropboxBackupRetention.obsolete(emptyList(), "/localtell-9.bklt").isEmpty())
    }

    @Test fun retentionNeverDeletesTheVerifiedUploadEvenIfListedOldest() {
        val files = listOf(
            DropboxRemoteFile("/localtell-new.bklt", "LocalTell-new.bklt", 1L),
            DropboxRemoteFile("/localtell-a.bklt", "LocalTell-a.bklt", 10L),
            DropboxRemoteFile("/localtell-b.bklt", "LocalTell-b.bklt", 20L),
            DropboxRemoteFile("/localtell-c.bklt", "LocalTell-c.bklt", 30L),
        )
        val obsolete = DropboxBackupRetention.obsolete(files, "/LocalTell-new.bklt").map { it.path }
        assertFalse("/localtell-new.bklt" in obsolete)
        assertEquals(listOf("/localtell-a.bklt"), obsolete)
    }

    @Test fun syncProtocolDetectsConflictsAndNeverOverwritesNewerRemote() {
        assertEquals(DropboxSyncDecision.UP_TO_DATE, DropboxSyncProtocol.decide("L", "L", null))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", null, null))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L2", "B", "B"))
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L2", "R2", "B"))
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L", "R", null))
    }

    @Test fun afterMergeAdoptingRemoteBaselineLetsTrimmedLocalUploadWithoutNewConflict() {
        // Merge adopts remote "R" as baseline; trimming over-limit places changes local to "T".
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("T", "R", "R"))
    }
}
