package com.actionanand.localtell.app.geography

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionanand.localtell.app.data.InstalledPack
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reads the installed app's AssetManager, never a source tree or test-APK asset. */
@RunWith(AndroidJUnit4::class)
class GeographicAssetRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installedAssetRetainsOriginalGzipAndCoverage() {
        val bytes = context.assets.open("geography/boundaries.ltgz").use { it.readBytes() }
        assertEquals(6_032_996, bytes.size)
        assertArrayEquals(byteArrayOf(0x1f, 0x8b.toByte(), 0x08), bytes.copyOf(3))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("e34ce12b0ad1d5a289704a30848fcb021f6a09ed70babf421e90136df1ebb4e0", digest)
        val metadata = context.assets.open("geography/provenance.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("generated")
        assertEquals(238, metadata.getJSONArray("countryCodes").length())
        assertEquals(36, metadata.getJSONArray("indiaAdminCodes").length())
    }

    @Test fun productionLoaderResolvesCountriesAndStatesFromInstalledAssets() = runBlocking {
        assertEquals("IN-KA", BuiltInGeography.identify(context, 12.971598, 77.594566, 20f)?.adminCode)
        assertEquals("IN-TN", BuiltInGeography.identify(context, 8.1833, 77.4119, 20f)?.adminCode)
        assertEquals("LK", BuiltInGeography.identify(context, 7.2906, 80.6337, 20f)?.countryCode)
        // An unsupported pack country is still geographically identified; uncertainty stays null.
        assertEquals("NP", BuiltInGeography.identify(context, 27.7172, 85.324, 20f)?.countryCode)
        assertNull(BuiltInGeography.identify(context, 0.0, 0.0, 20f))
        assertNull(BuiltInGeography.identify(context, 49.0, -100.0, 20f))
        assertNull(BuiltInGeography.identify(context, 12.971598, 77.594566, null))
    }

    @Test fun missingRuntimeAssetIsNotReportedAsUncertainCoordinates() {
        assertThrows(GeographyAssetException::class.java) {
            GeographyAssetLoader.load { context.assets.open("geography/nonexistent-test-asset.ltgz") }
        }
    }

    @Test fun installedLocalityPackStillResolvesIndependently() {
        // Isolated fixture preferences/database: never read or replace the user's installed packs.
        val app = context
        val preferenceName = "geographic_asset_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(preferenceName, mode)
        }
        val file = File.createTempFile("geographic-asset-test-", ".db", app.cacheDir)
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE pack_meta (key TEXT, value TEXT)")
                db.execSQL("INSERT INTO pack_meta VALUES ('schema_version','3')")
                db.execSQL("""CREATE TABLE place (id INTEGER PRIMARY KEY, name TEXT, place_type TEXT,
                    admin_level TEXT, sub_district TEXT, district TEXT, state TEXT, state_code TEXT,
                    latitude REAL, longitude REAL)""")
                db.execSQL("CREATE TABLE place_geometry (id INTEGER PRIMARY KEY, place_id INTEGER, geometry TEXT)")
                db.execSQL("INSERT INTO place VALUES (1,'Karnataka','administrative_boundary','4',NULL,NULL,'Karnataka','KA',NULL,NULL)")
                db.execSQL("INSERT INTO place_geometry VALUES (1,1,'12,76;14,76;14,79;12,79;12,76')")
                db.execSQL("INSERT INTO place VALUES (2,'Bengaluru fixture','neighbourhood',NULL,NULL,'Bengaluru','Karnataka','KA',12.971598,77.594566)")
                db.execSQL("INSERT INTO place_geometry VALUES (2,2,'12.96,77.58;12.98,77.58;12.98,77.61;12.96,77.61;12.96,77.58')")
            }
            val store = PackStore(isolated)
            store.put(InstalledPack("KA", "Karnataka", 1L, file.absolutePath, 0L))
            val resolver = OfflineLocalityResolver(store)
            val match = resolver.resolve(12.971598, 77.594566)
            assertEquals("Bengaluru fixture", match?.localityName)
            assertEquals("KA", match?.packId)
            assertEquals("polygon", match?.sourceQuality)
            assertEquals(setOf("KA"), resolver.coverage(12.971598, 77.594566).coveringIds)
        } finally {
            app.deleteSharedPreferences(preferenceName)
            SQLiteDatabase.deleteDatabase(file)
        }
    }
}
