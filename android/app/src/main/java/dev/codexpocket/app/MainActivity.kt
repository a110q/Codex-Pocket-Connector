package dev.codexpocket.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var model: PocketModel
    private lateinit var account: AccountModel
    private var serviceRevision by mutableIntStateOf(0)
    private var pendingService by mutableStateOf<String?>(null)
    private var scanError by mutableStateOf<String?>(null)
    private var connectionPage by mutableStateOf<String?>(null)
    private lateinit var lanModel:PocketModel
    private var lanMode by mutableStateOf(false)
    private val activeModel get()=if(lanMode)lanModel else model
    private fun switchMode(local:Boolean){
        activeModel.stop();account.stop();lanMode=local;getSharedPreferences("connection-mode",MODE_PRIVATE).edit().putBoolean("lan",local).apply()
        if(local)lanModel.start()else account.start()
    }
    private var imagePickKey:String?=null
    private var imagePickLan=false
    private val imagePicker=registerForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->
        val key=imagePickKey;imagePickKey=null
        if(key!=null&&uris.isNotEmpty())(if(imagePickLan)lanModel else model).addImages(uris,key)
    }
    private var filePickKey:String?=null
    private var filePickLan=false
    private val filePicker=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val key=filePickKey;filePickKey=null
        if(key!=null&&uris.isNotEmpty())(if(filePickLan)lanModel else model).addFiles(uris,key)
    }
    private var incoming by mutableStateOf<String?>(null)
    private var openAdmin by mutableStateOf(false)
    private var pendingTask by mutableStateOf<JSONObject?>(null)
    private var taskError by mutableStateOf<String?>(null)
    private fun receiveTaskIntent(intent:Intent?) {
        val id=intent?.getStringExtra("taskThreadId")?:return;val host=intent.getStringExtra("taskHostId")?:return
        if(intent.getBooleanExtra("taskLan",false)!=lanMode)switchMode(!lanMode)
        val relay=intent.getStringExtra("taskRelay");val pin=intent.getStringExtra("taskPin")
        if(relay!=null&&activeModel.computers.none{it.optString("hostId")==host&&it.optString("relay")==relay&&(pin==null||it.optString("pin")==pin)}){taskError="这条提醒对应的电脑已移除或连接信息已更新，请重新连接对应电脑。";return}
        if(!activeModel.switchComputer(host)){taskError=activeModel.error?:"暂时无法切换电脑，请先确认当前操作结果后重试。";return}
        connectionPage=null;pendingTask=JSONObject().put("id",id).put("hostId",host)
        intent.removeExtra("taskThreadId");intent.removeExtra("taskHostId")
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    private fun scan(){scanner.launch(ScanOptions().setCaptureActivity(PocketCaptureActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(if(connectionPage=="lan"||(connectionPage==null&&lanMode))"扫描电脑上的局域网配对码"else"扫描电脑上的 Codex Pocket 配对码").setBeepEnabled(false).setOrientationLocked(true))}
    private fun selectPublic(){
        val bundled=AccountApi.bundled(applicationContext)
        if(!AccountApi.same(account.api.defaults,bundled))changeService(bundled)
        else{if(lanMode)switchMode(false);connectionPage=if(account.canUse)null else"account"}
    }
    private fun receivePair(text:String){
        try{
            val pair=PocketConnection.validatePairing(text)
            if(!PocketConnection.isLanPairing(pair)&&!AccountApi.same(pair,account.api.defaults)){pendingService=text;return}
            if(PocketConnection.isLanPairing(pair)!=lanMode)switchMode(PocketConnection.isLanPairing(pair))
            connectionPage=if(!lanMode&&!account.canUse)"account"else null
            activeModel.scannedPair(text)
        }catch(e:Exception){scanError=e.message?:"配对码无效"}
    }
    private fun loadCloud(){
        model=PocketModel(applicationContext,"pocket-state${AccountApi.suffix(applicationContext)}.enc")
        account=AccountModel(applicationContext){authorization->
            if(authorization==null)model.clearAccountAccess()
            else{model.authorizeAccount(authorization);if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))model.start()}
        }
    }
    private fun changeService(server:JSONObject,pair:String?=null){
        activeModel.stop();account.close();AccountNotifications.cancel(applicationContext)
        AccountApi.select(applicationContext,server);lanMode=false;getSharedPreferences("connection-mode",MODE_PRIVATE).edit().putBoolean("lan",false).apply()
        TaskMonitorService.configurationChanged(applicationContext)
        loadCloud();connectionPage="account";pair?.let{model.scannedPair(it)};serviceRevision++;account.start()
    }
    private val scanner = registerForActivityResult(ScanContract()) { result -> result.contents?.let(::receivePair) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        lanModel=PocketModel(applicationContext,"pocket-lan-state.enc",true);lanMode=getSharedPreferences("connection-mode",MODE_PRIVATE).getBoolean("lan",false)
        loadCloud()
        connectionPage=savedInstanceState?.getString("connectionPage")
        filePickKey=savedInstanceState?.getString("filePickKey");filePickLan=savedInstanceState?.getBoolean("filePickLan")?:false
        imagePickKey=savedInstanceState?.getString("imagePickKey");imagePickLan=savedInstanceState?.getBoolean("imagePickLan")?:false
        incoming = activeModel.consumePairingIntent(intent?.dataString)
        openAdmin = intent?.getBooleanExtra("openAdmin",false)==true
        receiveTaskIntent(intent)
        intent?.data = null
        setContent { PocketTheme(activeModel.appearance) {
            CompositionLocalProvider(LocalOutgoingImagePicker provides { target,key->imagePickKey=key;imagePickLan=target===lanModel;imagePicker.launch("image/*")},LocalOutgoingFilePicker provides {target,key->filePickKey=key;filePickLan=target===lanModel;filePicker.launch(arrayOf("*/*"))}) {
            LaunchedEffect(account.isAdmin){
                if(account.isAdmin&&android.os.Build.VERSION.SDK_INT>=33){
                    val preferences=getSharedPreferences("admin-alerts",MODE_PRIVATE)
                    if(!preferences.getBoolean("asked-notifications",false)){preferences.edit().putBoolean("asked-notifications",true).apply();notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)}
                }
            }
            key(serviceRevision) {
                LaunchedEffect(pendingTask,activeModel.connected){pendingTask?.let{target->if(activeModel.connected&&activeModel.profile?.optString("hostId")==target.optString("hostId")){activeModel.openThread(JSONObject().put("id",target.getString("id")).put("hostId","local").put("title","任务通知"));pendingTask=null}}}
                LaunchedEffect(incoming){incoming?.let{receivePair(it);incoming=null}}
                LaunchedEffect(account.canUse,connectionPage){if(account.canUse&&connectionPage=="account")connectionPage=null}
                val hasWorkspace=if(lanMode)lanModel.profile!=null else account.canUse
                val page=connectionPage?:if(hasWorkspace)"workspace"else if(account.loggedIn&&!lanMode)"account"else"choose"
                BackHandler(page!="workspace"&&(page!="choose"||hasWorkspace)){connectionPage=if(page=="choose")null else"choose"}
                when(page){
                    "choose"->ConnectionChooser(onCloud=::selectPublic,onLan={connectionPage="lan"},onSelfHost={connectionPage="selfhost"},onBack=if(hasWorkspace){{connectionPage=null}}else null)
                    "lan"->ConnectionGuide(local=true,onBack={connectionPage="choose"},onScan=::scan)
                    "selfhost"->ConnectionGuide(local=false,onBack={connectionPage="choose"},onScan=::scan,currentService=account.api.defaults.getString("relay").takeUnless{AccountApi.same(account.api.defaults,AccountApi.bundled(applicationContext))},onLoginCurrent={if(lanMode)switchMode(false);connectionPage=if(account.canUse)null else"account"})
                    "account"->AccountGate(account,onBack={connectionPage="choose"})
                    else->PocketApp(activeModel,scan=::scan,incoming=null,dismissIncoming={incoming=null},account=if(lanMode)null else account,openAdmin=openAdmin,consumeAdmin={openAdmin=false},onExitLan=if(lanMode){{selectPublic()}}else null,onEnterLan=if(!lanMode){{connectionPage="lan"}}else null,onSwitchService={connectionPage="choose"})
                }
            }
            pendingService?.let{raw->
                val server=remember(raw){PocketConnection.validatePairing(raw)}
                AlertDialog(onDismissRequest={pendingService=null},title={Text("连接这个中继服务？")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(server.getString("relay"));Text("证书指纹：${server.getString("pin").take(16)}…",fontSize=12.sp)
                    Text("仅连接你信任的电脑所提供的服务。切换后需登录该服务的账号，原服务的登录信息不会发送过去。",fontSize=14.sp)
                }},confirmButton={TextButton(onClick={pendingService=null;changeService(server,raw)}){Text("切换并登录")}},dismissButton={TextButton(onClick={pendingService=null}){Text("取消")}})
            }
            taskError?.let{message->AlertDialog(onDismissRequest={taskError=null},title={Text("暂时无法打开提醒")},text={Text(message)},confirmButton={TextButton(onClick={taskError=null}){Text("知道了")}})}
            scanError?.let{message->AlertDialog(onDismissRequest={scanError=null},title={Text("无法读取配对码")},text={Text(message)},confirmButton={TextButton(onClick={scanError=null}){Text("知道了")}})}

        } } }
    }
    override fun onSaveInstanceState(outState:Bundle){super.onSaveInstanceState(outState);outState.putString("filePickKey",filePickKey);outState.putBoolean("filePickLan",filePickLan);outState.putString("imagePickKey",imagePickKey);outState.putBoolean("imagePickLan",imagePickLan);outState.putString("connectionPage",connectionPage)}
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);receiveTaskIntent(intent); incoming = activeModel.consumePairingIntent(intent.dataString);openAdmin=intent.getBooleanExtra("openAdmin",false);if(openAdmin){connectionPage=null;if(lanMode)switchMode(false)}; intent.data = null; setIntent(intent) }
    override fun onStart() { super.onStart();TaskMonitorService.foreground(this); if (::account.isInitialized){if(lanMode)lanModel.start()else account.start()} }
    override fun onStop() { if (::account.isInitialized) account.stop();if (::model.isInitialized) model.stop();if(::lanModel.isInitialized)lanModel.stop();TaskMonitorService.background(this); super.onStop() }
}

