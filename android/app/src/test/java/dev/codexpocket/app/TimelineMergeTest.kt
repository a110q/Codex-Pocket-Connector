package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class TimelineMergeTest {
    @Test fun liveUpdatesPreserveLoadedHistoryAndReplaceChangedTools() {
        val old=(0..79).map { TimelineEntry("item$it",it,"old$it") }
        val live=(40..85).map { TimelineEntry("item$it",it,"new$it") }
        val merged=mergeTimeline(old,live)
        assertEquals(86,merged.size)
        assertEquals("old0",merged.first().value)
        assertEquals("new79",merged[79].value)
        assertEquals("new85",merged.last().value)
    }
    @Test fun olderPagesPrependWithoutDuplicatesOrReordering() {
        val live=(40..85).map { TimelineEntry("item$it",it,it) }
        val earlier=(0..45).map { TimelineEntry("item$it",it,it) }
        assertEquals((0..85).toList(),mergeTimeline(live,earlier).map { it.value })
    }
}
