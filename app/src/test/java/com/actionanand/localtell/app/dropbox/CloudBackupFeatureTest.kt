package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.BuildConfig
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudBackupFeatureTest {
    private fun projectFile(path: String) = listOf(File("../$path"), File(path)).first { it.isFile }
    private fun projectText(path: String) = projectFile(path).readText().replace("\r\n", "\n")

    @Test fun appConfigDeclaresEnableCloudBackupAsJsonBooleanAndBuildConfigMirrorsIt() {
        val value = JSONObject(projectText("app-config.json")).get("enableCloudBackup")
        assertTrue("enableCloudBackup must be a JSON boolean, not ${value.javaClass.simpleName}", value is Boolean)
        assertEquals(value, BuildConfig.ENABLE_CLOUD_BACKUP)
        assertEquals(BuildConfig.ENABLE_CLOUD_BACKUP, CloudBackupFeature.enabled)
    }

    @Test fun gradleRejectsStringBooleansForEnableCloudBackup() {
        val gradle = projectText("app/build.gradle.kts")
        assertTrue(gradle.contains("require(value is Boolean)"))
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"ENABLE_CLOUD_BACKUP\""))
    }

    @Test fun enabledFeatureExposesCloudBackupAndLocalBackup() {
        assertEquals(listOf(BackupScreenSection.LOCAL_BACKUP, BackupScreenSection.CLOUD_BACKUP), backupScreenSections(true))
        assertEquals(CloudBackupFeature.StartupAction.SCHEDULE_CONFIGURED, CloudBackupFeature.startupAction(true))
        assertEquals(1L, DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.DAILY, true, cloudBackupEnabled = true))
    }

    @Test fun disabledFeatureHidesEveryCloudSectionButKeepsLocalBackupAndRestore() {
        assertEquals(listOf(BackupScreenSection.LOCAL_BACKUP), backupScreenSections(false))
    }

    @Test fun disabledFeatureCancelsScheduledWorkAndNeverSchedulesDailyOrWeekly() {
        assertEquals(CloudBackupFeature.StartupAction.CANCEL_SCHEDULED_WORK, CloudBackupFeature.startupAction(false))
        for (frequency in DropboxBackupFrequency.entries) {
            assertNull(DropboxBackupSchedulePolicy.periodDays(frequency, connected = true, cloudBackupEnabled = false))
        }
    }

    @Test fun disabledFeatureStopsQueuedWorkBeforeAnyDropboxOperation() {
        var networkOperations = 0
        var disabledHandled = 0
        val result = guardCloudBackupWork(
            enabled = false,
            onDisabled = { disabledHandled++; "failure" },
        ) {
            networkOperations++
            "synced"
        }
        assertEquals("failure", result)
        assertEquals(1, disabledHandled)
        assertEquals(0, networkOperations)
        assertEquals("synced", guardCloudBackupWork(true, { "failure" }) { networkOperations++; "synced" })
        assertEquals(1, networkOperations)
    }

    @Test fun workerAndSchedulerEntryPointsUseTheCentralSwitch() {
        val dropbox = "app/src/main/java/com/actionanand/localtell/app/dropbox"
        val worker = projectText("$dropbox/DropboxBackupWorker.kt")
        val state = projectText("$dropbox/DropboxBackupState.kt")
        val activity = projectText("app/src/main/java/com/actionanand/localtell/app/MainActivity.kt")
        assertTrue(worker.contains("guardCloudBackupWork(\n        enabled = CloudBackupFeature.enabled"))
        assertTrue(worker.contains("if (!CloudBackupFeature.enabled) return"))
        assertTrue(state.contains("CloudBackupFeature.startupAction()"))
        assertTrue(activity.contains("CloudBackupFeature.enabled && dropboxConnectionViewModel.consumeAuthorizationResult()"))
        assertTrue(activity.contains("!CloudBackupFeature.enabled || !dropboxConnectionViewModel.beginAuthorization()"))
    }
}
