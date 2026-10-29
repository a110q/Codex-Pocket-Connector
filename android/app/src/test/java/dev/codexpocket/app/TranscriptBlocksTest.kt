package dev.codexpocket.app

import org.junit.Assert.*
import org.junit.Test

class TranscriptBlocksTest {
    private data class Record(val id:String,val turn:String="turn1",val execution:Boolean=true)
    private fun blocks(records:List<Record>)=transcriptBlocks(records,{it.turn+":"+it.id},{it.turn},{it.execution})

    @Test fun repliesAndUserMessagesStaySeparateAndAllRecordsRemainInOrder(){
        val records=listOf(Record("user",execution=false),Record("tool1"),Record("thought"),Record("reply",execution=false),Record("tool2"))
        val grouped=blocks(records)
        assertEquals(listOf(false,true,false,true),grouped.map{it.execution})
        assertEquals(listOf(1,2,1,1),grouped.map{it.records.size})
        assertEquals(records,grouped.flatMap{it.records})
    }
    @Test fun adjacentToolsFromDifferentTurnsDoNotMerge(){
        assertEquals(2,blocks(listOf(Record("same"),Record("same",turn="turn2"))).size)
    }
    @Test fun appendingStreamedToolsKeepsTheExistingGroupIdentity(){
        val first=Record("first")
        val key=blocks(listOf(first)).single().key
        val streamed=blocks(listOf(first,Record("second"),Record("third")))
        assertEquals(key,streamed.single().key)
        assertEquals(3,streamed.single().records.size)
    }
    @Test fun earlierPagesPreserveExistingGroupsAndDoNotLoseRepeatedContent(){
        val current=listOf(Record("u",execution=false),Record("tool"),Record("reply",execution=false))
        val earlier=listOf(Record("u",turn="turn0",execution=false),Record("tool",turn="turn0"))
        val before=blocks(current)
        val after=blocks(earlier+current)
        assertEquals(before,after.takeLast(before.size))
        assertEquals(earlier+current,after.flatMap{it.records})
        assertTrue(blocks(emptyList()).isEmpty())
    }
}