@Composable fun PocketApp(model:PocketModel,scan:()->Unit,incoming:String?,dismissIncoming:()->Unit,account:AccountModel?=null,openAdmin:Boolean=false,consumeAdmin:()->Unit={},onExitLan:(()->Unit)?=null,onEnterLan:(()->Unit)?=null,onSwitchService:(()->Unit)?=null){
    var tab by rememberSaveable{mutableIntStateOf(0)}
    LaunchedEffect(openAdmin,account?.isAdmin){if(openAdmin){if(account?.isAdmin==true){model.back();tab=4};consumeAdmin()}}
    var showTools by remember{mutableStateOf(false)}
    var showPair by remember{mutableStateOf(false)}
    var showNew by remember{mutableStateOf(false)}
    var presetProject by remember{mutableStateOf<String?>(null)}
    val snack=remember{SnackbarHostState()}
    LaunchedEffect(model.notice){model.notice?.let{snack.showSnackbar(it);model.notice=null}}
    BackHandler(model.selected==null&&tab!=0){tab=0}
    BackHandler(model.selected!=null){if(model.previewKind!=null)model.closePreview()else model.back()}
    Scaffold(containerColor=MaterialTheme.colorScheme.background,snackbarHost={if(model.selected==null)SnackbarHost(snack)},
        topBar={if(model.selected==null){
            Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min=56.dp).padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
                if(tab==0){
                    CodexMark(Modifier.size(28.dp));Spacer(Modifier.width(8.dp))
                    Text("Codex Pocket",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(model.profile!=null)ConnectionBadge(model){tab=2}
                    PocketAction("工具","打开工作空间工具"){showTools=true}
                    PocketAction("设置","打开设置"){tab=3}
                }else{
                    PocketAction("返回","返回工作空间"){tab=0}
                    Text(when(tab){1->"任务动态";2->"我的电脑";4->"管理中心";5->"用户中心";else->"设置"},Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                    if(tab==1)PocketAction("刷新",enabled=model.connected){model.refresh()}
                }
            }
        }}
    ){padding->
        Column(Modifier.fillMaxSize().padding(if(model.selected==null)padding else PaddingValues()).imePadding()){
            if(model.selected!=null)key(model.selected?.optString("hostId"),model.selected?.optString("id")){ChatScreen(model,snack)}
            else{
                PendingCard(model);model.error?.let{ErrorBanner(it){model.error=null}}
                if(model.pairing&&tab !in 2..5)PairingWaiting(model)
                else if(model.profile==null&&tab !in 2..5)Welcome(scan,{showPair=true})
                else when(tab){
                    0,1->ThreadList(model,{tab=if(tab==0)1 else 0},{presetProject=it;showNew=true},tab==1,onCreate={presetProject=null;showNew=true})
                    2->ComputerScreen(model,scan,{showPair=true})
                    4,5->if(account?.isAdmin==true)key(tab){AdminScreen(account,if(tab==5)"all"else"pending")}else Text("需要管理员权限")
                    else->SettingsScreen(model,onComputer={tab=2},onTasks={tab=1},account=account,onAdmin={tab=4},onUsers={tab=5},onExitLan=onExitLan,onEnterLan=onEnterLan,onSwitchService=onSwitchService)
                }
            }
        }
    }
    if(showTools)ToolboxScreen(model,onClose={showTools=false})
    if(showPair||incoming!=null)PairDialog(incoming.orEmpty(),{showPair=false;dismissIncoming()}){model.pair(it);showPair=false;dismissIncoming()}
    if(showNew)NewTaskDialog(model,presetProject,{showNew=false}){text,project,id->model.create(text,project,id);showNew=false}
}

