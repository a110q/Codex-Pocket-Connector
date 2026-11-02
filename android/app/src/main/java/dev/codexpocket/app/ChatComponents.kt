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
import org.json.JSONArray

internal data class QuestionReply(val question:String,val answer:String)
internal fun questionReplies(message:JSONObject):List<QuestionReply> {
    val values=message.optJSONArray("questionReplies")?:run {
        val match=Regex("^\\s*<send_user_message_question_reply>\\s*([\\s\\S]*?)\\s*</send_user_message_question_reply>\\s*$").matchEntire(message.optString("text"))?:return emptyList()
        try { JSONArray(match.groupValues[1]) } catch(_:Exception){return emptyList()}
    }
    if(values.length()==0)return emptyList()
    return (0 until values.length()).map { index->
        val value=values.optJSONObject(index)?:return emptyList()
        if(value.opt("question") !is String||value.opt("answer") !is String)return emptyList()
        QuestionReply(value.getString("question"),value.getString("answer"))
    }
}

@Composable fun MessageCard(message:JSONObject,onLink:(String)->Unit,onDetail:(String)->Unit={},onRelated:(JSONObject)->Unit={},imageSupport:MarkdownImageSupport?=null,images:@Composable ()->Unit={}){
    val role=message.optString("role");val text=message.optString("text")
    val replies=remember(message){questionReplies(message)}
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
                        if(replies.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                            replies.forEach { reply->Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
                                Text(reply.question,fontSize=12.5.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer{Text(reply.answer,fontSize=15.5.sp,lineHeight=23.5.sp,fontWeight=FontWeight.Medium)}
                            } }
                        }else if(text.isNotBlank())SelectionContainer{Text(text,fontSize=15.5.sp,lineHeight=23.5.sp)}
                        if(!message.optBoolean("textComplete",true))TextButton(onClick={onDetail("text")}){Text("查看完整内容")}
                    }
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
        else->Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){
                Surface(Modifier.size(20.dp),shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=.10f),contentColor=MaterialTheme.colorScheme.primary,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.20f))){
                    Box(contentAlignment=Alignment.Center){PocketIcon("终端",Modifier.size(13.dp))}
                }
                Spacer(Modifier.width(8.dp));Text("Codex",fontSize=13.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick={scope.launch{clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Codex",text)))}},modifier=Modifier.size(48.dp).semantics{contentDescription="复制回复"}){
                    CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("复制",Modifier.size(14.dp))}
                }
            }
            MarkdownText(text,Modifier.fillMaxWidth(),imageSupport,onLink)
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
    val status=when{running>0->"运行中";complete->"已完成";failed>0&&states.none{it==ExecutionState.UNKNOWN}->"已结束";else->"查看记录"}
    Surface(onClick=onToggle,color=Color.Transparent,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().semantics{
        contentDescription=if(expanded)"收起执行过程" else "展开执行过程"
        stateDescription=if(expanded)"已展开" else "已折叠"
    }){
        Row(Modifier.heightIn(min=48.dp).padding(horizontal=4.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            if(running>0)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=1.8.dp)
            else CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon(if(complete)"检查"else"时钟",Modifier.size(17.dp))}
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text("执行过程 · ${records.size} 项",fontSize=13.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(failed>0)Text("$failed 条未成功记录",fontSize=11.sp,color=PocketPalette.muted)
            }
            Text(status,fontSize=12.sp,color=if(running>0)MaterialTheme.colorScheme.primary else PocketPalette.muted)
            Spacer(Modifier.width(10.dp))
            CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon(if(expanded)"上拉"else"下拉",Modifier.size(14.dp))}
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

@Composable internal fun LiveTaskProgress(activity:ThreadActivity,connected:Boolean,syncIssue:String?,messages:List<JSONObject>,onRefresh:()->Unit){
    val running=connected&&activity==ThreadActivity.RUNNING
    val last=messages.lastOrNull{it.optString("role")!="user"&&it.optString("type")!="queuedUserMessage"}
    val executing=last?.takeIf{it.optString("status") in listOf("inProgress","running","active")}
    val title=when{!connected->"等待重新连接";syncIssue!=null->"正在恢复实时同步";running&&executing!=null->"正在执行";running->"任务运行中";else->"正在同步最新内容"}
    val detail=when{!connected->"当前显示已缓存的内容，连接后自动同步。";syncIssue!=null->syncIssue;executing!=null->executing.optString("title").ifBlank{"新的执行结果会自动显示。"};else->"电脑仍在处理，新的回复会自动显示。"}
    Surface(Modifier.fillMaxWidth().semantics{contentDescription="任务实时进度"},color=PocketPalette.lavender.copy(alpha=.5f),shape=RoundedCornerShape(12.dp)){
        Row(Modifier.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
            if(connected)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.8.dp)
            else CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("时钟",Modifier.size(16.dp))}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(title,fontSize=13.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detail,fontSize=12.sp,lineHeight=18.sp,color=PocketPalette.muted)
            }
            if(connected&&syncIssue!=null)TextButton(onClick=onRefresh){Text("刷新内容",fontSize=12.sp)}
        }
    }
}
