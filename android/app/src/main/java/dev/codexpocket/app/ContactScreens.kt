package dev.codexpocket.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate

private fun emptyContacts()=JSONObject().put("title","联系与交流").put("description","").put("wechatId","").put("personalQr","").put("groupQr","").put("groupExpiresOn","")
internal fun contactBitmap(value:String):Bitmap?=try{
    if(value.length>700000||!value.startsWith("data:image/"))null else {
        val bytes=Base64.decode(value.substringAfter(','),Base64.NO_WRAP)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bytes.size>512*1024||bounds.outWidth !in 64..4096||bounds.outHeight !in 64..4096)null else BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    }
}catch(_:Exception){null}

class ContactsModel(private val context:Context,private val account:AccountModel?=null,val api:AccountApi=account?.api?:AccountApi(AccountApi.defaults(context))){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val store=SecureStore(context,"pocket-contacts${AccountApi.suffix(context,api.defaults)}.enc")
    private val builtin=AccountApi.same(api.defaults,AccountApi.bundled(context))
    var contacts by mutableStateOf(try{store.load().optJSONObject("contacts")?:defaults()}catch(_:Exception){defaults()});private set
    var busy by mutableStateOf(false);private set
    var editable by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    var error by mutableStateOf<String?>(null);private set
    private fun defaults()=if(builtin)JSONObject(context.assets.open("contacts.json").bufferedReader().use{it.readText()})else emptyContacts()
    fun close(){scope.cancel()}
    fun refresh(){if(busy)return;busy=true;error=null;message=null;scope.launch{try{
        val result=withContext(Dispatchers.IO){api.post("/api/account/contacts").getJSONObject("contacts")}
        contacts=result;editable=true;store.save(JSONObject().put("contacts",result))
    }catch(e:CancellationException){throw e}catch(e:Exception){editable=false;error=if(e is AccountException&&e.status==404)"当前服务尚未升级联系方式管理功能。"else"暂时无法刷新联系方式，请稍后重试。"}finally{busy=false}}}
    fun save(value:JSONObject){if(busy||!editable)return;busy=true;error=null;message=null;scope.launch{try{
        val result=account?.saveContacts(value)?:throw IllegalStateException("需要管理员权限")
        contacts=result;store.save(JSONObject().put("contacts",result));message="联系方式已保存，用户重新打开页面即可看到。"
    }catch(e:CancellationException){throw e}catch(e:Exception){error=when{e is AccountException&&e.status==404->"当前服务尚未升级联系方式管理功能。";e is AccountException&&e.status<500->e.message?:"保存失败，请重试";else->"保存失败，请检查网络后重试。"}}finally{busy=false}}}
}

@Composable fun ContactEntry(account:AccountModel?=null,admin:Boolean=false){
    val context=LocalContext.current.applicationContext
    var open by remember{mutableStateOf(false)}
    UtilityRow(if(admin)"联系方式管理"else"联系我们",if(admin)"更换微信二维码、联系说明与群码有效期"else"加入用户群 · 添加个人微信","会话"){open=true}
    if(open){
        val model=remember(account){ContactsModel(context,account)}
        DisposableEffect(model){onDispose{model.close()}}
        Dialog(onDismissRequest={if(!model.busy)open=false},properties=DialogProperties(usePlatformDefaultWidth=false)){
            ContactScreen(model,admin){open=false}
        }
    }
}

