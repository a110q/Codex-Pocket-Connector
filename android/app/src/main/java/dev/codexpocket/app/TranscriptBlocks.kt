package dev.codexpocket.app

data class TranscriptBlock<T>(val key: String, val execution: Boolean, val records: List<T>)

// Group only adjacent execution records from the same turn. The first record
// anchors the key so streaming additions do not reset the user's fold state.
fun <T> transcriptBlocks(
    records: List<T>,
    key: (T) -> String,
    turn: (T) -> String,
    execution: (T) -> Boolean,
    previous: List<TranscriptBlock<T>> = emptyList()
): List<TranscriptBlock<T>> {
    val identities=buildMap<String,String>{previous.filter{it.execution}.forEach{block->block.records.forEach{put(key(it),block.key)}}}
    return buildList {
    var index = 0
    while (index < records.size) {
        val first = records[index]
        val isExecution = execution(first)
        var end = index + 1
        if (isExecution) {
            val turnId = turn(first)
            while (end < records.size && execution(records[end]) && turn(records[end]) == turnId) end++
        }
        // A page can extend the first folded tool group backwards. Reuse its
        // identity when it shares a record, so LazyColumn retains its anchor.
        val oldKey = if (isExecution) records.subList(index,end).firstNotNullOfOrNull{identities[key(it)]} else null
        add(TranscriptBlock(oldKey ?: (if (isExecution) "execution:" else "message:") + key(first), isExecution, records.subList(index, end)))
        index = end
    }
    }
}

// This identity map is scoped to one chat and has no Compose state writes.
class TranscriptBlockIdentity<T> {
    private var previous=emptyList<TranscriptBlock<T>>()
    fun update(records:List<T>,key:(T)->String,turn:(T)->String,execution:(T)->Boolean):List<TranscriptBlock<T>> {
        val result=transcriptBlocks(records,key,turn,execution,previous)
        previous=result
        return result
    }
}
