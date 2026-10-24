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
    @Test fun persistedUserMessageReplacesTemporaryIdsAndPreviouslyCachedDuplicates() {
        val cached = listOf(
            TimelineEntry("turn:turn:input", 0, "hello"),
            TimelineEntry("turn:temporary-user", 0, "hello"),
            TimelineEntry("turn:answer", 1, "old answer")
        )
        val persisted = listOf(TimelineEntry("turn:user", 0, "hello"), TimelineEntry("turn:answer", 1, "answer"))
        val merged = replaceTimelineTail(cached, persisted, hasEarlier = false)
        assertEquals(listOf("hello", "answer"), merged.map { it.value })
        assertEquals(listOf("turn:user", "turn:answer"), merged.map { it.key })
    }
    @Test fun identicalMessagesWithDifferentIdsRemainSeparate() {
        val messages = listOf(TimelineEntry("turn1:user", 0, "hello"), TimelineEntry("turn2:user", 1, "hello"))
        assertEquals(messages, replaceTimelineTail(emptyList(), messages, hasEarlier = false))
    }
    @Test fun liveTailReplacementPreservesOlderPagesAndRemovesChangedIds() {
        val earlier = (0..39).map { TimelineEntry("old$it", it, "earlier$it") }
        val cached = earlier + listOf(TimelineEntry("temporary", 40, "awk"), TimelineEntry("answer", 41, "old answer"))
        val current = listOf(TimelineEntry("persisted", 40, "awk"), TimelineEntry("answer", 41, "answer"))
        val merged = replaceTimelineTail(cached, current, hasEarlier = true)
        assertEquals(42, merged.size)
        assertEquals(earlier, merged.take(40))
        assertEquals(current, merged.takeLast(2))
        val withEarlierPage = mergeTimeline(listOf(TimelineEntry("answer", 41, "outdated answer")), merged)
        assertEquals("answer", withEarlierPage.last().value)
    }
    @Test fun emptyCompleteSnapshotClearsCachedMessages() {
        assertTrue(replaceTimelineTail(listOf(TimelineEntry("deleted", 0, "old")), emptyList(), hasEarlier = false).isEmpty())
    }
}
