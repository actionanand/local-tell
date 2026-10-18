package com.actionanand.localtell.app.saved

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

class SavedPlacesTest {
    private val place = SavedPlace(
        id = "place-1", displayName = "நாகர்கோவில்", localityName = "Nagercoil",
        subDistrict = null, district = "Kanniyakumari", state = "Tamil Nadu", countryCode = "IN",
        latitude = 8.18, longitude = 77.41, savedAt = 1_700_000_000_000,
        source = SavedPlaceSource.HOME,
    )

    @Test fun namedIdentityNormalizesNameAndIgnoresCoordinateDrift() {
        assertTrue(SavedPlaceIdentity.matches(place, place.copy(localityName = "  NAGERCOIL  ", latitude = 8.181)))
        assertTrue(SavedPlaceIdentity.matches(place.copy(localityName = "New  Town"), place.copy(localityName = "new town")))
        assertFalse(SavedPlaceIdentity.matches(place, place.copy(state = "Kerala", latitude = 8.5)))
        assertFalse(SavedPlaceIdentity.matches(place, place.copy(countryCode = "LK", latitude = 6.9)))
    }

    @Test fun sameNameFarAwayIsNotADuplicateAndGridEdgesDoNotCreateDuplicates() {
        assertFalse(SavedPlaceIdentity.matches(place, place.copy(latitude = 9.18)))
        val edge = place.copy(localityName = null, latitude = 8.180049)
        assertTrue(SavedPlaceIdentity.matches(edge, edge.copy(latitude = 8.180051)))
        assertTrue(SavedPlaceIdentity.matches(place, place.copy(localityName = null, latitude = 8.18001)))
        assertTrue(SavedPlaceIdentity.matches(place, place.copy(district = null, countryCode = null, latitude = 8.18001)))
    }

    @Test fun coordinateOnlyIdentityUsesFourDecimalGrid() {
        val coordinate = place.copy(localityName = null)
        assertEquals(coordinate.identity, coordinate.copy(latitude = 8.18001).identity)
        assertNotEquals(coordinate.identity, coordinate.copy(latitude = 8.181).identity)
    }

    @Test fun validatesCoordinatesAndMetadata() {
        assertTrue(SavedPlaceIdentity.valid(place))
        assertFalse(SavedPlaceIdentity.valid(place.copy(latitude = Double.NaN)))
        assertFalse(SavedPlaceIdentity.valid(place.copy(longitude = 181.0)))
        assertFalse(SavedPlaceIdentity.valid(place.copy(countryCode = "India")))
        assertFalse(SavedPlaceIdentity.valid(place.copy(id = "../path")))
    }

    @Test fun duplicateAtCapacityWinsBeforeLimitAndNewPlaceCannotEvictAnything() {
        val current = (0 until 30).map { place.copy(id = "id-$it", localityName = "Place $it") }
        val before = current.toList()
        assertEquals(SaveResult.Duplicate, classifySavedPlace(current, current.first().copy(id = "another-id"), limit = 30))
        assertEquals(SaveResult.Full, classifySavedPlace(current, place, limit = 30))
        assertEquals(before, current)
    }

    @Test fun missingContextDoesNotMergeDistantNamesakesAndMatchingIsSymmetric() {
        val a = place.copy(subDistrict = null, district = null, state = null, countryCode = null)
        val b = a.copy(latitude = a.latitude + 0.01)
        assertFalse(SavedPlaceIdentity.matches(a, b))
        val nearby = a.copy(latitude = a.latitude + 0.00001)
        assertTrue(SavedPlaceIdentity.matches(a, nearby))
        assertEquals(SavedPlaceIdentity.matches(place, nearby), SavedPlaceIdentity.matches(nearby, place))
    }

    @Test fun mergePreviewPreservesDuplicatesAndRejectsCapacityOverflow() {
        val existing = (0 until 30).map { place.copy(id = "id-$it", localityName = "Place $it") }
        val duplicate = existing.first().copy(id = "another-id", latitude = 8.181)
        assertEquals(RestorePreview(0, 1, 30, true), planSavedPlacesMerge(existing, listOf(duplicate), limit = 30))
        assertEquals(RestorePreview(1, 0, 31, false), planSavedPlacesMerge(existing, listOf(place), limit = 30))
        assertThrows(IllegalArgumentException::class.java) {
            planSavedPlacesMerge(existing, listOf(place.copy(id = existing.first().id)), limit = 30)
        }
    }

    @Test fun mergeAcceptsExactlyThirtyAndDoesNotMutateEitherInput() {
        val current = listOf(place)
        val incoming = (1..29).map { place.copy(id = "new-$it", localityName = "Place $it") }
        assertEquals(RestorePreview(29, 0, 30, true), planSavedPlacesMerge(current, incoming, limit = 30))
        assertEquals(listOf(place), current)
        assertEquals(29, incoming.size)
        assertEquals(RestorePreview(0, 0, 1, true), planSavedPlacesMerge(current, emptyList(), limit = 30))
    }

    @Test fun invalidRestoreCannotReplaceExistingCoordinates() {
        val current = listOf(place)
        val bad = place.copy(id = "new-id", latitude = Double.POSITIVE_INFINITY)
        assertThrows(IllegalArgumentException::class.java) { planSavedPlacesMerge(current, listOf(bad), limit = 30) }
        assertEquals(listOf(place), current)
        val distantIdCollision = place.copy(latitude = -33.868820, longitude = 151.209296)
        assertThrows(IllegalArgumentException::class.java) { planSavedPlacesMerge(current, listOf(distantIdCollision), limit = 30) }
    }

    @Test fun coordinateOnlyBackupsPreserveOriginalPrecisionAccuracyAndSource() {
        val original = place.copy(localityName = null, subDistrict = null, district = null, state = null,
            countryCode = null, latitude = -33.868820123, longitude = 151.209296789,
            accuracyMetres = 12.75f, source = SavedPlaceSource.LOOKUP)
        assertEquals(original, AppBackupCodec.decode(AppBackupCodec.encode(AppBackupData(listOf(original), emptyList()))).savedPlaces.single())
    }
}

/** Shared full-backup fixture with multilingual bookmark metadata. */
internal fun backupFixture(): AppBackupData = AppBackupData(listOf(SavedPlace(
    id = "fixture", displayName = "நாகர்கோவில் / स्थानम्", localityName = "Nagercoil",
    subDistrict = null, district = "Kanniyakumari", state = "Tamil Nadu", countryCode = "IN",
    latitude = 8.18, longitude = 77.41, savedAt = 1_700_000_000_000,
    source = SavedPlaceSource.HOME, accuracyMetres = 12.75f,
)), emptyList())
