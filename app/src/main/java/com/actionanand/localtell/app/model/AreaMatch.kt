package com.actionanand.localtell.app.model

data class AreaMatch(
    val sourceSiteId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val areaName: String,
    val district: String?,
    val state: String?,
    val source: String?,
    val confidence: Int,
    val packId: String,
    val packVersion: Long,
    val matchedCell: RadioCell,
)
