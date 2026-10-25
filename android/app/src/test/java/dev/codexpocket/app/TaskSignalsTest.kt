package dev.codexpocket.app
import org.junit.Assert.*
import org.junit.Test
class TaskSignalsTest {
 private fun signal(turn:String="t",active:Boolean=false,outcome:String="completed",requests:Set<String> = emptySet())=TaskSignal("chat","Title",turn,active,outcome,requests)
 @Test fun oldHistoryDoesNotNotifyAndCompletionIsDeduplicated(){val tracker=TaskSignalTracker();assertTrue(tracker.observe(signal()).isEmpty());tracker.observe(signal("next",true,"inProgress"));assertEquals("completed",tracker.observe(signal("next")).single().kind);assertTrue(tracker.observe(signal("next")).isEmpty())}
 @Test fun approvalsNotifyOnceAcrossReconnectAndForegroundSeed(){val tracker=TaskSignalTracker();val pending=signal(active=true,requests=setOf("p"));assertEquals("approval",tracker.observe(pending).single().kind);assertTrue(tracker.observe(pending).isEmpty());val restored=TaskSignalTracker(tracker.states.toMutableMap());assertTrue(restored.observe(pending).isEmpty());assertEquals("approval",restored.observe(pending.copy(requests=setOf("p","m"))).single().kind);val foreground=TaskSignalTracker();foreground.observe(pending,false);assertTrue(foreground.observe(pending).isEmpty())}
 @Test fun failuresInterruptedTasksAndTurnsCompletedBetweenPolls(){val tracker=TaskSignalTracker();assertTrue(tracker.observe(signal(outcome="failed")).isEmpty());tracker.observe(signal("new",true,"inProgress"));assertEquals("failed",tracker.observe(signal("new",outcome="failed")).single().kind);assertTrue(tracker.observe(signal("new",outcome="failed")).isEmpty());tracker.observe(signal("stop",true,"inProgress"));assertTrue(tracker.observe(signal("stop",outcome="interrupted")).isEmpty());assertEquals("completed",tracker.observe(signal("fast")).single().kind)}
}