@Composable fun ContactScreen(model:ContactsModel,admin:Boolean=false,onBack:()->Unit){
    val context=LocalContext.current;val clipboard=LocalClipboardManager.current;val scope=rememberCoroutineScope()
    val colors=MaterialTheme.colorScheme
    var draft by remember(model.contacts){mutableStateOf(JSONObject(model.contacts.toString()))}
    var expanded by remember{mutableStateOf<Pair<String,String>?>(null)}
    var localMessage by remember{mutableStateOf<String?>(null)}
    var picking by remember{mutableStateOf("personalQr")}
    var processing by remember{mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)scope.launch{
        processing=true;localMessage=null
        try{val value=withContext(Dispatchers.IO){prepareContactImage(context,uri)};draft=JSONObject(draft.toString()).put(picking,value)}
        catch(e:CancellationException){throw e}catch(e:Exception){localMessage=e.message?:"无法读取图片，请重新选择"}finally{processing=false}
    }}
    LaunchedEffect(model){model.refresh()}
    val data=if(admin)draft else model.contacts
    Surface(Modifier.fillMaxSize(),color=colors.background){Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","返回设置",enabled=!model.busy&&!processing,onClick=onBack);Text(if(admin)"联系方式管理"else"联系我们",fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
        HorizontalDivider(color=colors.outlineVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(model.busy||processing)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(admin) {
                Text("让用户找到你。",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                Text("保存后对当前服务的用户生效。群码失效时，可以随时在这里换新。",fontSize=13.sp,color=colors.onSurfaceVariant)
                model.error?.let{Text(it,color=colors.error)}
                listOf("title" to "页面标题","description" to "联系说明","wechatId" to "个人微信号（选填）","groupExpiresOn" to "群码有效期（年-月-日，选填）").forEach{(key,label)->
                    OutlinedTextField(data.optString(key),{draft=JSONObject(draft.toString()).put(key,it.take(if(key=="description")500 else if(key=="title")60 else if(key=="wechatId")64 else 10))},label={Text(label)},modifier=Modifier.fillMaxWidth(),enabled=model.editable&&!model.busy&&!processing,singleLine=key!="description",minLines=if(key=="description")3 else 1)
                }
            }else{
                CodexMark(Modifier.size(40.dp));Text(data.optString("title").ifBlank{"联系与交流"},fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                data.optString("description").takeIf{it.isNotBlank()}?.let{Text(it,fontSize=14.sp,lineHeight=23.sp,color=colors.onSurfaceVariant)}
                if(model.error!=null)Text("暂时无法刷新，以下为已保存的联系方式。",fontSize=12.sp,color=colors.onSurfaceVariant)
            }
            listOf("personalQr" to "个人微信","groupQr" to "微信用户群").forEach{(key,label)->
                val value=data.optString(key);val bitmap=remember(value){contactBitmap(value)}
                if(admin||bitmap!=null||(key=="personalQr"&&data.optString("wechatId").isNotBlank()))Surface(shape=RoundedCornerShape(16.dp),color=colors.surface,border=BorderStroke(1.dp,colors.outlineVariant)){
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text(label,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                        if(key=="groupQr"&&data.optString("groupExpiresOn").isNotBlank()){
                            val date=data.optString("groupExpiresOn");val expired=try{!LocalDate.now().isBefore(LocalDate.parse(date))}catch(_:Exception){false}
                            Text(if(expired)"群二维码已到期，请添加个人微信获取新群码。"else"请在 $date 前使用，实际有效期以微信为准。",fontSize=12.sp,color=if(expired)colors.error else colors.onSurfaceVariant,modifier=Modifier.testTag("contact-expiry"))
                        }
                        if(bitmap!=null){
                            Image(bitmap.asImageBitmap(),"$label 二维码，点击放大",Modifier.fillMaxWidth().heightIn(max=330.dp).background(Color.White,RoundedCornerShape(10.dp)).clickable{expanded=label to value}.padding(8.dp),contentScale=ContentScale.Fit)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                TextButton(onClick={expanded=label to value}){Text("查看大图")}
                                TextButton(onClick={scope.launch{localMessage=try{withContext(Dispatchers.IO){saveContactImage(context,value)};"二维码已保存到相册，可在微信扫一扫中从相册选择。"}catch(e:CancellationException){throw e}catch(_:Exception){"保存失败，请稍后重试。"}}}){Text("保存到相册")}
                            }
                        }else if(admin)Text("尚未设置二维码",fontSize=13.sp,color=colors.onSurfaceVariant)
                        if(key=="personalQr"&&data.optString("wechatId").isNotBlank()){
                            Text("微信号：${data.optString("wechatId")}",fontSize=14.sp)
                            TextButton(onClick={clipboard.setText(AnnotatedString(data.optString("wechatId")));localMessage="微信号已复制"}){Text("复制微信号")}
                        }
                        if(admin)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedButton(onClick={picking=key;picker.launch("image/*")},enabled=model.editable&&!model.busy&&!processing){Text(if(value.isBlank())"上传二维码"else"更换二维码")}
                            if(value.isNotBlank())TextButton(onClick={draft=JSONObject(draft.toString()).put(key,"")},enabled=model.editable&&!model.busy&&!processing){Text("移除")}
                        }
                    }
                }
            }
            if(!admin&&data.optString("personalQr").isBlank()&&data.optString("groupQr").isBlank()&&data.optString("wechatId").isBlank()){
                Text("管理员尚未设置联系方式。")
            }
            model.message?.let{Text(it,color=colors.primary)};localMessage?.let{Text(it,fontSize=13.sp,color=colors.primary,modifier=Modifier.testTag("contact-message"))}
            if(admin)Button(onClick={model.save(draft)},enabled=model.editable&&!model.busy&&!processing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("保存联系方式")}
            OutlinedButton(onClick={model.refresh()},enabled=!model.busy&&!processing,modifier=Modifier.fillMaxWidth()){Text("刷新联系方式")}
        }
    }}
    expanded?.let{(label,value)->Dialog(onDismissRequest={expanded=null},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=colors.background){Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);TextButton(onClick={expanded=null}){Text("关闭大图")}}
            val bitmap=remember(value){contactBitmap(value)}
            bitmap?.let{Image(it.asImageBitmap(),"$label 二维码大图",Modifier.fillMaxWidth().weight(1f).background(Color.White),contentScale=ContentScale.Fit)}
            Text("保存后，打开微信扫一扫，从相册选择二维码。",Modifier.padding(vertical=16.dp),fontSize=12.sp,color=colors.onSurfaceVariant)
        }}
    }}
}

private fun prepareContactImage(context:Context,uri:Uri):String {
    val bytes=context.contentResolver.openInputStream(uri)?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192);while(output.size()<=8*1024*1024){val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n)};output.toByteArray()}?:error("无法打开图片")
    require(bytes.size<=8*1024*1024){"原图不能超过 8 MB"}
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth in 64..12000&&bounds.outHeight in 64..12000){"请选择有效的二维码图片"}
    val options=BitmapFactory.Options().apply{while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>1800)inSampleSize*=2}
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?:error("无法读取图片")
    try{val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,92,output);require(output.size()<=512*1024){"图片过大，请裁剪到二维码后重试"};return "data:image/jpeg;base64,"+Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)}finally{bitmap.recycle()}
}
internal fun saveContactImage(context:Context,value:String):Uri {
    val bitmap=contactBitmap(value)?:error("二维码无效")
    val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"Codex-Pocket-WeChat-${System.currentTimeMillis()}.png");put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/CodexPocket");put(MediaStore.Images.Media.IS_PENDING,1)}
    val resolver=context.contentResolver;val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("无法创建相册图片")
    try{resolver.openOutputStream(uri)?.use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}?:error("无法保存图片");check(resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)==1);return uri}
    catch(e:Exception){resolver.delete(uri,null,null);throw e}finally{bitmap.recycle()}
}
