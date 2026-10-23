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
    fun periodDays(
        frequency: DropboxBackupFrequency,
        connected: Boolean,
        cloudBackupEnabled: Boolean = CloudBackupFeature.enabled,
    ): Long? = if (cloudBackupEnabled && connected) frequency.periodDays else null
}

internal data class DropboxRemoteFile(
    val path: String,
    val name: String,
    val modifiedAtMillis: Long,
    val id: String,
    val revision: String,
    val size: Long,
    val contentHash: String?,
)

/** Recorded only from a successful upload response followed by a verified download. */
internal data class DropboxOwnedBackup(val file: DropboxRemoteFile) {
    fun matches(remote: DropboxRemoteFile): Boolean =
        file.id == remote.id && file.revision == remote.revision && file.path == remote.path &&
            file.size == remote.size && file.contentHash != null && file.contentHash == remote.contentHash
}

internal object DropboxBackupRetention {
    const val KEEP = 3

    /**
     * Unknown or modified files are never eligible, even inside the managed directory.
     * A missing/inconsistent record for the new upload disables cleanup entirely.
     */
    fun obsolete(
        files: List<DropboxRemoteFile>,
        ownership: List<DropboxOwnedBackup>,
        justUploaded: DropboxRemoteFile,
        keep: Int = KEEP,
    ): List<DropboxRemoteFile> {
        require(keep >= 1)
        if (ownership.map { it.file.id }.distinct().size != ownership.size) return emptyList()
        if (ownership.none { it.matches(justUploaded) } || files.none { it == justUploaded }) return emptyList()
        val newestFirst = files.filter { file -> ownership.any { it.matches(file) } }.sortedWith(
            compareByDescending<DropboxRemoteFile> { it.modifiedAtMillis }
                .thenByDescending { file -> ownership.indexOfFirst { it.file.id == file.id } }
                .thenByDescending { it.name },
        )
        val kept = (listOf(justUploaded.id) + newestFirst.map { it.id })
            .distinct().take(keep).toSet()
        return newestFirst.filter { it.id !in kept }
    }
}

internal data class DropboxBackupSession(val account: String, val generation: String, val credentialTag: String) {
    fun accepts(accountHint: String?, generationHint: String?, verifiedAccount: String, currentCredentialTag: String): Boolean =
        accountHint == account && generationHint == generation && verifiedAccount == account &&
            currentCredentialTag == credentialTag
}

internal object DropboxRemoteState {
    fun unchanged(before: List<DropboxRemoteFile>, after: List<DropboxRemoteFile>, excludingId: String? = null): Boolean =
        before.associateBy { it.id } == after.filter { it.id != excludingId }.associateBy { it.id }

    fun token(files: List<DropboxRemoteFile>): String {
        val description = files.sortedBy { it.id }.joinToString("") { file ->
            listOf(file.id, file.revision, file.path, file.size.toString(), file.contentHash.orEmpty())
                .joinToString("") { "${it.length}:$it" }
        }
        return java.security.MessageDigest.getInstance("SHA-256").digest(description.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
