package com.actionanand.localtell.app.dropbox

import com.dropbox.core.v2.files.CreateFolderErrorException
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.ListFolderErrorException

internal class DropboxBackupFolderException : IllegalStateException("Backup destination is not a folder")

internal class DropboxBackupFolder(
    private val isFolder: () -> Boolean,
    private val create: () -> Unit,
) {
    fun discover(list: () -> List<DropboxRemoteFile>): List<DropboxRemoteFile> = try {
        list()
    } catch (error: ListFolderErrorException) {
        if (!error.errorValue.isPath || !error.errorValue.pathValue.isNotFound) throw error
        emptyList()
    }

    fun prepare() {
        try {
            if (!isFolder()) throw DropboxBackupFolderException()
            return
        } catch (error: GetMetadataErrorException) {
            if (!error.errorValue.pathValue.isNotFound) throw error
        }
        try {
            create()
        } catch (error: CreateFolderErrorException) {
            // Only a concurrent folder creation may be accepted, not permission/quota failures.
            if (!error.errorValue.pathValue.isConflict || !isFolder()) throw error
        }
    }
}
