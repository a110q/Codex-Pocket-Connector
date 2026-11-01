package dev.codexpocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal data class RecentChanges(val files:List<PatchFile>,val records:Int,val complete:Boolean)

internal fun recentChanges(snapshot:JSONObject?):RecentChanges?{
    val messages=snapshot?.optJSONArray("messages").objects().filter{it.optString("type") in listOf("fileChange","turnDiff")}
    val diffs=snapshot?.optJSONArray("diffs").objects()
    val turn=(messages.lastOrNull()?:diffs.lastOrNull())?.optString("turnId")?:return null
    val records=messages.filter{it.optString("turnId")==turn}
    val structured=diffs.filter{it.optString("turnId")==turn}
    val full=structured.lastOrNull{it.optString("diff").isNotBlank()}
    val fullMessage=records.lastOrNull{it.optString("type")=="turnDiff"}
    val files=when{
        full!=null->patchFiles(full.optString("diff"))
        fullMessage!=null->patchFiles(fullMessage.optString("text"))
        else->{
            val entries=if(structured.isNotEmpty())structured.flatMap{it.optJSONArray("changes").objects()}.flatMap{change->
                val path=change.optString("path")
                patchFiles(change.optString("diff"),path).ifEmpty{if(path.isNotBlank())listOf(PatchFile(path,0,0))else emptyList()}
            }else records.filter{it.optString("type")=="fileChange"}.flatMap{patchFiles(it.optString("text"),fileChange=true)}
            entries.groupBy{it.path}.map{(path,values)->PatchFile(path,values.sumOf{it.added},values.sumOf{it.removed})}
        }
    }
    return RecentChanges(files,records.size.coerceAtLeast(structured.size),records.all{it.optBoolean("textComplete",true)&&!it.optBoolean("sourceTruncated")})
}

@Composable internal fun ChatDeliverables(artifacts:List<JSONObject>,changes:RecentChanges?,connected:Boolean,onPreview:(JSONObject)->Unit,onAll:()->Unit){
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon("文件",Modifier.size(18.dp))}
                Spacer(Modifier.width(8.dp));Text("交付与修改",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                if(artifacts.isNotEmpty())Text("${artifacts.size} 个文件",fontSize=12.sp,color=PocketPalette.muted)
            }
            artifacts.take(3).forEach{file->ArtifactRow(file,enabled=connected){onPreview(file)}}
            if(changes!=null){
                if(artifacts.isNotEmpty())HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Surface(onClick=onAll,color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().semantics{contentDescription="查看最近一轮代码修改"}){
                    Column(Modifier.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            PocketIcon("差异",Modifier.size(17.dp));Spacer(Modifier.width(8.dp))
                            Text(if(changes.files.isEmpty())"最近一轮 · ${changes.records} 条修改记录"else if(!changes.complete)"最近一轮 · 已加载 ${changes.files.size} 个文件"else"最近一轮 · 修改 ${changes.files.size} 个文件",Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium)
                            PocketIcon("展开",Modifier.size(14.dp))
                        }
                        changes.files.take(3).forEach{file->
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text(file.path,Modifier.weight(1f),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(changes.complete&&(file.added+file.removed>0)){
                                    Spacer(Modifier.width(10.dp));Text("+${file.added}",fontSize=11.sp,color=pocketSuccess())
                                    Spacer(Modifier.width(8.dp));Text("−${file.removed}",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if(!changes.complete)Text("当前为部分修改内容，展开查看完整记录",fontSize=11.sp,color=PocketPalette.muted)
                    }
                }
            }
            TextButton(onClick=onAll,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp),shape=RoundedCornerShape(10.dp)){
                Text(if(artifacts.size>3)"查看全部 ${artifacts.size} 个文件与修改"else"查看全部交付与修改",Modifier.weight(1f),fontSize=13.sp)
                PocketIcon("展开",Modifier.size(16.dp))
            }
        }
    }
}

internal fun artifactType(file:JSONObject):String{
    val mime=file.optString("mime");val extension=file.optString("name").substringAfterLast('.',"").lowercase()
    return when{
        mime.startsWith("image/")->"图片"
        mime=="text/html"||extension in listOf("html","htm")->"网页"
        extension=="pdf"->"PDF 文档"
        extension in listOf("md","markdown")->"Markdown 文档"
        mime.startsWith("text/")->"文本文件"
        extension in listOf("zip","gz","7z")->"压缩文件"
        else->"文件"
    }
}
internal fun artifactSize(file:JSONObject):String?{
    if(!file.has("size")||file.isNull("size"))return null
    val size=file.optLong("size",-1);if(size<0)return null
    return when{size<1024->"$size B";size<1024*1024->"${(size+1023)/1024} KB";else->String.format(java.util.Locale.ROOT,"%.1f MB",size/1048576.0)}
}