@Composable fun ErrorBanner(message:String,dismiss:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)) {
        Row(Modifier.padding(start=14.dp),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onErrorContainer);PocketAction("关闭",onClick=dismiss)}
    }
}

@Composable fun StatusPill(model:PocketModel) {
    Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if(model.connected)PocketGreen else MaterialTheme.colorScheme.tertiary));Spacer(Modifier.width(7.dp));Text(model.status,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}

@Composable fun PendingCard(model: PocketModel) {
    if(model.pendingOperation==null)return
    if(model.busy){ Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){ CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text(model.pendingStatus.ifBlank{"正在等待电脑确认操作…"},fontSize=12.sp) };return }
    var confirm by remember { mutableStateOf(false) }
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.tertiaryContainer),modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)) {
        Column(Modifier.padding(14.dp)) { Text("有一条操作等待确认",fontWeight=FontWeight.SemiBold);Text(model.pendingStatus.ifBlank{"断线或超时后会自动查询执行结果。请先查看会话，避免重复发送。"},fontSize=12.sp);Row { TextButton(onClick={model.confirmPending()},enabled=model.connected&&!model.busy){Text("重新确认")};TextButton(onClick={confirm=true}){Text("已查看，清除记录")} } }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("清除待确认记录？")},text={Text("请先在会话中检查这次操作是否已经执行。清除记录不会停止或撤销电脑上的任务。")},confirmButton={TextButton(onClick={model.clearPendingAfterInspection();confirm=false}){Text("已检查，清除")}},dismissButton={TextButton(onClick={confirm=false}){Text("返回检查")}})
}



