package dev.codexpocket.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private fun ByteArray.sha256()=MessageDigest.getInstance("SHA-256").digest(this).joinToString(""){"%02x".format(it)}
private fun File.sha256():String{
    val digest=MessageDigest.getInstance("SHA-256")
    inputStream().use{input->val buffer=ByteArray(64*1024);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
    return digest.digest().joinToString(""){"%02x".format(it)}
}
private fun pemBytes(value:String)=Base64.decode(value.lineSequence().filterNot{it.startsWith("-----")}.joinToString(""),Base64.DEFAULT)
data class UpdateConfig(val manifestUrl:String,val pin:String,val keyId:String,val publicKey:ByteArray){
    companion object{
        fun parse(json:JSONObject):UpdateConfig{
            val url=json.getString("manifestUrl").toHttpUrl()
            require(url.isHttps&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null){"更新服务地址无效。"}
            val pin=json.getString("pin");val key=pemBytes(json.getString("publicKey"));val keyId=json.getString("keyId")
            val prefix=byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)
            require(pin.matches(Regex("[a-f0-9]{64}"))&&key.size==44&&key.take(12).toByteArray().contentEquals(prefix)&&key.sha256().take(16)==keyId){"更新信任配置无效。"}
            return UpdateConfig(url.toString(),pin,keyId,key.copyOfRange(12,44))
        }
        fun bundled(context:Context)=parse(JSONObject(context.assets.open("updates.json").bufferedReader().use{it.readText()}))
    }
}
data class UpdateAsset(val name:String,val url:String,val size:Long,val sha256:String)
data class AppRelease(val version:String,val build:Int,val notes:String,val publishedAt:String,val asset:UpdateAsset)

class UpdateClient(val config:UpdateConfig){
    @Volatile private var call:Call?=null
    private fun client(timeout:Long)=PocketConnection.httpClient(config.pin).newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(timeout,TimeUnit.SECONDS).build()
    fun check():AppRelease{
        val request=Request.Builder().url(config.manifestUrl).header("Cache-Control","no-cache").header("Accept-Encoding","identity").build()
        val next=client(12).newCall(request);call=next
        try{next.execute().use{response->
            check(response.isSuccessful){"更新服务暂不可用（${response.code}），请稍后重试。"}
            val bytes=response.body?.byteStream()?.use{input->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(out.size()<=128*1024){val count=input.read(buffer,0,minOf(buffer.size,128*1024+1-out.size()));if(count<0)break;out.write(buffer,0,count)}
                out.toByteArray()
            }?:error("更新清单为空。")
            return parse(bytes)
        }}finally{call=null}
    }
    fun parse(bytes:ByteArray):AppRelease{
        require(bytes.size<=128*1024){"更新清单过大。"}
        val payload=try{
            val envelope=JSONObject(String(bytes,Charsets.UTF_8))
            require(envelope.getInt("schema")==1&&envelope.getString("algorithm")=="Ed25519"&&envelope.getString("keyId")==config.keyId)
            val data=Base64.decode(envelope.getString("payload"),Base64.DEFAULT);val signature=Base64.decode(envelope.getString("signature"),Base64.DEFAULT)
            val verifier=Ed25519Signer();verifier.init(false,Ed25519PublicKeyParameters(config.publicKey,0));verifier.update(data,0,data.size)
            require(signature.size==64&&verifier.verifySignature(signature))
            JSONObject(String(data,Charsets.UTF_8))
        }catch(_:Exception){error("更新清单验签失败，请稍后重试。")}
        try{
            require(payload.getInt("schema")==1&&payload.getString("channel")=="stable")
            val version=payload.getString("version");UpdateVersion.parts(version)
            val build=payload.getLong("build");require(build in 1..Int.MAX_VALUE)
            val notes=payload.getString("notes");require(notes.length<=8192)
            val publishedAt=payload.getString("publishedAt");java.time.Instant.parse(publishedAt)
            val assets=payload.getJSONArray("assets");require(assets.length()<=16)
            val base=config.manifestUrl.toHttpUrl();val names=mutableSetOf<String>();var selected:UpdateAsset?=null
            for(index in 0 until assets.length()){
                val asset=assets.getJSONObject(index);val name=asset.getString("name");val url=asset.getString("url").toHttpUrl()
                val size=asset.getLong("size");val hash=asset.getString("sha256")
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}"))&&names.add(name))
                require(url.isHttps&&url.host==base.host&&url.port==base.port&&url.username.isEmpty()&&url.password.isEmpty()&&url.fragment==null&&url.query==null)
                require(url.encodedPath=="/packages/$version/$name"&&size in 1..1024L*1024*1024&&hash.matches(Regex("[a-f0-9]{64}")))
                if(asset.optString("platform")=="android"&&asset.optString("kind")=="apk"&&name=="Codex-Pocket-$version.apk")selected=UpdateAsset(name,url.toString(),size,hash)
            }
            return AppRelease(version,build.toInt(),notes,publishedAt,selected?:error("这个版本暂无安卓安装包。"))
        }catch(e:Exception){if(e.message=="这个版本暂无安卓安装包。")throw e;error("更新清单格式无效，请稍后重试。")}
    }
    fun download(context:Context,release:AppRelease,progress:(Long)->Unit):File{
        val asset=release.asset;val directory=File(context.cacheDir,"updates/${release.version}").apply{mkdirs()}
        val target=File(directory,asset.name);val partial=File(directory,asset.name+".partial")
        val next=client(600).newCall(Request.Builder().url(asset.url).header("Accept-Encoding","identity").build());call=next
        try{
            partial.delete();var received=0L;val hash=MessageDigest.getInstance("SHA-256")
            next.execute().use{response->
                check(response.isSuccessful){"更新服务暂不可用（${response.code}），请稍后重试。"}
                val input=response.body?.byteStream()?:error("安装包为空。")
                input.use{source->partial.outputStream().use{output->
                    val buffer=ByteArray(64*1024);var lastProgress=-1
                    while(true){val count=source.read(buffer);if(count<0)break;received+=count;check(received<=asset.size){"安装包大小校验失败，请重新下载。"};output.write(buffer,0,count);hash.update(buffer,0,count)
                        val percent=(received*100/asset.size).toInt();if(percent!=lastProgress){lastProgress=percent;progress(received)}}
                }}
            }
            check(received==asset.size&&hash.digest().joinToString(""){"%02x".format(it)}==asset.sha256){"安装包完整性校验失败，请重新下载。"}
            verifyApk(context,partial,release)
            if(target.exists())check(target.delete()){"无法保存安装包，请重试。"}
            check(partial.renameTo(target)){"无法保存安装包，请重试。"};return target
        }finally{partial.delete();call=null}
    }
    fun cancel(){call?.cancel()}
}

