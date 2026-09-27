package com.actionanand.localtell.app.data

/**
 * Explicit pack contracts; schemas 1 and 2 remain readable for legacy cell-based packs.
 *
 * Schema 3 intentionally requires only portable SQLite tables at Android runtime.
 * A geographic pack may include `place_geometry_rtree` as an optional build/desktop index, but
 * Android must not require or query it because some vendor SQLite builds omit the RTree module.
 */
enum class OfflinePackSchema(val version: Int, val requiredTables: List<String>) {
    LEGACY_CELL(1, listOf("cell_lookup", "area")),
    TOWER_CELL(2, listOf("cell_lookup", "tower_site")),
    GEOGRAPHIC_LOCALITY(3, listOf("place", "place_geometry"));

    companion object {
        fun fromVersion(version: Int?): OfflinePackSchema? =
            values().firstOrNull { it.version == version }
    }
}