fun statusLabel(status:String)=when(status){"running","inProgress","active","working"->"进行中";"needs_attention","waitingForInput","waiting"->"等待操作";"error","failed","systemError"->"出错";else->"可继续"}

@Composable fun EmptyState(title:String,subtitle:String) { Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=32.dp),horizontalAlignment=Alignment.CenterHorizontally){Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.size(56.dp)){Box(contentAlignment=Alignment.Center){PocketIcon("会话")}};Spacer(Modifier.height(18.dp));Text(title,fontWeight=FontWeight.SemiBold,fontSize=16.sp,textAlign=TextAlign.Center);Spacer(Modifier.height(8.dp));Text(subtitle,fontSize=13.sp,lineHeight=21.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)} }

@Composable fun PairDialog(initial:String,onDismiss:()->Unit,connect:(String)->Unit){var text by remember(initial){mutableStateOf(initial)};AlertDialog(onDismissRequest=onDismiss,title={Text("连接你的电脑")},text={Column{Text("粘贴电脑配对页面提供的完整配对内容。",fontSize=14.sp);Spacer(Modifier.height(14.dp));OutlinedTextField(text,{text=it},modifier=Modifier.fillMaxWidth().heightIn(min=130.dp,max=250.dp),label={Text("配对内容")},visualTransformation=PasswordVisualTransformation(),maxLines=6)}},confirmButton={TextButton(onClick={connect(text)},enabled=text.isNotBlank()){Text("连接")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})}

@Composable fun DropdownPicker(label:String,options:List<Pair<String,String>>,selected:String?,defaultLabel:String,onChange:(String?)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    Box { Surface(onClick={expanded=true},color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(label,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(6.dp));Text(options.find{it.first==selected}?.second?:selected?:defaultLabel,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium)};PocketIcon("下拉",Modifier.size(16.dp))}
    };DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){DropdownMenuItem(text={Text(defaultLabel)},onClick={onChange(null);expanded=false});options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={onChange(id);expanded=false})}} }
}

