package com.actionanand.localtell.app.model

data class AreaMatch(
    val areaName: String,
    val district: String?,
    val state: String?,
    val confidence: Int,
    val packId: String,
    val packVersion: Long,
    val matchedCell: RadioCell,
)
