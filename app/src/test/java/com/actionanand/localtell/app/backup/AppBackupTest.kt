package com.actionanand.localtell.app.backup

import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.DataLimits
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.saved.*
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppBackupTest {
    private val place get() = backupFixture().savedPlaces.single()
    private fun point(number: Int = 1) = JourneyPoint(number.toLong(), 1_700_000_000_000 + number,
        "நாகர்கோவில் / स्थानम् $number", "Kanniyakumari", "Tamil Nadu", "NR", "405-869",
        4_367_237_120, 100, 8.181910123, 77.352330456, 12.75f, "Kalkulam")

    @Test fun `limits are validated and generated configuration is the single default`() {
        assertEquals(AppLimits.current.maxSavedPlaces, MAX_SAVED_PLACES)
        listOf(DataLimits(50, 200), DataLimits(30, 50), DataLimits(1, 1), DataLimits(200, 1000)).forEach {
            assertTrue(it.maxSavedPlaces > 0 && it.maxJourneyPoints > 0)
        }
        for ((saved, journey) in listOf(0 to 100, -1 to 100, 201 to 100, 30 to 0, 30 to 1001)) {
            assertThrows(IllegalArgumentException::class.java) { DataLimits(saved, journey) }
        }
    }

    @Test fun `saved capacity changes from thirty to fifty without changing identities or deleting existing data`() {
        val current = (1..35).map { place.copy(id = "p-$it", localityName = "Place $it") }
        val incoming = place.copy(id = "new", localityName = "New place")
        assertEquals(SaveResult.Saved, classifySavedPlace(current, incoming, 50))
        assertEquals(SaveResult.Full, classifySavedPlace(current, incoming, 30))
        assertEquals(SaveResult.Duplicate, classifySavedPlace(current, current.first(), 30))
        assertTrue(planSavedPlacesMerge(current, emptyList(), 30).fits)
        assertFalse(planSavedPlacesMerge(current, listOf(incoming), 30).fits)
        assertEquals(36, planSavedPlacesMerge(current, listOf(incoming), 50).total)
        // Lowering configuration must not prevent backing up existing over-limit bookmarks.
        assertEquals(current, AppBackupCodec.decode(AppBackupCodec.encode(AppBackupData(current, emptyList()))).savedPlaces)
        assertEquals(35, current.size)
    }

    @Test fun `saved only journey only and combined backups preserve every field and optional null coordinates`() {
        val legacyPoint = point(2).copy(latitude = null, longitude = null, accuracyMetres = null, subDistrict = null)
        for (data in listOf(backupFixture(), AppBackupData(emptyList(), listOf(point(), legacyPoint)),
            AppBackupData(listOf(place), listOf(point(), legacyPoint)))) {
            val decoded = AppBackupCodec.decode(AppBackupCodec.encode(data))
            assertEquals(data.savedPlaces, decoded.savedPlaces)
            assertEquals(data.journeys.map { it.copy(id = 0) }, decoded.journeys)
            assertFalse(data.isEmpty)
            assertEquals(decoded, com.actionanand.localtell.app.saved.BackupFileFormat.decode(
                "LocalTell.bklt", AppBackupCodec.encode(data).toByteArray(Charsets.UTF_8)))
        }
        assertTrue(AppBackupData(emptyList(), emptyList()).isEmpty)
    }

    @Test fun `full format has versioned payload integrity and never serializes SQLite journey IDs`() {
        val root = JSONObject(AppBackupCodec.encode(AppBackupData(listOf(place), listOf(point()))))
        assertEquals("localtell-backup", root.getString("format"))
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals(64, root.getString("sha256").length)
        assertFalse(JSONObject(root.getString("payload")).getJSONArray("journeys").getJSONObject(0).has("id"))
        for (mutated in listOf(JSONObject(root.toString()).put("schemaVersion", 2),
            JSONObject(root.toString()).put("schemaVersion", "1"),
            JSONObject(root.toString()).put("sha256", "0".repeat(64)),
            JSONObject(root.toString()).put("payload", root.getString("payload").replace("8.18", "8.19")))) {
            assertThrows(Exception::class.java) { AppBackupCodec.decode(mutated.toString()) }
        }
    }

    /** Re-sign mutated payloads so these tests exercise validation, not just checksum failure. */
    private fun malformed(change: (JSONObject) -> Unit): String {
        val root = JSONObject(AppBackupCodec.encode(AppBackupData(listOf(place), listOf(point()))))
        val body = JSONObject(root.getString("payload"))
        change(body)
        val payload = body.toString()
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return root.put("payload", payload).put("sha256", checksum).toString()
    }

    @Test fun `decoder rejects duplicate bookmarks with valid checksum rather than only exercising encoder guard`() {
        for (newId in listOf("fixture", "different-id")) {
            val bad = malformed { body ->
                val entry = body.getJSONArray("savedPlaces").getJSONObject(0)
                body.put("savedPlaces", JSONArray().put(entry).put(JSONObject(entry.toString()).put("id", newId)))
            }
            assertThrows(Exception::class.java) { AppBackupCodec.decode(bad) }
        }
    }

    @Test fun `invalid journey values and types fail complete file validation`() {
        for ((field, value) in listOf("cellId" to "123", "cellId" to -1, "confidence" to 101,
            "confidence" to 1.5, "timestamp" to "invalid", "latitude" to 91, "longitude" to JSONObject.NULL,
            "accuracyMetres" to -1, "radio" to "LTE\n", "areaName" to "", "state" to "x".repeat(201))) {
            assertThrows(Exception::class.java) {
                AppBackupCodec.decode(malformed { it.getJSONArray("journeys").getJSONObject(0).put(field, value) })
            }
        }
        assertThrows(Exception::class.java) {
            AppBackupCodec.decode(malformed { it.getJSONArray("savedPlaces").getJSONObject(0).put("latitude", "8.18") })
        }
        assertThrows(Exception::class.java) { AppBackupCodec.decode("{") }
        assertThrows(Exception::class.java) { AppBackupCodec.decode(" ".repeat(AppBackupCodec.MAX_BYTES + 1)) }
    }

    @Test fun `maximum valid metadata fits documented byte bound`() {
        // Control/invalid Unicode is forbidden; these valid characters maximize JSON/UTF8 overhead.
        // JSON implementations can emit every separator as \u2028, then escape it
        // again in the envelope. Keep a non-whitespace prefix so the text is valid.
        val longText = "A" + "\u2028".repeat(199)
        val saved = (1..DataLimits.MAX_SAVED_BOUND).map {
            place.copy(id = "id-$it", displayName = longText, localityName = "$it $longText".take(200),
                subDistrict = longText, district = longText, state = longText, latitude = it * 0.1)
        }
        val journeys = (1..DataLimits.MAX_JOURNEY_BOUND).map {
            point(it).copy(areaName = longText, district = longText, state = longText,
                subDistrict = longText, radio = longText, plmn = longText)
        }
        val content = AppBackupCodec.encode(AppBackupData(saved, journeys))
        assertTrue(content.toByteArray(Charsets.UTF_8).size < AppBackupCodec.MAX_BYTES)
        assertEquals(1000, AppBackupCodec.decode(content).journeys.size)
    }

    @Test fun `combined preview counts duplicates additions and retention without changing either input`() {
        val current = AppBackupData(listOf(place), listOf(point(3), point(2)))
        val incoming = AppBackupData(listOf(place.copy(id = "same-place"), place.copy(id = "new", localityName = "New")),
            listOf(point(1), point(2).copy(id = 900), point(4), point(4).copy(id = 901)))
        val plan = planAppRestore(current, incoming, DataLimits(30, 3))
        assertEquals(RestorePreview(1, 1, 2, true), plan.saved)
        assertEquals(2, plan.journey.additions.size)
        assertEquals(1, plan.journey.insertions.size)
        assertEquals(2, plan.journey.duplicates)
        assertEquals(1, plan.journey.removed)
        assertEquals(3, plan.journey.total)
        assertEquals(2, current.journeys.size)
        assertEquals(4, incoming.journeys.size)
    }

    private fun applyJourney(current: AppBackupData, incoming: AppBackupData, limits: DataLimits): AppBackupData {
        val plan = planAppRestore(current, incoming, limits)
        var lastId = current.journeys.maxOfOrNull { it.id } ?: 0L
        val inserted = plan.journey.insertions.map { it.copy(id = ++lastId) }
        return current.copy(journeys = (current.journeys + inserted)
            .sortedWith(compareByDescending<JourneyPoint> { it.timestamp }.thenByDescending { it.id }).take(limits.maxJourneyPoints))
    }

    @Test fun `repeated restore is idempotent even for equal timestamps and reduced journey limit`() {
        for (sameTimestamp in listOf(false, true)) {
            val incoming = AppBackupData(emptyList(), (1..200).map { point(it).let { p ->
                if (sameTimestamp) p.copy(timestamp = point().timestamp) else p
            } })
            for (limit in listOf(50, 100, 200)) {
                val limits = DataLimits(30, limit)
                val once = applyJourney(AppBackupData(emptyList(), emptyList()), incoming, limits)
                val twice = applyJourney(once, incoming, limits)
                assertEquals(limit, once.journeys.size)
                assertEquals(once, twice)
                assertEquals(0, planAppRestore(once, incoming, limits).journey.insertions.size)
            }
        }
    }

    @Test fun `saved overflow blocks combined restoration and existing values win duplicates`() {
        val current = AppBackupData(listOf(place), listOf(point()))
        val incoming = AppBackupData(listOf(place.copy(id = "same", displayName = "Other name"),
            place.copy(id = "new", localityName = "Distinct")), listOf(point(2)))
        assertFalse(planAppRestore(current, incoming, DataLimits(1, 100)).saved.fits)
        assertTrue(planAppRestore(current, incoming, DataLimits(50, 200)).saved.fits)
        assertEquals(place, current.savedPlaces.single())
        assertEquals(1, current.journeys.size)
    }

    @Test fun `journey duplicates ignore device IDs and signed numeric zero`() {
        val existing = point().copy(latitude = 0.0, longitude = 0.0, accuracyMetres = 0f)
        val incoming = existing.copy(id = 9999, latitude = -0.0, longitude = -0.0, accuracyMetres = -0.0f)
        val plan = planAppRestore(AppBackupData(emptyList(), listOf(existing)),
            AppBackupData(emptyList(), listOf(incoming)), DataLimits(30, 100))
        assertEquals(1, plan.journey.duplicates)
        assertTrue(plan.journey.additions.isEmpty())
    }
}
