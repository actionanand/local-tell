"""Host-side resource/manifest/source wiring checks, not Android runtime tests.

The JVM tests execute the real .bklt decoder; these checks cover configuration and
include newly created files even before Git staging.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
SAVED = MAIN / "java/com/actionanand/localtell/app/saved"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class BackupContractTest(unittest.TestCase):
    def test_private_file_provider_exposes_only_backup_cache(self):
        app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
        providers = app.findall("provider")
        provider = next(p for p in providers if p.get(ANDROID + "authorities") == "${applicationId}.backup-files")
        self.assertEqual("false", provider.get(ANDROID + "exported"))
        self.assertEqual("true", provider.get(ANDROID + "grantUriPermissions"))
        self.assertEqual("@xml/backup_file_paths", provider.find("meta-data").get(ANDROID + "resource"))
        paths = list(ET.parse(MAIN / "res/xml/backup_file_paths.xml").getroot())
        self.assertEqual(1, len(paths))
        self.assertEqual("cache-path", paths[0].tag)
        self.assertEqual("saved-place-backups/", paths[0].get("path"))

    def test_external_and_picker_restore_use_one_filename_and_content_boundary(self):
        activity = (MAIN / "java/com/actionanand/localtell/app/MainActivity.kt").read_text(encoding="utf-8")
        screen = (SAVED / "SavedPlacesScreen.kt").read_text(encoding="utf-8")
        vm = (SAVED / "SavedPlacesViewModel.kt").read_text(encoding="utf-8")
        documents = (SAVED / "BackupDocuments.kt").read_text(encoding="utf-8")
        self.assertIn("savedPlacesViewModel.inspectBackup(incomingBackup)", activity)
        self.assertIn("val visibleTab = if (incomingBackup != null) RootTab.MORE else tab", activity)
        self.assertIn("when (visibleTab)", activity)
        self.assertIn("uri?.let(vm::inspectBackup)", screen)
        self.assertIn("OpenableColumns.DISPLAY_NAME", documents)
        self.assertIn("BackupFileFormat.requireFilename(name)", documents)
        self.assertIn("BackupDocumentIO.read(", vm)
        self.assertIn("BackupDocumentIO.write(", vm)
        io = (SAVED / "BackupDocumentIO.kt").read_text(encoding="utf-8")
        self.assertIn("BackupFileFormat.decode(filename, bytes)", io)
        self.assertNotIn("lastPathSegment", documents)
        self.assertNotIn("SavedPlacesBackupCodec.decode", screen)
        self.assertIn("Intent.ACTION_VIEW", documents)
        self.assertIn("Intent.ACTION_SEND", documents)
        self.assertIn("Intent.EXTRA_STREAM", documents)
        self.assertIn("it.itemCount == 1", documents)
        self.assertIn("Intent.FLAG_GRANT_READ_URI_PERMISSION", documents)
        self.assertIn("ClipData.newRawUri(backup.filename, uri)", documents)
        self.assertIn("Activity.RESULT_OK", documents)
        self.assertIn("if (uri == null) vm.cancelBackup() else vm.export(uri)", screen)

    def test_no_backup_creation_uses_json_filename_or_json_mime(self):
        screen = (SAVED / "SavedPlacesScreen.kt").read_text(encoding="utf-8")
        documents = (SAVED / "BackupDocuments.kt").read_text(encoding="utf-8")
        format_source = (SAVED / "BackupFileFormat.kt").read_text(encoding="utf-8")
        self.assertIn("CreateBackupDocument()", screen)
        self.assertIn("Intent.ACTION_CREATE_DOCUMENT", documents)
        self.assertIn("Intent.EXTRA_LOCAL_ONLY", documents)
        self.assertNotIn("application/json", screen + documents + format_source)
        self.assertNotRegex(screen + documents + format_source, r'\.json["`]')
        self.assertIn('.bklt"', format_source)

    def test_external_filters_do_not_advertise_unrelated_text_images_or_archives(self):
        app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
        activity = next(a for a in app.findall("activity") if a.get(ANDROID + "name") == ".MainActivity")
        filters = {f.find("action").get(ANDROID + "name"): f for f in activity.findall("intent-filter")}
        for action in ("android.intent.action.VIEW", "android.intent.action.SEND"):
            data = filters[action].findall("data")
            self.assertEqual({"application/octet-stream", "application/vnd.localtell.backup"},
                             {d.get(ANDROID + "mimeType") for d in data})
        self.assertEqual("content", filters["android.intent.action.VIEW"].find("data").get(ANDROID + "scheme"))

    def test_manual_flow_explains_provider_limits_and_all_restore_counts(self):
        english = {n.get("name"): "".join(n.itertext()) for n in
                   ET.parse(MAIN / "res/values/strings.xml").getroot()}
        self.assertEqual("Backup ready", english["saved_backup_ready"])
        self.assertEqual("Choose cloud destination", english["saved_backup_options"])
        for key in ("saved_backup_local_help", "saved_backup_cloud_help"):
            self.assertIn("AR Apps / LocalTell / Backups", english[key])
        self.assertIn("both local and cloud", english["saved_backup_cloud_help"])
        self.assertIn("download it first", english["saved_restore_help"])
        for key in ("saved_restore_preview", "saved_restore_overflow"):
            self.assertEqual(["%1$d", "%2$d", "%3$d", "%4$d", "%5$d"], re.findall(r"%\d\$d", english[key]))

    def test_all_three_languages_have_unique_keys_and_matching_placeholders(self):
        maps = {}
        for language in ("values", "values-ta", "values-sa"):
            nodes = list(ET.parse(MAIN / f"res/{language}/strings.xml").getroot())
            names = [n.get("name") for n in nodes if n.get("name")]
            self.assertEqual(len(names), len(set(names)), language)
            maps[language] = {n.get("name"): "".join(n.itertext()) for n in nodes if n.get("name")}
        english = maps["values"]
        pattern = re.compile(r"%(?:\d+\$)?(?:[0-9.]+)?[sdf]")
        for language in ("values-ta", "values-sa"):
            self.assertEqual(set(english), set(maps[language]), language)
            for name, value in english.items():
                self.assertEqual(sorted(pattern.findall(value)), sorted(pattern.findall(maps[language][name])), name)
        for name in ("saved_limit_title", "saved_limit_count", "saved_limit_message", "saved_manage",
                     "saved_backup_local", "saved_backup_cloud", "saved_backup_invalid"):
            self.assertTrue(english[name])
        self.assertEqual("Saved Places limit reached", english["saved_limit_title"])
        self.assertEqual("%1$d of %2$d places saved", english["saved_limit_count"])
        self.assertEqual("You've reached the maximum of 30 saved places. Remove an existing place to save a new one.",
                         english["saved_limit_message"].replace("\\'", "'"))
        self.assertEqual("Save locally", english["saved_backup_local"])
        self.assertEqual("Upload to cloud", english["saved_backup_cloud"])

    def test_all_saved_place_source_resource_references_exist(self):
        resources = {n.get("name") for n in ET.parse(MAIN / "res/values/strings.xml").getroot()}
        for path in SAVED.glob("*.kt"):
            for key in re.findall(r"R\.string\.(\w+)", path.read_text(encoding="utf-8")):
                self.assertIn(key, resources, f"{path.name}: {key}")


if __name__ == "__main__":
    unittest.main()
