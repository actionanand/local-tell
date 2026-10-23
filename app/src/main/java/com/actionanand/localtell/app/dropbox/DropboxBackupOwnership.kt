package com.actionanand.localtell.app.dropbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Device-local proof, never imported from a backup or inferred from a Dropbox filename.
 * Loss/corruption of this ledger deliberately leaves remote files untouched.
 */
internal class DropboxBackupOwnership(context: Context) : DropboxOwnershipRecords {
    private val preferences = context.applicationContext.getSharedPreferences("localtell_dropbox_ownership", Context.MODE_PRIVATE)

    override fun read(account: String): List<DropboxOwnedBackup>? = try {
        preferences.getString(account, null)?.let { decode(it) } ?: if (preferences.contains(account)) null else emptyList()
    } catch (_: ClassCastException) {
        null
    }

    override fun record(account: String, file: DropboxRemoteFile): Boolean {
        val records = read(account) ?: return false
        if (file.contentHash == null) return false
        return write(account, records.filter { it.file.id != file.id } + DropboxOwnedBackup(file))
    }

    override fun forget(account: String, id: String): Boolean {
        val records = read(account) ?: return false
        return write(account, records.filter { it.file.id != id })
    }

    override fun pending(account: String): DropboxPendingUpload? {
        try {
            val value = preferences.getString("$account.pending", null) ?: return null
            val json = JSONObject(value)
            val file = decode(json.getString("file"))?.singleOrNull()
            checkNotNull(file) { "Pending upload record is invalid" }
            val fingerprint = json.getString("fingerprint")
            val before = json.getString("before")
            check(listOf(fingerprint, before).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Pending upload record is invalid" }
            check(json.getString("sha256") == checksum("${encode(listOf(file))}|$fingerprint|$before")) {
                "Pending upload checksum is invalid"
            }
            return DropboxPendingUpload(file.file, fingerprint, before)
        } catch (error: JSONException) {
            throw IllegalStateException("Pending upload record is invalid", error)
        } catch (error: ClassCastException) {
            throw IllegalStateException("Pending upload record is invalid", error)
        }
    }

    override fun savePending(account: String, upload: DropboxPendingUpload): Boolean {
        val file = encode(listOf(DropboxOwnedBackup(upload.file)))
        val json = JSONObject().put("file", file).put("fingerprint", upload.fingerprint)
            .put("before", upload.beforeToken)
            .put("sha256", checksum("$file|${upload.fingerprint}|${upload.beforeToken}"))
        return preferences.edit().putString("$account.pending", json.toString()).commit()
    }

    override fun clearPending(account: String): Boolean =
        preferences.edit().remove("$account.pending").commit()

    private fun write(account: String, records: List<DropboxOwnedBackup>): Boolean =
        preferences.edit().putString(account, encode(records)).commit()

    companion object {
        fun encode(records: List<DropboxOwnedBackup>): String {
            val payload = JSONArray().apply {
                records.forEach { record ->
                    val file = record.file
                    put(JSONObject().put("path", file.path).put("name", file.name).put("modified", file.modifiedAtMillis)
                        .put("id", file.id).put("revision", file.revision).put("size", file.size).put("hash", file.contentHash))
                }
            }.toString()
            return JSONObject().put("version", 1).put("payload", payload).put("sha256", checksum(payload)).toString()
        }

        fun decode(value: String): List<DropboxOwnedBackup>? = try {
            val envelope = JSONObject(value)
            require(envelope.getInt("version") == 1)
            val payload = envelope.getString("payload")
            require(envelope.getString("sha256") == checksum(payload))
            val array = JSONArray(payload)
            val records = (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                val file = DropboxRemoteFile(
                    entry.getString("path"), entry.getString("name"), entry.getLong("modified"),
                    entry.getString("id"), entry.getString("revision"), entry.getLong("size"), entry.getString("hash"),
                )
                require(file.id.startsWith("id:") && file.revision.isNotBlank() &&
                    file.path.startsWith("$MANAGED_DIRECTORY/") && file.size >= 0 &&
                    file.contentHash?.matches(Regex("[a-f0-9]{64}")) == true)
                DropboxOwnedBackup(file)
            }
            require(records.map { it.file.id }.distinct().size == records.size)
            records
        } catch (_: JSONException) {
            null // Caller reports a storage error; a corrupt ledger must never authorize deletion.
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun checksum(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        const val MANAGED_DIRECTORY = "/localtell-managed-backups"
    }
}
