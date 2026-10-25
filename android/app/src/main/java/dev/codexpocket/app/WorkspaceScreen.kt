package dev.codexpocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun ConnectionBadge(model:PocketModel,onClick:()->Unit){
    Surface(onClick=onClick,color=if(model.connected)PocketGreen.copy(alpha=.07f)else MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(8.dp),modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription="查看电脑连接"}){
        Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(6.dp).background(if(model.connected)PocketGreen else MaterialTheme.colorScheme.tertiary,RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp));Text(model.profile?.optString("name")?:"我的电脑",modifier=Modifier.widthIn(max=90.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=11.sp,fontWeight=FontWeight.Medium);PocketIcon("下拉",Modifier.size(14.dp))
        }
    }
}

internal fun threadTime(thread:JSONObject):String{
    val raw=thread.optString("updatedAt").ifBlank{thread.optString("recencyAt")}
    return try{
        val instant=raw.toLongOrNull()?.let{Instant.ofEpochMilli(if(it<100000000000L)it*1000 else it)}?:Instant.parse(raw)
        val date=instant.atZone(ZoneId.systemDefault());val today=LocalDate.now()
        when(date.toLocalDate()){today->date.format(DateTimeFormatter.ofPattern("HH:mm"));today.minusDays(1)->"昨天";else->date.format(DateTimeFormatter.ofPattern("M月d日"))}
    }catch(_:Exception){""}
}

