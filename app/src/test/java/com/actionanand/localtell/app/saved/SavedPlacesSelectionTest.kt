package com.actionanand.localtell.app.saved

import org.junit.Assert.*
import org.junit.Test

class SavedPlacesSelectionTest {
    @Test fun `long press then toggle select all and cancel selection`() {
        val initial = SavedPlacesSelection()
        val selected = initial.select("one").select("one").toggle("two")
        assertEquals(listOf("one", "two"), selected.selected)
        assertEquals(listOf("two"), selected.toggle("one").selected)
        assertEquals(listOf("one", "two", "three"), selected.selectAll(listOf("one", "two", "three")).selected)
        assertTrue(selected.cancelSelection().selected.isEmpty())
        assertTrue(initial.selected.isEmpty())
    }

    @Test fun `requesting or cancelling individual and bulk deletion cannot approve removal`() {
        listOf(listOf("one"), listOf("one", "two")).forEach { ids ->
            val pending = SavedPlacesSelection().selectAll(ids).requestRemoval(ids)
            assertEquals(ids, pending.pendingRemoval)
            assertEquals(ids, pending.selected)
            val cancelled = pending.cancelRemoval()
            assertTrue(cancelled.confirmRemoval().first.isEmpty())
            assertEquals(ids, cancelled.selected)
        }
    }

    @Test fun `confirming selected deletion captures only approved ids and clears them`() {
        val pending = SavedPlacesSelection().selectAll(listOf("a", "b", "c")).requestRemoval(listOf("a", "b"))
        val (ids, next) = pending.confirmRemoval()
        assertEquals(setOf("a", "b"), ids)
        assertEquals(listOf("c"), next.selected)
        assertTrue(next.pendingRemoval.isEmpty())
        assertTrue(next.confirmRemoval().first.isEmpty())
    }

    @Test fun `remove all has separate confirmation and does not include later additions`() {
        val originalIds = mutableListOf("one", "two")
        val pending = SavedPlacesSelection().requestRemoval(originalIds.toList(), all = true)
        originalIds.add("three")
        assertTrue(pending.removeAll)
        assertEquals(2, pending.pendingRemoval.size)
        assertEquals(setOf("one", "two"), pending.confirmRemoval().first)
        assertFalse(pending.cancelRemoval().removeAll)
    }
}
