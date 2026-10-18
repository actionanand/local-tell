package com.actionanand.localtell.app.data

import android.content.Context

/** Last validated catalog, not an installed-pack database. Only call on IO. No coordinates. */
class ManifestCatalogCache(context: Context) {
    private val context = context.applicationContext
    private val preferences get() = context.getSharedPreferences("geographic_catalog", Context.MODE_PRIVATE)
    private fun key(source: CountryManifestSource) = "${source.countryCode}:${source.manifestUrl}"
    fun save(source: CountryManifestSource, text: String) {
        preferences.edit().putString(key(source), text).putLong("${key(source)}:success", System.currentTimeMillis())
            .remove("${key(source)}:failure").apply()
    }
    fun failed(source: CountryManifestSource) { preferences.edit().putLong("${key(source)}:failure", System.currentTimeMillis()).apply() }
    fun read(source: CountryManifestSource): String? = preferences.getString(key(source), null)
    fun confirmed(source: CountryManifestSource, now: Long = System.currentTimeMillis()): Boolean {
        val success = preferences.getLong("${key(source)}:success", 0)
        return success > 0 && now - success in 0L..86_400_000L && preferences.getLong("${key(source)}:failure", 0) < success
    }
}
