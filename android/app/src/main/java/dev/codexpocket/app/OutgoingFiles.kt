package dev.codexpocket.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class OutgoingFile(val id:String,val path:String,val name:String,val mime:String,val size:Long) {
    fun json()=JSONObject().put("id",id).put("path",path).put("name",name).put("mime",mime).put("size",size)
    companion object {
        const val MAX_SIZE=50L*1024*1024
        fun restore(value:JSONObject)=OutgoingFile(value.getString("id"),value.getString("path"),value.getString("name"),value.getString("mime"),value.getLong("size"))
        fun import(context:Context,uri:Uri):OutgoingFile {
            var name=if(uri.scheme=="file")File(uri.path.orEmpty()).name.ifBlank{"附件"}else"附件";var declared=-1L
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor->
                if(cursor.moveToFirst()) { val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))declared=cursor.getLong(s) }
            }
            require(declared<=MAX_SIZE){"单个文件最多 50 MB"}
            name=name.replace(Regex("[\\x00-\\x1f<>:\"/\\\\|?*]"),"_").trim().trimEnd('.').take(150).ifBlank{"附件"}
            if(Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$").matches(name))name="file-$name"
            val mime=context.contentResolver.getType(uri)?.takeIf{it.matches(Regex("[-\\w.+]+/[-\\w.+]+"))}?:"application/octet-stream"
            val id=UUID.randomUUID().toString();val dir=File(context.filesDir,"outgoing-files").apply{mkdirs()};val file=File(dir,id)
            try {
                val total=context.contentResolver.openInputStream(uri)?.use { input->file.outputStream().use { output->val bytes=ByteArray(64*1024);var size=0L;while(true){val count=input.read(bytes);if(count<0)break;size+=count;require(size<=MAX_SIZE){"单个文件最多 50 MB"};output.write(bytes,0,count)};size } }?:error("无法读取选中的文件")
                return OutgoingFile(id,file.path,name,mime,total)
            }catch(e:Exception){file.delete();throw e}
        }
    }
}

val LocalOutgoingFilePicker=staticCompositionLocalOf<((PocketModel,String)->Unit)?>{null}
@Composable fun rememberFilePicker(model:PocketModel,key:String):()->Unit {
    val picker=LocalOutgoingFilePicker.current
    if(picker!=null)return {picker(model,key)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->model.addFiles(uris,key)}
    return {launcher.launch(arrayOf("*/*"))}
}

@Composable fun AttachmentPickerButton(model:PocketModel,key:String) {
    var menu by remember{mutableStateOf(false)}
    val images=rememberImagePicker(model,key);val files=rememberFilePicker(model,key)
    Box {
        IconButton(onClick={menu=true},enabled=!model.busy&&model.pendingOperation==null,modifier=Modifier.size(48.dp).semantics{contentDescription="添加附件"}){PocketIcon("附件",Modifier.size(21.dp))}
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
            DropdownMenuItem(text={Text("添加图片")},leadingIcon={PocketIcon("图片")},onClick={menu=false;images()})
            DropdownMenuItem(text={Column{Text("添加文件");Text(if(model.supportsFiles)"文档、代码等 · 单个最多 50 MB"else"请先更新电脑连接器",fontSize=11.sp)}},leadingIcon={PocketIcon("文件")},enabled=model.supportsFiles,onClick={menu=false;files()})
        }
    }
}

@Composable fun OutgoingFileStrip(model:PocketModel,key:String) {
    model.filesFor(key).forEach { file->
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){PocketIcon("文件",Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(file.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp);Text("${file.size/1024} KB",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};PocketAction("关闭","移除文件 ${file.name}",!model.busy){model.removeFile(key,file.id)}}
        }
    }
}

@Composable fun MessageFiles(files:List<JSONObject>,connected:Boolean,preview:(JSONObject)->Unit) {
    files.filter{it.optString("type")=="file"}.forEach { file->
        OutlinedButton(onClick={preview(file)},enabled=connected&&file.optBoolean("available"),modifier=Modifier.fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {PocketIcon("文件",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(file.optString("name"),Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=12.sp)}
    }
}
