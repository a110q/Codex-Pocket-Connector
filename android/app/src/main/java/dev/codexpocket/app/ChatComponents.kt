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
import androidx.compose.ui.platform.LocalDensity
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

@Composable fun MessageCard(message:JSONObject,onLink:(String)->Unit,onDetail:(String)->Unit={},onRelated:(JSONObject)->Unit={},computerName:String?=null,streaming:Boolean=false,images:@Composable ()->Unit={}){
    val role=message.optString("role");val text=message.optString("text")
    val clipboard=LocalClipboard.current;val scope=rememberCoroutineScope()
    when(role){
        "tool","summary"->ToolRecord(message,onDetail,onRelated)
        "user"->BoxWithConstraints(Modifier.fillMaxWidth()){
            val bubbleWidth=maxWidth*.84f
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.End){
                Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.onPrimaryContainer,
                    shape=RoundedCornerShape(16.dp,6.dp,16.dp,16.dp),border=BorderStroke(1.dp,PocketPalette.bubbleBorder),modifier=Modifier.widthIn(max=bubbleWidth)){
                    Column(Modifier.padding(horizontal=14.dp,vertical=10.dp)){
                        images()
                        if(text.isNotBlank())SelectionContainer{Text(text,fontSize=15.5.sp,lineHeight=23.5.sp)}
                        if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容")}
                    }
                }
                if(message.optString("type")!="queuedUserMessage"&&computerName!=null){
                    Text("已同步至$computerName",Modifier.padding(top=4.dp,end=2.dp),fontSize=12.sp,lineHeight=12.sp,color=PocketPalette.muted)
                }
                if(message.optString("type")=="queuedUserMessage"){
                    val queuedLabel=when(message.optString("status")){
                        "sending"->"正在发送"
                        "paused"->"排队已暂停 · 请在电脑端查看"
                        "outcome-unknown"->"发送结果待确认 · 请在电脑端查看"
                        else->"排队中 · 当前任务结束后自动发送"
                    }
                    Row(Modifier.padding(top=4.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically){
                        CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("时钟",Modifier.size(12.dp))}
                        Spacer(Modifier.width(4.dp));Text(queuedLabel,fontSize=12.sp,lineHeight=17.sp,color=PocketPalette.muted)
                    }
                }
            }
        }
        else->Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(Modifier.fillMaxWidth().padding(top=2.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(Modifier.size(20.dp),shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.10f),contentColor=MaterialTheme.colorScheme.primary,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.20f))){
                    Box(contentAlignment=Alignment.Center){PocketIcon("终端",Modifier.size(13.dp))}
                }
                Spacer(Modifier.width(8.dp));Text("Codex",fontSize=13.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(streaming){
                    Text(" · 远程工作区同步中",Modifier.weight(1f),fontSize=12.sp,color=PocketPalette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("流式输出",fontSize=12.sp,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary)
                }else Spacer(Modifier.weight(1f))
                IconButton(onClick={scope.launch{clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Codex",text)))}},modifier=Modifier.size(24.dp).semantics{contentDescription="复制回复"}){
                    CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("复制",Modifier.size(14.dp))}
                }
            }
            MarkdownText(text,Modifier.fillMaxWidth(),onLink)
            if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容（${message.optInt("textLength")} 字符）",fontSize=12.sp)}
        }
    }
}

private enum class ExecutionState { RUNNING, FAILED, COMPLETED, UNKNOWN }

private fun executionState(message:JSONObject):ExecutionState {
    val status=message.optString("status")
    return when {
        status in listOf("inProgress","running","active")->ExecutionState.RUNNING
        status in listOf("failed","error","declined","cancelled","canceled")||(!message.isNull("exitCode")&&message.optInt("exitCode")!=0)->ExecutionState.FAILED
        status=="completed"||(!message.isNull("exitCode")&&message.optInt("exitCode")==0)->ExecutionState.COMPLETED
        else->ExecutionState.UNKNOWN
    }
}

@Composable fun TaskErrorCard(message:JSONObject?,onDetail:(JSONObject)->Unit){
    Surface(color=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.onErrorContainer,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,PocketPalette.errorBorder),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=14.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                PocketIcon("异常",Modifier.size(16.dp));Spacer(Modifier.width(8.dp))
                Text("任务运行出错",Modifier.weight(1f),fontSize=13.5.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold)
                if(message!=null)TextButton(onClick={onDetail(message)},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onErrorContainer),contentPadding=PaddingValues(horizontal=8.dp)){Text("查看任务错误",fontSize=12.5.sp)}
            }
            Text("电脑报告当前任务出错。命令执行记录可在执行过程中展开查看。",fontSize=12.sp,lineHeight=18.sp)
        }
    }
}

