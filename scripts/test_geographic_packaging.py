"""Verifier regressions use ZIP fixtures, not a claim that Android artifacts were built."""
import gzip
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_geographic_artifacts import ROOT, SOURCE, EXPECTED_SHA256, verify_artifact
import hashlib


class GeographicPackagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = SOURCE.read_bytes()

    def check_archive(self, suffix, asset_name, data, valid, extra=None):
        with tempfile.TemporaryDirectory(prefix="localtell-packaging-") as directory:
            artifact = Path(directory) / ("fixture" + suffix)
            prefix = "base/" if suffix == ".aab" else ""
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr(prefix + "assets/geography/" + asset_name, data)
                if extra:
                    archive.writestr(prefix + "assets/geography/" + extra, b"stale")
            if valid:
                self.assertIn(EXPECTED_SHA256, verify_artifact(artifact))
            else:
                with self.assertRaises(ValueError):
                    verify_artifact(artifact)

    def test_apk_preserves_exact_gzip_asset(self):
        self.check_archive(".apk", "boundaries.ltgz", self.original, True)

    def test_aab_preserves_exact_gzip_asset(self):
        self.check_archive(".aab", "boundaries.ltgz", self.original, True)

    def test_android_legacy_decompression_and_rename_are_rejected_for_both_artifacts(self):
        for suffix in (".apk", ".aab"):
            with self.subTest(suffix=suffix):
                self.check_archive(suffix, "boundaries.ltg", gzip.decompress(self.original), False)

    def test_decompressed_bytes_under_new_name_are_rejected(self):
        self.check_archive(".apk", "boundaries.ltgz", gzip.decompress(self.original), False)

    def test_modified_gzip_bytes_are_rejected_even_when_content_is_identical(self):
        changed = bytearray(self.original)
        changed[4] ^= 1  # Only changes the gzip timestamp; decoded polygons remain identical.
        self.assertEqual(gzip.decompress(self.original), gzip.decompress(changed))
        self.check_archive(".aab", "boundaries.ltgz", changed, False)

    def test_stale_legacy_asset_is_rejected(self):
        self.check_archive(".apk", "boundaries.ltgz", self.original, False, "boundaries.ltg.gz")

    def test_new_name_is_used_by_generator_loader_and_tests(self):
        self.assertEqual(EXPECTED_SHA256, hashlib.sha256(self.original).hexdigest())
        self.assertFalse(SOURCE.with_name("boundaries.ltg.gz").exists())
        for relative in ("tools/geography/build_index.py",
                         "app/src/main/java/com/actionanand/localtell/app/geography/BuiltInGeography.kt",
                         "app/src/test/java/com/actionanand/localtell/app/geography/BoundaryIndexTest.kt"):
            text = (ROOT / relative).read_text(encoding="utf-8")
            self.assertIn("boundaries.ltgz", text)
            self.assertNotIn("boundaries.ltg.gz", text)
        home = (ROOT / "app/src/main/java/com/actionanand/localtell/app/HomeViewModel.kt").read_text(encoding="utf-8")
        self.assertIn("catch (_: GeographyAssetException)", home)
        self.assertIn("DataAvailability.ASSET_UNAVAILABLE", home)


if __name__ == "__main__":
    unittest.main()
