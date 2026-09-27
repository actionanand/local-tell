# Architecture

## Primary locality flow

LocalTell uses cellular information as a low-power freshness signal, not as a geographic coordinate. A changed serving-cell fingerprint can mean the cached locality should be refreshed, but a Cell ID/NCI is not itself a location.

The old experimental approach was `cellular Cell ID -> tower database -> locality`. The primary architecture is now `cellular change -> one-shot GNSS -> offline geographic locality database`.

1. `CellReader` reads registered and neighbouring cellular diagnostics.
2. The app compares the registered serving-cell fingerprint with its recent, private locality cache.
3. If the result is recent (currently 15 minutes) and the fingerprint is unchanged, the cached offline locality is reused.
4. Otherwise, `OneShotGnssLocator` obtains one GPS-provider fix with up to 50 m reported accuracy, for up to 25 seconds.
5. The listener is removed immediately after a usable result, timeout, cancellation, or error.
6. `OfflineLocalityResolver` resolves that coordinate entirely from a downloaded geographic SQLite pack.

Coordinates are processed only on-device and are never uploaded. New network cells do not require a new Cell-ID mapping: packs represent geographic places and boundaries instead.

## Geographic pack schema (v3)

Schema v3 is a geographic locality pack. It contains `pack_meta`, `place`, `place_geometry`, and `place_geometry_rtree`.

- `place`: `id`, `name`, `place_type`, `sub_district`, `district`, `state`, `state_code`, optional `latitude`, and optional `longitude`.
- `place_geometry`: `id`, `place_id`, and one encoded polygon/ring geometry per row.
- `place_geometry_rtree`: an RTree bounding-box index keyed by `id`, with `min_lat`, `max_lat`, `min_lng`, and `max_lng` for geometry candidates.

The Android resolver first performs point-in-polygon matching, then falls back to the nearest named place when no polygon contains the coordinate. Geographic data production remains in the data-pack pipeline; the app contains no hard-coded localities or tower mappings.

## Legacy packs

- Schema 1 remains the legacy cell-to-area pack.
- Schema 2 remains the cell-to-tower-site pack.
- Schema 3 is the primary geographic locality pack.

V1/V2 are still validated and readable by `OfflineAreaResolver` for compatibility, but they are not the primary Home architecture. If no geographic pack is installed, Home explicitly asks for one and may show a legacy cell-pack estimate as compatibility information.

## Journey mode

Journey remains its existing explicit foreground-service feature and polls cellular state about every 20 seconds. It has not been converted to continuous GPS tracking. The one-shot GNSS locator is reusable so Journey can later acquire a locality only after a cellular change.
