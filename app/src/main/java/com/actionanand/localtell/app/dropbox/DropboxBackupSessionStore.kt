package com.actionanand.localtell.app.dropbox

import android.content.Context
import java.util.UUID

internal class DropboxBackupSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("localtell_dropbox_session", Context.MODE_PRIVATE)

    fun current(): DropboxBackupSession? = synchronized(lock) {
        val account = preferences.getString("account", null) ?: return@synchronized null
        val generation = preferences.getString("generation", null) ?: return@synchronized null
        val tag = preferences.getString("credential_tag", null) ?: return@synchronized null
        DropboxBackupSession(account, generation, tag)
    }

    fun activate(account: String, credential: StoredDropboxCredential) = synchronized(lock) {
        require(account.matches(Regex("[a-f0-9]{64}")))
        val tag = backupAccountHash(credential.refreshToken)
        val previous = current()
        if (previous == null || previous.account != account || previous.credentialTag != tag) {
            check(preferences.edit().putString("account", account).putString("generation", UUID.randomUUID().toString())
                .putString("credential_tag", tag).commit()) { "Cannot persist Dropbox backup session" }
        }
    }

    fun invalidate() = synchronized(lock) {
        check(preferences.edit().clear().commit()) { "Cannot invalidate Dropbox backup session" }
    }

    companion object {
        private val lock = Any()
    }
}
