package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.AppLanguage
import java.time.Instant
import java.util.Locale
import org.json.JSONObject

enum class HomeAnnouncementAction {
    NONE,
    OFFLINE_DATA,
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
)

data class LocalizedHomeAnnouncement(
    val id: String,
    val title: String,
    val message: String,
    val action: HomeAnnouncementAction,
)

fun HomeAnnouncement.localize(language: AppLanguage, systemLanguageTag: String) = LocalizedHomeAnnouncement(
    id = id,
    title = title.resolve(language, systemLanguageTag),
    message = message.resolve(language, systemLanguageTag),
    action = action,
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
                else -> HomeAnnouncementAction.NONE
            },
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
