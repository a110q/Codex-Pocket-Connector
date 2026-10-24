package dev.codexpocket.app

data class TimelineEntry<T>(val key: String, val order: Int, val value: T)

fun <T> mergeTimeline(old: List<TimelineEntry<T>>, incoming: List<TimelineEntry<T>>): List<TimelineEntry<T>> =
    (old.associateBy { it.key } + incoming.associateBy { it.key }).values.sortedBy { it.order }
