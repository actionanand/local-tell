package com.actionanand.localtell.app.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.actionanand.localtell.app.model.ActiveSubscription
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.model.SubscriptionCells
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Reads Android-provided cellular identity only; it never accesses numbers or device identifiers. */
class CellReader(private val context: Context) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val executor = Executor { command -> command.run() }

    fun hasPermission(): Boolean = hasFineLocationPermission()

    fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun cachedServingCells(): List<RadioCell> = cachedServingCellsSafely(telephonyManager, null)

    /** Groups diagnostics by active subscription when the optional phone-state permission is granted. */
    suspend fun requestSubscriptionCells(): List<SubscriptionCells> {
        if (!hasFineLocationPermission()) return emptyList()
        val subscriptions = activeSubscriptions()
        if (subscriptions.isEmpty()) return listOf(SubscriptionCells(null, requestServingCells()))
        return subscriptions.map { subscription ->
            val manager = telephonyManager.createForSubscriptionId(subscription.subscriptionId)
            SubscriptionCells(subscription, requestServingCells(manager, subscription))
        }
    }

    suspend fun requestServingCells(): List<RadioCell> = requestServingCells(telephonyManager, null)

    private suspend fun requestServingCells(manager: TelephonyManager, subscription: ActiveSubscription?): List<RadioCell> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return cachedServingCellsSafely(manager, subscription)
        return requestServingCellsWithPermission(manager, subscription)
    }

    private fun cachedServingCellsSafely(
        manager: TelephonyManager,
        subscription: ActiveSubscription?,
    ): List<RadioCell> {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return try {
            manager.allCellInfo
                .orEmpty()
                .toRadioCells(subscription)
                .sortedByDescending(RadioCell::registered)
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestServingCellsWithPermission(manager: TelephonyManager, subscription: ActiveSubscription?): List<RadioCell> =
        suspendCancellableCoroutine { continuation ->
            val callback = object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (continuation.isActive) continuation.resume(cellInfo.toRadioCells(subscription).sortedByDescending(RadioCell::registered))
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (continuation.isActive) continuation.resume(cachedServingCellsSafely(manager, subscription))
                }
            }
            runCatching { manager.requestCellInfoUpdate(executor, callback) }
                .onFailure {
                    if (continuation.isActive) continuation.resume(cachedServingCellsSafely(manager, subscription))
                }
        }

    private fun activeSubscriptions(): List<ActiveSubscription> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return activeSubscriptionsWithPermission()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun activeSubscriptionsWithPermission(): List<ActiveSubscription> = runCatching {
        subscriptionManager.activeSubscriptionInfoList.orEmpty().map { info ->
            ActiveSubscription(
                subscriptionId = info.subscriptionId,
                simSlotIndex = info.simSlotIndex,
                carrierName = (info.carrierName ?: info.displayName).toString().ifBlank { "Unknown carrier" },
            )
        }.filter { it.simSlotIndex >= 0 }
    }.getOrDefault(emptyList())

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun List<CellInfo>.toRadioCells(subscription: ActiveSubscription?): List<RadioCell> = mapNotNull { info ->
        val cell = when {
            info is CellInfoLte -> info.cellIdentity.let { id ->
                val signal = info.cellSignalStrength
                validCell(
                    radio = "LTE",
                    mcc = id.compatMcc(),
                    mnc = id.compatMnc(),
                    areaCode = id.tac.unavailableIntToLong(),
                    cellId = id.ci.unavailableIntToLong(),
                    dbm = signal.dbm,
                    registered = info.isRegistered,
                    pci = id.pci.unavailableIntToNull(),
                    channelNumber = id.earfcn.unavailableIntToNull(),
                    bands = id.compatBands(),
                    rsrp = signal.rsrp.unavailableIntToNull(),
                    rsrq = signal.rsrq.unavailableIntToNull(),
                    sinr = signal.rssnr.unavailableIntToNull(),
                    timingAdvance = signal.timingAdvance.unavailableIntToNull(),
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr ->
                (info.cellIdentity as? CellIdentityNr)?.let { id ->
                    val signal = info.cellSignalStrength as? CellSignalStrengthNr
                    validCell(
                        radio = "NR",
                        mcc = id.mccString,
                        mnc = id.mncString,
                        areaCode = id.tac.unavailableIntToLong(),
                        cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG },
                        dbm = signal?.dbm,
                        registered = info.isRegistered,
                        pci = id.pci.unavailableIntToNull(),
                        channelNumber = id.nrarfcn.unavailableIntToNull(),
                        bands = id.compatBands(),
                        rsrp = signal?.ssRsrp?.unavailableIntToNull(),
                        rsrq = signal?.ssRsrq?.unavailableIntToNull(),
                        sinr = signal?.ssSinr?.unavailableIntToNull(),
                        timingAdvance = signal?.compatTimingAdvance(),
                    )
                }
            info is CellInfoWcdma -> info.cellIdentity.let { id ->
                validCell("WCDMA", id.compatMcc(), id.compatMnc(), id.lac.unavailableIntToLong(), id.cid.unavailableIntToLong(), info.cellSignalStrength.dbm, info.isRegistered)
            }
            info is CellInfoGsm -> info.cellIdentity.let { id ->
                validCell("GSM", id.compatMcc(), id.compatMnc(), id.lac.unavailableIntToLong(), id.cid.unavailableIntToLong(), info.cellSignalStrength.dbm, info.isRegistered)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoTdscdma -> info.cellIdentity.let { id ->
                validCell("TDSCDMA", id.mccString, id.mncString, id.lac.unavailableIntToLong(), id.cid.unavailableIntToLong(), info.cellSignalStrength.dbm, info.isRegistered)
            }
            else -> null
        }
        cell?.copy(subscriptionId = subscription?.subscriptionId, simSlotIndex = subscription?.simSlotIndex, carrierName = subscription?.carrierName)
    }

    @Suppress("DEPRECATION")
    private fun CellIdentityLte.compatMcc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMcc(mccString) else validMcc(mcc)
    @Suppress("DEPRECATION")
    private fun CellIdentityLte.compatMnc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMnc(mncString) else validMnc(mnc)
    @Suppress("DEPRECATION")
    private fun CellIdentityWcdma.compatMcc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMcc(mccString) else validMcc(mcc)
    @Suppress("DEPRECATION")
    private fun CellIdentityWcdma.compatMnc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMnc(mncString) else validMnc(mnc)
    @Suppress("DEPRECATION")
    private fun CellIdentityGsm.compatMcc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMcc(mccString) else validMcc(mcc)
    @Suppress("DEPRECATION")
    private fun CellIdentityGsm.compatMnc(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMnc(mncString) else validMnc(mnc)

    private fun CellIdentityLte.compatBands(): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands.toList() else emptyList()

    private fun CellIdentityNr.compatBands(): List<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bands.toList() else emptyList()

    private fun CellSignalStrengthNr.compatTimingAdvance(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) timingAdvanceMicros.unavailableIntToNull() else null

    private fun Int.unavailableIntToLong(): Long? = takeIf { it != CellInfo.UNAVAILABLE }?.toLong()
    private fun Int.unavailableIntToNull(): Int? = takeIf { it != CellInfo.UNAVAILABLE }
    private fun validMcc(value: String?): String? = value?.takeIf { it.toIntOrNull() in 100..999 }
    private fun validMcc(value: Int): String? = value.takeIf { it in 100..999 }?.toString()
    private fun validMnc(value: String?): String? = value?.takeIf { it.toIntOrNull() in 0..999 }
    private fun validMnc(value: Int): String? = value.takeIf { it in 0..999 }?.toString()

    private fun validCell(
        radio: String,
        mcc: String?,
        mnc: String?,
        areaCode: Long?,
        cellId: Long?,
        dbm: Int?,
        registered: Boolean,
        pci: Int? = null,
        channelNumber: Int? = null,
        bands: List<Int> = emptyList(),
        rsrp: Int? = null,
        rsrq: Int? = null,
        sinr: Int? = null,
        timingAdvance: Int? = null,
    ): RadioCell? {
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank() || cellId == null || cellId < 0) return null
        return RadioCell(
            radio = radio,
            mcc = mcc,
            mnc = mnc,
            areaCode = areaCode,
            cellId = cellId,
            dbm = dbm?.takeIf { it in -160..-20 },
            registered = registered,
            pci = pci,
            channelNumber = channelNumber,
            bands = bands,
            rsrp = rsrp,
            rsrq = rsrq,
            sinr = sinr,
            timingAdvance = timingAdvance,
        )
    }
}
