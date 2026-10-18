"""Exercise actual SQLite table/retention SQL and attached-file transaction contract.

These host tests do not execute Android SQLiteDatabase, ViewModels or Kotlin codecs.
The JVM and Android/device suites remain mandatory.
"""
import json
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/actionanand/localtell/app"
SAVED_SOURCE = (APP / "saved/SavedPlacesRepository.kt").read_text(encoding="utf-8")
JOURNEY_SOURCE = (APP / "journey/JourneyDbHelper.kt").read_text(encoding="utf-8")
SAVED_SCHEMA = re.search(r'"""(CREATE TABLE saved_place.*?)"""', SAVED_SOURCE, re.S).group(1)
JOURNEY_SCHEMA = re.search(r'"""(CREATE TABLE journey_point.*?)"""', JOURNEY_SOURCE, re.S).group(1)
TRIM = re.search(r'"""(DELETE FROM journey_point.*?)"""', JOURNEY_SOURCE, re.S).group(1)
INSERT_SAVED = """INSERT INTO saved_place(id,identity,name,latitude,longitude,saved_at,source)
    VALUES(?,?,'Original',8.18,77.41,123,'HOME')"""
INSERT_JOURNEY = """INSERT INTO journey_point(timestamp,area_name,radio,plmn,cell_id,confidence)
    VALUES(?,'Nagercoil','NR','405-869',4367237120,100)"""


class AppBackupStorageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="localtell-backup-sql-")
        base = Path(self.temp.name)
        saved, journey = base / "saved_places.db", base / "journeys.db"
        with sqlite3.connect(saved) as db:
            db.execute(SAVED_SCHEMA)
            db.execute(INSERT_SAVED, ("original", "original"))
        db.close()
        with sqlite3.connect(journey) as db:
            db.execute(JOURNEY_SCHEMA)
            db.execute(INSERT_JOURNEY, (123,))
        db.close()
        self.db = sqlite3.connect(saved)
        self.db.execute("ATTACH DATABASE ? AS journey_data", (str(journey),))
        for schema in ("main", "journey_data"):
            self.assertIn(self.db.execute(f"PRAGMA {schema}.journal_mode").fetchone()[0],
                          {"delete", "truncate", "persist"})
            self.db.execute(f"PRAGMA {schema}.synchronous=FULL")

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def counts(self):
        return tuple(self.db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                     for table in ("saved_place", "journey_point"))

    def test_one_attached_transaction_commits_both_existing_databases(self):
        with self.db:
            self.db.execute(INSERT_SAVED, ("new", "new"))
            self.db.execute(INSERT_JOURNEY, (456,))
        self.assertEqual((2, 2), self.counts())
        self.assertEqual((4367237120, None, None), self.db.execute(
            "SELECT cell_id,latitude,longitude FROM journey_point ORDER BY timestamp DESC LIMIT 1").fetchone())

    def test_failure_in_second_dataset_rolls_back_first_and_second(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute(INSERT_SAVED, ("new", "new"))
                self.db.execute(INSERT_JOURNEY, (456,))
                self.db.execute("INSERT INTO journey_point(timestamp) VALUES(789)")
        self.assertEqual((1, 1), self.counts())

    def test_cancellation_before_commit_rolls_back_inserts_and_retention(self):
        before = self.db.execute("SELECT * FROM journey_point").fetchall()
        with self.assertRaises(InterruptedError):
            with self.db:
                self.db.execute(INSERT_SAVED, ("new", "new"))
                self.db.execute(INSERT_JOURNEY, (456,))
                self.db.execute(TRIM, (1,))
                raise InterruptedError("cancel before commit")
        self.assertEqual((1, 1), self.counts())
        self.assertEqual(before, self.db.execute("SELECT * FROM journey_point").fetchall())

    def test_capacity_rejection_leaves_both_untouched(self):
        with self.assertRaises(ValueError):
            with self.db:
                raise ValueError("Saved Places capacity exceeded")
        self.assertEqual((1, 1), self.counts())

    def test_conflicting_saved_identity_cannot_leave_partial_restore(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute(INSERT_JOURNEY, (456,))
                self.db.execute(INSERT_SAVED, ("other-id", "original"))
        self.assertEqual((1, 1), self.counts())

    def test_source_uses_attached_transaction_and_preserves_existing_schema_versions(self):
        source = (APP / "backup/AppBackupRepository.kt").read_text(encoding="utf-8")
        self.assertIn("ATTACH DATABASE ? AS journey_data", source)
        self.assertIn('requireJournal("journey_data")', source)
        self.assertIn("db.beginTransaction()", source)
        self.assertIn("db.setTransactionSuccessful()", source)
        self.assertIn("finally { db.endTransaction() }", source)
        self.assertIn("currentCoroutineContext().ensureActive()", source)
        self.assertIn('SQLiteOpenHelper(context, "saved_places.db", null, 1)', SAVED_SOURCE)
        self.assertIn('SQLiteOpenHelper(context, "journeys.db", null, 2)', JOURNEY_SOURCE)
        self.assertIn("trimOnOpen = false", source)
        self.assertNotIn("DROP TABLE", source + SAVED_SOURCE + JOURNEY_SOURCE)

    def test_build_and_runtime_limits_read_config_not_duplicated_business_defaults(self):
        config = json.loads((ROOT / "app-config.json").read_text(encoding="utf-8"))
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        limits = (APP / "AppLimits.kt").read_text(encoding="utf-8")
        for key, maximum, field in (("maxSavedPlaces", 200, "MAX_SAVED_PLACES"),
                                    ("maxJourneyPoints", 1000, "MAX_JOURNEY_POINTS")):
            self.assertIs(type(config[key]), int)
            self.assertTrue(1 <= config[key] <= maximum)
            self.assertIn(f'configuredLimit("{key}", {maximum})', gradle)
            self.assertIn(f'buildConfigField("int", "{field}"', gradle)
            self.assertIn(f"BuildConfig.{field}", limits)
        self.assertIn("not a decimal or string", gradle)

    def test_byte_ceiling_covers_maximum_length_double_escaped_metadata(self):
        codec = (APP / "backup/AppBackupCodec.kt").read_text(encoding="utf-8")
        ceiling = int(re.search(r"MAX_BYTES = (\d+) \* 1024 \* 1024", codec).group(1)) * 1024 * 1024
        text = "A" + "\u2028" * 199
        saved = dict(id="x" * 100, name=text, localityName=text, subDistrict=text,
                     district=text, state=text, countryCode="IN", latitude=8.18,
                     longitude=77.41, savedAt="2026-10-18T15:00:00Z", source="JOURNEY", accuracyMetres=12.75)
        journey = dict(timestamp="2026-10-18T15:00:00Z", areaName=text, district=text,
                       state=text, subDistrict=text, radio=text, plmn=text, cellId=4367237120,
                       confidence=100, latitude=8.18, longitude=77.41, accuracyMetres=12.75)
        # Deliberately ASCII-escape separators as org.json may, then escape the payload.
        # This checks the bound, not Kotlin encoding/validation; JVM tests exercise that.
        payload = json.dumps(dict(exportedAt="2026-10-18T15:00:00Z",
                                  savedPlaces=[saved] * 200, journeys=[journey] * 1000), separators=(",", ":"))
        encoded = json.dumps(dict(format="localtell-backup", schemaVersion=1,
                                  payload=payload, sha256="0" * 64), separators=(",", ":")).encode("utf-8")
        self.assertGreater(len(encoded), 8 * 1024 * 1024)
        self.assertLess(len(encoded), ceiling)


if __name__ == "__main__":
    unittest.main()
