package com.actionanand.localtell.app.model

data class RadioCell(
    val radio: String,
    val mcc: String,
    val mnc: String,
    val areaCode: Long?,
    val cellId: Long,
    val dbm: Int?,
    val registered: Boolean,
    /** Radio measurements used only for diagnostics; they are never persisted in offline packs. */
    val pci: Int? = null,
    val channelNumber: Int? = null,
    val bands: List<Int> = emptyList(),
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val timingAdvance: Int? = null,
    /** Metadata used only to present diagnostics; it is never persisted in offline packs. */
    val subscriptionId: Int? = null,
    val simSlotIndex: Int? = null,
    val carrierName: String? = null,
) {
    val plmn: String get() = if (mcc.isBlank() || mnc.isBlank()) "Unknown" else "$mcc-$mnc"
}

data class ActiveSubscription(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val carrierName: String,
)

data class SubscriptionCells(
    val subscription: ActiveSubscription?,
    val cells: List<RadioCell>,
)
