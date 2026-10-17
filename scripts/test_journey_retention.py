import re
import sqlite3
import unittest
from pathlib import Path


SOURCE = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/java/com/actionanand/localtell/app/journey/JourneyDbHelper.kt"
).read_text(encoding="utf-8")
MAX_POINTS = int(re.search(r"MAX_JOURNEY_POINTS = (\d+)", SOURCE).group(1))
SCHEMA = re.search(r'"""(CREATE TABLE journey_point.*?)"""', SOURCE, re.S).group(1)
TRIM_SQL = re.search(r'"""(DELETE FROM journey_point.*?)"""', SOURCE, re.S).group(1)
INSERT_SQL = (
    "INSERT INTO journey_point(timestamp,area_name,radio,plmn,cell_id,confidence) "
    "VALUES (?, 'Area', 'LTE', '404-45', 1, 100)"
)


class JourneyRetentionTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute(SCHEMA)

    def tearDown(self):
        self.db.close()

    def add(self, timestamp):
        with self.db:
            point_id = self.db.execute(INSERT_SQL, (timestamp,)).lastrowid
            self.db.execute(TRIM_SQL, (MAX_POINTS,))
        return point_id

    def ids(self):
        return [
            row[0]
            for row in self.db.execute(
                "SELECT id FROM journey_point ORDER BY timestamp DESC, id DESC"
            )
        ]

    def test_99_then_insert_retains_100(self):
        for timestamp in range(99):
            self.add(timestamp)
        self.add(99)
        self.assertEqual(100, len(self.ids()))

    def test_101st_insert_discards_oldest_and_keeps_newest(self):
        oldest = self.add(0)
        for timestamp in range(1, 100):
            self.add(timestamp)
        newest = self.add(100)
        self.assertEqual(100, len(self.ids()))
        self.assertNotIn(oldest, self.ids())
        self.assertEqual(newest, self.ids()[0])

    def test_equal_timestamps_use_newest_id(self):
        oldest = self.add(1)
        for _ in range(100):
            newest = self.add(1)
        self.assertNotIn(oldest, self.ids())
        self.assertEqual(newest, self.ids()[0])

    def test_timestamp_takes_priority_over_insert_order(self):
        for timestamp in range(100, 200):
            self.add(timestamp)
        older = self.add(0)
        self.assertEqual(100, len(self.ids()))
        self.assertNotIn(older, self.ids())

    def test_trims_all_existing_excess_rows(self):
        self.db.executemany(INSERT_SQL, [(timestamp,) for timestamp in range(180)])
        self.db.execute(TRIM_SQL, (MAX_POINTS,))
        self.assertEqual(list(range(180, 80, -1)), self.ids())

    def test_repeated_inserts_never_exceed_limit(self):
        for timestamp in range(150):
            self.add(timestamp)
            self.assertLessEqual(len(self.ids()), 100)

    def test_clear_leaves_zero_rows(self):
        for timestamp in range(101):
            self.add(timestamp)
        self.db.execute("DELETE FROM journey_point")
        self.assertEqual([], self.ids())

    def test_insert_and_trim_roll_back_together(self):
        for timestamp in range(100):
            self.add(timestamp)
        before = self.ids()
        with self.assertRaises(RuntimeError):
            with self.db:
                self.db.execute(INSERT_SQL, (100,))
                self.db.execute(TRIM_SQL, (MAX_POINTS,))
                raise RuntimeError("rollback")
        self.assertEqual(before, self.ids())

    def test_upgrade_preserves_legacy_rows_without_inventing_coordinates(self):
        old_schema = """CREATE TABLE journey_point (
            id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL,
            area_name TEXT NOT NULL, district TEXT, state TEXT, radio TEXT NOT NULL,
            plmn TEXT NOT NULL, cell_id INTEGER NOT NULL, confidence INTEGER NOT NULL)"""
        with sqlite3.connect(":memory:") as legacy:
            legacy.execute(old_schema)
            legacy.execute(INSERT_SQL, (123,))
            alterations = re.findall(r'db.execSQL\("(ALTER TABLE journey_point[^"\n]+)"\)', SOURCE)
            self.assertEqual(4, len(alterations))
            for sql in alterations:
                legacy.execute(sql)
            row = legacy.execute("SELECT timestamp,area_name,latitude,longitude,accuracy,sub_district FROM journey_point").fetchone()
            self.assertEqual((123, "Area", None, None, None, None), row)

    def test_new_coordinates_and_accuracy_survive_database_roundtrip(self):
        self.db.execute(
            "INSERT INTO journey_point(timestamp,area_name,radio,plmn,cell_id,confidence,latitude,longitude,accuracy,sub_district) "
            "VALUES (1,'Place','NR','405-869',1,100,?,?,?,?)",
            (-33.868820123, 151.209296789, 12.75, "Subdivision"),
        )
        self.assertEqual(
            (-33.868820123, 151.209296789, 12.75, "Subdivision"),
            self.db.execute("SELECT latitude,longitude,accuracy,sub_district FROM journey_point").fetchone(),
        )


if __name__ == "__main__":
    unittest.main()
