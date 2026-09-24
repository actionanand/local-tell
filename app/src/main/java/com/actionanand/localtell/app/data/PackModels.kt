package com.actionanand.localtell.app.data

data class RemotePack(
    val id: String,
    val name: String,
    val version: Long,
    val downloadUrl: String,
    val sha256: String,
    val compressedBytes: Long?,
    val uncompressedBytes: Long?,
)

data class RemoteManifest(
    val schemaVersion: Int,
    val generatedAt: String?,
    val packs: List<RemotePack>,
)

data class InstalledPack(
    val id: String,
    val name: String,
    val version: Long,
    val filePath: String,
    val installedAt: Long,
)
