package com.actionanand.localtell.app.saved

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import java.io.File

class PreparedBackup internal constructor(internal val snapshot: BackupSnapshot, val shareUri: Uri) {
    val file: File get() = snapshot.file
    val filename: String get() = file.name
}
class CreateBackupDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        BackupFileFormat.requireFilename(input)
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = BackupFileFormat.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, input)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Must run on IO: providers may be remote. No URI path or MIME-based filename fallback. */
internal fun ContentResolver.backupDisplayName(uri: Uri): String {
    BackupDocumentIO.requireContentUri(uri.scheme, uri.authority)
    val name = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }
    BackupFileFormat.requireFilename(name)
    return requireNotNull(name)
}

fun backupUriFromIntent(intent: Intent): Uri? = runCatching {
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.data ?: Uri.EMPTY
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri ?: Uri.EMPTY
        else -> null
    }
}.getOrElse { Uri.EMPTY } // Route malformed external parcels to validation, never Home acquisition.

fun sharePreparedBackup(context: Context, backup: PreparedBackup, chooserTitle: String) {
    BackupFileFormat.requireFilename(backup.filename)
    val uri = backup.shareUri // FileProvider path resolution was already performed on IO.
    val send = Intent(Intent.ACTION_SEND).apply {
        type = BackupFileFormat.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(backup.filename, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
