package dev.codexpocket.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskComputerPollerTest {
    @Test fun busyComputerEventuallyVisitsEveryChatAndKeepsFailedReadsQueued()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val profile=JSONObject().put("relay","https://poller.invalid").put("pin","a".repeat(64)).put("hostId","poller-${System.nanoTime()}")
        val poller=TaskComputerPoller(context,profile)
        val visited=mutableListOf<String>();var failNext=true
        val read:suspend(String,JSONObject)->JSONObject={action,args->
            if(action=="threads.list")JSONObject().put("threads",JSONArray().also{rows->repeat(65){i->rows.put(JSONObject().put("id","chat-$i").put("status","active").put("updatedAt","1"))}})
            else {
                val id=args.getString("threadId")
                if(id=="chat-30"&&failNext){failNext=false;throw IllegalStateException("isolated temporary failure")}
                visited+=id;JSONObject()
            }
        }
        poller.poll(read);assertEquals(30,visited.distinct().size)
        repeat(3){poller.poll(read)}
        assertEquals((0 until 65).map{"chat-$it"}.toSet(),visited.toSet())
    }
    @Test fun permanentlyUnreadableChatDoesNotBlockOtherApprovals()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val profile=JSONObject().put("relay","https://poller.invalid").put("pin","b".repeat(64)).put("hostId","poller-${System.nanoTime()}")
        val poller=TaskComputerPoller(context,profile);val visited=mutableSetOf<String>()
        repeat(3){poller.poll({action,args->
            if(action=="threads.list")JSONObject().put("threads",JSONArray().also{rows->repeat(65){i->rows.put(JSONObject().put("id","chat-$i").put("status","active"))}})
            else {val id=args.getString("threadId");if(id=="chat-0")throw IllegalStateException("chat no longer readable");visited+=id;JSONObject()}
        })}
        assertEquals((1 until 65).map{"chat-$it"}.toSet(),visited)
    }
}