@Composable fun ExecutionGroupHeader(records:List<JSONObject>,expanded:Boolean,onToggle:()->Unit){
    val states=records.map(::executionState)
    val running=states.count{it==ExecutionState.RUNNING};val failed=states.count{it==ExecutionState.FAILED}
    val complete=states.all{it==ExecutionState.COMPLETED}
    val summary=listOfNotNull(if(failed>0)"$failed 条未成功记录"else null,if(running>0)"$running 运行中"else null).joinToString(" · ")
    val status=when{running>0->"运行中";complete->"已完成";failed>0&&states.none{it==ExecutionState.UNKNOWN}->"已结束";else->"查看记录"}
    Surface(onClick=onToggle,color=PocketPalette.subtle,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth().semantics{
        contentDescription=if(expanded)"收起执行过程" else "展开执行过程"
        stateDescription=if(expanded)"已展开" else "已折叠"
    }){
        Row(Modifier.heightIn(min=48.dp).padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon(if(expanded)"下拉"else"展开",Modifier.size(14.dp))}
            Spacer(Modifier.width(10.dp))
            if(LocalDensity.current.fontScale>1.2f)Column(Modifier.weight(1f)){
                Text("执行过程 · ${records.size} 项",fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(summary.isNotBlank())Text(summary,fontSize=11.sp,color=PocketPalette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }else{
                Text("执行过程 · ${records.size} 项",fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1)
                Spacer(Modifier.width(8.dp))
                Text(summary,Modifier.weight(1f),fontSize=11.sp,fontFamily=FontFamily.Monospace,color=PocketPalette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Surface(color=if(running>0)PocketPalette.lavender else MaterialTheme.colorScheme.surface,
                contentColor=MaterialTheme.colorScheme.primary,
                shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,PocketPalette.lavenderBorder)){
                Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                    if(running>0){Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(50)));Spacer(Modifier.width(5.dp))}
                    Text(status,fontSize=12.sp,lineHeight=17.sp)
                }
            }
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
    val outcome=when{
        running->"正在执行…"
        state=="declined"->"已拒绝"
        state in listOf("cancelled","canceled")->"已取消"
        failed&&!message.isNull("exitCode")->"命令返回非零 · 退出码 ${message.optInt("exitCode")}"
        failed->"工具返回错误"
        completed->"已完成"
        else->"点击查看记录"
    }
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){
        Column{
            Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.heightIn(min=60.dp).padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(color=MaterialTheme.colorScheme.primaryContainer,contentColor=MaterialTheme.colorScheme.primary,shape=RoundedCornerShape(10.dp),modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){PocketIcon(icon,Modifier.size(19.dp))}}
                Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){
                    Text(title+(command?.let{" · ${it.lineSequence().first().take(100)}"}?:""),fontSize=13.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(if(detail!=null)"$detail · $outcome"else outcome,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                when{running->CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.5.dp);completed->CompositionLocalProvider(LocalContentColor provides pocketSuccess()){PocketIcon("检查",Modifier.size(15.dp))};failed->Text("!",color=MaterialTheme.colorScheme.error,fontWeight=FontWeight.Bold)}
                Spacer(Modifier.width(9.dp));PocketIcon(if(expanded)"上拉" else "下拉",Modifier.size(14.dp))
            }
            if(expanded){
                if(message.optBoolean("sourceTruncated"))Text("桌面接口已截断这段输出",Modifier.padding(horizontal=12.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.error)
                val terminal=command!=null||type=="commandExecution"
                Surface(color=PocketPalette.subtle,contentColor=MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp)){
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
    val model=settings.optString("model").takeIf{it.isNotBlank()&&it!="null"}?:"默认模型"
    Surface(onClick={onOpen("model")},enabled=enabled,color=Color.Transparent,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription="打开模型与权限设置面板"}){
        Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(Modifier.size(22.dp),shape=RoundedCornerShape(8.dp),color=PocketPalette.lavender,contentColor=MaterialTheme.colorScheme.primary,border=BorderStroke(1.dp,PocketPalette.lavenderBorder)){
                Box(contentAlignment=Alignment.Center){PocketIcon("运行配置",Modifier.size(13.dp))}
            }
            Spacer(Modifier.width(8.dp))
            if(LocalDensity.current.fontScale>1.2f)Column(Modifier.weight(1f)){
                Text(model,fontSize=12.5.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(effortLabel(settings.optString("reasoningEffort"))+"推理 · "+permissionLabel(settings.optString("permissionMode")),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }else{
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
                Text(model,Modifier.weight(1f,false),fontSize=12.5.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(" · ",fontSize=12.sp,color=PocketPalette.muted)
                Text(effortLabel(settings.optString("reasoningEffort"))+"推理",fontSize=12.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" · ",fontSize=12.sp,color=PocketPalette.muted)
                Text(permissionLabel(settings.optString("permissionMode")),fontSize=12.5.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary)
            }
            }
            Spacer(Modifier.width(8.dp));Text("调整",fontSize=12.sp,color=PocketPalette.muted)
            Spacer(Modifier.width(4.dp));CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("上拉",Modifier.size(14.dp))}
        }
    }
}
