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
import androidx.compose.ui.semantics.stateDescription
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
            Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(18.dp,18.dp,6.dp,18.dp),modifier=Modifier.widthIn(max=350.dp)){
                Column(Modifier.padding(horizontal=13.dp,vertical=11.dp)){
                    images()
                    if(text.isNotBlank())SelectionContainer{Text(text,fontSize=15.sp,lineHeight=24.sp)}
                    if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容")}
                }
            }
        }
        else->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){
            CodexMark(Modifier.padding(top=3.dp).size(24.dp));Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)){
                MarkdownText(text,Modifier.fillMaxWidth().padding(top=3.dp),onLink)
                if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容（${message.optInt("textLength")} 字符）",fontSize=12.sp)}
                IconButton(onClick={scope.launch{clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Codex",text)))}},modifier=Modifier.size(48.dp).semantics{contentDescription="复制回复"}){
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("复制",Modifier.size(15.dp))}
                }
            }
        }
    }
}

private enum class ExecutionState { RUNNING, FAILED, COMPLETED, UNKNOWN }

private fun executionState(message:JSONObject):ExecutionState {
    val status=message.optString("status")
    return when {
        status in listOf("failed","error","declined","cancelled","canceled")||(!message.isNull("exitCode")&&message.optInt("exitCode")!=0)->ExecutionState.FAILED
        status in listOf("inProgress","running","active")->ExecutionState.RUNNING
        status=="completed"||(!message.isNull("exitCode")&&message.optInt("exitCode")==0)->ExecutionState.COMPLETED
        else->ExecutionState.UNKNOWN
    }
}

@Composable fun ExecutionGroupHeader(records:List<JSONObject>,expanded:Boolean,onToggle:()->Unit){
    val states=records.map(::executionState)
    val running=states.count{it==ExecutionState.RUNNING}
    val failed=states.count{it==ExecutionState.FAILED}
    val summary=listOfNotNull(
        if(running>0)"$running 项进行中" else null,
        if(failed>0)"$failed 项失败或拒绝" else null
    ).joinToString(" · ").ifBlank{if(states.all{it==ExecutionState.COMPLETED})"已完成" else "查看工具与思考记录"}
    Surface(onClick=onToggle,color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().semantics{
        contentDescription=if(expanded)"收起执行过程" else "展开执行过程"
        stateDescription=if(expanded)"已展开" else "已折叠"
    }){
        Row(Modifier.heightIn(min=56.dp).padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("终端",Modifier.size(19.dp))}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)){
                Text("执行过程 · ${records.size} 条",fontSize=13.sp,fontWeight=FontWeight.Medium)
                Text(summary,fontSize=11.sp,lineHeight=17.sp,color=if(failed>0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(running>0){CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp);Spacer(Modifier.width(10.dp))}
            Text(if(expanded)"收起" else "展开",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp));PocketIcon(if(expanded)"上拉" else "下拉",Modifier.size(13.dp))
        }
    }
}

@Composable private fun ToolRecord(message:JSONObject,onDetail:(String)->Unit,onRelated:(JSONObject)->Unit){
    var expanded by remember(message.optString("id")){mutableStateOf(false)}
    val type=message.optString("type");val state=message.optString("status")
    val recordState=executionState(message)
    val failed=recordState==ExecutionState.FAILED
    val running=recordState==ExecutionState.RUNNING
    val completed=recordState==ExecutionState.COMPLETED
    val command=message.optString("command").takeIf{it.isNotBlank()&&it!="null"}
    val title=message.optString("title").ifBlank{if(message.optString("role")=="summary")"思考摘要" else "工具调用"}
    val icon=when{command!=null||type=="commandExecution"->"终端";type in listOf("fileChange","turnDiff")->"编辑";type.contains("search",true)->"搜索";message.optString("role")=="summary"->"推理";else->"文件"}
    val detail=message.optString("tool").takeIf{it.isNotBlank()&&it!="null"}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){
        Column{
            Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.heightIn(min=60.dp).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.primary,shape=RoundedCornerShape(10.dp),modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){PocketIcon(icon,Modifier.size(19.dp))}}
                Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){
                    Text(title+(command?.let{" · ${it.lineSequence().first().take(100)}"}?:""),fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(detail?:when{failed->if(state=="declined")"已拒绝" else "执行失败";running->"正在执行…";completed->"已完成";else->"点击查看记录"},fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                when{running->CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.5.dp);completed->CompositionLocalProvider(LocalContentColor provides pocketSuccess()){PocketIcon("检查",Modifier.size(15.dp))};failed->Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)}
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
            Surface(onClick={onOpen(section)},enabled=enabled,color=MaterialTheme.colorScheme.surfaceContainerLow,contentColor=if(enabled)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f),shape=RoundedCornerShape(10.dp),modifier=Modifier.weight(if(index==0)1.2f else 1f).heightIn(min=48.dp).semantics{contentDescription=description}){
                Row(Modifier.padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(label,Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.width(3.dp));PocketIcon("下拉",Modifier.size(12.dp))
                }
            }
        }
    }
}