@Composable fun ChatScreen(model:PocketModel,snackbar:SnackbarHostState?=null) {
    val thread=model.selected?:return;val focus=LocalFocusManager.current
    val snapshot=model.snapshot;val messages=snapshot?.optJSONArray("messages").objects();val list=rememberLazyListState()
    val chatScope=rememberCoroutineScope()
    val pickImages=rememberImagePicker(model,thread.getString("id"))
    var showResults by remember{mutableStateOf(false)};var showSettings by remember{mutableStateOf(false)};var settingsSection by remember{mutableStateOf("model")};var expanded by remember{mutableStateOf(false)}
    var tools by remember{mutableStateOf<String?>(null)};var rename by remember{mutableStateOf(false)}
    var addressFocused by remember { mutableStateOf(false) }
    var previewCollapsed by remember{mutableStateOf(false)}
    LaunchedEffect(model.previewKind,model.previewArtifact?.optString("id"),model.previewUrl){if(model.previewKind!=null){focus.clearFocus();previewCollapsed=false}}
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val editingAddress = model.previewKind == "browser" && addressFocused && keyboardOpen
    val previewExpanded = (expanded || editingAddress) && !previewCollapsed
    val requests=snapshot?.optJSONArray("requests").objects();val activeId=snapshot?.optJSONObject("thread")?.optString("activeTurnId");val active=!activeId.isNullOrBlank()&&activeId!="null"
    val settings=snapshot?.optJSONObject("thread")?.optJSONObject("settings")?:JSONObject()
    val lastText=messages.lastOrNull()?.optString("text")?.length?:0
    var initialScroll by remember{mutableStateOf(false)}
    var followLatest by remember{mutableStateOf(true)}
    var lastScrollRequest by remember{mutableIntStateOf(model.scrollRequest)}
    val scrollConnection=remember { object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset { if(source==NestedScrollSource.UserInput)followLatest=false;return Offset.Zero }
    } }
    val followBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(list){snapshotFlow{list.canScrollForward}.collect{if(!it)followLatest=true}}
    LaunchedEffect(messages.lastOrNull()?.optString("key"),lastText,model.scrollRequest,previewExpanded,model.previewKind){
        if(lastScrollRequest!=model.scrollRequest){lastScrollRequest=model.scrollRequest;followLatest=true}
        if(messages.isNotEmpty()&&(!initialScroll||followLatest)&&!(previewExpanded&&model.previewKind!=null)){list.scrollToItem(messages.size+if(snapshot?.optJSONArray("artifacts").objects().isNotEmpty())1 else 0);initialScroll=true}
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding()) {
        var menu by remember{mutableStateOf(false)}
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
            PocketAction("返回","返回项目"){model.back()}
            Column(Modifier.weight(1f)){
                Text(thread.label(),fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                val project=model.projects.find{it.optString("projectId")==thread.optString("projectId")}
                Text(project?.label()?:snapshot?.optJSONObject("thread")?.optString("cwd")?.takeIf{it.isNotBlank()&&it!="null"}?.substringAfterLast('/')?:"独立对话",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            PocketAction("文件","打开项目文件"){tools="files"}
            PocketAction("网页","打开网页预览"){model.showPreviewTab("browser")}
            Box{
                PocketAction("更多","对话更多操作"){menu=true}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text("重命名对话")},onClick={menu=false;rename=true})
                    DropdownMenuItem(text={Text("工作空间工具")},onClick={menu=false;tools="home"})
                    DropdownMenuItem(text={Text("查看交付文件")},onClick={menu=false;showResults=true})
                    DropdownMenuItem(text={Text("模型与权限")},enabled=model.connected&&!model.loadingThread,onClick={menu=false;settingsSection="model";showSettings=true})
                    DropdownMenuItem(text={Text("刷新对话")},enabled=model.connected,onClick={menu=false;model.openThread(thread)})
                }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        if(!model.connected)Surface(color=MaterialTheme.colorScheme.tertiaryContainer){Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically){Text("连接恢复后可继续发送",Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={model.reconnectNow()}){Text("重新连接",fontSize=12.sp)}}}
        model.error?.let{ErrorBanner(it){model.error=null}}
        if(requests.isNotEmpty())Column(Modifier.heightIn(max=250.dp).verticalScroll(rememberScrollState()).padding(horizontal=14.dp)){requests.forEach{ApprovalCard(it,model)}}
        if(model.loadingThread)LinearProgressIndicator(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.secondary)
        if(model.previewKind!=null)PreviewPane(model,if(previewCollapsed)Modifier.fillMaxWidth() else Modifier.fillMaxWidth().weight(if(previewExpanded)1f else 1.1f),previewExpanded,toggleExpand={focus.clearFocus();previewCollapsed=false;expanded=!expanded},onAddressFocusChange={addressFocused=it},minimized=previewCollapsed,onMinimize={focus.clearFocus();previewCollapsed=!previewCollapsed;expanded=false})
        if(!(previewExpanded&&model.previewKind!=null))LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth().nestedScroll(scrollConnection),contentPadding=PaddingValues(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item(key="history-header") {
                val history=snapshot?.optJSONObject("history")
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                    if(history?.optBoolean("hasEarlier")==true)OutlinedButton(onClick=model::loadEarlier,enabled=model.connected&&!model.loadingHistory){Text(if(model.loadingHistory)"正在加载历史…" else "加载更早记录")}
                    Text("已显示 ${messages.size} / ${history?.optInt("total")?:messages.size} 条记录",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(snapshot!=null && snapshot.optBoolean("historyComplete")!=true)Text("完整历史尚未同步，返回后重新进入可重试。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                    if(snapshot?.optString("source")=="desktop-history-summary")Text("当前仅取得摘要，实时记录暂不可用。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                }
            }
            if(messages.isEmpty())item{EmptyState(if(model.loadingThread)"正在读取对话" else "工作从这里继续",if(model.connected)"发送指令，交给电脑上的 Codex。" else "连接后会读取最新内容。")}
            items(messages,key={it.optString("turnId")+":"+it.optString("id")}){message->MessageCard(message,model::openLink,onDetail={mode->model.showMessage(message,mode)},onRelated=model::openAgentThread,images={MessageImages(message.optJSONArray("attachments").objects().filter{it.optString("type")=="image"},model.connected,model::attachmentBitmap,model::preview);MessageFiles(message.optJSONArray("attachments").objects(),model.connected,model::preview)})}
            val artifacts=snapshot?.optJSONArray("artifacts").objects()
            if(artifacts.isNotEmpty())item{Surface(onClick={showResults=true},color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){PocketIcon("文件",Modifier.size(17.dp));Spacer(Modifier.width(9.dp));Text("查看 ${artifacts.size} 个交付文件",Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);PocketIcon("展开",Modifier.size(14.dp))}
            }}
        }
        snackbar?.let{SnackbarHost(it,Modifier.fillMaxWidth().padding(horizontal=14.dp))}
        if(!editingAddress)Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal=12.dp).padding(top=5.dp,bottom=if(keyboardOpen)4.dp else 8.dp)){
            if(messages.isNotEmpty()&&!followBottom&&!(previewExpanded&&model.previewKind!=null))Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){TextButton(onClick={followLatest=true;chatScope.launch{list.animateScrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}}){Text("跳到最新 ↓",fontSize=11.sp)}}
            PendingCard(model)
            FileMentionSuggestions(model){tools="files"}
            OutgoingImageStrip(model,thread.getString("id"))
            OutgoingFileStrip(model,thread.getString("id"))
            if(model.referencesFor().isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState())){model.referencesFor().forEach{path->InputChip(true,{model.removeReference(path)},label={Text("@$path ×",fontSize=11.sp)})}}
            model.imageStatus?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)}
            if(active)Row(Modifier.fillMaxWidth().padding(start=6.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp);Spacer(Modifier.width(8.dp));Text("Codex 正在处理",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));TextButton(onClick={model.stopTurn()},enabled=model.connected&&!model.busy&&model.pendingOperation==null){PocketIcon("停止",Modifier.size(13.dp));Spacer(Modifier.width(4.dp));Text("停止",fontSize=12.sp)}}
            Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                Row(Modifier.fillMaxWidth().padding(horizontal=3.dp,vertical=2.dp),verticalAlignment=Alignment.Bottom){
                    AttachmentPickerButton(model,thread.getString("id"))
                    BasicTextField(model.draft,{model.draft=it},textStyle=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),modifier=Modifier.weight(1f).heightIn(min=48.dp,max=136.dp).padding(top=13.dp,bottom=13.dp,end=4.dp).semantics{contentDescription="聊天输入框"},maxLines=5,decorationBox={inner->
                        Box { if(model.draft.isEmpty())Text(if(active)"补充你的想法…" else "继续向 Codex 发送指令…",fontSize=13.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis);inner() }
                    })
                    val canSend=model.connected&&!model.busy&&(model.draft.isNotBlank()||model.imagesFor(thread.getString("id")).isNotEmpty()||model.filesFor(thread.getString("id")).isNotEmpty())&&model.pendingOperation==null
                    IconButton(onClick={model.send(model.draft)},enabled=canSend,modifier=Modifier.size(48.dp).semantics{contentDescription="发送"}){
                        Surface(Modifier.size(36.dp),shape=CircleShape,color=if(canSend)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,contentColor=if(canSend)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f)){
                            Box(contentAlignment=Alignment.Center){if(model.busy)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.5.dp)else PocketIcon("发送",Modifier.size(18.dp))}
                        }
                    }
                }
            }
            if(!keyboardOpen){Spacer(Modifier.height(3.dp));ComposerSettings(settings,model.connected&&!model.loadingThread&&!model.busy){section->focus.clearFocus();settingsSection=section;showSettings=true}}
        }
    }

    if(rename)RenameThreadDialog(model,thread){rename=false}
    tools?.let{initial->ToolboxScreen(model,initial){tools=null}}
    if(showSettings)ThreadSettingsSheet(model,settingsSection){showSettings=false}
    if(showResults)ResultsDialog(model){showResults=false}
    if(model.detailMessage!=null)MessageDetails(model)
}

