package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.AppLanguage
import java.time.Instant
import java.net.URI
import java.util.Locale
import org.json.JSONObject

enum class HomeAnnouncementAction {
    NONE,
    OFFLINE_DATA,
    OPEN_PLAY_STORE,
}

enum class AnnouncementType { GENERAL, FEATURE, MAINTENANCE, DOWNTIME, MIGRATION, CRITICAL }
enum class AnnouncementRepeatPolicy { EVERY_LAUNCH, ONCE_PER_REVISION }

object AnnouncementPlayStoreLink {
    fun validate(value: String?): String? {
        return runCatching {
            val uri = URI(value ?: return null)
            if (uri.scheme != "https" || uri.host != "play.google.com" || uri.rawAuthority != "play.google.com" ||
                uri.path != "/store/apps/details" || uri.fragment != null) return null
            val query = uri.rawQuery ?: return null
            val packageName = query.removePrefix("id=")
            if (query != "id=$packageName" || !packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) return null
            "https://play.google.com/store/apps/details?id=$packageName"
        }.getOrNull()
    }
}

data class LocalizedAnnouncementText(
    val en: String,
    val ta: String? = null,
    val sa: String? = null,
) {
    fun resolve(language: AppLanguage, systemLanguageTag: String): String {
        val languageCode = when (language) {
            AppLanguage.TAMIL -> "ta"
            AppLanguage.SANSKRIT -> "sa"
            AppLanguage.ENGLISH -> "en"
            AppLanguage.SYSTEM -> systemLanguageTag.substringBefore('-').lowercase(Locale.ROOT)
        }
        return when (languageCode) {
            "ta" -> ta ?: en
            "sa" -> sa ?: en
            else -> en
        }
    }
}

data class HomeAnnouncement(
    val id: String,
    val enabled: Boolean,
    val priority: Int,
    val publishedAt: Instant?,
    val expiresAt: Instant?,
    val minVersionCode: Int?,
    val maxVersionCode: Int?,
    val title: LocalizedAnnouncementText,
    val message: LocalizedAnnouncementText,
    val action: HomeAnnouncementAction,
    val type: AnnouncementType = AnnouncementType.GENERAL,
    val revision: Int = 1,
    val repeatPolicy: AnnouncementRepeatPolicy = AnnouncementRepeatPolicy.EVERY_LAUNCH,
    val actionUrl: String? = null,
)

data class LocalizedHomeAnnouncement(
    val id: String,
    val title: String,
    val message: String,
    val action: HomeAnnouncementAction,
    val actionUrl: String? = null,
)

fun HomeAnnouncement.localize(language: AppLanguage, systemLanguageTag: String) = LocalizedHomeAnnouncement(
    id = id,
    title = title.resolve(language, systemLanguageTag),
    message = message.resolve(language, systemLanguageTag),
    action = action,
    actionUrl = actionUrl,
)

data class HomeAnnouncementUiState(
    val announcement: HomeAnnouncement? = null,
    val sessionSuppressed: Boolean = false,
) {
    val visibleAnnouncement: HomeAnnouncement?
        get() = announcement.takeUnless { sessionSuppressed }

    fun withAnnouncement(next: HomeAnnouncement?): HomeAnnouncementUiState =
        copy(announcement = next)

    fun suppressForSession(): HomeAnnouncementUiState =
        if (sessionSuppressed) this else copy(sessionSuppressed = true)
}

object HomeAnnouncementParser {
    private const val SCHEMA_VERSION = 1
    private const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_MESSAGE_LENGTH = 600
    private const val MAX_PRIORITY = 1_000_000
    private val idPattern = Regex("[A-Za-z0-9._-]{1,100}")