@Suppress("DEPRECATION")
fun verifyApk(context:Context,file:File,release:AppRelease){
    val flags=PackageManager.GET_SIGNING_CERTIFICATES
    val downloaded=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("安装包格式无效。")
    val installed=context.packageManager.getPackageInfo(context.packageName,flags)
    check(downloaded.packageName==context.packageName&&downloaded.versionName==release.version&&downloaded.longVersionCode==release.build.toLong()){"安装包版本或应用信息不匹配。"}
    val expected=installed.signingInfo?.apkContentsSigners?.map{it.toByteArray().sha256()}?.toSet()
    val actual=downloaded.signingInfo?.apkContentsSigners?.map{it.toByteArray().sha256()}?.toSet()
    check(!expected.isNullOrEmpty()&&expected==actual){"安装包签名与当前应用不一致，请联系维护者。"}
}

class AppUpdateModel(private val context:Context,private val source:UpdateClient?=null,val currentVersion:String=BuildConfig.VERSION_NAME,val currentBuild:Int=BuildConfig.VERSION_CODE){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var job:Job?=null;private var client:UpdateClient?=source
    var status by mutableStateOf("idle");private set
    var release by mutableStateOf<AppRelease?>(null);private set
    var error by mutableStateOf<String?>(null);private set
    var download by mutableStateOf("idle");private set
    var received by mutableLongStateOf(0);private set
    var installMessage by mutableStateOf<String?>(null);private set
    private var file:File?=null
    val busy get()=status=="checking"||download=="downloading"
    private fun service()=client?:UpdateClient(UpdateConfig.bundled(context)).also{client=it}
    fun check(){
        if(busy)return
        status="checking";release=null;error=null;download="idle";file=null;received=0;installMessage=null
        job=scope.launch{try{
            val result=withContext(Dispatchers.IO){service().check()};release=result
            status=if(UpdateVersion.compare(result.version,currentVersion)>0){check(result.build>currentBuild){"更新安装包的构建号无效，请联系维护者。"};"available"}else"current"
        }catch(e:CancellationException){throw e}catch(e:Exception){status="error";release=null;error=message(e)}}
    }
    fun download(){
        val target=release?:return;if(busy||status!="available"||download=="ready")return
        error=null;download="downloading";received=0
        job=scope.launch{try{
            file=withContext(Dispatchers.IO){service().download(context,target){count->scope.launch{received=count}}};download="ready"
        }catch(e:CancellationException){throw e}catch(e:Exception){download="error";error=message(e)}}
    }
    fun installIntent():Intent{
        val target=file?:error("请先完成下载。" );val info=release?:error("请先检测更新。")
        check(download=="ready"&&target.exists()&&target.length()==info.asset.size&&target.sha256()==info.asset.sha256){"安装包已改变，请重新下载。"}
        verifyApk(context,target,info)
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",target)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun install(){
        if(busy||download!="ready")return
        job=scope.launch{try{
            error=null
            if(!context.packageManager.canRequestPackageInstalls()){
                installMessage="请允许 Codex Pocket 安装应用，返回后再次点击「安装更新」。"
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }else{
                val intent=withContext(Dispatchers.IO){installIntent()};context.startActivity(intent);installMessage="请在系统安装页面确认更新，原账号和配对信息会保留。"
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(e.message?.contains("安装包")==true){download="error";file=null};error=message(e)}}
    }
    fun close(){client?.cancel();job?.cancel();scope.cancel()}
    private fun message(e:Exception)=e.message?.takeIf{it.contains("更新")||it.contains("安装包")||it.contains("检测") }?:"暂时无法连接更新服务，请检查网络后重试。"
}
