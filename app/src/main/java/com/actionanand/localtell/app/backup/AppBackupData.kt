package com.actionanand.localtell.app.backup

import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.DataLimits
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.saved.*

data class AppBackupData(val savedPlaces: List<SavedPlace>, val journeys: List<JourneyPoint>) {
    val isEmpty: Boolean get() = savedPlaces.isEmpty() && journeys.isEmpty()
}

data class JourneyMergePlan(val additions: List<JourneyPoint>, val insertions: List<JourneyPoint>, val duplicates: Int, val removed: Int, val total: Int)
data class AppRestorePlan(val saved: RestorePreview, val journey: JourneyMergePlan)

/** Local SQLite IDs never participate in cross-device duplicate matching. */
fun journeyIdentity(point: JourneyPoint): JourneyPoint = point.copy(id = 0, latitude = point.latitude?.let { if (it == 0.0) 0.0 else it },
    longitude = point.longitude?.let { if (it == 0.0) 0.0 else it },
    accuracyMetres = point.accuracyMetres?.let { if (it == 0f) 0f else it })

fun validJourney(point: JourneyPoint): Boolean {
    fun text(value: String?) = value == null || (value.isNotBlank() && value.length <= 200 &&
        value.none(Char::isISOControl) && validBackupUnicode(value))
    return point.timestamp > 0 && text(point.areaName) && text(point.district) && text(point.state) &&
        text(point.subDistrict) && point.radio.length <= 200 && point.plmn.length <= 200 &&
        point.radio.none(Char::isISOControl) && point.plmn.none(Char::isISOControl) &&
        validBackupUnicode(point.radio) && validBackupUnicode(point.plmn) && point.cellId >= 0 && point.confidence in 0..100 &&
        ((point.latitude == null && point.longitude == null) ||
            (point.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                point.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true)) &&
        (point.accuracyMetres == null || (point.latitude != null && point.accuracyMetres.isFinite() && point.accuracyMetres >= 0))
}

fun planAppRestore(current: AppBackupData, incoming: AppBackupData, limits: DataLimits = AppLimits.current): AppRestorePlan {
    require(incoming.journeys.size <= DataLimits.MAX_JOURNEY_BOUND && incoming.journeys.all(::validJourney))
    val existing = current.journeys.map(::journeyIdentity).toHashSet()
    val additions = incoming.journeys.filter { existing.add(journeyIdentity(it)) }
        .sortedWith(compareBy<JourneyPoint> { it.timestamp }.thenBy { AppBackupCodec.journeySortKey(it) })
    val beforeTrim = current.journeys.size + additions.size
    // Existing rows win equal-timestamp retention ties. Otherwise restoring a backup
    // larger than the current limit could repeatedly displace its own retained rows.
    val candidates = current.journeys.map { it to false } + additions.map { it to true }
    val retained = candidates.sortedWith(compareByDescending<Pair<JourneyPoint, Boolean>> { it.first.timestamp }
        .thenBy { it.second }.thenByDescending { if (!it.second) it.first.id else 0L }
        .thenByDescending { AppBackupCodec.journeySortKey(it.first) }).take(limits.maxJourneyPoints)
    val insertionKeys = retained.filter { it.second }.map { journeyIdentity(it.first) }.toHashSet()
    return AppRestorePlan(
        planSavedPlacesMerge(current.savedPlaces, incoming.savedPlaces, limits.maxSavedPlaces),
        JourneyMergePlan(additions, additions.filter { journeyIdentity(it) in insertionKeys }, incoming.journeys.size - additions.size,
            (beforeTrim - limits.maxJourneyPoints).coerceAtLeast(0), beforeTrim.coerceAtMost(limits.maxJourneyPoints)),
    )
}
