package com.actionanand.localtell.app.saved

/** Immutable, saveable UI state. Requesting/cancelling a removal never performs a database write. */
data class SavedPlacesSelection(
    val selected: List<String> = emptyList(),
    val pendingRemoval: List<String> = emptyList(),
    val removeAll: Boolean = false,
) : java.io.Serializable {
    fun toggle(id: String) = copy(selected = if (id in selected) selected - id else selected + id)
    fun select(id: String) = copy(selected = (selected + id).distinct())
    fun selectAll(ids: List<String>) = copy(selected = ids.distinct())
    fun cancelSelection() = copy(selected = emptyList())
    fun requestRemoval(ids: List<String>, all: Boolean = false) = copy(pendingRemoval = ids.distinct(), removeAll = all)
    fun cancelRemoval() = copy(pendingRemoval = emptyList(), removeAll = false)
    fun confirmRemoval(): Pair<Set<String>, SavedPlacesSelection> =
        pendingRemoval.toSet() to copy(selected = selected - pendingRemoval.toSet(), pendingRemoval = emptyList(), removeAll = false)
}
