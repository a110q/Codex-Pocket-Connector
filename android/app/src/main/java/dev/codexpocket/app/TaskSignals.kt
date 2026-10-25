package dev.codexpocket.app

data class TaskSignal(val threadId:String,val title:String,val turnId:String,val active:Boolean,val outcome:String,val requests:Set<String>)
data class TaskSignalState(val turnId:String,val active:Boolean,val outcome:String,val requests:Set<String>)
data class TaskAlert(val kind:String,val threadId:String,val title:String,val key:String)

// Pure transition logic: opening an old completed chat must never create a
// completion notification. Reconnecting to the same approval must not repeat it.
class TaskSignalTracker(val states:MutableMap<String,TaskSignalState> = mutableMapOf()) {
    fun observe(signal:TaskSignal,notify:Boolean=true):List<TaskAlert> {
        val old=states[signal.threadId];val result=mutableListOf<TaskAlert>()
        if(notify) {
            val fresh=signal.requests-(if(old?.turnId==signal.turnId)old.requests else emptySet())
            if(fresh.isNotEmpty())result+=TaskAlert("approval",signal.threadId,signal.title,"${signal.turnId}:approval:${fresh.sorted().joinToString()}")
            if(old!=null&&!signal.active&&signal.turnId.isNotBlank()&&signal.requests.isEmpty()) {
                if(signal.outcome=="failed"&&(old.turnId!=signal.turnId||old.outcome!="failed"))result+=TaskAlert("failed",signal.threadId,signal.title,"${signal.turnId}:failed")
                else if((old.active||old.turnId!=signal.turnId)&&signal.outcome=="completed")result+=TaskAlert("completed",signal.threadId,signal.title,"${signal.turnId}:completed")
            }
        }
        states.remove(signal.threadId);states[signal.threadId]=TaskSignalState(signal.turnId,signal.active,signal.outcome,signal.requests)
        while(states.size>300)states.remove(states.keys.first())
        return result
    }
}
