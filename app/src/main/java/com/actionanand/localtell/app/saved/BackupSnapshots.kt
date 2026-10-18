package com.actionanand.localtell.app.saved

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Own the provisional snapshot even if dispatcher cancellation drops the prepared result.
 * Publication is synchronous: ownership transfers only when publish returns successfully.
 */
internal suspend fun <T> BackupSnapshots.prepareForPublication(
    content: () -> String,
    build: (BackupSnapshot) -> T,
    publish: (T) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var provisional: BackupSnapshot? = null
    var published = false
    try {
        val prepared = withContext(ioDispatcher) {
            val snapshot = prepare(content())
            provisional = snapshot // Assigned before returning across the cancellable handoff.
            build(snapshot)
        }
        publish(prepared)
        published = true
    } finally {
        if (!published) {
            withContext(NonCancellable + ioDispatcher) {
                provisional?.let { snapshot ->
                    snapshot.release()
                    discardUnused(snapshot)
                }
            }
        }
    }
}

/** Pure in-memory leases: UI callbacks never wait for a disk lock. */
internal class BackupSnapshot(val file: File) {
    private data class State(val used: Boolean = false, val active: Boolean = false,
        val prepared: Boolean = true, val deleted: Boolean = false)
    private val state = AtomicReference(State())

    fun beginUse(): Boolean {
        while (true) {
            val old = state.get()
            if (old.deleted || old.active || !old.prepared) return false
            if (state.compareAndSet(old, old.copy(active = true))) return true
        }
    }

    fun markUsed() { state.updateAndGet { check(it.active && !it.deleted); it.copy(used = true) } }
    fun endUse() { state.updateAndGet { it.copy(active = false) } }
    fun release() { state.updateAndGet { it.copy(prepared = false) } }

    fun claimDeletion(unusedOnly: Boolean): Boolean {
        while (true) {
            val old = state.get()
            if (old.active || old.prepared || (unusedOnly && old.used)) return false
            // A failed filesystem deletion may be retried later, but this handle cannot be reused.
            if (old.deleted || state.compareAndSet(old, old.copy(deleted = true))) return true
        }
    }
}

/** Only the dedicated private directory is managed. Call filesystem methods on Dispatchers.IO. */
internal class BackupSnapshots(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val filename: () -> String = { BackupFileFormat.filename() },
) {
    init { require(directory.name == "saved-place-backups") }

    fun prepare(content: String): BackupSnapshot {
        check(!Files.isSymbolicLink(directory.toPath()))
        check(directory.isDirectory || directory.mkdirs())
        cleanup()
        val name = filename()
        require(generatedName.matches(name))
        val file = File(directory, name)
        var created = false
        try {
            created = file.createNewFile()
            check(created) // Never overwrite or delete another snapshot on collision.
            file.writeText(content, Charsets.UTF_8)
            check(file.setLastModified(now()))
            return BackupSnapshot(file).also { tracked[file.absolutePath] = it }
        } catch (failure: Exception) { if (created) delete(file); throw failure }
    }

    /** Called with a live lease, before launching Share or opening an export stream. */
    fun retain(snapshot: BackupSnapshot) {
        check(owns(snapshot.file))
        check(snapshot.file.setLastModified(now())) { "Could not retain backup snapshot" }
    }

    fun discardUnused(snapshot: BackupSnapshot) {
        if (owns(snapshot.file) && snapshot.claimDeletion(unusedOnly = true) && delete(snapshot.file)) {
            tracked.remove(snapshot.file.absolutePath, snapshot)
        }
    }

    /** Opportunistic age cleanup, never recursive and never following a symlink. */
    fun cleanup() {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        val instant = now()
        val candidates = try { directory.listFiles() } catch (_: SecurityException) { null }
        candidates?.forEach { file ->
            if (!owns(file)) return@forEach
            val modified = file.lastModified()
            // Unknown/future timestamps are retained conservatively. Exactly 30 days is retained.
            if (modified <= 0 || modified >= instant || instant - modified <= RETENTION_MILLIS) return@forEach
            val snapshot = tracked[file.absolutePath]
            if (snapshot != null && !snapshot.claimDeletion(unusedOnly = false)) return@forEach
            // A handoff can finish between the first timestamp read and the lease check.
            // Once deletion is claimed no new use can begin; recheck its persisted age.
            val latest = file.lastModified()
            if (latest <= 0 || latest >= instant || instant - latest <= RETENTION_MILLIS) return@forEach
            if (delete(file) && snapshot != null) tracked.remove(file.absolutePath, snapshot)
        }
    }

    private fun delete(file: File): Boolean = try { file.delete() } catch (_: SecurityException) { false }

    private fun owns(file: File): Boolean = try {
        generatedName.matches(file.name) && file.absoluteFile.parentFile == directory.absoluteFile &&
            !Files.isSymbolicLink(directory.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.canonicalFile.parentFile == directory.canonicalFile
    } catch (_: IOException) { false } catch (_: SecurityException) { false }

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
        // Multiple Activity owners in one process must respect each other's live leases.
        private val tracked = ConcurrentHashMap<String, BackupSnapshot>()
        private val generatedName = Regex(
            "LocalTell-[0-9]{2}-(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-[0-9]{4}-" +
                "[0-9]{2}-[0-9]{2}(?:AM|PM)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bklt",
        )
    }
}
