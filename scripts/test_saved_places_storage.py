"""SQLite contract checks using the actual Kotlin CREATE TABLE statement.

These supplement, not replace, Android/device tests of SavedPlacesRepository.
"""
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/actionanand/localtell/app/saved/SavedPlacesRepository.kt").read_text(encoding="utf-8")
SCHEMA = re.search(r'"""(CREATE TABLE saved_place.*?)"""', SOURCE, re.S).group(1)
INSERT = """INSERT INTO saved_place
    (id,identity,name,latitude,longitude,saved_at,source,accuracy)
    VALUES (?,?,?,?,?,?,?,?)"""


class SavedPlacesStorageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "saved_places.db"
        self.db = sqlite3.connect(self.path)
        self.db.execute(SCHEMA)

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def insert(self, id="one", timestamp=1):
        self.db.execute(INSERT, (id, id, "சென்னை / स्थानम्", -33.868820123, 151.209296789, timestamp, "LOOKUP", 12.75))

    def test_persistence_unicode_original_coordinates_and_accuracy_after_reopen(self):
        self.insert()
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.assertEqual(
            ("சென்னை / स्थानम्", -33.868820123, 151.209296789, 12.75, "LOOKUP"),
            self.db.execute("SELECT name,latitude,longitude,accuracy,source FROM saved_place").fetchone(),
        )

    def test_individual_and_bulk_removal_are_isolated_from_journey(self):
        self.db.execute("CREATE TABLE journey_point (id INTEGER PRIMARY KEY)")
        self.db.execute("INSERT INTO journey_point VALUES (1)")
        for id in ("a", "b", "c"):
            self.insert(id)
        self.db.execute("DELETE FROM saved_place WHERE id=?", ("a",))
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM saved_place").fetchone()[0])
        self.db.execute("DELETE FROM saved_place WHERE id IN (?,?)", ("b", "c"))
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM saved_place").fetchone()[0])
        self.assertEqual(1, self.db.execute("SELECT count(*) FROM journey_point").fetchone()[0])

    def test_failed_transaction_preserves_existing_bookmarks(self):
        self.insert()
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.insert("new")
                self.insert("one")
        self.assertEqual([("one",)], self.db.execute("SELECT id FROM saved_place").fetchall())

    def test_newest_first_with_stable_tie_break(self):
        self.insert("a", 1)
        self.insert("b", 2)
        self.insert("c", 2)
        self.assertEqual([("c",), ("b",), ("a",)], self.db.execute(
            "SELECT id FROM saved_place ORDER BY saved_at DESC,id DESC").fetchall())


if __name__ == "__main__":
    unittest.main()
