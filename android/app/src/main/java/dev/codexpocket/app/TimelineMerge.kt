package dev.codexpocket.app

data class TimelineEntry<T>(val key: String, val order: Int, val value: T)

fun <T> mergeTimeline(old: List<TimelineEntry<T>>, incoming: List<TimelineEntry<T>>): List<TimelineEntry<T>> =
    (old.associateBy { it.key } + incoming.associateBy { it.key }).values.sortedBy { it.order }

// A live page is the current tail of the desktop snapshot. Placeholder IDs
// can change when a turn is persisted; retaining the old tail creates ghosts.
// Only history preceding this page survives. Loading older pages still uses
// mergeTimeline so that newer tool output keeps its current value.
fun <T> replaceTimelineTail(old: List<TimelineEntry<T>>, incoming: List<TimelineEntry<T>>, hasEarlier: Boolean): List<TimelineEntry<T>> {
    if (!hasEarlier) return mergeTimeline(emptyList(), incoming)
    val first = incoming.minByOrNull { it.order } ?: return old
    return mergeTimeline(old.filter { it.order < first.order }, incoming)
}
