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
        val intent=Intent(context,MainActivity::class.java).putExtra("taskHostId",profile.optString("hostId")).putExtra("taskThreadId",alert.threadId).putExtra("taskLan",PocketConnection.isLanPairing(profile)).putExtra("taskRelay",profile.optString("relay")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,identity.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=when(alert.kind){"approval"->"Codex 等待你的操作";"failed"->"Codex 任务出错";else->"Codex 任务已完成"}
        manager.notify(identity.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText("${profile.optString("name","电脑")} · ${alert.title}").setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}

// One socket per computer: UI sockets close before background monitoring starts,
// and all monitor sockets close synchronously before the Activity reconnects.
// This uses the existing end-to-end encrypted relay and requires no cloud push
// account, new relay API, or Google Play services.
class TaskMonitorService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val watchers=mutableListOf<Watcher>()
    private var job:Job?=null
    companion object {
        private var instance:TaskMonitorService?=null
        private var uiVisible=false
        fun foreground(context:Context) {
            uiVisible=true;instance?.pause()
            if(TaskNotifications.enabled(context))try{ContextCompat.startForegroundService(context,Intent(context,TaskMonitorService::class.java))}catch(_:Exception){}
        }
        fun background(context:Context) {uiVisible=false;instance?.monitor()}
    }
    override fun onCreate(){super.onCreate();instance=this}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {TaskNotifications.setEnabled(this,false);return START_NOT_STICKY}
        if(!TaskNotifications.enabled(this)||!TaskNotifications.allowed(this)){stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("task_connection","后台任务连接",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TaskMonitorService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(7101,NotificationCompat.Builder(this,"task_connection").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Codex Pocket 任务提醒已开启").setContentText("保持后台连接，接收任务结果与审批提醒").setContentIntent(open).setOngoing(true).addAction(0,"关闭提醒",stop).build())
        if(!uiVisible)monitor()
        return START_STICKY
    }
    private fun pause(){job?.cancel();job=null;watchers.forEach{it.close()};watchers.clear()}
    private suspend fun profiles():List<JSONObject> {
        val local=getSharedPreferences("connection-mode",MODE_PRIVATE).getBoolean("lan",false)
        val saved=SecureStore(this,if(local)"pocket-lan-state.enc"else"pocket-state${AccountApi.suffix(this)}.enc").load()
        val values=saved.optJSONArray("computers").objects().ifEmpty{listOfNotNull(saved.optJSONObject("profile"))}.filter{it.optBoolean("approved")}
        if(local)return values.filter{PocketConnection.isLanPairing(it)}
        val api=AccountApi(AccountApi.defaults(this));val token=SecureStore(this,"pocket-account${AccountApi.suffix(this)}.enc").load().optString("token")
        if(token.isBlank())return emptyList()
        val me=withContext(Dispatchers.IO){api.post("/api/account/me",token=token)}
        if(me.optJSONObject("user")?.optString("accessState")!="active")return emptyList()
        val hosts=me.optJSONArray("hosts").objects().map{it.optString("id")}.toSet()+listOfNotNull(me.optJSONObject("host")?.optString("id"))
        return values.filter{!PocketConnection.isLanPairing(it)&&AccountApi.same(it,api.defaults)&&it.optString("hostId") in hosts}.map{JSONObject(it.toString()).put("accountToken",token)}
    }
    private fun monitor() {
        if(uiVisible||job?.isActive==true||!TaskNotifications.enabled(this))return
        job=scope.launch {
            while(isActive&&!uiVisible) {
                try {
                    val allowed=profiles();val current=watchers.map{TaskNotifications.namespace(it.profile)}.toSet()
                    if(current!=allowed.map{TaskNotifications.namespace(it)}.toSet()) {watchers.forEach{it.close()};watchers.clear();allowed.forEach{watchers+=Watcher(it)}}
                    repeat(4) { if(!isActive||uiVisible)return@launch;watchers.toList().forEach{it.poll()};delay(15000) }
                }catch(e:CancellationException){throw e}catch(e:Exception){if(e is AccountException&&(e.status==401||e.status==403)){watchers.forEach{it.close()};watchers.clear()};delay(15000)}
            }
        }
    }
    private inner class Watcher(val profile:JSONObject) {
        private var connection:PocketConnection?=null
        private var retry=false
        private var valid=true
        private val seen=mutableMapOf<String,String>()
        init{connect()}
        private fun connect() {
            connection?.close();retry=false
            lateinit var candidate:PocketConnection
            candidate=PocketConnection(profile,{state->handler.post{if(connection===candidate)retry=state.retry}},{event->handler.post{
                if(valid&&connection===candidate&&!uiVisible&&event.optString("type")=="event")event.optJSONObject("event")?.takeIf{it.optString("type")=="thread.updated"}?.optJSONObject("data")?.let{snapshot->try{TaskNotifications.observe(this@TaskMonitorService,profile,snapshot,true)}catch(_:Exception){}}
            }})
            connection=candidate;candidate.connect()
        }
        private suspend fun read(action:String,args:JSONObject=JSONObject()):JSONObject=suspendCancellableCoroutine{continuation->
            val channel=connection
            if(channel==null){continuation.resumeWithException(IllegalStateException("离线"));return@suspendCancellableCoroutine}
            channel.request(action,args){result,error->if(continuation.isActive){if(error!=null)continuation.resumeWithException(IllegalStateException(error))else continuation.resume(result?:JSONObject())}}
        }
        suspend fun poll() {
            if(!valid)return
            if(retry){connect();return}
            if(connection?.ready!=true)return
            try {
                val threads=read("threads.list").optJSONArray("threads").objects()
                val tracked=TaskNotifications.tracked(this@TaskMonitorService,profile)
                val candidates=threads.filter{t->val id=t.optString("id");val version=t.optString("updatedAt");id in tracked||t.optString("status") in listOf("active","running","inProgress","waitingForApproval","waitingForUserInput","systemError")||(seen.containsKey(id)&&seen[id]!=version)}.toMutableList()
                if(seen.isEmpty())candidates+=threads.take(6)
                threads.forEach{seen[it.optString("id")]=it.optString("updatedAt")}
                for(thread in candidates.distinctBy{it.optString("id")}.take(30)) {
                    if(!valid||uiVisible)break
                    val snapshot=read("thread.read",JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")))
                    TaskNotifications.observe(this@TaskMonitorService,profile,snapshot,true)
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){}
        }
        fun close(){valid=false;connection?.close();connection=null}
    }
    override fun onDestroy(){pause();scope.cancel();if(instance===this)instance=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
