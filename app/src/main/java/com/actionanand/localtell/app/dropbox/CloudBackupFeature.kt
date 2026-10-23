package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.BuildConfig

/**
 * Build-time switch from `app-config.json` → `enableCloudBackup` → `BuildConfig.ENABLE_CLOUD_BACKUP`.
 * Disabling hides and stops cloud backup only; credentials, session metadata, Dropbox files and local
 * backups are preserved so a later build can resume through normal verification.
 */
internal object CloudBackupFeature {
    val enabled: Boolean get() = BuildConfig.ENABLE_CLOUD_BACKUP

    enum class StartupAction { SCHEDULE_CONFIGURED, CANCEL_SCHEDULED_WORK }

    /** An APK built with the feature disabled must remove work scheduled by an earlier build. */
    fun startupAction(enabled: Boolean = this.enabled): StartupAction =
        if (enabled) StartupAction.SCHEDULE_CONFIGURED else StartupAction.CANCEL_SCHEDULED_WORK
}

/** Entry-point guard shared by manual and periodic Dropbox work; [work] never runs when disabled. */
internal inline fun <T> guardCloudBackupWork(enabled: Boolean, onDisabled: () -> T, work: () -> T): T =
    if (enabled) work() else onDisabled()

internal enum class BackupScreenSection { LOCAL_BACKUP, CLOUD_BACKUP }

/** Local backup, share and restore stay available regardless of the cloud backup switch. */
internal fun backupScreenSections(cloudBackupEnabled: Boolean = CloudBackupFeature.enabled): List<BackupScreenSection> =
    buildList {
        add(BackupScreenSection.LOCAL_BACKUP)
        if (cloudBackupEnabled) add(BackupScreenSection.CLOUD_BACKUP)
    }