@Composable fun ArtifactRow(file:JSONObject,onClick:()->Unit) {
    Surface(onClick=onClick,color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(10.dp),modifier=Modifier.size(36.dp)){Box(contentAlignment=Alignment.Center){PocketIcon(if(file.optString("mime").startsWith("image/"))"图片" else "文件",Modifier.size(18.dp))}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(file.optString("name"),fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${file.optLong("size")/1024} KB · 点击预览",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};PocketIcon("展开",Modifier.size(14.dp))}
    }
}

fun permissionLabel(mode:String)=when(mode){"read-only"->"只读";"auto"->"工作区权限";"full-access"->"完全访问";else->"自定义权限"}
fun effortLabel(value:String)=when(value){"none"->"无";"minimal"->"最低";"low"->"低";"medium"->"中";"high"->"高";"xhigh"->"极高";"max"->"最大";"ultra"->"超高";else->"默认"}





@Composable fun MessageDetails(model:PocketModel) {
    Dialog(onDismissRequest=model::closeMessage,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","关闭记录详情",onClick=model::closeMessage);Text(model.detailMessage?.optString("title")?.ifBlank{"完整内容"}?:"完整内容",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Text(if(model.detailDone)"已加载完整" else "分段加载",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(model.detailChunks){chunk->SelectionContainer{Text(chunk,fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=19.sp)}}
                model.detailError?.let{item{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}}
            }
            if(!model.detailDone)Button(onClick=model::loadMoreMessage,enabled=!model.detailLoading&&model.connected,modifier=Modifier.fillMaxWidth().padding(16.dp)){Text(if(model.detailLoading)"正在读取…" else if(model.detailError!=null)"重试" else "继续加载完整内容")}
        }}
    }
}

