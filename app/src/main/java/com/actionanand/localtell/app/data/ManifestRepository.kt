package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ManifestRepository {
    suspend fun fetch(): RemoteManifest = withContext(Dispatchers.IO) {
        val connection = (URL(BuildConfig.DATA_MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LocalTell/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "Manifest request failed: HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            parse(text)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(text: String): RemoteManifest {
        val root = JSONObject(text)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion in 1..2) { "Unsupported manifest schema: $schemaVersion" }
        val packsJson = root.getJSONArray("packs")
        val packs = buildList {
            for (i in 0 until packsJson.length()) {
                val p = packsJson.getJSONObject(i)
                val id = p.getString("id")
                val name = p.getString("name").trim()
                val version = p.getLong("version")
                val downloadUrl = p.getString("downloadUrl")
                val sha256 = p.getString("sha256").lowercase()
                require(id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid pack id: $id" }
                require(name.isNotBlank() && name.length <= 120) { "Invalid pack name for $id" }
                require(version >= 1) { "Invalid pack version for $id" }
                require(URL(downloadUrl).protocol.equals("https", ignoreCase = true)) { "Pack URL must use HTTPS: $id" }
                require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $id" }
                add(
                    RemotePack(
                        id = id,
                        name = name,
                        version = version,
                        downloadUrl = downloadUrl,
                        sha256 = sha256,
                        compressedBytes = p.optLong("compressedBytes").takeIf { it > 0 },
                        uncompressedBytes = p.optLong("uncompressedBytes").takeIf { it > 0 },
                    )
                )
            }
        }
        return RemoteManifest(schemaVersion, root.optString("generatedAt").takeIf { it.isNotBlank() }, packs)
    }
}
