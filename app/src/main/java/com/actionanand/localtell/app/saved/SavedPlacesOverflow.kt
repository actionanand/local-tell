package com.actionanand.localtell.app.saved

/** Saved Places can exceed the limit only after a Dropbox merge. Saving and Dropbox sync stay paused until resolved. */
internal object SavedPlacesOverflow {
    fun excess(count: Int, limit: Int = MAX_SAVED_PLACES): Int = (count - limit).coerceAtLeast(0)

    /** The oldest places, i.e. the last entries in the newest-first Saved Places list. */
    fun oldestToRemove(places: List<SavedPlace>, limit: Int = MAX_SAVED_PLACES): List<SavedPlace> {
        val excess = excess(places.size, limit)
        if (excess == 0) return emptyList()
        return places.sortedWith(compareBy<SavedPlace> { it.savedAt }.thenBy { it.id }).take(excess)
    }
}