@Composable fun ThreadList(model:PocketModel,onNew:()->Unit,onProjectNew:(String)->Unit,activeOnly:Boolean=false,onCreate:()->Unit={}){
    val search=if(activeOnly)model.taskSearch else model.projectSearch
    fun updateSearch(v:String){if(activeOnly)model.taskSearch=v else model.projectSearch=v}
    var searching by rememberSaveable(activeOnly){mutableStateOf(search.isNotBlank())}
    var menu by remember{mutableStateOf(false)}
    val threads=model.threads.filter{it.optBoolean("archived")==model.showArchived}.filter{!activeOnly||statusLabel(it.optString("status")) in listOf("进行中","等待操作","出错")}
    fun matches(t:JSONObject)=t.label().contains(search,true)
    val projectIds=model.projects.map{it.optString("projectId")}.toSet()
    val visible=model.projects.filter{p->search.isBlank()||p.label().contains(search,true)||threads.any{it.optString("projectId")==p.optString("projectId")&&matches(it)}}
    val standalone=threads.filter{it.optString("projectId") !in projectIds}.filter(::matches)
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(activeOnly)"运行中与待处理"else if(model.showArchived)"已归档 · 长按恢复或删除"else "${model.projects.size} 个项目 · ${model.threads.size} 个对话",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PocketAction("搜索",if(searching)"收起搜索"else"展开搜索"){searching=!searching;if(!searching)updateSearch("")}
            PocketAction("新增","新建对话",model.connected&&!model.busy&&model.pendingOperation==null,onCreate)
            Box{
                PocketAction("更多","工作空间选项"){menu=true}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text(if(activeOnly)"所有对话"else"任务动态")},onClick={menu=false;onNew()})
                    if(!activeOnly)DropdownMenuItem(text={Text(if(model.showArchived)"返回全部对话"else"已归档")},enabled=model.connected,onClick={menu=false;model.toggleArchived()})
                    DropdownMenuItem(text={Text("刷新项目与对话")},enabled=model.connected,onClick={menu=false;model.refresh()})
                }
            }
        }
        if(searching)TextField(search,::updateSearch,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=4.dp).heightIn(min=52.dp).semantics{contentDescription="搜索项目或对话"},placeholder={Text("搜索项目或对话",fontSize=14.sp)},leadingIcon={PocketIcon("搜索",Modifier.size(20.dp))},trailingIcon={if(search.isNotBlank())PocketAction("关闭","清空搜索"){updateSearch("")}},singleLine=true,shape=RoundedCornerShape(12.dp),colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp)){
            if(activeOnly){
                val shown=threads.filter(::matches)
                if(shown.isEmpty())item{EmptyState(if(search.isBlank())"暂无待处理任务" else "没有找到相关任务","运行中、等待审批和出错的对话会显示在这里。")}
                items(shown,key={it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,false)}
            }else{
                visible.forEach{project->
                    val id=project.optString("projectId");val children=threads.filter{it.optString("projectId")==id}
                    val open=id !in model.collapsedProjects||search.isNotBlank()
                    item(key="project:$id"){
                        ProjectHeading(project,children.size,open,model.connected,onToggle={model.collapsedProjects=if(id in model.collapsedProjects)model.collapsedProjects-id else model.collapsedProjects+id},onNew={onProjectNew(id)})
                    }
                    if(open){
                        val shown=children.filter{project.label().contains(search,true)||matches(it)}
                        if(shown.isEmpty())item{Text("还没有对话，点 ＋ 开始",Modifier.padding(start=51.dp,top=8.dp,bottom=18.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        items(shown,key={"project-chat:"+it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,true)}
                    }
                    item(key="divider:$id"){HorizontalDivider(Modifier.padding(horizontal=8.dp,vertical=4.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))}
                }
                if(standalone.isNotEmpty()){
                    item{Text("独立对话",Modifier.padding(horizontal=8.dp,vertical=8.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(standalone,key={"independent:"+it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,false)}
                }
                if(visible.isEmpty()&&standalone.isEmpty())item{EmptyState(if(search.isNotBlank())"没有找到相关项目或对话" else "你的工作空间","${if(search.isNotBlank())"试试其他关键词，或清空搜索。" else "连接电脑后，项目与对话会同步到这里。"}")}
            }
        }
    }
}

@Composable private fun ProjectHeading(project:JSONObject,count:Int,open:Boolean,connected:Boolean,onToggle:()->Unit,onNew:()->Unit){
    var menu by remember{mutableStateOf(false)};var path by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Row(Modifier.weight(1f).clickable(onClick=onToggle).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
            PocketIcon(if(open)"下拉" else "展开",Modifier.size(14.dp));Spacer(Modifier.width(6.dp))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon("项目",Modifier.size(20.dp))}
            Spacer(Modifier.width(8.dp));Text(project.label(),Modifier.weight(1f,false),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.width(6.dp));Text("$count",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PocketAction("新增","在 ${project.label()} 中新开对话",connected,onNew)
        Box{
            PocketAction("更多","${project.label()} 项目选项"){menu=true}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                DropdownMenuItem(text={Text("新建对话")},enabled=connected,onClick={menu=false;onNew()})
                DropdownMenuItem(text={Text("查看项目路径")},onClick={menu=false;path=true})
            }
        }
    }
    if(path)AlertDialog(onDismissRequest={path=false},title={Text(project.label())},text={androidx.compose.foundation.text.selection.SelectionContainer{Text(project.optString("path").ifBlank{"桌面未提供项目路径"},fontSize=14.sp)}},confirmButton={TextButton(onClick={path=false}){Text("完成")}})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun WorkspaceThread(thread:JSONObject,model:PocketModel,nested:Boolean){
    var menu by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf(false)}
    val state=statusLabel(thread.optString("status"));val running=state=="进行中";val needs=state in listOf("等待操作","出错")
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
        if(nested){Spacer(Modifier.width(12.dp));Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant));Spacer(Modifier.width(8.dp))}
        Box(Modifier.weight(1f)){
        Surface(shape=RoundedCornerShape(9.dp),color=if(running)MaterialTheme.colorScheme.primaryContainer else Color.Transparent,modifier=Modifier.fillMaxWidth().combinedClickable(onClick={model.openThread(thread)},onLongClick={menu=true},onLongClickLabel="管理对话")){
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                CompositionLocalProvider(LocalContentColor provides if(running)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("文件",Modifier.size(17.dp))}
                Spacer(Modifier.width(10.dp));Text(thread.label(),Modifier.weight(1f),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(running){Spacer(Modifier.width(8.dp));CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp);Spacer(Modifier.width(5.dp))}
                if(needs){Spacer(Modifier.width(6.dp));Text(state,fontSize=10.sp,color=if(state=="出错")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)}
                val time=threadTime(thread);if(time.isNotBlank()){Spacer(Modifier.width(10.dp));Text(time,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
            DropdownMenuItem(text={Text(if(thread.optBoolean("archived"))"恢复对话"else"归档对话")},enabled=model.connected&&!model.busy&&model.pendingOperation==null&&!running,onClick={menu=false;model.manageThread(thread,if(thread.optBoolean("archived"))"unarchive"else"archive")})
            DropdownMenuItem(text={Text("删除对话",color=MaterialTheme.colorScheme.error)},enabled=model.connected&&!model.busy&&model.pendingOperation==null&&!running,onClick={menu=false;deleting=true})
        }
        }
    }
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除对话？")},text={Text("将从 ${model.profile?.optString("name")?:"电脑"} 删除“${thread.label()}”及其对话记录，无法恢复。项目文件不会删除。")},confirmButton={TextButton(onClick={deleting=false;model.manageThread(thread,"delete")}){Text("确认删除",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}
