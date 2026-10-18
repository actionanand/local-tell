package com.actionanand.localtell.app

data class DataLimits(val maxSavedPlaces: Int, val maxJourneyPoints: Int) {
    init {
        require(maxSavedPlaces in 1..MAX_SAVED_BOUND)
        require(maxJourneyPoints in 1..MAX_JOURNEY_BOUND)
    }
    companion object {
        const val MAX_SAVED_BOUND = 200
        const val MAX_JOURNEY_BOUND = 1000
    }
}

object AppLimits {
    val current = DataLimits(BuildConfig.MAX_SAVED_PLACES, BuildConfig.MAX_JOURNEY_POINTS)
}
