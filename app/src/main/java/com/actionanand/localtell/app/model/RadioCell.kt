package com.actionanand.localtell.app.model

data class RadioCell(
    val radio: String,
    val mcc: String,
    val mnc: String,
    val areaCode: Long?,
    val cellId: Long,
    val dbm: Int?,
    val registered: Boolean,
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
