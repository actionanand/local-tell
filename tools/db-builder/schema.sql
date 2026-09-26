PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE pack_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE tower_site (
    id INTEGER PRIMARY KEY,
    source_site_id TEXT NOT NULL UNIQUE,
    latitude REAL,
    longitude REAL,
    area_name TEXT NOT NULL,
    district TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT '',
    address TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT ''
);
CREATE TABLE cell_lookup (
    mcc INTEGER NOT NULL,
    mnc INTEGER NOT NULL,
    radio TEXT NOT NULL,
    area_code INTEGER NOT NULL,
    cell_id INTEGER NOT NULL,
    tower_site_id INTEGER NOT NULL,
    pci INTEGER,
    arfcn INTEGER,
    confidence INTEGER NOT NULL DEFAULT 50 CHECK(confidence BETWEEN 0 AND 100),
    last_seen INTEGER,
    PRIMARY KEY (mcc, mnc, radio, area_code, cell_id),
    FOREIGN KEY (tower_site_id) REFERENCES tower_site(id)
) WITHOUT ROWID;
CREATE INDEX idx_cell_lookup_plmn_cell ON cell_lookup(mcc, mnc, radio, cell_id);
CREATE INDEX idx_cell_lookup_tower_site ON cell_lookup(tower_site_id);
