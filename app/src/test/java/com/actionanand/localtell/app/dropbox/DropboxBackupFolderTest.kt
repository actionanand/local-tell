package com.actionanand.localtell.app.dropbox

import com.dropbox.core.v2.files.*
import org.junit.Assert.*
import org.junit.Test

class DropboxBackupFolderTest {
    private fun missingMetadata() = GetMetadataErrorException("metadata", "req", null, GetMetadataError.path(LookupError.NOT_FOUND))
    private fun missingListing() = ListFolderErrorException("list", "req", null, ListFolderError.path(LookupError.NOT_FOUND))

    @Test fun absentManagedFolderIsEmptyAndCreatedForFirstUpload() {
        var exists = false
        var creates = 0
        val folder = DropboxBackupFolder(
            isFolder = { if (!exists) throw missingMetadata() else true },
            create = { creates++; exists = true },
        )
        // A root folder does not need to be listed to discover managed backups.
        assertTrue(folder.discover { throw missingListing() }.isEmpty())
        folder.prepare()
        folder.prepare()
        assertTrue(exists)
        assertEquals(1, creates)
    }

    @Test fun creationRaceAcceptsOnlyAnExistingFolder() {
        var lookups = 0
        val conflict = CreateFolderErrorException("create", "req", null,
            CreateFolderError.path(WriteError.conflict(WriteConflictError.FOLDER)))
        val folder = DropboxBackupFolder(
            isFolder = { if (++lookups == 1) throw missingMetadata() else true },
            create = { throw conflict },
        )
        folder.prepare()
        assertEquals(2, lookups)
        var fileLookups = 0
        val occupiedByFile = DropboxBackupFolder(
            { if (++fileLookups == 1) throw missingMetadata() else false },
            { throw conflict },
        )
        assertSame(conflict, assertThrows(CreateFolderErrorException::class.java) { occupiedByFile.prepare() })
    }

    @Test fun permissionFailureIsNotSwallowedAsFolderCreationRace() {
        val permission = CreateFolderErrorException("create", "req", null,
            CreateFolderError.path(WriteError.NO_WRITE_PERMISSION))
        val folder = DropboxBackupFolder({ throw missingMetadata() }, { throw permission })
        assertSame(permission, assertThrows(CreateFolderErrorException::class.java) { folder.prepare() })
    }

    @Test fun nonMissingListingErrorIsNeverTreatedAsEmpty() {
        val error = ListFolderErrorException("list", "req", null, ListFolderError.path(LookupError.NOT_FOLDER))
        val folder = DropboxBackupFolder({ true }, {})
        assertSame(error, assertThrows(ListFolderErrorException::class.java) { folder.discover { throw error } })
    }

    @Test fun existingFileAtFolderPathReportsPathErrorWithoutTryingToCreateAnything() {
        val folder = DropboxBackupFolder({ false }, { fail("Must not create over an existing file") })
        val error = assertThrows(DropboxBackupFolderException::class.java) { folder.prepare() }
        assertEquals(DropboxBackupError.DROPBOX_PATH,
            classifyDropboxFailure(error, DropboxBackupStage.PREPARE_BACKUP_FOLDER).error)
    }
}
