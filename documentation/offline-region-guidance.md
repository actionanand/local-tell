# Built-in region detection and offline-data guidance

This feature identifies a country/territory and, for India, a state/UT without
downloaded geographic packs. It does **not** resolve a town/locality, replace
schema-v3 packs, estimate a position from cellular signals or alter location codes.

## Sources and redistribution

The shipped `assets/geography/boundaries.ltgz` is **6,032,996 bytes** (about
5.75 MiB), containing 4,900 polygon parts, 1,593,585 vertices, 238 country/territory
codes and all 36 India state/UT identifiers. A code being present does not promise
a confident result everywhere in that territory.

- Country polygons and dispute masks: **Natural Earth 1:10m**, pinned to the vector
  repository's **v5.1.2** tag. Public domain; [terms](https://www.naturalearthdata.com/about/terms-of-use/).
- India: **geoBoundaries gbOpen IND ADM1**, boundary ID **IND-ADM1-1811400**,
  pinned release commit **9469f09**; DataMeet India community / Election Commission
  of India. **CC BY 2.5 IN**, not public domain. The license URI and attribution
  accompany the APK in `assets/geography/NOTICE.txt` and are accessible offline
  from the final section of More → Settings (View data sources and licenses).
  [Source metadata](https://www.geoboundaries.org/api/current/gbOpen/IND/ADM1/),
  [license](https://creativecommons.org/licenses/by/2.5/in/).
- Metadata describes a 2011 represented year and 2023 build, but the actual 36
  features include Telangana, Ladakh and the merged Dadra and Nagar Haveli and
  Daman and Diu. Do not interpret that metadata as a contemporary survey.
  The inventory/names were cross-checked against the Government of India's
  [State/UT directory](https://www.india.gov.in/directory/web-directory/state-uts)
  and its [28-state/8-UT parliamentary inventory, pages 2–3](https://sansad.in/getFile/annex/256/AU2797.pdf?source=pqars).
  This validates the administrative inventory, not cadastral geometry or claims.

Exact input URLs/SHA-256 checksums, license identifiers, transformations, generated
size/hash and complete code lists are in `tools/geography/sources.json` and bundled
`assets/geography/provenance.json`. Source files are build-time inputs, not runtime
downloads; no downloaded raw GeoJSON is committed.

## Generation and runtime index

Download the three pinned inputs to a separate source directory using the URLs in
`sources.json`, with filenames `countries.geojson`, `india.geojson` and
`disputed.geojson`. The India URL uses Git LFS media, not its small pointer file.
Then run Python (standard library only):

```sh
python -B tools/geography/build_index.py /path/to/source-directory
python -B scripts/test_geographic_guidance.py -v
```

Generation checks all hashes and the complete 36-unit India inventory. The raw
parts and holes are retained; there is **no geometric simplification**, area cutoff,
country-centre assignment or rectangular containment approximation. Coordinates
are quantized to 1e-6 degree (less than 0.2 m rounding displacement), consecutive
duplicate quantized points removed, and longitudes unwrapped consistently across
the antimeridian, including holes. Source ISO aliases CT/OR/TG/UT are reconciled
to CG/OD/TS/UK and official English display names; existing pack names are not
modified. Binary delta-varints plus gzip replace raw JSON parsing. Gzip timestamp
and OS fields are fixed; reproducibility checks use the same Python/zlib toolchain.

`LTG1` contains a big-endian polygon count; each part has kind (country/admin/
uncertain), UTF-8 code/name, source uncertainty allowance, quantized bounds and
closed rings. Coordinates are signed zigzag-varint deltas. A baked two-degree
grid maps cells to candidate part IDs. Grid cells/bounds only prune candidates;
ray casting of outer rings and holes determines containment. The globe is not
classified by rectangular extents. Antimeridian cut segments are not treated as
real coastlines; nearby genuine edges still apply.

### Android asset packaging hotfix

The file extension is deliberately **`.ltgz`**, not `.gz`. The production APK/AAB
inspection confirmed that Android packaging expanded the old `.gz` input and
removed its suffix, leaving `assets/geography/boundaries.ltg` (7,079,992 decoded
bytes). The loader's old `.gz` lookup consequently failed despite valid GPS.
The renamed asset retains the original gzip bytes, size and SHA-256:

```text
e34ce12b0ad1d5a289704a30848fcb021f6a09ed70babf421e90136df1ebb4e0
```

The generator always writes `boundaries.ltgz`. Android opens that path through
AssetManager, and BoundaryIndex still decodes it with GZIPInputStream. There is
no geographic-data or index-format change. Missing/corrupt assets raise a distinct
GeographyAssetException, log a loading error without coordinates and show localized
"Country detection unavailable" guidance rather than "Location uncertain".
Genuinely ambiguous positions still return null after a successful load. Failed
loads are not cached; subsequent attempts can retry. Successful installed locality
matches bypass the bundled index as before.

The feature-validation workflow verifies the **actual built** debug APK, release
APK and AAB before upload, checking their respective `assets/geography/boundaries.ltgz`
and `base/assets/geography/boundaries.ltgz` entries against the original gzip hash
and source bytes. It rejects old paths, missing/duplicate assets, expanded data or
changed compressed bytes. It also compiles the instrumentation APK. Production
CI/signing/versioning is unchanged. This fix branch does not match the existing
`feature/**` push trigger; run Feature Validation manually on this branch.

The process-wide immutable index loads once on Dispatchers.IO behind a mutex.
It stores integer vertex arrays (about 12.2 MiB for coordinates, plus indexing and
object overhead). Lookups inspect indexed candidates, not the entire world.
Cancellation is checked during polygon/ring scans. No network, reverse geocoder,
API key, new permission, background location loop or additional GPS request is
used for detection. The index does not persist the detected coordinate/region.

## Conservative classification

- Invalid coordinates, missing/invalid accuracy, accuracy over 25 km and polar
  coordinates above 85° absolute latitude return unknown. Antarctica is not
  assigned to a country.
- An uncertainty circle must clear polygon edges: reported accuracy plus a
  **2 km cartographic allowance for Natural Earth** or **100 m for India ADM1**,
  with a small quantization allowance. Spherical segment distances include hole
  boundaries. These are conservative engineering margins, **not certified source
  accuracy or a statistical confidence level**.
- All marked disputed/indeterminate areas and nearby uncertainty buffers return
  unknown. Overlapping country classifications or disagreement between detailed
  India and world data also return unknown. This is not a political map or a
  determination of sovereignty. The pinned dispute inventory is not guaranteed
  to capture every subsequent change or dispute.
- The detailed India state/UT geometry can establish India on small islands/coasts
  omitted by the generalized world polygons, but never override a conflicting
  country/dispute classification. A confident India result may have no confident
  state; then guidance stays country-level.
- Small countries/islands, coastal points, enclaves, source omissions and boundaries
  may remain uncertain even with precise GPS. Unknown never means nearest-country
  guessing. No representation is suitable for emergency/legal/navigation decisions.

The data model carries stable country and optional administrative codes. Only
India ADM1 is included now; the binary kind/model can accommodate future
country-specific administrative sources through an explicit data-generation update.

## Home flow

The missing-geographic-pack early return is removed. The existing serialized Home
request reads cellular diagnostics, respects reusable valid locality cache results,
and otherwise obtains **one existing GNSS request**, even without packs. Cellular
cards appear while location acquisition/resolution continues. Existing timeout,
permission checks, approximate-location choice, assisted consent and cancellation
remain unchanged; providers and GPS thresholds are not modified.
Unavailable telephony diagnostics do not block the independent GPS lookup; they
use an empty diagnostic group, while cancellation still propagates normally.

Normal successful locality resolution and cache behavior stay unchanged. If no
locality resolves, built-in region detection and a read-only installed-coverage
inspection run on IO. Coverage uses the existing schema-v3 level-4 geometry rules,
without RTree assumptions. Installed-but-unreadable and installed-but-unmatched
data are not presented as missing downloads. Legacy cell estimates remain optional
supporting information after acquisition, never a source for geographic identity.

## Availability is independent

`OfflineDataGuidancePolicy` separates the detected region from configuration,
validated catalog knowledge and actual installed coverage:

- Relevant installed data with no locality: explain unavailable locality or a
  read problem; offer viewing existing Offline Data, not a missing-pack claim.
- Configured country + recent confirmed manifest containing the relevant pack:
  show missing-data guidance and a targeted **Download … data** navigation button.
- A confirmed list lacking the pack: explain it is absent from that list; offer
  browsing/updating, without a misleading Download button.
- No catalog, a stale catalog, or a failed manifest refresh: availability is
  **unknown**, not unsupported. Offer browsing/retry. A 24-hour maximum age and
  per-source failure marker prevent stale absence from looking authoritative.
- Country absent from `app-config.json`: explain locality data is not yet supported;
  worldwide codes and country identification remain available. No Download button.
- Uncertain geographic identity: general guidance; no arbitrary pack selection.

The lightweight manifest cache records only validated catalog text, source URL,
fetch success time and failure marker. Home does **not** fetch manifests. Existing
Offline Data refresh fetches the same configured public catalogs without any
coordinates. The cache is revalidated against configuration and reused when a
source fails; installed packs and successful other-country catalogs remain visible.
No download/checksum/install/removal/cancellation algorithm is replaced.

India pack matching uses `adminCode`, or the current verified ISO-suffix ID
convention (e.g. IN-KA → KA), not display names. Sri Lanka remains the configured
**nine-province** catalog, not a fabricated single country database. Actual point
coverage wins; partial country installs are explained without claiming the entire
country is missing. Other configured countries use the same policy automatically;
no country-specific networking/UI branch is needed for future support.

## Navigation and localization

Home passes an `OfflineDataTarget` through root callbacks. Country/admin/pack IDs
and a display label survive recreation with rememberSaveable primitives. A known
state/pack opens a compact, filtered and outlined existing pack row; country-wide
guidance opens the corresponding expanded country section. Targeted state mode
does not expose a misleading whole-country batch action. Show all restores normal
browsing. Search clears targeting; a new target clears any previous search and
reveals the start of its filtered list. Progress updates do not retrigger targeting
or scroll effects. Normal manual region expansion/reveal behavior remains intact.

No target navigation initiates a download. Existing explicit buttons perform
downloads. Home refresh re-reads PackStore, so installing a pack is recognized
without a new app session or any unsolicited GPS request on returning to Home.
Normal bottom tabs, More navigation and Back behavior are retained.

English/Tamil/Sanskrit messages share parameterized resources. Country display
names use Android/Java's offline locale data, falling back to dataset names. Eight
requested administrative examples have explicit display translations; other names
fall back safely to canonical English. These display labels are not written into
the pack catalog, search source, locality database or backup records.

Settings uses **Icons.Default.SettingsBackupRestore** instead of Download for
Backup & Restore. Verified in Google's official `material-icons-extended:1.7.8`
source archive, the exact version selected by the existing Compose BOM 2026.09.00.
No dependency or Settings layout/theme/accessibility change is required.

Built-in boundary attribution appears only at the bottom of More → Settings,
after the language choices, not in Home guidance or the Offline Data downloader.
The compact section explicitly distinguishes Natural Earth / geoBoundaries /
DataMeet India **bundled boundaries** from LocalTell's separately downloaded
locality databases, which are not sourced from those organizations. The read-only,
scrollable details dialog includes localized modifications/limitations and the
unchanged canonical `geography/NOTICE.txt`: credits, source references, license
names/URLs and non-endorsement. The notice is read on IO only when opened, without
network access; its text is selectable for copying references. Both NOTICE.txt
and provenance.json remain bundled and unchanged. English/Tamil/Sanskrit controls
and explanations use matching resources; proper names and canonical legal
references remain verbatim. Typography/theme colors follow Settings, headings
have accessibility semantics, and the existing 32dp bottom padding is retained.

## Validation and device gates

Host reference queries of the shipped binary cover Karnataka, Tamil Nadu, Kerala,
Maharashtra, Delhi, Puducherry, Andaman/Nicobar, Lakshadweep, Sri Lanka, Nepal,
Bangladesh, Germany, Australia, Japan, Brazil, South Africa, United States and
Lesotho; ocean, dispute, international/state-border, accuracy, holes and longitude
wrap cases; checksum/coverage, IO/navigation/source contracts and locale parity.
The independent Python reader supplements, not replaces, Kotlin execution.

New JVM suites execute the actual BoundaryIndex and availability policy, including
no installed packs, TN installed/KA missing, installed/unreadable data, unknown vs
unpublished catalogs, partial/full Sri Lanka and future configured-country support.
The existing Home request cancellation/single-flight tests remain relevant.

Run all host checks and Android CI:

```sh
python -B -m unittest discover -s scripts -p 'test_*.py' -v
git diff --check
gradle lint testDebugUnitTest
gradle assembleDebug assembleRelease bundleRelease
python -B scripts/verify_geographic_artifacts.py app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk app/build/outputs/bundle/release/*.aab
gradle connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.actionanand.localtell.app.geography.GeographicAssetRuntimeTest
```

Compilation, lint, JVM tests, R8/builds and real Android SQLite/asset integration
still require Android tooling. Real-device release gates include no packs and
TN-only/KA location, permissions denied/revoked, provider disabled/timeout,
approximate/assisted/cancellation, one-tap tabs/scrolling during first index load,
catalog network failure, installation then manual Home refresh, exact target
navigation/Back/recreation, cached catalogs, large fonts/TalkBack/all locales,
coastal/island/border uncertainty and low-memory asset-loading performance.

GeographicPackagingTest uses ZIP fixtures to test the artifact verifier, including
the exact former packaging failure. Passing those fixtures does not prove an
Android build passed; the separate verifier command must be run against new build
outputs. GeographicAssetRuntimeTest uses the **target app's** AssetManager and the
production loader for Bengaluru (IN-KA), Nagercoil (IN-TN), Sri Lanka (LK), Nepal,
ocean/border uncertainty and missing accuracy; it checks the pinned gzip bytes and
238/36 metadata counts. It also checks a real missing-asset error and resolves an
isolated schema-v3 SQLite fixture through the unchanged installed-pack resolver,
without touching user pack preferences or files. Run on an emulator/device; these
are not source-file-only JVM tests. The current environment has no Gradle/Java/ADB,
so actual new APK/AAB and Android execution remain validation gates, not claimed
successes.

This feature does not change Saved Places/Journey databases, limits, identity,
location-code algorithms, `.bklt` schema/transactions, sharing/snapshot retention,
production signing/workflow, version 1.0.25/35 or the localtell-data repository.