    fun parse(json: String): List<HomeAnnouncement> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Announcement document is too large" }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported announcement schema" }
        val jsonAnnouncements = root.getJSONArray("announcements")
        val ids = mutableSetOf<String>()
        return List(jsonAnnouncements.length()) { index ->
            parseAnnouncement(jsonAnnouncements.getJSONObject(index)).also {
                require(ids.add(it.id)) { "Duplicate announcement ID" }
            }
        }
    }

    private fun parseAnnouncement(json: JSONObject): HomeAnnouncement {
        val id = json.getString("id")
        require(idPattern.matches(id)) { "Invalid announcement ID" }
        val priority = if (json.has("priority") && !json.isNull("priority")) json.getInt("priority") else 0
        require(priority in -MAX_PRIORITY..MAX_PRIORITY) { "Invalid announcement priority" }
        val publishedAt = json.optionalInstant("publishedAt")
        val expiresAt = json.optionalInstant("expiresAt")
        require(publishedAt == null || expiresAt == null || expiresAt.isAfter(publishedAt)) {
            "Announcement expiry must follow publication"
        }
        val minVersionCode = json.optionalNonNegativeInt("minVersionCode")
        val maxVersionCode = json.optionalNonNegativeInt("maxVersionCode")
        val revision = if (!json.has("revision") || json.isNull("revision")) 1
            else json.get("revision").toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid announcement revision")
        require(revision > 0) { "Invalid announcement revision" }
        val actionUrl = AnnouncementPlayStoreLink.validate(json.optString("actionUrl"))
        require(minVersionCode == null || maxVersionCode == null || maxVersionCode >= minVersionCode) {
            "Invalid announcement version range"
        }
        return HomeAnnouncement(
            id = id,
            enabled = json.getBoolean("enabled"),
            priority = priority,
            publishedAt = publishedAt,
            expiresAt = expiresAt,
            minVersionCode = minVersionCode,
            maxVersionCode = maxVersionCode,
            title = parseLocalizedText(json.getJSONObject("title"), MAX_TITLE_LENGTH),
            message = parseLocalizedText(json.getJSONObject("message"), MAX_MESSAGE_LENGTH),
            action = when (json.optString("action", "none").lowercase(Locale.ROOT)) {
                "offline_data" -> HomeAnnouncementAction.OFFLINE_DATA
                "open_play_store" -> if (actionUrl != null) HomeAnnouncementAction.OPEN_PLAY_STORE else HomeAnnouncementAction.NONE
                else -> HomeAnnouncementAction.NONE
            },
            type = AnnouncementType.entries.firstOrNull { it.name.equals(json.optString("type", "general"), true) } ?: AnnouncementType.GENERAL,
            revision = revision,
            repeatPolicy = when (json.optString("repeatPolicy", "every_launch")) {
                "once_per_revision" -> AnnouncementRepeatPolicy.ONCE_PER_REVISION
                else -> AnnouncementRepeatPolicy.EVERY_LAUNCH
            },
            actionUrl = actionUrl,
        )
    }

    private fun parseLocalizedText(json: JSONObject, maxLength: Int): LocalizedAnnouncementText {
        fun requiredEnglish(): String = json.getString("en").also {
            require(it.isNotBlank() && it.length <= maxLength) { "Invalid English announcement content" }
        }
        fun optional(language: String): String? = json.optString(language).takeIf { it.isNotEmpty() }?.also {
            require(it.isNotBlank() && it.length <= maxLength) { "Invalid localized announcement content" }
        }
        return LocalizedAnnouncementText(requiredEnglish(), optional("ta"), optional("sa"))
    }

    private fun JSONObject.optionalInstant(name: String): Instant? =
        if (has(name) && !isNull(name)) Instant.parse(getString(name)) else null

    private fun JSONObject.optionalNonNegativeInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val value = getLong(name)
        require(value in 0..Int.MAX_VALUE.toLong()) { "Invalid $name" }
        return value.toInt()
    }
}

/** Retained by the root ViewModel, never by a screen or a recomposition. */
class AnnouncementSession(private val persistedRevisions: MutableMap<String, Int> = mutableMapOf()) {
    private val dismissedRevisions = mutableMapOf<String, Int>()
    var resultsLoaded: Boolean = false
        private set

    fun observeResults(homeReady: Boolean, easyCurrent: Boolean, easyResolved: Boolean) {
        resultsLoaded = resultsLoaded || homeReady || easyCurrent || easyResolved
    }

    fun dismiss(announcement: HomeAnnouncement) {
        dismissedRevisions[announcement.id] = maxOf(dismissedRevisions[announcement.id] ?: 0, announcement.revision)
        if (announcement.repeatPolicy == AnnouncementRepeatPolicy.ONCE_PER_REVISION) {
            persistedRevisions[announcement.id] = maxOf(persistedRevisions[announcement.id] ?: 0, announcement.revision)
        }
    }

    fun select(announcements: List<HomeAnnouncement>, now: Instant, versionCode: Int): HomeAnnouncement? {
        if (resultsLoaded) return null
        return HomeAnnouncementSelector.select(announcements.filter {
            it.revision > (dismissedRevisions[it.id] ?: 0) &&
                (it.repeatPolicy != AnnouncementRepeatPolicy.ONCE_PER_REVISION || it.revision > (persistedRevisions[it.id] ?: 0))
        }, now, versionCode, emptySet())
    }
}

/** Validation precedes persistence, so a failed request or malformed response cannot replace good data. */
class AnnouncementDocumentCache(private val read: () -> String?, private val write: (String) -> Unit) {
    fun load(): List<HomeAnnouncement>? = read()?.let { runCatching { HomeAnnouncementParser.parse(it) }.getOrNull() }
    fun accept(json: String): List<HomeAnnouncement> = HomeAnnouncementParser.parse(json).also { write(json) }

    suspend fun refresh(fetch: suspend () -> String?): List<HomeAnnouncement> {
        val json = fetch()
        return if (json == null) load() ?: throw IllegalStateException("No cached announcement") else accept(json)
    }
}

object HomeAnnouncementSelector {
    fun select(
        announcements: List<HomeAnnouncement>,
        now: Instant,
        versionCode: Int,
        dismissedIds: Set<String>,
    ): HomeAnnouncement? = announcements.asSequence()
        .filter(HomeAnnouncement::enabled)
        .filter { it.id !in dismissedIds }
        .filter { it.publishedAt == null || !now.isBefore(it.publishedAt) }
        .filter { it.expiresAt == null || now.isBefore(it.expiresAt) }
        .filter { it.minVersionCode == null || versionCode >= it.minVersionCode }
        .filter { it.maxVersionCode == null || versionCode <= it.maxVersionCode }
        .sortedWith(
            compareByDescending<HomeAnnouncement> { it.priority }
                .thenByDescending { it.publishedAt ?: Instant.MIN }
                .thenBy { it.id },
        )
        .firstOrNull()
}
