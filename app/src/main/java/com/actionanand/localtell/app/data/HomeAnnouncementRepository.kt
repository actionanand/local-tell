package com.actionanand.localtell.app.data

import android.content.Context
import com.actionanand.localtell.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class HomeAnnouncementRepository(
    context: Context,
    private val remoteLoader: HomeAnnouncementRemoteLoader = HomeAnnouncementRemoteLoader(BuildConfig.HOME_ANNOUNCEMENT_URL),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadCached(): List<HomeAnnouncement>? = preferences.getString(CACHED_JSON_KEY, null)?.let { json ->
        runCatching { HomeAnnouncementParser.parse(json) }.getOrNull()
    }

    suspend fun refresh(): List<HomeAnnouncement> {
        val json = remoteLoader.load()
        val announcements = HomeAnnouncementParser.parse(json)
        preferences.edit().putString(CACHED_JSON_KEY, json).apply()
        return announcements
    }

    fun dismissedIds(): Set<String> =
        preferences.getStringSet(DISMISSED_IDS_KEY, emptySet()).orEmpty().toSet()

    fun dismiss(id: String) {
        preferences.edit().putStringSet(DISMISSED_IDS_KEY, dismissedIds() + id).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "localtell_announcements"
        const val CACHED_JSON_KEY = "cached_json"
        const val DISMISSED_IDS_KEY = "dismissed_ids"
    }
}

class HomeAnnouncementRemoteLoader(private val url: String) {
    suspend fun load(): String = withContext(Dispatchers.IO) {
        require(URI(url).scheme.equals("https", ignoreCase = true)) { "Announcement URL must use HTTPS" }
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Announcement URL must use HTTPS")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val responseCode = connection.responseCode
            require(responseCode in 200..299) { "Announcement request failed with HTTP $responseCode" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_RESPONSE_BYTES) { "Announcement response is too large" }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 10_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
