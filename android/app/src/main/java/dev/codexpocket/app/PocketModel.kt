package dev.codexpocket.app

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.label(): String = optString("title").ifBlank { optString("label").ifBlank { "未命名会话" } }
fun JSONObject.computerName():String=optString("alias").takeIf{it.isNotBlank()&&it!="null"}?:optString("name").takeIf{it.isNotBlank()&&it!="null"}?:"我的电脑"

class PocketModel(private val context: Context,filename:String="pocket-state.enc",private val requireLan:Boolean=false) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val store = SecureStore(context,filename)
    private var saved = try { store.load() } catch (_: Exception) { JSONObject() }
    var profile by mutableStateOf(saved.optJSONObject("profile")); private set
    var computers by mutableStateOf(saved.optJSONArray("computers").objects().ifEmpty{listOfNotNull(profile)});private set
    private var computerStates=(saved.optJSONObject("computerStates")?:JSONObject()).also { states ->
        // Older snapshots can contain already-merged placeholder duplicates.
        // Reload only this derived cache; keep pairings, drafts and operations.
        if (saved.optInt("timelineFormat") < 3) {
            saved.remove("snapshot")
            states.keys().forEach { states.optJSONObject(it)?.remove("snapshot") }
        }
    }
    private var pendingScannedPair=saved.optString("pendingScan").takeIf{it.isNotBlank()&&it!="null"}
    var status by mutableStateOf(if (profile == null) "尚未连接电脑" else "等待连接"); private set
    var connected by mutableStateOf(false); private set
    var threads by mutableStateOf(saved.optJSONArray("threads").objects()); private set
    var projects by mutableStateOf<List<JSONObject>>(saved.optJSONArray("projects").objects()); private set
    var models by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var snapshot by mutableStateOf(saved.optJSONObject("snapshot")); private set
    var selected by mutableStateOf<JSONObject?>(null); private set
    private val threadBackStack = mutableListOf<Pair<JSONObject,JSONObject?>>()
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var projectSearch by mutableStateOf("")
    var taskSearch by mutableStateOf("")
    var showArchived by mutableStateOf(false);private set
    var scrollRequest by mutableIntStateOf(0); private set
    var collapsedProjects by mutableStateOf(setOf<String>())
    var busy by mutableStateOf(false); private set
    var pairing by mutableStateOf(profile != null && profile?.optBoolean("approved") != true); private set
    var pendingOperation by mutableStateOf(saved.optJSONObject("pending")); private set
    var pendingStatus by mutableStateOf(""); private set
    private var computerGeneration=0
    private var supportsReceipts=false
    private var receiptPolling:Job?=null
    private var pendingCompletion:Pair<String,(JSONObject)->Unit>?=null
    val canSwitchComputer get()=!busy||pendingOperation!=null
    var appearance by mutableStateOf(saved.optString("appearance", "system")); private set
    private var drafts by mutableStateOf(DraftBook(saved.optJSONObject("drafts")?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } }.orEmpty()))
    var draft: String
        get() = selected?.let { drafts.text(it.optString("hostId", "local"), it.optString("id")) }.orEmpty()
        set(text) { selected?.let { drafts = drafts.withDraft(it.optString("hostId", "local"), it.optString("id"), text); persistSoon() } }
    init {
        val legacy = saved.optString("draft")
        val thread = snapshot?.optJSONObject("thread")
        if (legacy.isNotEmpty() && thread != null && thread.optString("id").isNotBlank()) {
            drafts = drafts.withDraft(thread.optString("hostId", "local"), thread.optString("id"), legacy)
        }
    }
    private var referenceDrafts by mutableStateOf(saved.optJSONObject("referenceDrafts")?.let{obj->obj.keys().asSequence().associateWith{key->obj.optJSONArray(key)?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()}}.orEmpty())
    fun referencesFor(key:String=imageDraftKey)=referenceDrafts[key].orEmpty()
    fun addReference(path:String){val key=imageDraftKey;if(referencesFor(key).size>=20){error="每条消息最多引用 20 个文件";return};referenceDrafts=referenceDrafts+(key to (referencesFor(key)+path).distinct());if(!draft.contains("@$path"))draft=draft+" @$path ";persist()}
    fun removeReference(path:String){val key=imageDraftKey;referenceDrafts=referenceDrafts+(key to referencesFor(key).filter{it!=path});persist()}
    fun toolMutation(action:String,args:JSONObject,onSuccess:(JSONObject)->Unit={}){if(!connected||busy)return;mutate(action,args,onSuccess)}
    fun renameThread(thread:JSONObject,name:String){toolMutation("thread.rename",JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")).put("name",name)){r->if(r.optBoolean("verified")){val copy=JSONObject(thread.toString()).put("title",r.optString("name",name));threads=threads.map{if(it.optString("id")==thread.optString("id"))copy else it};if(selected?.optString("id")==thread.optString("id"))selected=copy;notice="对话已重命名"}else error="名称已提交，请刷新确认"}}
    private var outgoing by mutableStateOf(saved.optJSONObject("outgoingImages")?.let{obj->obj.keys().asSequence().associateWith{key->obj.optJSONArray(key).objects().mapNotNull{try{OutgoingImage.restore(it).takeIf{image->File(image.path).isFile}}catch(_:Exception){null}}}}.orEmpty())
    private fun restoredFiles(value:JSONObject?)=value?.let{obj->obj.keys().asSequence().associateWith{key->obj.optJSONArray(key).objects().mapNotNull{try{OutgoingFile.restore(it).takeIf{f->File(f.path).isFile}}catch(_:Exception){null}}}}.orEmpty()
    private var outgoingFiles by mutableStateOf(restoredFiles(saved.optJSONObject("outgoingFiles")))
    var toolRevision by mutableIntStateOf(0);private set
    var supportsFiles by mutableStateOf(false);private set
    fun filesFor(key:String)=outgoingFiles[key].orEmpty()
    fun addFiles(uris:List<android.net.Uri>,key:String=imageDraftKey) {
        if(busy||uris.isEmpty())return
        if(!supportsFiles){error="请先更新电脑连接器，再上传文件";return}
        if(filesFor(key).size+uris.size>4){error="每条消息最多添加 4 个文件";return}
        scope.launch { busy=true;imageStatus="正在读取附件…";error=null
            try { val added=withContext(Dispatchers.IO){val result=mutableListOf<OutgoingFile>();try{uris.forEach{result.add(OutgoingFile.import(context,it))};result}catch(e:Exception){result.forEach{File(it.path).delete()};throw e}}
                outgoingFiles=outgoingFiles+(key to (filesFor(key)+added));persist()
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"无法读取文件"}finally{busy=false;imageStatus=null}
        }
    }
    fun removeFile(key:String,id:String) { if(busy)return;filesFor(key).find{it.id==id}?.let{File(it.path).delete()};outgoingFiles=outgoingFiles+(key to filesFor(key).filter{it.id!=id});persist() }
    private suspend fun uploadFiles(key:String):List<String> {
        if(filesFor(key).isNotEmpty())require(supportsFiles){"电脑连接器尚不支持附件，请先更新电脑端"}
        return filesFor(key).mapIndexed { index,file->
            val sha=withContext(Dispatchers.IO){val hash=java.security.MessageDigest.getInstance("SHA-256");File(file.path).inputStream().use{input->val bytes=ByteArray(64*1024);while(true){val count=input.read(bytes);if(count<0)break;hash.update(bytes,0,count)}};hash.digest().joinToString(""){"%02x".format(it)}}
            val start=rpc("file.upload.start",JSONObject().put("uploadId",file.id).put("threadId",key).put("size",file.size).put("name",file.name).put("mime",file.mime).put("sha256",sha))
            var offset=start.getLong("nextOffset");require(offset in 0..file.size){"文件上传进度无效"}
            while(offset<file.size) {
                imageStatus="上传附件 ${index+1}/${filesFor(key).size} · ${offset*100/file.size}%"
                val bytes=withContext(Dispatchers.IO){java.io.RandomAccessFile(file.path,"r").use{input->input.seek(offset);ByteArray(minOf(192L*1024,file.size-offset).toInt()).also{input.readFully(it)}}}
                val next=offset+bytes.size
                val part=rpc("file.upload.chunk",JSONObject().put("uploadId",file.id).put("offset",offset).put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)))
                require(part.getLong("nextOffset")==next){"文件传输不完整"};offset=next
            }
            rpc("file.upload.finish",JSONObject().put("uploadId",file.id));file.id
        }
    }
    var imageStatus by mutableStateOf<String?>(null);private set
    val imageDraftKey get()=selected?.optString("id")?:"__new__"
    fun imagesFor(key:String)=outgoing[key].orEmpty()
    fun addImages(uris:List<android.net.Uri>,key:String=imageDraftKey) {
        if(busy||uris.isEmpty())return
        if(imagesFor(key).size+uris.size>4){error="每条消息最多添加 4 张图片";return}
        scope.launch {
            busy=true;imageStatus="正在处理图片…";error=null
            try { val added=withContext(Dispatchers.IO){val result=mutableListOf<OutgoingImage>();try{uris.forEach{result.add(OutgoingImage.import(context,it))};result}catch(e:Exception){result.forEach{File(it.path).delete()};throw e}}
                outgoing=outgoing+(key to (imagesFor(key)+added));persist()
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"无法读取图片"}finally{busy=false;imageStatus=null}
        }
    }
    fun removeImage(key:String,id:String) { if(busy)return;imagesFor(key).find{it.id==id}?.let{File(it.path).delete()};outgoing=outgoing+(key to imagesFor(key).filter{it.id!=id});persist() }
    private suspend fun uploadImages(key:String):List<String> {
        return imagesFor(key).mapIndexed{index,image->
            val bytes=withContext(Dispatchers.IO){File(image.path).readBytes()}
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val start=rpc("image.upload.start",JSONObject().put("uploadId",image.id).put("threadId",key).put("size",bytes.size).put("mime",image.mime).put("sha256",sha))
            var offset=start.getInt("nextOffset")
            require(offset in 0..bytes.size){"图片上传进度无效"}
            while(offset<bytes.size){
                imageStatus="上传图片 ${index+1}/${imagesFor(key).size} · ${offset*100/bytes.size}%"
                val end=minOf(offset+192*1024,bytes.size)
                val part=rpc("image.upload.chunk",JSONObject().put("uploadId",image.id).put("offset",offset).put("data",android.util.Base64.encodeToString(bytes.copyOfRange(offset,end),android.util.Base64.NO_WRAP)))
                require(part.getInt("nextOffset")==end){"图片传输不完整"};offset=end
            }
            rpc("image.upload.finish",JSONObject().put("uploadId",image.id));image.id
        }
    }
    private fun sendWithImages(action:String,args:JSONObject,key:String,success:(JSONObject)->Unit={}) {
        if(pendingOperation!=null){error="上一条操作结果待确认，请先检查会话";return}
        scope.launch {
            busy=true;error=null
            try {
                val ids=uploadImages(key)
                args.put("fileReferences",JSONArray(referencesFor(key))).put("imageUploadIds",JSONArray(ids)).put("fileUploadIds",JSONArray(uploadFiles(key)))
                if(action=="message.send"){draft="";scrollRequest++}
                persist();busy=false
                mutate(action,args){result->success(result)}
            }catch(e:CancellationException){throw e}catch(e:Exception){busy=false;error=e.message?:"附件发送失败，草稿已保留"}finally{imageStatus=null}
        }
    }
    var loadingThread by mutableStateOf(false); private set
    var loadingHistory by mutableStateOf(false); private set
    var detailMessage by mutableStateOf<JSONObject?>(null); private set
    var detailChunks by mutableStateOf<List<String>>(emptyList()); private set
    var detailLoading by mutableStateOf(false); private set
    var detailDone by mutableStateOf(false); private set
    var detailError by mutableStateOf<String?>(null); private set
    private var detailOffset = 0
    private var detailMode = "text"
    private var detailGeneration = 0
    var downloadStatus by mutableStateOf<String?>(null); private set
    var previewKind by mutableStateOf<String?>(null); private set
    var previewUrl by mutableStateOf(""); private set
    var previewArtifact by mutableStateOf<JSONObject?>(null); private set
    var previewFile by mutableStateOf<File?>(null); private set
    var previewLoading by mutableStateOf(false); private set
    var previewError by mutableStateOf<String?>(null); private set
    var previewRevision by mutableIntStateOf(0); private set
    private var previewJob: Job? = null
    private var messageImages = MessageImageLoader(context,profile?.optString("hostId").orEmpty())
    suspend fun attachmentBitmap(image: JSONObject): android.graphics.Bitmap {
        val threadId = selected?.optString("id") ?: error("请先打开对话")
        return messageImages.load(threadId,image,::chunk)
    }
    var foreground = false; private set
    private var connection: PocketConnection? = null
    private var account: AccountAuthorization? = null
    fun authorizeAccount(next: AccountAuthorization) {
        val previous=saved.optString("accountUserId")
        if(previous.isNotBlank()&&previous!=next.userId)forgetAll()
        account=next;saved.put("accountUserId",next.userId)
        computers.forEach{it.put("accountToken",next.token)};profile?.put("accountToken",next.token);persist();TaskMonitorService.configurationChanged(context)
        pendingScannedPair?.let{text->pendingScannedPair=null;persist();pair(text)}
    }
    fun clearAccountAccess() { stop();account=null;computers.forEach{it.remove("accountToken")};profile?.remove("accountToken");persist() }
    private var pairingJob: Job? = null
    private var pairingRequest: Job? = null
    private var reconnect: Runnable? = null
    private var cacheWrite: Runnable? = null
    private fun persist() {
        try {
            saved.put("referenceDrafts",JSONObject().also{obj->referenceDrafts.forEach{(key,refs)->obj.put(key,JSONArray(refs))}})
            saved.put("outgoingFiles",JSONObject().also{obj->outgoingFiles.forEach{(key,files)->obj.put(key,JSONArray(files.map{it.json()}))}})
            saved.put("outgoingImages",JSONObject().also{obj->outgoing.forEach{(key,images)->obj.put(key,JSONArray(images.map{it.json()}))}})
            saved.put("timelineFormat", 3).put("profile", profile ?: JSONObject.NULL).put("threads", JSONArray(threads)).put("snapshot", snapshot ?: JSONObject.NULL)
                .put("pending", pendingOperation ?: JSONObject.NULL).put("drafts", JSONObject(drafts.entries)).put("projects", JSONArray(projects)).put("appearance", appearance)
            saved.remove("draft")
            profile?.let{p->
                computers=computers.filter{it.optString("hostId")!=p.optString("hostId")}+JSONObject(p.toString())
                val cache=JSONObject();listOf("threads","projects","snapshot","pending","drafts","outgoingImages","outgoingFiles","referenceDrafts").forEach{cache.put(it,saved.opt(it))};computerStates.put(p.getString("hostId"),cache)
            }
            saved.put("computers",JSONArray(computers)).put("computerStates",computerStates)
            saved.put("pendingScan",pendingScannedPair?:JSONObject.NULL)
            store.save(saved)
        } catch (_: Exception) { error = "本地状态保存失败，请保留电脑端配对信息" }
    }
    private fun persistSoon() {
        cacheWrite?.let(handler::removeCallbacks)
        cacheWrite = Runnable { persist() }.also { handler.postDelayed(it, 1000) }
    }
    private var taskPolling:Job?=null
    fun start() {
        foreground = true
        if(requireLan&&profile!=null&&!PocketConnection.isLanPairing(profile!!)){error="局域网模式只允许连接私有 IPv4 地址，请重新扫码";return}
        if (profile != null) {
            if (profile?.optBoolean("approved") == true) {
                TaskMonitorService.claimUi(context,this,profile!!)
                if(taskPolling?.isActive!=true)taskPolling=scope.launch{
                    val peer=JSONObject(profile!!.toString());val poller=TaskComputerPoller(context,peer)
                    while(isActive&&foreground){
                        if(TaskNotifications.enabled(context)&&connected)try{poller.poll({action,args->rpc(action,args)},skipThread={selected?.optString("id")},canRead={foreground&&connected&&TaskNotifications.enabled(context)})}catch(e:CancellationException){throw e}catch(_:Exception){}
                        delay(15000)
                    }
                }
                if (connection == null) connect()
            } else if (pairingRequest?.isActive != true) waitForApproval()
        }
    }
    fun stop() {
        closePreview()
        foreground = false; reconnect?.let(handler::removeCallbacks); reconnect = null
        cacheWrite?.let(handler::removeCallbacks);cacheWrite=null
        receiptPolling?.cancel();receiptPolling=null
        if(pendingOperation!=null)busy=false
        pairingJob?.cancel(); pairingJob = null;taskPolling?.cancel();taskPolling=null; connection?.close(); connection = null; connected = false; persist()
        TaskMonitorService.releaseUi(context,this)
    }
    private fun connect() {
        val p = profile ?: return
        reconnect?.let(handler::removeCallbacks);reconnect=null
        connection?.close()
        lateinit var candidate: PocketConnection
        candidate = PocketConnection(p, { state -> handler.post {
            if (connection !== candidate || !foreground) return@post
            status = state.label; connected = state.ready
            if(!connected){receiptPolling?.cancel();receiptPolling=null}
            reconnect?.let(handler::removeCallbacks);reconnect=null
            if (state.retry) {
                reconnect = Runnable { if (foreground && !connected) connect() }.also { handler.postDelayed(it, 4000) }
            }
        } }, { event -> handler.post {
            if (connection !== candidate || !foreground) return@post
            when (event.optString("type")) {
                "hello" -> { if(event.has("capabilities")){supportsFiles=event.optJSONObject("capabilities")?.optBoolean("file.upload")==true;supportsReceipts=event.optJSONObject("capabilities")?.optBoolean("command.status")==true};previewRevision++; error = event.optString("error").takeIf { it.isNotBlank() }; refresh(); selected?.let { openThread(it) };watchPendingReceipt(0) }
                "event" -> {
                    val change = event.optJSONObject("event") ?: return@post
                    if(change.optString("type") in listOf("mcp.updated","automation.updated","catalog.updated")){toolRevision++;if(change.optString("type")=="catalog.updated")refresh()}
                    if(change.optString("type")=="thread.updated"&&change.optString("threadId")!=selected?.optString("id"))change.optJSONObject("data")?.let{data->profile?.let{TaskNotifications.observe(context,it,data,true)}}
                    if (change.optString("type") == "thread.updated" && change.optString("threadId") == selected?.optString("id")) {
                        change.optJSONObject("data")?.let { acceptSnapshot(it) }; loadingThread = false; persistSoon()
                    }
                    if (change.optString("type") == "desktop.disconnected" || change.optString("type") == "desktop.incompatible") error = change.optString("error", "电脑上的 Codex 连接已变化")
                }
            }
        } })
        connection = candidate; candidate.connect()
    }
    fun reconnectNow() { if (profile?.optBoolean("approved") == true) connect() else if (profile != null) waitForApproval() }
    private fun activateComputer(next:JSONObject?) {
        val resume=foreground;stop();scope.cancel();pairingRequest=null;scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        computerGeneration++;supportsReceipts=false;pendingStatus="";pendingCompletion=null
        profile=next?.let{JSONObject(it.toString())};account?.let{profile?.put("accountToken",it.token)}
        val cache=next?.let{computerStates.optJSONObject(it.optString("hostId"))}?:JSONObject()
        threads=cache.optJSONArray("threads").objects();projects=cache.optJSONArray("projects").objects();snapshot=cache.optJSONObject("snapshot");pendingOperation=cache.optJSONObject("pending")
        referenceDrafts=cache.optJSONObject("referenceDrafts")?.let{obj->obj.keys().asSequence().associateWith{key->obj.optJSONArray(key)?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()}}.orEmpty()
        drafts=DraftBook(cache.optJSONObject("drafts")?.let{obj->obj.keys().asSequence().associateWith{obj.optString(it)}}.orEmpty())
        outgoing=cache.optJSONObject("outgoingImages")?.let{obj->obj.keys().asSequence().associateWith{key->obj.optJSONArray(key).objects().mapNotNull{try{OutgoingImage.restore(it).takeIf{image->File(image.path).isFile}}catch(_:Exception){null}}}}.orEmpty()
        outgoingFiles=restoredFiles(cache.optJSONObject("outgoingFiles"));supportsFiles=false
        selected=null;threadBackStack.clear();closeMessage();models=emptyList();busy=false;loadingThread=false;loadingHistory=false
        projectSearch="";taskSearch="";showArchived=false;collapsedProjects=emptySet();error=null;downloadStatus=null
        pairing=profile!=null&&profile?.optBoolean("approved")!=true;status=if(profile==null)"尚未连接电脑"else"等待连接"
        messageImages=MessageImageLoader(context,profile?.optString("hostId").orEmpty());persist();if(resume)start()
    }
    fun switchComputer(id:String):Boolean {
        if(profile?.optString("hostId")==id)return true
        if(!canSwitchComputer){error="请等待当前附件准备完成后再切换电脑";return false}
        val next=computers.find{it.optString("hostId")==id}?:return false
        activateComputer(next)
        return true
    }
    fun removeComputer(id:String) {
        if(busy||pendingOperation!=null){error="请先确认当前操作的结果，再移除电脑";return}
        if(profile?.optString("hostId")==id){activateComputer(null)}
        computers=computers.filter{it.optString("hostId")!=id};computerStates.remove(id);persist()
        TaskMonitorService.configurationChanged(context)
        if(profile==null&&computers.isNotEmpty())activateComputer(computers.first())
    }
    fun renameComputer(id:String,value:String):Boolean {
        val name=value.trim()
        if(name.isEmpty()||name.length>80||name.any{it.isISOControl()}){error="电脑名称需要 1–80 个字符，不能包含换行";return false}
        if(computers.none{it.optString("hostId")==id})return false
        computers=computers.map{if(it.optString("hostId")==id)JSONObject(it.toString()).put("alias",name)else it}
        if(profile?.optString("hostId")==id){profile=JSONObject(profile.toString()).put("alias",name);taskPolling?.cancel();taskPolling=null}
        persist();TaskMonitorService.configurationChanged(context)
        if(foreground)start()
        notice="电脑名称已更新";return true
    }
    fun updateAppearance(value: String) { if (value in listOf("system", "light", "dark")) { appearance = value; persist() } }
    fun consumePairingIntent(text:String?):String? {
        if(text==null||!text.startsWith("codexpocket://pair?"))return null
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
        if(saved.optString("lastPairingIntent")==fingerprint)return null
        saved.put("lastPairingIntent",fingerprint);persist();return text
    }
    fun pair(text: String) {
        if (busy) return
        if (pendingOperation != null) { error = "请先检查并确认上一条操作的结果，再更换配对"; return }
        try {
            val next = PocketConnection.validatePairing(text)
            if(requireLan)require(PocketConnection.isLanPairing(next)){"请扫描局域网配对码；公网远程连接需要登录已批准的账号"}
            if(!PocketConnection.isLanPairing(next))require(account!=null){"请先登录已批准的账号，再扫描公网配对码"}
            account?.let { auth ->
                require(next.getString("relay").trimEnd('/')==auth.relay.trimEnd('/')&&next.getString("pin")==auth.pin){"请使用当前服务的电脑配对码"}
                require(next.getString("hostId") in auth.hostIds||next.optBoolean("claimFromMobile")){"这台电脑不属于当前账号，请使用新的电脑配对码"}
                next.put("accountToken",auth.token)
            }
            val existing=computers.find{it.optString("hostId")==next.optString("hostId")&&it.optString("pairCode")==next.optString("pairCode")}
            next.remove("alias")
            computers.find{TaskNotifications.namespace(it)==TaskNotifications.namespace(next)}?.optString("alias")?.takeIf{it.isNotBlank()}?.let{next.put("alias",it)}
            val attempts=saved.optJSONObject("pairAttempts")?:JSONObject()
            val attempt=attempts.optJSONObject(next.getString("hostId"))?.takeIf{it.optString("pairCode")==next.getString("pairCode")}
            next.put("mobileToken",existing?.optString("mobileToken")?.takeIf{it.isNotBlank()}?:attempt?.optString("mobileToken")?.takeIf{it.isNotBlank()}?:Encoding.secret())
            attempts.put(next.getString("hostId"),JSONObject().put("pairCode",next.getString("pairCode")).put("mobileToken",next.getString("mobileToken")));saved.put("pairAttempts",attempts)
            next.put("approved", false)
            persist();cacheWrite?.let(handler::removeCallbacks);cacheWrite=null;pairingJob?.cancel();pairingJob=null;reconnect?.let(handler::removeCallbacks);reconnect=null;connection?.close(); connection = null; connected = false;scope.cancel();pairingRequest=null;taskPolling=null;scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            closePreview(); closeMessage(); threadBackStack.clear(); threads = emptyList(); projects = emptyList(); models = emptyList(); snapshot = null; selected = null; drafts = DraftBook(); outgoing=emptyMap()
            computerGeneration++;supportsReceipts=false;receiptPolling?.cancel();receiptPolling=null
            profile = next;messageImages=MessageImageLoader(context,next.getString("hostId")); pairing = true; error = null; persist()
            TaskMonitorService.releaseUi(context,this)
            status = "配对中，等待电脑确认"
            waitForApproval()
        } catch (e: Exception) { error = e.message ?: "配对内容无效，请重新扫码" }
    }
    fun scannedPair(text:String){
        if(requireLan||account!=null){pair(text);return}
        try{
            PocketConnection.validatePairing(text)
            pendingScannedPair=text;error=null;status="正在验证登录状态，扫码结果已保留";persist()
        }catch(e:Exception){error=e.message?:"配对内容无效，请重新扫码"}
    }
    fun cancelPairing() {
        if(!pairing)return
        val p=profile?:return
        // This job outlives the pairing scope that removeComputer cancels.
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { PocketConnection.post(p,"/api/pair/cancel",JSONObject().put("hostId",p.getString("hostId")).put("mobileToken",p.getString("mobileToken"))) }catch(_:Exception){}
        }
        removeComputer(p.getString("hostId"))
    }
    private fun waitForApproval() {
        if (!foreground || pairingJob?.isActive == true) return
        val p = profile ?: return
        pairingJob = scope.launch {
            status = "配对中，等待电脑确认"; pairing = true; error = null
            while (foreground && profile === p && !p.optBoolean("approved")) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if(!p.optBoolean("pairSubmitted")) {
                            try { PocketConnection.post(p,"/api/pair/status",JSONObject().put("hostId",p.getString("hostId")).put("mobileToken",p.getString("mobileToken"))) }
                            catch(e:PairingHttpException) { if(e.status !in listOf(401,403))throw e
                            PocketConnection.post(p, "/api/pair", JSONObject().put("hostId",p.getString("hostId")).put("pairCode",p.getString("pairCode")).put("mobileToken",p.getString("mobileToken")).put("name","${Build.MANUFACTURER} ${Build.MODEL}")) }
                        } else PocketConnection.post(p,"/api/pair/status",JSONObject().put("hostId",p.getString("hostId")).put("mobileToken",p.getString("mobileToken")))
                    }
                    p.put("pairSubmitted",true); error=null; status="配对中，等待电脑确认";persist()
                    if (result.optString("state") == "approved") {
                        p.put("approved", true); p.remove("pairCode"); pairing = false; persist(); start(); return@launch
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if(e is PairingHttpException && e.status in listOf(400,401,403,404,409,410)) {
                        error=e.message?:"配对已失效，请重新扫码";status="配对未完成，请重新扫码";return@launch
                    }
                    error=null;status="配对中，网络恢复后继续等待电脑确认"
                }
                delay(2500)
            }
        }
    }
    private fun request(action: String, args: JSONObject = JSONObject(), id: String = UUID.randomUUID().toString(), done: (JSONObject?, String?) -> Unit) {
        val c = connection
        if (c == null || !c.ready) { done(null, "电脑尚未连接"); return }
        val generation=computerGeneration
        c.request(action, args, id) { result, problem -> handler.post {
            if(generation==computerGeneration) {
                if(connection===c)done(result,problem)
                else done(null,"连接已更新，操作结果将在恢复连接后确认")
            }
        } }
    }
    fun refresh() {
        if (!connected) return
        request("threads.list",JSONObject().put("includeArchived",showArchived)) { r, e -> if (e != null) error = e else { threads = (r?.optJSONArray("pinnedThreads").objects() + r?.optJSONArray("threads").objects()).filter { it.optString("kind", "codex") == "codex" }.distinctBy { it.optString("hostId") + ":" + it.optString("id") }; persistSoon() } }
        request("projects.list") { r, e -> if (e != null) error = e else { projects = r?.optJSONArray("projects").objects().filter { it.optString("hostId", "local") == "local" }; persistSoon() } }
        request("models.list") { r, _ -> models = r?.optJSONArray("models").objects() }
    }
    fun openThread(thread: JSONObject) {
        if(selected?.optString("id")!=thread.optString("id"))closeMessage()
        if (selected?.optString("id") != thread.optString("id")) closePreview()
        if (selected?.optString("id") != thread.optString("id")) error = null
        selected = thread
        if (snapshot?.optJSONObject("thread")?.optString("id") != thread.optString("id")) snapshot = null
        if (!connected) return
        loadingThread = true
        request("thread.read", JSONObject().put("threadId", thread.getString("id")).put("hostId", thread.optString("hostId", "local"))) { r, e ->
            if (selected?.optString("id") != thread.optString("id")) return@request
            loadingThread = false; if (e != null) error = e else { r?.let { acceptSnapshot(it) }; persistSoon() }
        }
    }
    private fun acceptSnapshot(next: JSONObject, olderPage: Boolean = false) {
        val current = snapshot
        val same = current?.optJSONObject("history") != null && current.optJSONObject("thread")?.optString("id") == next.optJSONObject("thread")?.optString("id")
        val sameSource = same && current?.optString("source") == next.optString("source")
        fun entries(value: JSONObject?) = value?.optJSONArray("messages").objects().mapIndexed { index, message -> TimelineEntry(message.optString("key", message.optString("turnId")+":"+message.optString("id")), message.optInt("order",index), message) }
        val stale = sameSource && !next.isNull("revision") && current?.isNull("revision") == false && current.optLong("revision") > next.optLong("revision")
        if (stale && !olderPage) return
        if (olderPage && same && !sameSource) { selected?.let { openThread(it) }; return }
        val previous = if (sameSource) entries(current) else emptyList()
        val merged = if (olderPage && sameSource) mergeTimeline(entries(next), previous)
            else replaceTimelineTail(previous, entries(next), next.optJSONObject("history")?.optBoolean("hasEarlier") == true)
        val accepted = if (olderPage && sameSource && current != null) JSONObject(current.toString()) else next
        accepted.put("messages", JSONArray(merged.map { it.value }))
        accepted.optJSONObject("history")?.apply { put("before", merged.firstOrNull()?.key ?: JSONObject.NULL); put("hasEarlier", (merged.firstOrNull()?.order ?: 0) > 0) }
        snapshot = accepted
        if(!olderPage)profile?.let{TaskNotifications.observe(context,it,accepted,false)}
    }
    fun loadEarlier() {
        val thread = selected ?: return
        val history = snapshot?.optJSONObject("history") ?: return
        if (loadingHistory || !connected || !history.optBoolean("hasEarlier")) return
        loadingHistory = true
        request("thread.history", JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")).put("before",history.getString("before"))) { r,e ->
            loadingHistory = false
            if (selected?.optString("id") != thread.optString("id")) return@request
            if(e != null) error=e else { r?.let { acceptSnapshot(it, olderPage = true) }; persistSoon() }
        }
    }
    fun showMessage(message: JSONObject, mode: String = "text") {
        closeMessage(); detailMessage=message; detailMode=mode; loadMoreMessage()
    }
    fun closeMessage() { detailGeneration++;detailMessage=null;detailChunks=emptyList();detailOffset=0;detailLoading=false;detailDone=false;detailError=null }
    fun loadMoreMessage() {
        val message=detailMessage ?: return
        val thread=selected ?: return
        if(detailLoading||detailDone)return
        val generation=detailGeneration;detailLoading=true;detailError=null
        request("message.read",JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")).put("messageKey",message.getString("key")).put("mode",detailMode).put("version",message.optString(if(detailMode=="detail")"detailVersion" else "contentVersion")).put("offset",detailOffset)) { r,e ->
            if(generation!=detailGeneration)return@request
            detailLoading=false
            if(e!=null)detailError=e else if(r!=null){detailChunks=detailChunks+r.optString("text");detailOffset=r.optInt("nextOffset");detailDone=r.optBoolean("done")}
        }
    }
    fun openAgentThread(thread:JSONObject) { selected?.let { threadBackStack.add(it to snapshot) };openThread(thread) }
    fun back() {
        closeMessage();closePreview()
        if(threadBackStack.isNotEmpty()){val previous=threadBackStack.removeAt(threadBackStack.lastIndex);selected=previous.first;snapshot=previous.second;openThread(previous.first)}
        else{selected=null;refresh()}
    }
    private fun mutate(action: String, args: JSONObject, onSuccess: (JSONObject) -> Unit = {}) {
        if (pendingOperation != null) { error = "上一条操作结果待确认，请先检查会话"; return }
        val op = JSONObject().put("id", UUID.randomUUID().toString()).put("action", action).put("args", args)
        pendingOperation = op; persist(); deliver(op, onSuccess)
    }
    private fun deliver(op: JSONObject, onSuccess: (JSONObject) -> Unit = {}) {
        busy = true;pendingStatus="正在等待电脑确认操作…";pendingCompletion=op.getString("id") to onSuccess
        request(op.getString("action"), op.getJSONObject("args"), op.getString("id")) { result, problem ->
            completePending(op,result,problem)
        }
        watchPendingReceipt()
    }
    private fun completePending(op:JSONObject,result:JSONObject?,problem:String?) {
            if(pendingOperation?.optString("id")!=op.optString("id"))return
            busy = false
            val completion=pendingCompletion?.takeIf{it.first==op.optString("id")}?.second
            if (result?.optBoolean("_confirmed") == true && !result.optBoolean("_uncertain")) { pendingOperation = null;pendingCompletion=null;pendingStatus="";receiptPolling?.cancel();receiptPolling=null;persist() }
            if (problem != null) {
                error = problem
                if (result?.optBoolean("_confirmed") == true && !result.optBoolean("_uncertain") && op.optString("action") == "message.send") {
                    val args = op.getJSONObject("args"); val host = args.optString("hostId", "local"); val id = args.optString("threadId")
                    if (drafts.text(host, id).isEmpty()) { drafts = drafts.withDraft(host, id, args.optString("text")); persist() }
                }
            } else if (result != null) {
                val sentIds=op.optJSONObject("args")?.optJSONArray("imageUploadIds")?.let{array->(0 until array.length()).map{array.getString(it)}.toSet()}.orEmpty()
                if(sentIds.isNotEmpty()){outgoing=outgoing.mapValues{(_,images)->images.filter{it.id !in sentIds}};persist()}
                val fileIds=op.optJSONObject("args")?.optJSONArray("fileUploadIds")?.let{array->(0 until array.length()).map{array.getString(it)}.toSet()}.orEmpty()
                if(fileIds.isNotEmpty()){outgoingFiles.values.flatten().filter{it.id in fileIds}.forEach{File(it.path).delete()};outgoingFiles=outgoingFiles.mapValues{(_,files)->files.filter{it.id !in fileIds}};persist()}
                if(op.optString("action") in listOf("message.send","thread.create")){val key=if(op.optString("action")=="thread.create")"__new__"else op.optJSONObject("args")?.optString("threadId").orEmpty();referenceDrafts=referenceDrafts-key;persist()}
                error = null
                if(completion!=null)completion(result)
                else if(op.optString("action")=="thread.create")result.optString("threadId").takeIf{it.isNotBlank()}?.let{id->openThread(JSONObject().put("id",id).put("hostId",result.optString("hostId","local")).put("title",op.optJSONObject("args")?.optString("text").orEmpty().take(40)))}
                refresh(); selected?.let { openThread(it) } }
    }
    private fun watchPendingReceipt(firstDelay:Long=3000) {
        if(!supportsReceipts||!foreground||!connected||receiptPolling?.isActive==true)return
        val op=pendingOperation?:return
        val generation=computerGeneration
        receiptPolling=scope.launch {
            try {
                delay(firstDelay)
                while(isActive&&foreground&&connected&&computerGeneration==generation&&pendingOperation?.optString("id")==op.optString("id")) {
                    try {
                        val receipt=rpc("command.status",JSONObject().put("id",op.getString("id")).put("action",op.getString("action")).put("args",op.getJSONObject("args")))
                        if(pendingOperation?.optString("id")!=op.optString("id"))break
                        when(receipt.optString("state")) {
                            "done"->{completePending(op,(receipt.optJSONObject("result")?:JSONObject()).put("_confirmed",true),null);break}
                            "rejected"->{completePending(op,JSONObject().put("_confirmed",true),receipt.optString("error","操作未执行，请重试"));break}
                            "not_found"->{busy=false;pendingStatus="电脑未找到这次操作的记录，请先检查会话再重发。";break}
                            "running"->{busy=false;pendingStatus="电脑已收到操作，正在确认结果…"}
                            "uncertain"->{busy=false;pendingStatus="正在核对上次操作结果，请勿重复发送。"}
                            else->{busy=false;pendingStatus="暂时无法确认，请检查会话。";break}
                        }
                    }catch(e:CancellationException){throw e}catch(_:Exception){busy=false;pendingStatus="连接恢复后会继续确认操作结果。"}
                    delay(5000)
                }
            }finally{if(receiptPolling===coroutineContext[Job])receiptPolling=null}
        }
    }
    fun confirmPending() { if(supportsReceipts)watchPendingReceipt(0)else pendingOperation?.let { deliver(it) } }
    fun clearPendingAfterInspection() { receiptPolling?.cancel();receiptPolling=null;pendingCompletion=null;pendingStatus="";pendingOperation = null;busy=false;persist();error=null }
    fun send(text: String, model: String? = null) {
        val thread = selected ?: return
        if ((text.isBlank() && imagesFor(imageDraftKey).isEmpty() && filesFor(imageDraftKey).isEmpty()) || busy || !connected) return
        if (pendingOperation != null) { error = "上一条操作结果待确认，请先检查会话"; return }
        val args = JSONObject().put("threadId", thread.getString("id")).put("hostId", thread.optString("hostId", "local")).put("text", text)
        if (!model.isNullOrBlank()) args.put("model", model)
        sendWithImages("message.send",args,thread.getString("id"))
    }
    fun create(text: String, projectId: String?, model: String?) {
        if ((text.isBlank() && imagesFor("__new__").isEmpty() && filesFor("__new__").isEmpty()) || busy || !connected) return
        val args = JSONObject().put("text", text)
        if (!projectId.isNullOrBlank()) args.put("projectId", projectId)
        if (!model.isNullOrBlank()) args.put("model", model)
        sendWithImages("thread.create", args,"__new__") { result ->
            result.optString("threadId").takeIf { it.isNotBlank() }?.let { id -> openThread(JSONObject().put("id", id).put("title", text.take(40)).put("hostId", result.optString("hostId", "local"))) }
        }
    }
    fun stopTurn() {
        val thread = snapshot?.optJSONObject("thread") ?: return
        val turnId = thread.optString("activeTurnId").takeIf { it.isNotBlank() && it != "null" } ?: run { error = "当前没有可停止的运行任务"; return }
        mutate("turn.stop", JSONObject().put("threadId", thread.getString("id")).put("hostId", thread.optString("hostId", "local")).put("turnId", turnId))
    }
    fun updateSettings(model: String?, effort: String?, permission: String?) {
        val thread = selected ?: return
        val args = JSONObject().put("threadId", thread.getString("id")).put("hostId", thread.optString("hostId", "local"))
        model?.let { args.put("model", it) }; effort?.let { args.put("reasoningEffort", it) }; permission?.let { args.put("permissionMode", it) }
        mutate("thread.settings.update", args) { result -> if (!result.optBoolean("verified")) error = result.optString("error", "电脑已接收设置，但重新读取尚未确认，请刷新检查") else notice = "设置已保存，应用到后续消息" }
    }
    fun toggleArchived(){showArchived=!showArchived;refresh()}
    fun manageThread(thread:JSONObject,action:String){
        if(!connected||busy||action !in listOf("archive","unarchive","delete"))return
        mutate("thread.$action",JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local"))){result->
            if(!result.optBoolean("verified")){error="操作结果尚未确认，请刷新电脑列表";return@mutate}
            if(selected?.optString("id")==thread.optString("id")){selected=null;snapshot=null}
            notice=when(action){"archive"->"对话已归档";"unarchive"->"对话已恢复";else->"对话已从电脑删除"}
        }
    }
    suspend fun rpc(action: String, args: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        handler.post { request(action, args) { r, e ->
            if (continuation.isActive) { if (e != null) continuation.resumeWith(Result.failure(IllegalStateException(e))) else continuation.resumeWith(Result.success(r ?: JSONObject())) }
        } }
    }
    fun openBrowser(url: String) {
        try {
            val uri = java.net.URI(url)
            require(uri.scheme in listOf("http", "https") && uri.host != null) { "请输入完整的 HTTP 或 HTTPS 地址" }
            closePreview(); previewUrl = url; previewKind = "browser"
        } catch (e: Exception) { error = e.message ?: "网页地址无效" }
    }
    fun showPreviewTab(kind: String) { previewKind = kind }
    fun showArtifactList(kind: String) { closePreview(); previewKind = kind }
    fun openLink(link: String) {
        if (link.startsWith("https://") || link.startsWith("http://")) { openBrowser(link); return }
        val path = link.removePrefix("file://").replace(Regex(":\\d+$"), "")
        val artifact = snapshot?.optJSONArray("artifacts").objects().find { it.optString("path") == path || it.optString("name") == path }
        if (artifact != null) preview(artifact) else error = "该文件未包含在当前会话可访问的文件中，请先刷新会话"
    }
    fun closePreview() {
        previewJob?.cancel(); previewJob = null; previewFile?.delete(); previewFile = null
        previewKind = null; previewArtifact = null; previewError = null; previewLoading = false
    }
    fun preview(artifact: JSONObject) {
        val threadId = selected?.optString("id") ?: if(artifact.has("workspaceArgs"))"__workspace__"else return
        closePreview(); previewArtifact = artifact
        previewKind = if (artifact.optString("mime").startsWith("image/")) "image" else "file"
        previewLoading = true
        previewJob = scope.launch {
            var file: File? = null
            try {
                require(artifact.getLong("size") <= 50L * 1024 * 1024) { "此文件超过 50 MB，请保存后查看" }
                val temp = File.createTempFile("pocket-preview-", ".tmp", context.cacheDir)
                file = temp
                withContext(Dispatchers.IO) {
                    try {
                        temp.outputStream().use { output ->
                            var offset = 0L
                            while (true) {
                                val part = if(artifact.has("workspaceArgs"))rpc("workspace.read",JSONObject(artifact.getJSONObject("workspaceArgs").toString()).put("offset",offset).put("fingerprint",artifact.optString("fingerprint")))else chunk(threadId, artifact.getString("id"), offset)
                                val data = android.util.Base64.decode(part.getString("data"), android.util.Base64.DEFAULT)
                                require(part.getLong("total") <= 50L * 1024 * 1024 && part.getLong("nextOffset") == offset + data.size)
                                output.write(data); offset = part.getLong("nextOffset")
                                if (part.getBoolean("done")) break
                            }
                        }
                    } catch (e: Exception) { temp.delete(); throw e }
                }
                previewFile = temp
            } catch (e: CancellationException) { file?.delete(); throw e }
            catch (e: Exception) { previewError = e.message ?: "预览读取失败" }
            finally { if (previewFile !== file) file?.delete(); if (previewArtifact === artifact) previewLoading = false }
        }
    }
    fun previewProjectFile(args:JSONObject,path:String){scope.launch{try{val input=JSONObject(args.toString()).put("path",path);val result=rpc("workspace.read",input.put("offset",0));val ext=path.substringAfterLast('.').lowercase();val mime=when(ext){"pdf"->"application/pdf";"png","jpg","jpeg","gif","webp","bmp"->"image/"+if(ext=="jpg")"jpeg"else ext;else->"text/plain"};preview(JSONObject().put("name",result.getString("name")).put("size",result.getLong("size")).put("mime",mime).put("fingerprint",result.getString("fingerprint")).put("workspaceArgs",input))}catch(e:Exception){error=e.message}}}
    fun respondApproval(request:JSONObject,decision:String,response:JSONObject?=null) {
        val thread=selected?:return
        val args=JSONObject().put("threadId",thread.getString("id")).put("hostId",thread.optString("hostId","local")).put("requestId",request.get("id")).put("decision",decision)
        response?.let{args.put("response",it)};mutate("approval.respond",args)
    }
    fun approve(request: JSONObject, decision: Any, answers: JSONObject? = null) {
        val thread = selected ?: return
        val args = JSONObject().put("threadId", thread.getString("id")).put("hostId", thread.optString("hostId", "local")).put("requestId", request.get("id")).put("decision", decision)
        if (answers != null) args.put("response", JSONObject().put("answers", answers))
        mutate("approval.respond", args)
    }
    fun forget() {
        profile?.optString("hostId")?.let{removeComputer(it)}
    }
    private fun forgetAll() {
        pairingRequest?.cancel();pairingRequest=null
        computerGeneration++;supportsReceipts=false;pendingCompletion=null;pendingStatus=""
        scope.cancel();scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);busy=false
        stop(); threadBackStack.clear(); closeMessage(); profile = null; snapshot = null; selected = null; threads = emptyList(); projects = emptyList(); models = emptyList(); drafts = DraftBook(); pendingOperation = null; outgoing=emptyMap();outgoingFiles=emptyMap();pairing = false; status = "尚未连接电脑"; saved = JSONObject(); persist()
        computers=emptyList();computerStates=JSONObject();pendingScannedPair=null;persist()
    }
    suspend fun chunk(threadId: String, artifactId: String, offset: Long): JSONObject = suspendCancellableCoroutine { continuation ->
        handler.post { request("file.read", JSONObject().put("threadId", threadId).put("artifactId", artifactId).put("offset", offset)) { r, e ->
            if (continuation.isActive) { if (e != null) continuation.resumeWith(Result.failure(IllegalStateException(e))) else continuation.resumeWith(Result.success(r ?: JSONObject())) }
        } }
    }
    fun download(artifact: JSONObject) {
        val threadId = selected?.optString("id") ?: if(artifact.has("workspaceArgs"))"__workspace__"else return
        if (downloadStatus != null) return
        scope.launch {
            var uri: android.net.Uri? = null
            try {
                downloadStatus = "正在准备 ${artifact.getString("name")}"; error = null
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, artifact.getString("name")); put(MediaStore.Downloads.MIME_TYPE, artifact.optString("mime", "application/octet-stream")); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CodexPocket"); put(MediaStore.Downloads.IS_PENDING, 1) }
                uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
                val target = uri
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target)?.use { output ->
                        var offset = 0L
                        while (true) {
                            val part = if(artifact.has("workspaceArgs"))rpc("workspace.read",JSONObject(artifact.getJSONObject("workspaceArgs").toString()).put("offset",offset).put("fingerprint",artifact.optString("fingerprint")))else chunk(threadId, artifact.getString("id"), offset)
                            output.write(android.util.Base64.decode(part.getString("data"), android.util.Base64.DEFAULT))
                            offset = part.getLong("nextOffset")
                            withContext(Dispatchers.Main) { downloadStatus = "已下载 ${offset / 1024} / ${part.getLong("total") / 1024} KB" }
                            if (part.getBoolean("done")) break
                        }
                    } ?: error("无法打开下载文件")
                }
                context.contentResolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                notice = "已保存到 下载 / CodexPocket"
            } catch (e: Exception) {
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                error = "下载未完成：${e.message}"
            }
            finally { downloadStatus = null }
        }
    }
}
