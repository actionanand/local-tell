package com.actionanand.localtell.app.model

/** A place resolved from an installed geographic pack, never from a cell identity. */
data class LocalityMatch(
    val localityName: String,
    val localityType: String?,
    val subDistrict: String?,
    val district: String?,
    val state: String?,
    val stateCode: String?,
    val sourceQuality: String,
    val packId: String,
    val packVersion: Long,
)

/** The serving-cell identity used only to decide whether a recent locality can be reused. */
data class CellularFingerprint(
    val subscriptionId: Int?,
    val mcc: String,
    val mnc: String,
    val radio: String,
    val areaCode: Long?,
    val cellId: Long,
) {
    companion object {
        fun from(cells: List<RadioCell>): CellularFingerprint? = cells
            .asSequence()
            .filter(RadioCell::registered)
            .sortedWith(compareByDescending<RadioCell> { it.radio == "NR" }.thenByDescending { it.dbm ?: -999 })
            .firstOrNull()
            ?.let {
                CellularFingerprint(it.subscriptionId, it.mcc, it.mnc, it.radio, it.areaCode, it.cellId)
            }
    }
}
