package com.actionanand.localtell.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PackStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("offline_packs", Context.MODE_PRIVATE)
    val directory: File = File(context.filesDir, "offline-packs").apply { mkdirs() }

    fun all(): List<InstalledPack> {
        val raw = prefs.getString("installed", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val p = array.getJSONObject(i)
                    val file = File(p.getString("filePath"))
                    if (file.isFile) {
                        add(
                            InstalledPack(
                                id = p.getString("id"),
                                name = p.getString("name"),
                                version = p.getLong("version"),
                                filePath = file.absolutePath,
                                installedAt = p.getLong("installedAt"),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun put(pack: InstalledPack) {
        val packs = all().filterNot { it.id == pack.id } + pack
        save(packs)
    }

    fun remove(id: String) {
        val current = all()
        current.firstOrNull { it.id == id }?.let { runCatching { File(it.filePath).delete() } }
        save(current.filterNot { it.id == id })
    }

    private fun save(packs: List<InstalledPack>) {
        val array = JSONArray()
        packs.sortedBy { it.id }.forEach { p ->
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("version", p.version)
                put("filePath", p.filePath)
                put("installedAt", p.installedAt)
            })
        }
        prefs.edit().putString("installed", array.toString()).apply()
    }
}
