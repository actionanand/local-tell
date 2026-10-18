package com.actionanand.localtell.app.saved

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class BackupSnapshotsTest {
    private val day = 24L * 60 * 60 * 1_000
    private val epoch = 1_800_000_000_000L

    private fun fixture(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("localtell-snapshot-test-").toFile()
        val directory = File(root, "saved-place-backups").apply { mkdir() }
        try { test(root, directory) }
        finally {
            // Test-only, explicitly created temporary directory; do not follow links.
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /** Explicit dispatcher queues expose the exact IO-complete/UI-not-resumed boundary. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        var running = false
            private set
        val pending: Boolean get() = queue.isNotEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() {
            repeat(queue.size) {
                running = true
                try { queue.removeFirst().run() } finally { running = false }
            }
        }
    }

    private fun drain(ui: QueuedDispatcher, io: QueuedDispatcher) {
        repeat(20) {
            if (!ui.pending && !io.pending) return
            ui.runCurrent()
            io.runCurrent()
        }
        fail("Preparation dispatchers did not finish")
    }

    private fun assertNotTracked(snapshot: BackupSnapshot) {
        val field = BackupSnapshots::class.java.getDeclaredField("tracked").apply { isAccessible = true }
        val tracked = field.get(null) as Map<*, *>
        assertFalse("Unpublished snapshot must leave the process registry", tracked.containsKey(snapshot.file.absolutePath))
    }

    @Test fun `cancel after IO completion before UI publication discards the provisional snapshot on IO`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val ui = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scope = CoroutineScope(ui)
        lateinit var snapshot: BackupSnapshot
        var published = false
        val request = scope.launch {
            store.prepareForPublication(
                content = { "unpublished backup" },
                build = { assertTrue(io.running); snapshot = it; it },
                publish = { published = true },
                ioDispatcher = io,
            )
        }
        try {
            ui.runCurrent() // Start the request, queue file creation on IO.
            io.runCurrent() // Complete IO; its result is queued for UI delivery.
            assertTrue(snapshot.file.exists())
            assertTrue(ui.pending)
            request.cancel()
            ui.runCurrent() // Drop the result and enter finally; cleanup must be dispatched.
            assertFalse(published)
            assertTrue(snapshot.file.exists()) // No filesystem deletion on the UI dispatcher.
            assertTrue(io.pending)
            io.runCurrent() // NonCancellable cleanup still runs after its parent was cancelled.
            assertFalse(snapshot.file.exists())
            ui.runCurrent()
            assertTrue(request.isCompleted)
            assertTrue(request.isCancelled)
            assertFalse(snapshot.beginUse())
            assertNotTracked(snapshot)
        } finally { scope.cancel(); drain(ui, io) }
    }

    @Test fun `successful publication keeps the snapshot until its UI owner cancels`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val ui = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scope = CoroutineScope(ui)
        lateinit var owned: BackupSnapshot
        val request = scope.launch {
            store.prepareForPublication(
                content = { "owned backup" }, build = { it },
                publish = { assertTrue(ui.running); owned = it }, ioDispatcher = io,
            )
        }
        try {
            drain(ui, io)
            assertTrue(request.isCompleted)
            assertFalse(request.isCancelled)
            assertEquals("owned backup", owned.file.readText())
            assertFalse(owned.claimDeletion(unusedOnly = false)) // Still belongs to the UI.
            owned.release()
            store.discardUnused(owned)
            assertFalse(owned.file.exists())
            assertNotTracked(owned)
        } finally { scope.cancel(); drain(ui, io) }
    }

    @Test fun `cancellation immediately after publication does not discard the UI owned unused snapshot`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val ui = QueuedDispatcher()
        val io = QueuedDispatcher()
        val scope = CoroutineScope(ui)
        lateinit var owned: BackupSnapshot
        lateinit var request: Job
        request = scope.launch {
            store.prepareForPublication(
                content = { "published before cancellation" }, build = { it }, ioDispatcher = io,
                publish = { owned = it; request.cancel() },
            )
        }
        try {
            drain(ui, io)
            assertTrue(request.isCancelled)
            assertTrue(owned.file.exists())
            assertFalse(owned.claimDeletion(unusedOnly = true)) // Publication transferred the lease.
            owned.release()
            store.discardUnused(owned)
            assertFalse(owned.file.exists())
            assertNotTracked(owned)
        } finally { scope.cancel(); drain(ui, io) }
    }

    @Test fun `cancellation after publication leaves active shared or exported snapshots with their owner`() = fixture { _, directory ->
        for (operation in listOf("share", "export")) {
            val store = BackupSnapshots(directory, { epoch })
            val ui = QueuedDispatcher()
            val io = QueuedDispatcher()
            val scope = CoroutineScope(ui)
            lateinit var owned: BackupSnapshot
            lateinit var request: Job
            request = scope.launch {
                store.prepareForPublication(
                    content = { operation }, build = { it }, ioDispatcher = io,
                    publish = {
                        owned = it
                        assertTrue(owned.beginUse())
                        owned.markUsed()
                        request.cancel() // Already published, before the helper returns.
                    },
                )
            }
            try {
                drain(ui, io)
                assertTrue(request.isCancelled)
                assertEquals(operation, owned.file.readText())
                owned.release()
                store.discardUnused(owned)
                assertTrue(owned.file.exists()) // Active input is protected.
                owned.endUse()
                store.discardUnused(owned)
                assertTrue(owned.file.exists()) // Completed handoff still gets 30-day retention.
                assertEquals(30 * day, BackupSnapshots.RETENTION_MILLIS)
            } finally { scope.cancel(); drain(ui, io) }
        }
    }

    @Test fun `only snapshots strictly older than thirty days are removed`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        fun file(age: Long): File = File(directory, BackupFileFormat.filename()).apply {
            writeText("snapshot")
            assertTrue(setLastModified(epoch - age))
        }
        val old = file(30 * day + 1)
        val boundary = file(30 * day)
        val recent = file(day)
        val future = file(-day)
        store.cleanup()
        assertFalse(old.exists())
        listOf(boundary, recent, future).forEach { assertTrue(it.exists()) }
    }

    @Test fun `cleanup skips other names nested files directories and files outside its private root`() = fixture { root, directory ->
        val unrelated = listOf(File(directory, "personal.bklt"), File(directory, "LocalTell-test.json"),
            File(directory, "LocalTell-18-Oct-2026-08-30PM-not-a-uuid.bklt"),
            File(root, BackupFileFormat.filename()))
        val nested = File(directory, "nested").apply { mkdir() }
        val nestedFile = File(nested, BackupFileFormat.filename())
        (unrelated + nestedFile).forEach { it.writeText("keep"); assertTrue(it.setLastModified(epoch - 60 * day)) }
        val namedDirectory = File(directory, BackupFileFormat.filename()).apply { mkdir() }
        BackupSnapshots(directory, { epoch }).cleanup()
        (unrelated + nestedFile + namedDirectory).forEach { assertTrue(it.exists()) }
    }

    @Test fun `explicit cancellation deletes a never-used snapshot and prevents a stale use`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val snapshot = store.prepare("private backup")
        snapshot.release()
        store.discardUnused(snapshot)
        assertFalse(snapshot.file.exists())
        assertFalse(snapshot.beginUse())
    }

    @Test fun `delayed share survives cancel and subsequent preparation then expires from its handoff time`() = fixture { _, directory ->
        var now = epoch
        val store = BackupSnapshots(directory, { now })
        val shared = store.prepare("shared backup")
        assertTrue(shared.file.setLastModified(epoch - 40 * day))
        assertTrue(shared.beginUse())
        store.retain(shared) // Done before opening the Sharesheet, even if preparation was long ago.
        shared.markUsed()
        shared.release()
        store.discardUnused(shared)
        shared.endUse()
        assertEquals("shared backup", shared.file.readText())
        now += day
        store.prepare("next backup")
        assertEquals("shared backup", shared.file.readText())
        // A new process/store has no in-memory leases; the handoff timestamp must survive.
        now = epoch + 30 * day
        BackupSnapshots(directory, { now }).cleanup()
        assertTrue(shared.file.exists())
        now++
        BackupSnapshots(directory, { now }).cleanup()
        assertFalse(shared.file.exists())
    }

    @Test fun `a pending picker or active operation is protected even beyond retention`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val snapshot = store.prepare("active export")
        assertTrue(snapshot.file.setLastModified(epoch - 60 * day))
        store.cleanup() // Still prepared, including while a destination picker is open.
        assertTrue(snapshot.file.exists())
        assertTrue(snapshot.beginUse())
        snapshot.release() // A Cancel/dismiss racing the export must not remove its input.
        store.discardUnused(snapshot)
        store.cleanup()
        assertTrue(snapshot.file.exists())
        snapshot.endUse()
        store.cleanup()
        assertFalse(snapshot.file.exists())
    }

    @Test fun `cancellation before a coroutine starts releases the operation lease`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val snapshot = store.prepare("cancelled operation")
        runBlocking {
            assertTrue(snapshot.beginUse())
            val job = launch(start = CoroutineStart.LAZY) { fail("Cancelled operation must not run") }
            job.invokeOnCompletion { snapshot.endUse() }
            snapshot.release()
            job.cancel()
            job.join()
        }
        store.discardUnused(snapshot)
        assertFalse(snapshot.file.exists())
    }

    @Test fun `another owner in the same process cannot clean an active snapshot`() = fixture { _, directory ->
        val owner = BackupSnapshots(directory, { epoch })
        val snapshot = owner.prepare("still in use")
        assertTrue(snapshot.file.setLastModified(epoch - 60 * day))
        assertTrue(snapshot.beginUse())
        snapshot.release()
        BackupSnapshots(directory, { epoch }).cleanup()
        assertTrue(snapshot.file.exists())
        snapshot.endUse()
        BackupSnapshots(directory, { epoch }).cleanup()
        assertFalse(snapshot.file.exists())
    }

    @Test fun `cancel during pre-share preparation waits for the lease then deletes the unused file`() = fixture { _, directory ->
        val store = BackupSnapshots(directory, { epoch })
        val snapshot = store.prepare("not handed off")
        assertTrue(snapshot.beginUse())
        store.retain(snapshot)
        snapshot.release()
        store.discardUnused(snapshot)
        assertTrue(snapshot.file.exists())
        snapshot.endUse()
        store.discardUnused(snapshot)
        assertFalse(snapshot.file.exists())
    }

    @Test fun `a filename collision never deletes or overwrites the existing snapshot`() = fixture { _, directory ->
        val name = BackupFileFormat.filename()
        val store = BackupSnapshots(directory, { epoch }, { name })
        val existing = store.prepare("original")
        assertThrows(IllegalStateException::class.java) { store.prepare("replacement") }
        assertEquals("original", existing.file.readText())
    }

    @Test fun `cleanup rechecks age when a handoff renews an initially old timestamp`() = fixture { _, directory ->
        val actual = File(directory, BackupFileFormat.filename()).apply {
            writeText("just shared")
            assertTrue(setLastModified(epoch - 60 * day))
        }
        val racedFile = object : File(actual.path) {
            var reads = 0
            override fun lastModified(): Long {
                if (++reads == 2) assertTrue(actual.setLastModified(epoch))
                return super.lastModified()
            }
        }
        val racedDirectory = object : File(directory.path) {
            override fun listFiles(): Array<File> = arrayOf(racedFile)
        }
        BackupSnapshots(racedDirectory, { epoch }).cleanup()
        assertEquals(2, racedFile.reads)
        assertEquals("just shared", actual.readText())
    }

    @Test fun `cleanup never follows symlinks and preparation rejects a linked directory`() = fixture { root, directory ->
        val outside = File(root, "exported.bklt").apply { writeText("user document") }
        val linkedSnapshot = File(directory, BackupFileFormat.filename()).toPath()
        try { Files.createSymbolicLink(linkedSnapshot, outside.toPath()) }
        catch (unsupported: IOException) { assumeNoException(unsupported) }
        catch (unsupported: UnsupportedOperationException) { assumeNoException(unsupported) }
        catch (unsupported: SecurityException) { assumeNoException(unsupported) }
        BackupSnapshots(directory, { epoch + 60 * day }).cleanup()
        assertTrue(Files.isSymbolicLink(linkedSnapshot))
        assertEquals("user document", outside.readText())

        val otherRoot = File(root, "other").apply { mkdir() }
        val aliasParent = File(root, "alias").apply { mkdir() }
        val alias = File(aliasParent, "saved-place-backups")
        Files.createSymbolicLink(alias.toPath(), otherRoot.toPath())
        assertThrows(IllegalStateException::class.java) { BackupSnapshots(alias).prepare("never write outside") }
        assertTrue(requireNotNull(otherRoot.list()).isEmpty())
    }
}
