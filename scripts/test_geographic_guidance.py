"""Host checks of the shipped index and UI wiring, not Android/Kotlin execution."""
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools/geography"))
from read_index import Index
from build_index import INDIA_NAMES, rings_for

APP = ROOT / "app/src/main/java/com/actionanand/localtell/app"
ASSET = ROOT / "app/src/main/assets/geography"


class GeographicGuidanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.index = Index(ASSET / "boundaries.ltg.gz")

    def test_actual_india_state_and_island_polygons(self):
        for code, lat, lng in [("KA",12.971598,77.594566), ("TN",8.181910,77.352330),
                              ("KL",10.5276,76.2144), ("MH",18.5204,73.8567),
                              ("DL",28.6139,77.209), ("PY",11.934,79.812),
                              ("AN",11.6234,92.7265), ("LD",10.5667,72.6417)]:
            with self.subTest(code=code):
                self.assertEqual(("IN", "IN-" + code), self.index.identify(lat,lng))

    def test_actual_countries_across_continents_and_enclave(self):
        for country, lat, lng in [("LK",7.2906,80.6337), ("NP",27.7172,85.324),
                                  ("BD",23.8103,90.4125), ("DE",52.52,13.405),
                                  ("AU",-35.2809,149.13), ("JP",35.6762,139.65),
                                  ("BR",-15.7939,-47.8828), ("ZA",-25.7479,28.2293),
                                  ("US",38.9072,-77.0369), ("LS",-29.5,28.5)]:
            with self.subTest(country=country):
                self.assertEqual((country,None), self.index.identify(lat,lng))

    def test_oceans_disputes_and_international_borders_are_not_guessed(self):
        for point in [(0,0),(-30,-30),(20,65),(49,-100),(34.08,74.79),(-89,0)]:
            with self.subTest(point=point): self.assertIsNone(self.index.identify(*point))

    def test_uncertain_state_can_preserve_country_and_bad_accuracy_is_unknown(self):
        self.assertEqual(("IN",None), self.index.identify(28.6139,77.209,25000))
        self.assertIsNone(self.index.identify(52.52,13.405,100000))
        self.assertIsNone(self.index.identify(52.52,13.405,None))
        boundary = next(point for p in self.index.polygons if p[1] == "IN-KA"
                        for point in p[5][0] if 77.5 < point[0] < 78.5 and 12.5 < point[1] < 13.2)
        self.assertEqual(("IN",None), self.index.identify(boundary[1],boundary[0],500))

    def test_ring_holes_and_antimeridian_unwrapping(self):
        ring = rings_for([[[170,10],[-170,10],[-170,20],[170,20],[170,10]]])[0]
        coordinates = [(x / 1e6,y / 1e6) for x,y in ring]
        polygon = (0,"XX","Test",0,(10,20,170,190),[coordinates])
        self.assertTrue(self.index.contains(polygon,15,175))
        self.assertTrue(self.index.contains(polygon,15,-175))
        self.assertFalse(self.index.contains(polygon,15,0))
        polygon = (0,"XX","Test",0,(0,10,0,10),[
            [(0,0),(10,0),(10,10),(0,10),(0,0)],[(4,4),(6,4),(6,6),(4,6),(4,4)]])
        self.assertTrue(self.index.contains(polygon,3,3))
        self.assertFalse(self.index.contains(polygon,5,5))
        self.assertTrue(self.index.near(polygon,3.999,5,200))

    def test_provenance_hashes_all_current_36_units_and_bounded_size(self):
        metadata = json.loads((ASSET / "provenance.json").read_text(encoding="utf-8"))
        raw = (ASSET / "boundaries.ltg.gz").read_bytes()
        self.assertEqual(metadata["generated"]["bytes"],len(raw))
        self.assertEqual(metadata["generated"]["sha256"],hashlib.sha256(raw).hexdigest())
        self.assertLess(len(raw),7 * 1024 * 1024)
        self.assertEqual({"IN-"+key for key in INDIA_NAMES},{p[1] for p in self.index.polygons if p[0] == 1})
        self.assertEqual(36,len(metadata["generated"]["indiaAdminCodes"]))
        self.assertGreater(len(metadata["generated"]["countryCodes"]),200)
        self.assertIn("CC BY 2.5 IN",(ASSET / "NOTICE.txt").read_text())
        self.assertTrue(all(source["url"].startswith("https://") and len(source["sha256"]) == 64 for source in metadata["sources"]))

    def test_geo_detection_has_no_runtime_network_and_is_loaded_once_on_io(self):
        runtime = "\n".join(p.read_text(encoding="utf-8") for p in (APP / "geography").glob("*.kt"))
        for forbidden in ("HttpURLConnection", "openConnection(", "Geocoder(", "getLocation("):
            self.assertNotIn(forbidden,runtime)
        loader = (APP / "geography/BuiltInGeography.kt").read_text()
        self.assertIn("withContext(Dispatchers.IO)",loader)
        self.assertIn("mutex.withLock",loader)
        self.assertIn("index ?: BoundaryIndex.read",loader)

    def test_home_does_not_return_for_missing_pack_before_acquisition(self):
        home = (APP / "HomeViewModel.kt").read_text(encoding="utf-8")
        refresh = home[home.index("fun refresh()"):home.index("fun useApproximateLocation()")]
        self.assertIn("gnssLocator.getLocation()",refresh)
        self.assertEqual(1,refresh.count("gnssLocator.getLocation()"))
        self.assertLess(refresh.index("if (!reader.hasPermission())"),refresh.index("gnssLocator.getLocation()"))
        self.assertIn("GnssFixResult.ProviderDisabled",refresh)
        self.assertIn("GnssFixResult.PermissionMissing",refresh)
        self.assertIn("catch (_: Exception) { listOf(SubscriptionCells(null, emptyList())) }",refresh)
        self.assertNotIn("hasGeographicPack()",refresh)
        self.assertNotIn("ManifestRepository",home)
        self.assertIn("HomeStatus.AwaitingLocationChoice",refresh)
        self.assertIn("location.hasAccuracy()",home)
        self.assertIn("guidanceResolver.resolve",home)
        self.assertIn("withContext(Dispatchers.IO)",home)

    def test_navigation_targets_codes_without_downloading_and_preserves_cells_during_loading(self):
        activity = (APP / "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("onOpenRegionData = openRegionData",activity)
        self.assertIn("PacksScreen(target = offlineTarget",activity)
        self.assertIn("OfflineDataGuidancePolicy.matches(it, target)",activity)
        self.assertIn("HomeCellularDiagnostics(current.subscriptions",activity)
        start = activity.index("val openRegionData:")
        navigation = activity[start:activity.index("// A cold-start",start)]
        self.assertNotIn(".download(",navigation)
        self.assertIn("MoreDestination.OFFLINE_DATA",navigation)
        self.assertIn("rememberSaveable { mutableStateOf<String?>(null) }",activity)
        self.assertIn("Icons.Default.SettingsBackupRestore, stringResource(R.string.backup_title)",activity)

    def test_app_resources_are_complete_and_geographic_labels_do_not_replace_pack_names(self):
        pattern = re.compile(r"%(?:\d+\$)?(?:[\d.]+)?[sdf]")
        resources = {}
        for lang in ("values","values-ta","values-sa"):
            nodes = list(ET.parse(ROOT / f"app/src/main/res/{lang}/strings.xml").getroot())
            names = [n.get("name") for n in nodes if n.get("name")]
            self.assertEqual(len(names),len(set(names)),lang)
            resources[lang] = {n.get("name"):"".join(n.itertext()) for n in nodes if n.get("name")}
        for lang in ("values-ta","values-sa"):
            self.assertEqual(resources["values"].keys(),resources[lang].keys())
            for name,value in resources["values"].items():
                self.assertEqual(sorted(pattern.findall(value)),sorted(pattern.findall(resources[lang][name])),name)
        for path in (APP / "geography").glob("*.kt"):
            for key in re.findall(r"R\.string\.(\w+)",path.read_text(encoding="utf-8")):
                self.assertIn(key,resources["values"],str(path))
        policy = (APP / "geography/OfflineDataGuidance.kt").read_text(encoding="utf-8")
        self.assertNotIn("pack.name",policy)
        self.assertNotIn("region.adminName ==",policy)


if __name__ == "__main__":
    unittest.main()
