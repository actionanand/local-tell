# Architecture

## Runtime lookup

1. `CellReader` asks Android `TelephonyManager` for registered serving cells.
2. `OfflineAreaResolver` queries every installed state/India pack using an indexed lookup.
3. The first matching cell becomes the displayed approximate area.
4. No coordinate/GPS lookup is performed.

## Offline packs

The app keeps packs as separate read-only SQLite files in private app storage. It does not merge Tamil Nadu, Karnataka, etc. into a giant local database. This gives smaller updates and avoids rewriting hundreds of MB when a single state changes.

## Data identity

- GSM/WCDMA/TDSCDMA: MCC + MNC + RAT + LAC + CID
- LTE: MCC + MNC + LTE ECI (TAC retained for exact match)
- 5G NR: MCC + MNC + NCI (TAC retained for exact match)

TAC/LAC and Cell ID are never assumed globally unique across operators; MCC+MNC identifies the PLMN.

## Journey mode

Journey mode is explicit and user-started. A foreground service polls for a cell refresh about every 20 seconds and writes only when the resolved `area_name` changes. The service notification shows the latest approximate area. Android may rate-limit cell refreshes, so the cadence is intentionally approximate.

## Size optimization

Area/locality strings live once in the `area` table. Cell rows store only `area_id`, which significantly reduces state/India pack size when many cells resolve to the same locality.