@Composable fun MarkdownText(text:String,modifier:Modifier=Modifier,onLink:(String)->Unit={}){
    val colors=MaterialTheme.colorScheme;val context=LocalContext.current
    val linkCallback by rememberUpdatedState(onLink)
    fun argb(c:Color)=android.graphics.Color.argb((c.alpha*255).toInt(),(c.red*255).toInt(),(c.green*255).toInt(),(c.blue*255).toInt())
    val markwon=remember(context,colors){Markwon.builder(context).usePlugin(object:AbstractMarkwonPlugin(){
        override fun configureConfiguration(builder:MarkwonConfiguration.Builder){builder.linkResolver{_,link->linkCallback(link)}}
        override fun configureTheme(builder:io.noties.markwon.core.MarkwonTheme.Builder){builder.linkColor(argb(colors.primary)).codeBackgroundColor(argb(colors.surfaceVariant)).codeBlockBackgroundColor(argb(colors.surfaceVariant)).blockQuoteColor(argb(colors.primary))}
    }).build()}
    AndroidView(modifier=modifier,factory={TextView(it).apply{textSize=15f;setTextIsSelectable(true);setLineSpacing(4f,1.15f)}},update={it.setTextColor(argb(colors.onSurface));it.setLinkTextColor(argb(colors.primary));markwon.setMarkdown(it,text)})
}

