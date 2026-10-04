package com.actionanand.localtell.app.location

enum class LocationSource { GPS, ASSISTED }
enum class LocationQuality { PRECISE, APPROXIMATE }

/** Pure accuracy rules shared by GPS and explicitly requested assisted location. */
object LocationAccuracy {
    const val APPROXIMATE_MAX_ACCURACY_METRES = 250f

    fun classify(hasAccuracy: Boolean, accuracyMetres: Float): LocationQuality? = when {
        !hasAccuracy -> null
        accuracyMetres <= OneShotGnssLocator.MAX_ACCURACY_METRES -> LocationQuality.PRECISE
        accuracyMetres <= APPROXIMATE_MAX_ACCURACY_METRES -> LocationQuality.APPROXIMATE
        else -> null
    }

    fun isBetterApproximate(candidate: Float, current: Float?): Boolean =
        classify(true, candidate) == LocationQuality.APPROXIMATE &&
            (current == null || candidate < current)
}
