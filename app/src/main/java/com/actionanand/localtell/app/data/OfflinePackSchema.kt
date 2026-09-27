package com.actionanand.localtell.app.data

/** Explicit pack contracts; schemas 1 and 2 remain readable for legacy cell-based packs. */
enum class OfflinePackSchema(val version: Int, val requiredTables: List<String>) {
    LEGACY_CELL(1, listOf("cell_lookup", "area")),
    TOWER_CELL(2, listOf("cell_lookup", "tower_site")),
    GEOGRAPHIC_LOCALITY(3, listOf("place", "place_geometry", "place_geometry_rtree"));

    companion object {
        fun fromVersion(version: Int?): OfflinePackSchema? = values().firstOrNull { it.version == version }
    }
}
