package dev.codexpocket.app

data class TranscriptBlock<T>(val key: String, val execution: Boolean, val records: List<T>)

// Group only adjacent execution records from the same turn. The first record
// anchors the key so streaming additions do not reset the user's fold state.
fun <T> transcriptBlocks(
    records: List<T>,
    key: (T) -> String,
    turn: (T) -> String,
    execution: (T) -> Boolean
): List<TranscriptBlock<T>> = buildList {
    var index = 0
    while (index < records.size) {
        val first = records[index]
        val isExecution = execution(first)
        var end = index + 1
        if (isExecution) {
            val turnId = turn(first)
            while (end < records.size && execution(records[end]) && turn(records[end]) == turnId) end++
        }
        add(TranscriptBlock((if (isExecution) "execution:" else "message:") + key(first), isExecution, records.subList(index, end)))
        index = end
    }
}
