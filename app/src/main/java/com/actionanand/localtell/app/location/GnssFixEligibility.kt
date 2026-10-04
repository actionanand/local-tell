package com.actionanand.localtell.app.location

/** Pure GPS-fix quality and freshness rule, shared by production code and JVM unit tests. */
object GnssFixEligibility {
    fun isRecentPreciseFix(
        ageMillis: Long?,
        hasAccuracy: Boolean,
        accuracyMetres: Float,
        maxAccuracyMetres: Float,
        maxAgeMillis: Long,
    ): Boolean =
        ageMillis != null && ageMillis in 0..maxAgeMillis &&
            hasAccuracy && accuracyMetres <= maxAccuracyMetres
}
