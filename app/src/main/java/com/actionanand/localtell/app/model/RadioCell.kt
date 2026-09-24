package com.actionanand.localtell.app.model

data class RadioCell(
    val radio: String,
    val mcc: String,
    val mnc: String,
    val areaCode: Long?,
    val cellId: Long,
    val dbm: Int?,
    val registered: Boolean,
) {
    val plmn: String get() = if (mcc.isBlank() || mnc.isBlank()) "Unknown" else "$mcc-$mnc"
}
