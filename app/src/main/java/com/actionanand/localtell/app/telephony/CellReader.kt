package com.actionanand.localtell.app.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.actionanand.localtell.app.model.RadioCell
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class CellReader(private val context: Context) {
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val executor = Executor { command -> command.run() }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun cachedServingCells(): List<RadioCell> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return cachedServingCellsWithPermission()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun cachedServingCellsWithPermission(): List<RadioCell> =
        runCatching { telephonyManager.allCellInfo.orEmpty().toRadioCells() }
            .getOrDefault(emptyList())
            .filter { it.registered }

    suspend fun requestServingCells(): List<RadioCell> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return cachedServingCells()

        return requestServingCellsWithPermission()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestServingCellsWithPermission(): List<RadioCell> =
        suspendCancellableCoroutine { continuation ->
            val callback = object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (continuation.isActive) {
                        continuation.resume(cellInfo.toRadioCells().filter { it.registered })
                    }
                }

                override fun onError(errorCode: Int, detail: Throwable?) {
                    if (continuation.isActive) continuation.resume(cachedServingCells())
                }
            }
            runCatching { telephonyManager.requestCellInfoUpdate(executor, callback) }
                .onFailure {
                    if (continuation.isActive) continuation.resume(cachedServingCells())
                }
        }

    private fun List<CellInfo>.toRadioCells(): List<RadioCell> = mapNotNull { info ->
        when {
            info is CellInfoLte -> info.cellIdentity.let { id ->
                validCell(
                    radio = "LTE",
                    mcc = id.compatMcc(),
                    mnc = id.compatMnc(),
                    areaCode = id.tac.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    cellId = id.ci.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    registered = info.isRegistered,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr ->
                (info.cellIdentity as? CellIdentityNr)?.let { id ->
                    validCell(
                        radio = "NR",
                        mcc = id.mccString,
                        mnc = id.mncString,
                        areaCode = id.tac.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                        cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG },
                        dbm = (info.cellSignalStrength as? CellSignalStrengthNr)?.dbm,
                        registered = info.isRegistered,
                    )
                }
            info is CellInfoWcdma -> info.cellIdentity.let { id ->
                validCell(
                    radio = "WCDMA",
                    mcc = id.compatMcc(),
                    mnc = id.compatMnc(),
                    areaCode = id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    cellId = id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    registered = info.isRegistered,
                )
            }
            info is CellInfoGsm -> info.cellIdentity.let { id ->
                validCell(
                    radio = "GSM",
                    mcc = id.compatMcc(),
                    mnc = id.compatMnc(),
                    areaCode = id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    cellId = id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                    dbm = info.cellSignalStrength.dbm,
                    registered = info.isRegistered,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoTdscdma ->
                info.cellIdentity.let { id ->
                    validCell(
                        radio = "TDSCDMA",
                        mcc = id.mccString,
                        mnc = id.mncString,
                        areaCode = id.lac.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                        cellId = id.cid.takeIf { it != CellInfo.UNAVAILABLE }?.toLong(),
                        dbm = info.cellSignalStrength.dbm,
                        registered = info.isRegistered,
                    )
                }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun CellIdentityLte.compatMcc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            validMcc(mccString)
        } else {
            validMcc(mcc)
        }

    @Suppress("DEPRECATION")
    private fun CellIdentityLte.compatMnc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            validMnc(mncString)
        } else {
            validMnc(mnc)
        }

    @Suppress("DEPRECATION")
    private fun CellIdentityWcdma.compatMcc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMcc(mccString) else validMcc(mcc)

    @Suppress("DEPRECATION")
    private fun CellIdentityWcdma.compatMnc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMnc(mncString) else validMnc(mnc)

    @Suppress("DEPRECATION")
    private fun CellIdentityGsm.compatMcc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMcc(mccString) else validMcc(mcc)

    @Suppress("DEPRECATION")
    private fun CellIdentityGsm.compatMnc(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) validMnc(mncString) else validMnc(mnc)

    private fun validMcc(value: String?): String? =
        value?.takeIf { it.toIntOrNull()?.let { code -> code in 100..999 } == true }

    private fun validMcc(value: Int): String? = value.takeIf { it in 100..999 }?.toString()

    private fun validMnc(value: String?): String? =
        value?.takeIf { it.toIntOrNull()?.let { code -> code in 0..999 } == true }

    private fun validMnc(value: Int): String? = value.takeIf { it in 0..999 }?.toString()

    private fun validCell(
        radio: String,
        mcc: String?,
        mnc: String?,
        areaCode: Long?,
        cellId: Long?,
        dbm: Int?,
        registered: Boolean,
    ): RadioCell? {
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank() || cellId == null || cellId < 0) return null
        val saneDbm = dbm?.takeIf { it in -160..-20 }
        return RadioCell(radio, mcc, mnc, areaCode, cellId, saneDbm, registered)
    }
}
