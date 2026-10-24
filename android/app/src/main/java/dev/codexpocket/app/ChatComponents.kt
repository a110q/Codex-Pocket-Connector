package dev.codexpocket.app

import android.content.ClipData
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun MessageCard(message:JSONObject,onLink:(String)->Unit,onDetail:(String)->Unit={},onRelated:(JSONObject)->Unit={},images:@Composable ()->Unit={}){
    val role=message.optString("role");val text=message.optString("text")
    val clipboard=LocalClipboard.current;val scope=rememberCoroutineScope()
    when(role){
        "tool","summary"->ToolRecord(message,onDetail,onRelated)
        "user"->Row(Modifier.fillMaxWidth().padding(start=34.dp),horizontalArrangement=Arrangement.End){
            Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(16.dp,16.dp,5.dp,16.dp),modifier=Modifier.widthIn(max=350.dp)){
                Column(Modifier.padding(horizontal=13.dp,vertical=11.dp)){
                    images()
                    if(text.isNotBlank())SelectionContainer{Text(text,fontSize=15.sp,lineHeight=24.sp)}
                    if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容")}
                }
            }
        }
        else->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
            CodexMark(Modifier.padding(top=3.dp));Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)){
                MarkdownText(text,Modifier.fillMaxWidth().padding(top=3.dp),onLink)
                if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容（${message.optInt("textLength")} 字符）",fontSize=12.sp)}
                IconButton(onClick={scope.launch{clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Codex",text)))}},modifier=Modifier.size(36.dp).semantics{contentDescription="复制回复"}){
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("复制",Modifier.size(15.dp))}
                }
            }
        }
    }
}

@Composable private fun ToolRecord(message:JSONObject,onDetail:(String)->Unit,onRelated:(JSONObject)->Unit){
    var expanded by remember(message.optString("id")){mutableStateOf(false)}
    val type=message.optString("type");val state=message.optString("status")
    val failed=state in listOf("failed","error","declined")||(!message.isNull("exitCode")&&message.optInt("exitCode")!=0)
    val running=state in listOf("inProgress","running","active")
    val completed=state=="completed"||(!message.isNull("exitCode")&&message.optInt("exitCode")==0)
    val command=message.optString("command").takeIf{it.isNotBlank()&&it!="null"}
    val title=message.optString("title").ifBlank{if(message.optString("role")=="summary")"思考摘要" else "工具调用"}
    val icon=when{command!=null||type=="commandExecution"->"终端";type in listOf("fileChange","turnDiff")->"编辑";type.contains("search",true)->"搜索";message.optString("role")=="summary"->"推理";else->"文件"}
    val detail=message.optString("tool").takeIf{it.isNotBlank()&&it!="null"}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().padding(start=38.dp)){
        Column{
            Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(start=12.dp,end=8.dp,top=12.dp,bottom=12.dp).heightIn(min=38.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(7.dp),modifier=Modifier.size(30.dp)){Box(contentAlignment=Alignment.Center){PocketIcon(icon,Modifier.size(19.dp))}}
                Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){
                    Text(title+(command?.let{" · ${it.lineSequence().first().take(100)}"}?:""),fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(detail?:when{failed->if(state=="declined")"已拒绝" else "执行失败";running->"正在执行…";completed->"已完成";else->"点击查看记录"},fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                when{running->CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.5.dp);completed->CompositionLocalProvider(LocalContentColor provides PocketGreen){PocketIcon("检查",Modifier.size(15.dp))};failed->Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)}
                Spacer(Modifier.width(9.dp));PocketIcon(if(expanded)"上拉" else "下拉",Modifier.size(14.dp))
            }
            if(expanded){
                if(message.optBoolean("sourceTruncated"))Text("桌面接口已截断这段输出",Modifier.padding(horizontal=12.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                val terminal=command!=null||type=="commandExecution"
                Surface(color=if(terminal)Color(0xFF1C242D)else MaterialTheme.colorScheme.surfaceVariant,contentColor=if(terminal)Color(0xFFD9E5EE)else MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp)){
                    SelectionContainer{Text((command?.let{"> $it\n\n"}?:"")+message.optString("text"),Modifier.padding(12.dp),fontFamily=FontFamily.Monospace,fontSize=11.sp,lineHeight=18.sp,maxLines=18,overflow=TextOverflow.Ellipsis)}
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={onDetail(if(message.optBoolean("hasDetail"))"detail" else "text")}){Text("查看完整详情",fontSize=12.sp)}
                    if(!message.isNull("exitCode"))Text("退出 ${message.optInt("exitCode")}",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                message.optJSONArray("relatedThreads").objects().forEach{agent->TextButton(onClick={onRelated(agent)}){Text("查看 ${agent.label()} 对话",fontSize=12.sp)}}
            }
        }
    }
}

@Composable fun ComposerSettings(settings:JSONObject,enabled:Boolean,onOpen:(String)->Unit){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        listOf(
            Triple("model",settings.optString("model").takeIf{it.isNotBlank()&&it!="null"}?:"默认模型","选择模型"),
            Triple("effort","推理 · ${effortLabel(settings.optString("reasoningEffort"))}","选择推理强度"),
            Triple("permission",permissionLabel(settings.optString("permissionMode")),"选择访问权限")
        ).forEachIndexed{index,(section,label,description)->
            Box(Modifier.weight(if(index==0)1.2f else 1f).height(48.dp),contentAlignment=Alignment.Center){
                Surface(onClick={onOpen(section)},enabled=enabled,color=MaterialTheme.colorScheme.surfaceContainerLow,contentColor=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,shape=RoundedCornerShape(10.dp),border=BorderStroke(.7.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f)),modifier=Modifier.fillMaxWidth().heightIn(min=40.dp).semantics{contentDescription=description}){
                    Row(Modifier.padding(horizontal=8.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
                        if(index>0){PocketIcon(if(index==1)"推理" else if(settings.optString("permissionMode")=="auto")"项目" else "锁",Modifier.size(14.dp));Spacer(Modifier.width(5.dp))}
                        Column(Modifier.weight(1f)){if(index==0)Text("当前模型",fontSize=8.sp,lineHeight=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(label,fontSize=10.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        Spacer(Modifier.width(2.dp));PocketIcon("下拉",Modifier.size(10.dp))
                    }
                }
            }
        }
    }
}
