PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE pack_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE area (
    id INTEGER PRIMARY KEY,
    area_name TEXT NOT NULL,
    district TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT '',
    UNIQUE(area_name, district, state)
);
CREATE TABLE cell_lookup (
    mcc INTEGER NOT NULL,
    mnc INTEGER NOT NULL,
    radio TEXT NOT NULL,
    area_code INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    area_id INTEGER NOT NULL,
    confidence INTEGER NOT NULL DEFAULT 50 CHECK(confidence BETWEEN 0 AND 100),
    last_seen INTEGER,
    PRIMARY KEY (mcc, mnc, radio, area_code, cell_id)
) WITHOUT ROWID;
CREATE INDEX idx_cell_lookup_plmn_cell ON cell_lookup(mcc, mnc, radio, cell_id);
