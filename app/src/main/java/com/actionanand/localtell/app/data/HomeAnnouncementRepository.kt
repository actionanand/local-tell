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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class HomeAnnouncementRepository(
    context: Context,
    private val remoteLoader: HomeAnnouncementRemoteLoader = HomeAnnouncementRemoteLoader(BuildConfig.HOME_ANNOUNCEMENT_URL),
) {
    private val preferences by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }
    private val dismissalMutex = Mutex()
    private val cache = AnnouncementDocumentCache(
        read = { preferences.getString(CACHED_JSON_KEY, null) },
        write = { preferences.edit().putString(CACHED_JSON_KEY, it).apply() },
    )

    fun loadCached(): List<HomeAnnouncement>? = cache.load()

    suspend fun refresh(): List<HomeAnnouncement> = withContext(Dispatchers.IO) {
        var response: HomeAnnouncementRemoteLoader.Response? = null
        val announcements = cache.refresh {
            remoteLoader.load(preferences.getString("etag", null), preferences.getString("last_modified", null))
                .also { response = it }.json
        }
        response?.takeIf { it.json != null }?.let {
            preferences.edit().putString("etag", it.etag).putString("last_modified", it.lastModified).apply()
        }
        announcements
    }

    fun dismissedRevisions(): MutableMap<String, Int> {
        // Old permanent ID dismissals migrate to revision 1 only for once-per-revision notices.
        // Every-launch selection deliberately ignores this persistent map.
        val revisions = preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith("revision:") && value is Int) key.removePrefix("revision:") to value else null
        }.toMap().toMutableMap()
        val legacy = preferences.getStringSet(DISMISSED_IDS_KEY, emptySet()).orEmpty()
        if (legacy.isNotEmpty()) {
            preferences.edit().apply {
                legacy.forEach { id ->
                    revisions[id] = maxOf(revisions[id] ?: 0, 1)
                    putInt("revision:$id", revisions.getValue(id))
                }
                remove(DISMISSED_IDS_KEY)
            }.apply()
        }
        return revisions
    }

    suspend fun dismiss(announcement: HomeAnnouncement) = withContext(Dispatchers.IO) {
        if (announcement.repeatPolicy != AnnouncementRepeatPolicy.ONCE_PER_REVISION) return@withContext
        dismissalMutex.withLock {
            val key = "revision:${announcement.id}"
            preferences.edit().putInt(key, maxOf(preferences.getInt(key, 0), announcement.revision)).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "localtell_announcements"
        const val CACHED_JSON_KEY = "cached_json"
        const val DISMISSED_IDS_KEY = "dismissed_ids"
    }
}

class HomeAnnouncementRemoteLoader(private val url: String) {
    data class Response(val json: String?, val etag: String? = null, val lastModified: String? = null)

    suspend fun load(etag: String? = null, lastModified: String? = null): Response = withContext(Dispatchers.IO) {
        require(URI(url).scheme.equals("https", ignoreCase = true)) { "Announcement URL must use HTTPS" }
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Announcement URL must use HTTPS")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val responseCode = connection.responseCode
            if (responseCode == 304) return@withContext Response(null)
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
                Response(output.toString(Charsets.UTF_8.name()), connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
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
