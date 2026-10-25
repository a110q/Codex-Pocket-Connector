package dev.codexpocket.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TaskNotifications {
    const val CHANNEL="task_results"
    fun enabled(context:Context)=context.getSharedPreferences("task-alerts",Context.MODE_PRIVATE).getBoolean("enabled",false)
    fun allowed(context:Context)=Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    fun setEnabled(context:Context,value:Boolean) {
        context.getSharedPreferences("task-alerts",Context.MODE_PRIVATE).edit().putBoolean("enabled",value).apply()
        if(value)ContextCompat.startForegroundService(context,Intent(context,TaskMonitorService::class.java))
        else context.stopService(Intent(context,TaskMonitorService::class.java))
    }
    fun namespace(profile:JSONObject)=profile.optString("relay").trimEnd('/')+"|"+profile.optString("pin")+"|"+profile.optString("hostId")
    private fun key(profile:JSONObject)=Encoding.hex(java.security.MessageDigest.getInstance("SHA-256").digest(namespace(profile).toByteArray())).take(32)
    fun observe(context:Context,profile:JSONObject,snapshot:JSONObject,notify:Boolean) {
        if(!enabled(context))return
        val thread=snapshot.optJSONObject("thread")?:return
        val messages=snapshot.optJSONArray("messages").objects()
        val active=thread.optString("activeTurnId").takeIf{it.isNotBlank()&&it!="null"}
        val turn=thread.optString("lastTurnId").takeIf{it.isNotBlank()&&it!="null"}?:active?:messages.lastOrNull()?.optString("turnId").orEmpty()
        val last=thread.optString("lastTurnStatus").takeIf{it.isNotBlank()&&it!="null"}?:if(active!=null)"inProgress"else if(messages.any{it.optString("turnId")==turn&&it.optString("type")=="turnError"})"failed"else "completed"
        val signal=TaskSignal(thread.optString("id"),thread.label(),turn,active!=null,last,snapshot.optJSONArray("requests").objects().filter{!it.optBoolean("completed")}.map{it.optString("method")+":"+it.optString("id")}.toSet())
        val store=SecureStore(context,"task-signals-${key(profile)}.enc");val saved=store.load()
        val states=saved.keys().asSequence().associateWith { id->val state=saved.getJSONObject(id);TaskSignalState(state.optString("turn"),state.optBoolean("active"),state.optString("outcome"),state.optJSONArray("requests")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}.orEmpty()) }.toMutableMap()
        val tracker=TaskSignalTracker(states);val alerts=tracker.observe(signal,notify)
        store.save(JSONObject().also{data->tracker.states.forEach{(id,state)->data.put(id,JSONObject().put("turn",state.turnId).put("active",state.active).put("outcome",state.outcome).put("requests",JSONArray(state.requests.toList())))}})
        alerts.forEach{show(context,profile,it)}
    }
    fun tracked(context:Context,profile:JSONObject):Set<String> {
        val data=SecureStore(context,"task-signals-${key(profile)}.enc").load()
        return data.keys().asSequence().filter{data.optJSONObject(it)?.let{v->v.optBoolean("active")||v.optJSONArray("requests")?.length()?.let{n->n>0}==true}==true}.toSet()
    }
    private fun show(context:Context,profile:JSONObject,alert:TaskAlert) {
        if(!allowed(context))return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"任务完成与待处理提醒",NotificationManager.IMPORTANCE_DEFAULT))
        val identity=namespace(profile)+alert.threadId+alert.key
        val intent=Intent(context,MainActivity::class.java).putExtra("taskHostId",profile.optString("hostId")).putExtra("taskThreadId",alert.threadId).putExtra("taskLan",PocketConnection.isLanPairing(profile)).putExtra("taskRelay",profile.optString("relay")).putExtra("taskPin",profile.optString("pin")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,identity.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=when(alert.kind){"approval"->"Codex 等待你的操作";"failed"->"Codex 任务出错";else->"Codex 任务已完成"}
        manager.notify(identity.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("${profile.computerName()} · ${alert.title}").setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}

// A computer has exactly one mobile socket. The visible workspace claims that
// socket synchronously before connecting; the monitor only owns other computers.
// Switching workspaces preserves every unrelated monitoring connection.
class TaskMonitorService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val watchers=linkedMapOf<String,Watcher>()
    private var job:Job?=null
    companion object {
        private var instance:TaskMonitorService?=null
        private var uiVisible=false
        private var foregroundSelection:String?=null
        private val uiOwners=java.util.IdentityHashMap<Any,String>()
        private fun reserved()=uiOwners.values.toSet()+listOfNotNull(foregroundSelection.takeIf{uiVisible})
        private fun ensure(context:Context) {
            if(TaskNotifications.enabled(context)&&TaskNotifications.allowed(context))try{ContextCompat.startForegroundService(context,Intent(context,TaskMonitorService::class.java))}catch(_:Exception){}
        }
        fun claimUi(context:Context,owner:Any,profile:JSONObject) {
            val id=TaskNotifications.namespace(profile)
            if(uiOwners[owner]==id)return
            uiOwners[owner]=id
            if(uiVisible)foregroundSelection=id
            instance?.removeReserved() // Close the old monitor before UI opens a socket.
            instance?.refresh();ensure(context)
        }
        fun releaseUi(context:Context,owner:Any) {
            val id=uiOwners.remove(owner)?:return
            if(foregroundSelection==id)foregroundSelection=null
            instance?.refresh()
        }
        fun configurationChanged(context:Context){instance?.refresh();ensure(context)}
        fun foreground(context:Context) {
            uiVisible=true
            foregroundSelection=try{
                val local=context.getSharedPreferences("connection-mode",MODE_PRIVATE).getBoolean("lan",false)
                SecureStore(context,if(local)"pocket-lan-state.enc"else"pocket-state${AccountApi.suffix(context)}.enc").load().optJSONObject("profile")?.let{TaskNotifications.namespace(it)}
            }catch(_:Exception){null}
            instance?.removeReserved();instance?.refresh();ensure(context)
        }
        fun background(context:Context) {uiVisible=false;foregroundSelection=null;instance?.refresh()}
    }
    override fun onCreate(){super.onCreate();instance=this}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {TaskNotifications.setEnabled(this,false);return START_NOT_STICKY}
        if(!TaskNotifications.enabled(this)||!TaskNotifications.allowed(this)){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("task_connection","多电脑任务连接",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TaskMonitorService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(7101,NotificationCompat.Builder(this,"task_connection").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Codex Pocket 多电脑提醒已开启").setContentText("切换电脑或锁屏后，继续接收任务与审批提醒").setContentIntent(open).setOngoing(true).addAction(0,"关闭提醒",stop).build())
        monitor()
        return START_STICKY
    }
    private fun removeReserved(){val keys=reserved();watchers.keys.filter{it in keys}.forEach{watchers.remove(it)?.close()}}
    private fun refresh(){job?.cancel();job=null;removeReserved();monitor()}
    private suspend fun profiles():List<JSONObject> {
        val lan=SecureStore(this,"pocket-lan-state.enc").load().let{saved->saved.optJSONArray("computers").objects().ifEmpty{listOfNotNull(saved.optJSONObject("profile"))}}.filter{it.optBoolean("approved")&&PocketConnection.isLanPairing(it)}
        val saved=SecureStore(this,"pocket-state${AccountApi.suffix(this)}.enc").load()
        val values=saved.optJSONArray("computers").objects().ifEmpty{listOfNotNull(saved.optJSONObject("profile"))}.filter{it.optBoolean("approved")&&!PocketConnection.isLanPairing(it)}
        if(values.isEmpty())return lan
        val api=AccountApi(AccountApi.defaults(this));val account=SecureStore(this,"pocket-account${AccountApi.suffix(this)}.enc").load();val token=account.optString("token")
        if(token.isBlank()||token=="null"||account.optJSONObject("user")?.optString("accessState")!="active")return lan
        val me=try{withContext(Dispatchers.IO){api.post("/api/account/me",token=token)}}catch(e:CancellationException){throw e}catch(e:Exception){
            if(e is AccountException&&(e.status==401||e.status==403))return lan
            // Cloud outages must not prevent new LAN computers from being watched.
            // Retain only existing cloud watchers for this same account and service.
            return lan+values.mapNotNull { next ->
                watchers.values.map { it.profile }.find { previous ->
                    previous.optString("accountToken")==token && AccountApi.same(previous,api.defaults) &&
                        TaskNotifications.namespace(next)==TaskNotifications.namespace(previous) &&
                        listOf("mobileToken","key").all { field -> next.optString(field)==previous.optString(field) }
                }?.let { JSONObject(next.toString()).put("accountToken",token) }
            }
        }
        if(me.optJSONObject("user")?.optString("accessState")!="active")return lan
        val hosts=me.optJSONArray("hosts").objects().map{it.optString("id")}.toSet()+listOfNotNull(me.optJSONObject("host")?.optString("id"))
        return lan+values.filter{AccountApi.same(it,api.defaults)&&it.optString("hostId") in hosts}.map{JSONObject(it.toString()).put("accountToken",token)}
    }
    private fun reconcile(profiles:List<JSONObject>) {
        val allowed=profiles.distinctBy{TaskNotifications.namespace(it)}.filter{TaskNotifications.namespace(it) !in reserved()}.associateBy{TaskNotifications.namespace(it)}
        watchers.keys.filter{it !in allowed||!watchers.getValue(it).matches(allowed.getValue(it))}.forEach{watchers.remove(it)?.close()}
        allowed.forEach{(id,profile)->if(id !in watchers)watchers[id]=Watcher(profile)else watchers.getValue(id).updateName(profile)}
    }
    private fun monitor() {
        if(job?.isActive==true||!TaskNotifications.enabled(this))return
        job=scope.launch {
            while(isActive) {
                try {
                    reconcile(profiles())
                    repeat(4) {
                        if(!isActive)return@launch
                        removeReserved()
                        coroutineScope{watchers.values.toList().map{watcher->async{watcher.poll()}}.awaitAll()}
                        delay(15000)
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){delay(15000)}
            }
        }
    }
    private inner class Watcher(val profile:JSONObject) {
        fun updateName(next:JSONObject){profile.put("name",next.optString("name"));if(next.has("alias"))profile.put("alias",next.get("alias"))else profile.remove("alias")}
        private var connection:PocketConnection?=null
        private var retry=false
        private var valid=true
        private val poller=TaskComputerPoller(this@TaskMonitorService,profile)
        init{connect()}
        fun matches(next:JSONObject)=listOf("mobileToken","key","accountToken").all{profile.optString(it)==next.optString(it)}
        private fun allowed()=valid&&TaskNotifications.namespace(profile) !in reserved()
        private fun connect() {
            if(!allowed())return
            connection?.close();retry=false
            lateinit var candidate:PocketConnection
            candidate=PocketConnection(profile,{state->handler.post{if(connection===candidate)retry=state.retry}},{event->handler.post{
                if(allowed()&&connection===candidate&&event.optString("type")=="event")event.optJSONObject("event")?.takeIf{it.optString("type")=="thread.updated"}?.optJSONObject("data")?.let{snapshot->try{TaskNotifications.observe(this@TaskMonitorService,profile,snapshot,true)}catch(_:Exception){}}
            }})
            connection=candidate;candidate.connect()
        }
        private suspend fun read(action:String,args:JSONObject):JSONObject=suspendCancellableCoroutine{continuation->
            val channel=connection
            if(channel==null){continuation.resumeWithException(IllegalStateException("离线"));return@suspendCancellableCoroutine}
            channel.request(action,args){result,error->if(continuation.isActive){if(error!=null)continuation.resumeWithException(IllegalStateException(error))else continuation.resume(result?:JSONObject())}}
        }
        suspend fun poll() {
            if(!allowed())return
            if(retry){connect();return}
            if(connection?.ready!=true)return
            try{poller.poll(::read,canRead={allowed()})}catch(e:CancellationException){throw e}catch(_:Exception){}
        }
        fun close(){valid=false;connection?.close();connection=null}
    }
    override fun onDestroy(){job?.cancel();watchers.values.forEach{it.close()};watchers.clear();scope.cancel();if(instance===this)instance=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}

// Reuse the foreground workspace socket for other chats on that computer.
// The service uses the same reader for the remaining paired computers.
internal class TaskComputerPoller(private val context:Context,private val profile:JSONObject) {
    private val seen=mutableMapOf<String,String>()
    private val pending=linkedMapOf<String,JSONObject>()
    suspend fun poll(read:suspend(String,JSONObject)->JSONObject,skipThread:()->String?={null},canRead:()->Boolean={true}) {
        if(!canRead())return
        val threads=read("threads.list",JSONObject()).optJSONArray("threads").objects()
        val tracked=TaskNotifications.tracked(context,profile)
        val candidates=threads.filter{t->val id=t.optString("id");id in tracked||t.optString("status") in listOf("active","running","inProgress","waitingForApproval","waitingForUserInput","systemError")||(seen.containsKey(id)&&seen[id]!=t.optString("updatedAt"))}.toMutableList()
        if(seen.isEmpty())candidates+=threads.take(6)
        threads.forEach{seen[it.optString("id")]=it.optString("updatedAt")}
        val listed=threads.map{it.optString("id")}.toSet()
        pending.keys.retainAll(listed)
        candidates.forEach{pending[it.optString("id")]=it}
        // Keep unvisited chats queued, so a busy computer with more than 30
        // running chats cannot permanently starve the later ones.
        for((id,thread) in pending.toList().take(30)) {
            if(!canRead())break
            if(id==skipThread()){pending.remove(id);continue}
            try {
                val snapshot=read("thread.read",JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")))
                if(canRead())TaskNotifications.observe(context,profile,snapshot,true)
                pending.remove(id)
            }catch(e:CancellationException){throw e}catch(_:Exception){
                // A removed or unreadable chat must not block every other chat.
                pending.remove(id);pending[id]=thread
            }
        }
    }
}