@Composable fun ApprovalCard(request:JSONObject,model:PocketModel) {
    if(request.optString("method") in listOf("item/permissions/requestApproval","mcpServer/elicitation/request")){ExtendedApprovalCard(request,model);return}
    val method=request.optString("method");val params=request.optJSONObject("params")?:JSONObject()
    val question=method=="item/tool/requestUserInput"
    val supported=question||method=="item/commandExecution/requestApproval"||method=="item/fileChange/requestApproval"
    var details by remember(request.optString("id")){mutableStateOf(false)}
    val enabled=model.connected&&!model.busy&&model.pendingOperation==null
    Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(question)"Codex 需要你的回答" else "等待你的审批",fontWeight=FontWeight.SemiBold,fontSize=14.sp,modifier=Modifier.weight(1f));TextButton(onClick={details=true}){Text("查看详情",fontSize=12.sp)}}
            val summary=if(question)params.optJSONArray("questions").objects().firstOrNull()?.optString("question").orEmpty() else params.optString("command").ifBlank{params.optString("reason")}
            if(summary.isNotBlank())Text(summary,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,fontFamily=if(question)FontFamily.SansSerif else FontFamily.Monospace)
            if(!supported)Text("此类授权请在电脑处理。",fontSize=12.sp)
            else if(question)Button(onClick={details=true},enabled=enabled){Text("回答问题")}
            else Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                val offered=params.optJSONArray("availableDecisions")?.let{a->(0 until a.length()).map{a.get(it)}}
                if(offered==null||"accept" in offered)Button(onClick={model.approve(request,"accept")},enabled=enabled){Text("允许本次")}
                if(offered==null||"decline" in offered)OutlinedButton(onClick={model.approve(request,"decline")},enabled=enabled){Text("拒绝")}
                if(offered!=null&&"accept" !in offered)Button(onClick={details=true},enabled=enabled){Text("查看并审批")}
            }
        }
    }
    if(details) {
        var answers by remember(request.optString("id")){mutableStateOf(mapOf<String,String>())}
        Dialog(onDismissRequest={details=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically){Text(if(question)"回答 Codex" else "审批详情",fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));PocketAction("关闭"){details=false}}
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        if(!question)ApprovalDecisionList(request,model){details=false}
                        if(question)params.optJSONArray("questions").objects().forEach{q->
                            val id=q.getString("id");Text(q.optString("question"),fontWeight=FontWeight.Medium)
                            q.optJSONArray("options").objects().forEach{o->FilterChip(selected=answers[id]==o.optString("label"),onClick={answers=answers+(id to o.optString("label"))},label={Column{Text(o.optString("label"));val d=o.optString("description");if(d.isNotBlank())Text(d,fontSize=11.sp)}})}
                            OutlinedTextField(answers[id].orEmpty(),{answers=answers+(id to it)},label={Text("回答")},modifier=Modifier.fillMaxWidth(),visualTransformation=if(q.optBoolean("isSecret"))PasswordVisualTransformation()else VisualTransformation.None)
                        }else {
                            val command=params.optString("command");if(command.isNotBlank()){Text("命令",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);SelectionContainer{Text(command,fontFamily=FontFamily.Monospace,fontSize=13.sp,lineHeight=21.sp)}}
                            val reason=params.optString("reason");if(reason.isNotBlank()&&reason!="null")Text(reason,fontSize=14.sp)
                            if(!params.isNull("cwd")){Text("工作目录",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);SelectionContainer{Text(params.optString("cwd"),fontSize=12.sp)}}
                            if(method=="item/fileChange/requestApproval")SelectionContainer{Text(params.toString(2),fontFamily=FontFamily.Monospace,fontSize=12.sp)}
                        }
                    }
                    if(supported&&question)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(question)Button(onClick={val response=JSONObject();answers.forEach{(id,value)->response.put(id,JSONObject().put("answers",org.json.JSONArray().put(value)))};model.approve(request,"answer",response);details=false},enabled=enabled&&params.optJSONArray("questions").objects().all{answers[it.optString("id")]?.isNotBlank()==true},modifier=Modifier.fillMaxWidth()){Text("提交回答")}
                        else {OutlinedButton(onClick={model.approve(request,"decline");details=false},enabled=enabled,modifier=Modifier.weight(1f)){Text("拒绝")};Button(onClick={model.approve(request,"accept");details=false},enabled=enabled,modifier=Modifier.weight(1f)){Text("允许本次")}}
                    }
                }
            }
        }
    }
}

@Composable fun ResultsDialog(model:PocketModel,onDismiss:()->Unit){
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.statusBarsPadding().navigationBarsPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    PocketAction("返回","关闭交付文件",onClick=onDismiss)
                    Text("交付与修改",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    val artifacts=model.snapshot?.optJSONArray("artifacts").objects()
                    item{Text("交付文件",fontWeight=FontWeight.SemiBold,fontSize=14.sp)}
                    if(artifacts.isEmpty())item{EmptyState("暂无交付文件","当前对话可访问的文件和图片会显示在这里。")}
                    items(artifacts){file->
                        Column{
                            ArtifactRow(file){onDismiss();model.preview(file)}
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick={model.download(file)},enabled=model.connected&&model.downloadStatus==null){Text("保存到手机",fontSize=12.sp)}}
                        }
                    }
                    model.downloadStatus?.let{item{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)}}
                    val diffs=model.snapshot?.optJSONArray("messages").objects().filter{it.optString("type") in listOf("fileChange","turnDiff")}
                    item{Text("代码修改",Modifier.padding(top=12.dp),fontWeight=FontWeight.SemiBold,fontSize=14.sp)}
                    if(diffs.isEmpty())item{Text("已加载的记录中暂无代码修改；可在对话顶部加载更早记录。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(diffs){diff->MessageCard(diff,model::openLink,onDetail={mode->onDismiss();model.showMessage(diff,mode)})}
                    model.error?.let{item{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}}
                }
            }
        }
    }
}

@Composable fun PairingWaiting(model:PocketModel) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(36.dp))
        if(model.error==null)CircularProgressIndicator(Modifier.size(38.dp),strokeWidth=3.dp)
        Text(if(model.error==null)"配对中，等待电脑确认"else"配对尚未完成",fontSize=21.sp,fontWeight=FontWeight.SemiBold)
        Text(model.profile?.computerName().orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(model.error==null)"请在电脑连接器中点击「允许连接」。确认后会自动打开工作空间。"else"可返回电脑更新二维码，然后重新扫码。",fontSize=14.sp,lineHeight=23.sp)
        if(model.status.contains("网络"))Text(model.status,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick=model::cancelPairing){Text("取消配对")}
    }
}
