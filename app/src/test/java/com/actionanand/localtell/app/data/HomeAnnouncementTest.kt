package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.AppLanguage
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAnnouncementTest {
    private val now = Instant.parse("2026-10-16T00:00:00Z")

    @Test
    fun `valid Sri Lanka announcement parses`() {
        val announcement = HomeAnnouncementParser.parse(document()).single()

        assertEquals("sri-lanka-support-2026-10", announcement.id)
        assertEquals("Sri Lanka is now supported", announcement.title.en)
        assertEquals(HomeAnnouncementAction.OFFLINE_DATA, announcement.action)
    }

    @Test
    fun `unsupported schema is rejected`() {
        val json = JSONObject(document()).put("schemaVersion", 2).toString()

        assertThrows(IllegalArgumentException::class.java) { HomeAnnouncementParser.parse(json) }
    }

    @Test
    fun `duplicate IDs are rejected`() {
        val duplicate = validAnnouncement()
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("announcements", JSONArray().put(duplicate).put(JSONObject(duplicate.toString())))
            .toString()

        assertThrows(IllegalArgumentException::class.java) { HomeAnnouncementParser.parse(json) }
    }

    @Test
    fun `disabled announcement is ignored`() {
        assertNull(select(parsed().copy(enabled = false)))
    }

    @Test
    fun `future announcement is ignored`() {
        assertNull(select(parsed().copy(publishedAt = now.plusSeconds(1))))
    }

    @Test
    fun `expired announcement is ignored at expiry instant`() {
        assertNull(select(parsed().copy(expiresAt = now)))
    }

    @Test
    fun `minimum version code is respected`() {
        assertNull(select(parsed().copy(minVersionCode = 32), versionCode = 31))
        assertEquals(parsed().id, select(parsed().copy(minVersionCode = 31), versionCode = 31)?.id)
    }

    @Test
    fun `maximum version code is respected`() {
        assertNull(select(parsed().copy(maxVersionCode = 30), versionCode = 31))
        assertEquals(parsed().id, select(parsed().copy(maxVersionCode = 31), versionCode = 31)?.id)
    }

    @Test
    fun `highest priority wins`() {
        val low = parsed().copy(id = "low", priority = 1)
        val high = parsed().copy(id = "high", priority = 2)

        assertEquals("high", select(low, high)?.id)
    }

    @Test
    fun `newest publication breaks a priority tie`() {
        val older = parsed().copy(id = "older", publishedAt = now.minusSeconds(20))
        val newer = parsed().copy(id = "newer", publishedAt = now.minusSeconds(10))

        assertEquals("newer", select(older, newer)?.id)
    }

    @Test
    fun `ID provides deterministic final tie breaker`() {
        val zulu = parsed().copy(id = "zulu")
        val alpha = parsed().copy(id = "alpha")

        assertEquals("alpha", select(zulu, alpha)?.id)
    }

    @Test
    fun `dismissed announcement is skipped`() {
        val announcement = parsed()

        assertNull(select(announcement, dismissedIds = setOf(announcement.id)))
    }

    @Test
    fun `next eligible announcement is selected after dismissal`() {
        val first = parsed().copy(id = "first", priority = 2)
        val second = parsed().copy(id = "second", priority = 1)

        assertEquals("second", select(first, second, dismissedIds = setOf("first"))?.id)
    }

    @Test
    fun `Tamil locale uses Tamil content`() {
        val announcement = parsed().localize(AppLanguage.TAMIL, "en-US")

        assertEquals("இலங்கை ஆதரவு இப்போது கிடைக்கிறது", announcement.title)
    }

    @Test
    fun `Sanskrit locale uses Sanskrit content`() {
        val announcement = parsed().localize(AppLanguage.SANSKRIT, "en-US")

        assertEquals("श्रीलङ्कायाः समर्थनम् अधुना उपलब्धम्", announcement.title)
    }

    @Test
    fun `system language uses supported locale and missing translation falls back to English`() {
        val localized = LocalizedAnnouncementText(en = "English", ta = "தமிழ்")
        val englishOnly = LocalizedAnnouncementText(en = "English")

        assertEquals("தமிழ்", localized.resolve(AppLanguage.SYSTEM, "ta-IN"))
        assertEquals("English", localized.resolve(AppLanguage.SYSTEM, "sa-IN"))
        assertEquals("English", localized.resolve(AppLanguage.SYSTEM, "fr-FR"))
        assertEquals("English", englishOnly.resolve(AppLanguage.TAMIL, "en-US"))
        assertEquals("English", englishOnly.resolve(AppLanguage.SANSKRIT, "en-US"))
    }

    @Test
    fun `missing English content is rejected`() {
        val json = validAnnouncement().apply {
            getJSONObject("title").remove("en")
        }

        assertThrows(Exception::class.java) { HomeAnnouncementParser.parse(document(json)) }
    }

    @Test
    fun `unknown action becomes none`() {
        val json = validAnnouncement().put("action", "open_arbitrary_url")

        assertEquals(HomeAnnouncementAction.NONE, HomeAnnouncementParser.parse(document(json)).single().action)
    }

    @Test
    fun `parsing and selection do not mutate their inputs`() {
        val json = document()
        val parsed = HomeAnnouncementParser.parse(json)
        val original = parsed.toList()

        HomeAnnouncementSelector.select(parsed, now, 31, emptySet())

        assertEquals(document(), json)
        assertEquals(original, parsed)
    }

    @Test
    fun `invalid date and version ranges are rejected`() {
        val invalidDate = validAnnouncement().put("expiresAt", "2026-10-14T00:00:00Z")
        val invalidVersion = validAnnouncement().put("minVersionCode", 40).put("maxVersionCode", 30)
        val invalidPriority = validAnnouncement().put("priority", "important")

        assertThrows(IllegalArgumentException::class.java) { HomeAnnouncementParser.parse(document(invalidDate)) }
        assertThrows(IllegalArgumentException::class.java) { HomeAnnouncementParser.parse(document(invalidVersion)) }
        assertThrows(Exception::class.java) { HomeAnnouncementParser.parse(document(invalidPriority)) }
    }

    @Test
    fun `session suppression remains effective after remote announcement changes`() {
        val initial = HomeAnnouncementUiState(announcement = parsed()).suppressForSession()
        val refreshed = initial.withAnnouncement(parsed().copy(id = "new-remote-announcement"))

        assertTrue(refreshed.sessionSuppressed)
        assertNull(refreshed.visibleAnnouncement)
        assertSame(initial, initial.suppressForSession())
    }

    private fun parsed() = HomeAnnouncementParser.parse(document()).single()

    private fun select(
        vararg announcements: HomeAnnouncement,
        versionCode: Int = 31,
        dismissedIds: Set<String> = emptySet(),
    ) = HomeAnnouncementSelector.select(announcements.toList(), now, versionCode, dismissedIds)

    private fun document(announcement: JSONObject = validAnnouncement()) = JSONObject()
        .put("schemaVersion", 1)
        .put("announcements", JSONArray().put(announcement))
        .toString()

    private fun validAnnouncement() = JSONObject()
        .put("id", "sri-lanka-support-2026-10")
        .put("enabled", true)
        .put("priority", 100)
        .put("publishedAt", "2026-10-15T00:00:00Z")
        .put("expiresAt", "2027-01-15T00:00:00Z")
        .put("minVersionCode", 31)
        .put(
            "title",
            JSONObject()
                .put("en", "Sri Lanka is now supported")
                .put("ta", "இலங்கை ஆதரவு இப்போது கிடைக்கிறது")
                .put("sa", "श्रीलङ्कायाः समर्थनम् अधुना उपलब्धम्"),
        )
        .put(
            "message",
            JSONObject()
                .put("en", "Offline locality data is now available for all 9 Sri Lankan Provinces.")
                .put("ta", "இலங்கையின் 9 மாகாணங்களுக்கும் இணையமில்லா பகுதி தரவு இப்போது கிடைக்கிறது.")
                .put("sa", "श्रीलङ्कायाः सर्वेषां नवप्रान्तानां कृते अन्तर्जालरहित-स्थानीयस्थानदत्तांशः अधुना उपलब्धः अस्ति।"),
        )
        .put("action", "offline_data")
}
