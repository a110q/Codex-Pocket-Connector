package dev.codexpocket.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

data class AccountAuthorization(val userId:String,val token:String,val hostId:String?,val relay:String,val pin:String,val hostIds:Set<String> = setOfNotNull(hostId))
class AccountException(val status:Int,message:String):Exception(message)

class AccountApi(val defaults:JSONObject) {
    private val client=PocketConnection.httpClient(defaults.getString("pin"))
    fun post(path:String,body:JSONObject=JSONObject(),token:String?=null):JSONObject {
        require(path.startsWith("/api/account/")||path.startsWith("/api/admin/"))
        val request=Request.Builder().url(defaults.getString("relay").trimEnd('/')+path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply{token?.takeIf{it.isNotBlank()}?.let{header("Authorization","Bearer $it")}}.build()
        client.newCall(request).execute().use { response->
            val json=JSONObject(response.body?.string()?:"{}")
            if(!response.isSuccessful)throw AccountException(response.code,json.optString("error","请求失败 ${response.code}"))
            return json
        }
    }
    companion object {
        fun bundled(context:Context)=JSONObject(context.assets.open("server.json").bufferedReader().use{it.readText()})
        fun defaults(context:Context)=try{SecureStore(context,"pocket-service.enc").load().optJSONObject("selected")?:bundled(context)}catch(_:Exception){bundled(context)}
        fun same(a:JSONObject,b:JSONObject)=a.getString("relay").trimEnd('/')==b.getString("relay").trimEnd('/')&&a.getString("pin")==b.getString("pin")
        fun suffix(context:Context,defaults:JSONObject=defaults(context))=if(same(defaults,bundled(context)))"" else "-"+MessageDigest.getInstance("SHA-256").digest((defaults.getString("relay").trimEnd('/')+"|"+defaults.getString("pin")).toByteArray()).joinToString(""){"%02x".format(it)}.take(24)
        fun select(context:Context,server:JSONObject){SecureStore(context,"pocket-service.enc").save(JSONObject().put("selected",JSONObject().put("relay",server.getString("relay").trimEnd('/')).put("pin",server.getString("pin")).put("signingSha256",bundled(context).optString("signingSha256"))))}
    }
}

class AccountModel(private val context:Context,val api:AccountApi=AccountApi(AccountApi.defaults(context)),private val onAccess:(AccountAuthorization?)->Unit={}) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val store=SecureStore(context,"pocket-account${AccountApi.suffix(context,api.defaults)}.enc")
    private var saved=try{store.load()}catch(_:Exception){JSONObject()}
    private var generation=0
    private var polling:Job?=null
    private var foreground=false
    private var granted:AccountAuthorization?=null
    private var integrityFingerprint:String?=null
    private var integrityReportedAt=0L
    private var token by mutableStateOf<String?>(saved.optString("token").takeIf{it.isNotBlank()})
    private val deviceId=saved.optString("deviceId").takeIf{it.isNotBlank()}?:Encoding.secret().also{saved.put("deviceId",it);store.save(saved)}
    var user by mutableStateOf(saved.optJSONObject("user"));private set
    var verified by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var users by mutableStateOf<List<JSONObject>>(emptyList());private set
    var events by mutableStateOf<List<JSONObject>>(emptyList());private set
    var pending by mutableIntStateOf(0);private set
    var alerts by mutableIntStateOf(0);private set
    var hostId:String?=null;private set
    var hosts by mutableStateOf<List<JSONObject>>(emptyList());private set
    val loggedIn get()=token!=null
    val canUse get()=loggedIn&&verified&&user?.optString("accessState")=="active"
    val isAdmin get()=canUse&&user?.optString("role")=="admin"
    private fun persist(){saved.put("token",token?:JSONObject.NULL).put("user",user?:JSONObject.NULL);store.save(saved)}
    private fun grant(){
        val next=if(canUse)AccountAuthorization(user!!.getString("id"),token!!,hostId,api.defaults.getString("relay"),api.defaults.getString("pin"),hosts.map{it.getString("id")}.toSet())else null
        if(next!=granted){granted=next;onAccess(next)}
    }
    fun close(){generation++;stop();scope.cancel()}
    fun start(){foreground=true;polling?.cancel();polling=scope.launch{while(isActive&&foreground){if(!busy&&token!=null)refreshNow();delay(15000)}}}
    fun stop(){foreground=false;polling?.cancel();polling=null;verified=false;grant()}
    fun refresh(){if(!busy)scope.launch{busy=true;try{refreshNow()}finally{busy=false}}}
    private suspend fun refreshNow(){
        val current=token?:return;val epoch=generation
        try {
            val result=withContext(Dispatchers.IO){api.post("/api/account/me",token=current)}
            if(epoch!=generation)return
            user=result.getJSONObject("user");hostId=result.optJSONObject("host")?.optString("id");hosts=if(result.has("hosts"))result.optJSONArray("hosts").objects()else listOfNotNull(result.optJSONObject("host"));verified=true;error=null;persist();grant()
            if(isAdmin){
                val lists=withContext(Dispatchers.IO){api.post("/api/admin/users",token=current) to api.post("/api/admin/events",token=current)}
                if(epoch!=generation)return
                users=lists.first.optJSONArray("users").objects();events=lists.second.optJSONArray("events").objects();pending=lists.second.optInt("pending");alerts=lists.second.optInt("alerts")
                AccountNotifications.show(context,events)
                AccountNotifications.schedule(context)
            }
            reportIntegrity()
        } catch(e:CancellationException){throw e}
        catch(e:Exception){
            if(epoch!=generation)return
            verified=false;error=e.message?:"无法验证账号状态"
            if(e is AccountException&&e.status==401){token=null;user=null;persist();AccountNotifications.cancel(context)}
            grant()
        }
    }
    fun login(username:String,password:String){
        if(busy)return;busy=true;error=null;val epoch=++generation
        scope.launch{try{
            val result=withContext(Dispatchers.IO){api.post("/api/account/login",JSONObject().put("username",username.trim()).put("password",password).put("client","android").put("deviceId",deviceId).put("deviceName","${Build.MANUFACTURER} ${Build.MODEL}"))}
            if(epoch==generation){token=result.getString("token");user=result.getJSONObject("user");integrityFingerprint=null;persist();refreshNow()}
        }catch(e:Exception){if(epoch==generation)error=e.message?:"登录失败"}finally{if(epoch==generation)busy=false}}
    }
    fun register(username:String,password:String,displayName:String,reason:String){
        if(busy)return;busy=true;error=null;val epoch=generation
        scope.launch{try{withContext(Dispatchers.IO){api.post("/api/account/register",JSONObject().put("username",username.trim()).put("password",password).put("displayName",displayName.trim()).put("reason",reason.trim()))};if(epoch==generation)notice="申请已提交，请登录查看审批进度"}catch(e:Exception){if(epoch==generation)error=e.message?:"申请失败"}finally{if(epoch==generation)busy=false}}
    }
    private suspend fun reportIntegrity(){
        if(BuildConfig.DEBUG)return
        val current=token?:return
        try{
            val report=AppIntegrity.report(context,api.defaults.optString("signingSha256"));val fingerprint=report.toString();val now=System.currentTimeMillis()
            if(fingerprint==integrityFingerprint&&now-integrityReportedAt<86400000)return
            withContext(Dispatchers.IO){api.post("/api/account/security/report",report,current)}
            if(current==token){integrityFingerprint=fingerprint;integrityReportedAt=now}
        }catch(e:CancellationException){throw e}catch(_:Exception){/* Retry after a verified check; delivery has not been confirmed. */}
    }
    private fun operation(path:String,body:JSONObject,done:()->Unit={}){
        val current=token?:return;if(busy)return;busy=true;error=null;val epoch=generation
        scope.launch{try{withContext(Dispatchers.IO){api.post(path,body,current)};if(epoch==generation){done();refreshNow()}}catch(e:Exception){if(epoch==generation)error=e.message?:"操作失败"}finally{if(epoch==generation)busy=false}}
    }
    fun apply(reason:String)=operation("/api/account/apply",JSONObject().put("reason",reason))
    fun decision(id:String,action:String,days:Int=30,note:String="")=operation("/api/admin/users/decision",JSONObject().put("userId",id).put("action",action).put("days",days).put("note",note))
    fun create(username:String,password:String,displayName:String,days:Int)=operation("/api/admin/users/create",JSONObject().put("username",username).put("password",password).put("displayName",displayName).put("days",days))
    fun removePhone(id:String)=operation("/api/admin/devices/remove",JSONObject().put("userId",id).put("device","phone"))
    fun removeComputer(id:String,hostId:String){
        if(hostId.isBlank()){error="请选择要移除的电脑";return}
        operation("/api/admin/devices/remove",JSONObject().put("userId",id).put("device","computer").put("hostId",hostId)){notice="已移除所选电脑"}
    }
    fun computerLimit(id:String,limit:Int)=operation("/api/admin/users/computers",JSONObject().put("userId",id).put("limit",limit))
    fun deleteAccount(id:String,username:String)=operation("/api/admin/users/delete",JSONObject().put("userId",id).put("confirmUsername",username)){users=users.filterNot{it.optString("id")==id};notice="账号 $username 已删除"}
    fun resetPassword(id:String,password:String)=operation("/api/admin/password/reset",JSONObject().put("userId",id).put("password",password))
    fun acknowledge(id:String)=operation("/api/admin/events/ack",JSONObject().put("id",id))
    fun changePassword(old:String,next:String)=operation("/api/account/password",JSONObject().put("currentPassword",old).put("password",next)){notice="登录密码已修改，其他设备需要重新登录"}
    fun logout(){
        val current=token;generation++;token=null;user=null;verified=false;busy=false;error=null;notice=null;users=emptyList();events=emptyList();pending=0;alerts=0;hostId=null;hosts=emptyList();persist();grant();AccountNotifications.cancel(context)
        if(current!=null)scope.launch{try{withContext(Dispatchers.IO){api.post("/api/account/logout",token=current)}}catch(_:Exception){}}
    }
}

object AppIntegrity {
    fun report(context:Context,expected:String):JSONObject {
        val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures=info.signingInfo?.apkContentsSigners.orEmpty()
        val certificate=signatures.firstOrNull()?.let{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){byte->"%02x".format(byte)}}.orEmpty()
        val signals=JSONArray()
        if(expected.isNotBlank()&&certificate!=expected)signals.put("signature_mismatch")
        if(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)signals.put("debuggable_release")
        if(Debug.isDebuggerConnected()||Debug.waitingForDebugger())signals.put("debugger_attached")
        return JSONObject().put("certificate",certificate).put("signals",signals).put("version",BuildConfig.VERSION_NAME)
    }
}
