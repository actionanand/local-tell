package com.actionanand.localtell.app.data

import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.concurrent.ConcurrentHashMap

class PackDownloader(private val store: PackStore) {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    fun cancel(packId: String) {
        activeConnections.remove(packId)?.disconnect()
    }

    suspend fun download(pack: RemotePack, onProgress: (Int) -> Unit = {}): InstalledPack =
        withContext(Dispatchers.IO) {
            val compressed = File(store.directory, ".${pack.id}-${pack.version}.db.gz.part")
            val unpacked = File(store.directory, ".${pack.id}-${pack.version}.db.part")
            val destination = File(store.directory, "${pack.id}.db")
            compressed.delete()
            unpacked.delete()

            try {
                ensureStorage(pack)
                downloadFile(pack, compressed, onProgress)
                verifySha256(compressed, pack.sha256)
                GZIPInputStream(FileInputStream(compressed)).use { input ->
                    FileOutputStream(unpacked).use { output -> copyWithCancellation(input, output) }
                }
                validateDatabase(unpacked)
                currentCoroutineContext().ensureActive()

                activateDatabase(unpacked, destination)

                InstalledPack(
                    id = pack.id,
                    name = pack.name,
                    version = pack.version,
                    filePath = destination.absolutePath,
                    installedAt = System.currentTimeMillis(),
                ).also(store::put)
            } finally {
                compressed.delete()
                unpacked.delete()
            }
        }

    private fun activateDatabase(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to guarded rename on filesystems without atomic replace.
        }

        val backup = File(destination.parentFile, ".${destination.name}.bak")
        backup.delete()
        if (destination.exists() && !destination.renameTo(backup)) {
            error("Unable to prepare existing database for replacement")
        }
        if (!source.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination)
            error("Unable to activate downloaded database")
        }
        backup.delete()
    }

    private fun ensureStorage(pack: RemotePack) {
        val expected = (pack.compressedBytes ?: 0L) + (pack.uncompressedBytes ?: 0L)
        if (expected <= 0L) return
        val available = StatFs(store.directory.absolutePath).availableBytes
        // Download + decompressed DB temporarily coexist; keep a small safety margin.
        val required = expected + 64L * 1024L * 1024L
        check(available >= required) {
            "Not enough free storage. Need about ${required / (1024 * 1024)} MB."
        }
    }

    private suspend fun downloadFile(pack: RemotePack, file: File, onProgress: (Int) -> Unit) {
        val connection = (URL(pack.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalTell")
        }
        activeConnections[pack.id] = connection
        try {
            currentCoroutineContext().ensureActive()
            check(connection.responseCode in 200..299) { "Pack download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: pack.compressedBytes ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastProgress = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0) {
                            val progress = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            activeConnections.remove(pack.id, connection)
            connection.disconnect()
        }
    }

    private suspend fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expected, ignoreCase = true)) { "SHA-256 mismatch for downloaded pack" }
    }

    private suspend fun copyWithCancellation(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
        }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Downloaded SQLite database failed integrity check" }
            }
            val schemaVersion = db.rawQuery("SELECT value FROM pack_meta WHERE key='schema_version'", null).use { cursor ->
                check(cursor.moveToFirst()) { "Offline pack schema is missing" }
                cursor.getString(0).toIntOrNull()
            }
            val schema = OfflinePackSchema.fromVersion(schemaVersion)
            check(schema != null) { "Unsupported offline pack schema" }
            requireTables(db, *schema.requiredTables.toTypedArray())
        } finally {
            db.close()
        }
    }

    private fun requireTables(db: SQLiteDatabase, vararg tables: String) {
        tables.forEach { table ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','virtual table') AND name=?",
                arrayOf(table),
            ).use { cursor -> check(cursor.moveToFirst()) { "Offline pack is missing required table: $table" } }
        }
    }
}
