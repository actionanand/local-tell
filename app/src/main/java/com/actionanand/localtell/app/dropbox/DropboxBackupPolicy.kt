package com.actionanand.localtell.app.dropbox

/** How LocalTell backs up to Dropbox. OFF never syncs; MANUAL syncs only when the user taps Sync now. */
internal enum class DropboxBackupFrequency(val preferenceValue: String, val periodDays: Long?) {
    OFF("off", null),
    MANUAL("manual", null),
    DAILY("daily", 1L),
    WEEKLY("weekly", 7L),
    ;

    val syncAllowed: Boolean get() = this != OFF

    companion object {
        fun fromPreference(value: String?): DropboxBackupFrequency =
            entries.firstOrNull { it.preferenceValue == value } ?: OFF
    }
}

internal object DropboxBackupSchedulePolicy {
    /** Repeat interval in days for scheduled work, or null when no periodic work must exist. */
    fun periodDays(frequency: DropboxBackupFrequency, connected: Boolean): Long? =
        if (connected) frequency.periodDays else null
}

internal data class DropboxRemoteFile(val path: String, val name: String, val modifiedAtMillis: Long)

internal object DropboxBackupRetention {
    const val KEEP = 3

    /**
     * Managed snapshots older than the newest [keep]. The verified [justUploadedPath] is never returned,
     * so the only valid recovery copy cannot be deleted.
     */
    fun obsolete(files: List<DropboxRemoteFile>, justUploadedPath: String, keep: Int = KEEP): List<DropboxRemoteFile> {
        require(keep >= 1)
        val newestFirst = files.sortedWith(
            compareByDescending<DropboxRemoteFile> { it.modifiedAtMillis }.thenByDescending { it.name },
        )
        val kept = (listOf(justUploadedPath.lowercase()) + newestFirst.map { it.path.lowercase() })
            .distinct().take(keep).toSet()
        return newestFirst.filter { it.path.lowercase() !in kept }
    }
}

internal enum class DropboxSyncDecision { UP_TO_DATE, UPLOAD_LOCAL, CONFLICT }

internal object DropboxSyncProtocol {
    fun decide(local: String, remote: String?, baseline: String?): DropboxSyncDecision {
        if (remote == local) return DropboxSyncDecision.UP_TO_DATE
        if (remote == null && baseline == null) return DropboxSyncDecision.UPLOAD_LOCAL
        if (baseline != null && remote == baseline && local != baseline) return DropboxSyncDecision.UPLOAD_LOCAL
        return DropboxSyncDecision.CONFLICT
    }
}
