package dev.codexpocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConnectionBadge(model:PocketModel,compact:Boolean=false,onClick:()->Unit){
    var expanded by remember{mutableStateOf(false)}
    Box(Modifier.heightIn(min=48.dp).clickable{expanded=true}.semantics{contentDescription="快速切换电脑";stateDescription=if(model.connected)"已连接"else model.status},contentAlignment=Alignment.Center){
        Row(Modifier.widthIn(max=if(compact)148.dp else 260.dp).heightIn(min=48.dp).padding(end=8.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(6.dp).background(if(model.connected)pocketSuccess() else PocketPalette.muted,RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp))
            Text(model.profile?.computerName()?:"我的电脑",modifier=Modifier.weight(1f,false),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(!model.connected){Spacer(Modifier.width(4.dp));Text(if(model.status.contains("离线"))"离线"else"未连接",style=MaterialTheme.typography.bodySmall,color=PocketPalette.muted)}
            Spacer(Modifier.width(6.dp));CompositionLocalProvider(LocalContentColor provides PocketPalette.muted){PocketIcon("下拉",Modifier.size(12.dp))}
        }
    }
    if(expanded)ModalBottomSheet(onDismissRequest={expanded=false},containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=18.dp,topEnd=18.dp)){
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("切换连接的电脑",Modifier.padding(bottom=4.dp),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            model.computers.forEach{computer->
                val current=computer.optString("hostId")==model.profile?.optString("hostId")
                Surface(onClick={if(model.switchComputer(computer.getString("hostId")))expanded=false},enabled=current||model.canSwitchComputer,
                    color=if(current)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.5f)else PocketPalette.subtle,
                    shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)){
                    Row(Modifier.fillMaxWidth().heightIn(min=54.dp).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){
                            Text(computer.computerName(),fontSize=14.sp,fontWeight=if(current)FontWeight.SemiBold else FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(if(current)model.status else if(PocketConnection.isLanPairing(computer))"局域网 · 已保存"else"公网远程 · 已保存",fontSize=12.sp,color=PocketPalette.muted)
                        }
                        Spacer(Modifier.width(8.dp));Text(if(current)if(model.connected)"已连接"else"当前"else"切换",fontSize=12.sp,color=if(current&&model.connected)pocketSuccess()else PocketPalette.muted)
                    }
                }
            }
            TextButton(onClick={expanded=false;onClick()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){PocketIcon("电脑",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("管理电脑与名称")}
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

internal fun threadBelongsToProject(thread:JSONObject,project:JSONObject):Boolean {
    if(normalizeLocalThreadHost(thread).optString("hostId","local")!=normalizeLocalProjectHost(project).optString("hostId","local"))return false
    if(thread.isNull("projectId")||thread.optString("projectId").isBlank())return false
    fun ids(value:JSONObject)=listOf("projectId","canonicalProjectId").filterNot{value.isNull(it)}.map{value.optString(it)}.filter{it.isNotBlank()}
    val projectIds=ids(project)
    return ids(thread).any{it in projectIds}
}

private enum class WorkspaceFilter(val label:String){
    ALL("全部"),RUNNING("运行中"),PENDING("待我处理");

    fun matches(activity:ThreadActivity)=when(this){
        ALL->true
        RUNNING->activity==ThreadActivity.RUNNING
        PENDING->activity.needsUserAction
    }
}

@Composable fun ThreadList(model:PocketModel,onNew:()->Unit,onProjectNew:(String)->Unit,activeOnly:Boolean=false,onCreate:()->Unit={},onOpenPending:(JSONObject)->Unit={model.openThread(it)}){
    val search=if(activeOnly)model.taskSearch else model.projectSearch
    fun updateSearch(v:String){if(activeOnly)model.taskSearch=v else model.projectSearch=v}
    var searching by rememberSaveable(activeOnly){mutableStateOf(search.isNotBlank())}
    val pager=key(model.showArchived){rememberPagerState(pageCount={WorkspaceFilter.entries.size})}
    val pagerScope=rememberCoroutineScope()
    val filter=WorkspaceFilter.entries[pager.currentPage]
    var menu by remember{mutableStateOf(false)}
    val allThreads=model.threads.filter{it.optBoolean("archived")==model.showArchived}.filter{!activeOnly||threadActivity(it).actionable}
    fun matches(t:JSONObject)=t.label().contains(search,true)
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(activeOnly)"运行、等待处理与出错的对话"else"${model.projects.size} 个项目 · ${allThreads.size} 个对话",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            PocketAction("搜索",if(searching)"收起搜索"else"展开搜索"){searching=!searching;if(!searching)updateSearch("")}
            FilledTonalButton(onClick=onCreate,enabled=model.connected&&!model.busy&&model.pendingOperation==null,shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription="新建对话"}){
                PocketIcon("新增",Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("新建",style=MaterialTheme.typography.labelLarge)
            }
            Box{
                PocketAction("更多","工作空间选项"){menu=true}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text(if(activeOnly)"所有对话"else"任务动态")},onClick={menu=false;onNew()})
                    if(!activeOnly)DropdownMenuItem(text={Text(if(model.showArchived)"返回全部对话"else"已归档")},enabled=model.connected,onClick={menu=false;model.toggleArchived()})
                    DropdownMenuItem(text={Text("刷新项目与对话")},enabled=model.connected,onClick={menu=false;model.refresh()})
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            WorkspaceFilter.entries.forEach{option->
                val count=allThreads.count{option.matches(threadActivity(it))}
                Surface(onClick={pagerScope.launch{pager.animateScrollToPage(option.ordinal)}},color=Color.Transparent,contentColor=if(filter==option)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,shape=RoundedCornerShape(12.dp),modifier=Modifier.heightIn(min=48.dp).semantics{contentDescription="筛选${option.label}对话";selected=filter==option;role=Role.Tab;stateDescription=if(filter==option)"已选中"else"未选中"}){
                    Column(horizontalAlignment=Alignment.CenterHorizontally){
                        Row(Modifier.padding(horizontal=8.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            Text(option.label,style=MaterialTheme.typography.labelLarge,fontWeight=if(filter==option)FontWeight.SemiBold else FontWeight.Normal)
                            Text("$count",style=MaterialTheme.typography.bodySmall)
                        }
                        Box(Modifier.width(24.dp).height(2.dp).background(if(filter==option)MaterialTheme.colorScheme.primary else Color.Transparent,RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        if(!model.connected&&allThreads.any{threadActivity(it).actionable})Text("当前显示上次状态，连接后自动更新",Modifier.padding(horizontal=20.dp,vertical=4.dp),fontSize=11.sp,color=PocketPalette.muted)
        if(searching)TextField(search,::updateSearch,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=8.dp).heightIn(min=52.dp).semantics{contentDescription="搜索项目或对话"},placeholder={Text("搜索项目或对话",fontSize=14.sp)},leadingIcon={PocketIcon("搜索",Modifier.size(20.dp))},trailingIcon={if(search.isNotBlank())PocketAction("关闭","清空搜索"){updateSearch("")}},singleLine=true,shape=RoundedCornerShape(12.dp),colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
        HorizontalPager(pager,modifier=Modifier.weight(1f).fillMaxWidth().testTag("workspace-pages"),verticalAlignment=Alignment.Top,key={WorkspaceFilter.entries[it].name}){page->
            val pageFilter=WorkspaceFilter.entries[page]
            val threads=allThreads.filter{pageFilter.matches(threadActivity(it))}
            val openThread:(JSONObject)->Unit=if(pageFilter==WorkspaceFilter.PENDING)onOpenPending else model::openThread
            val visible=model.projects.filter{p->
                val children=threads.filter{threadBelongsToProject(it,p)}
                (pageFilter==WorkspaceFilter.ALL||children.isNotEmpty())&&(search.isBlank()||p.label().contains(search,true)||children.any(::matches))
            }
            val standalone=threads.filter{thread->model.projects.none{threadBelongsToProject(thread,it)}}.filter(::matches)
            LazyColumn(Modifier.fillMaxSize().then(if(page==pager.currentPage)Modifier else Modifier.clearAndSetSemantics{}).testTag("workspace-list-${pageFilter.name}").navigationBarsPadding(),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                if(activeOnly){
                    val shown=threads.filter(::matches)
                    if(shown.isEmpty())item{WorkspaceEmpty(pageFilter,search,true)}
                    items(shown,key={it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,false){openThread(it)}}
                }else{
                    visible.forEach{project->
                        val id=project.optString("projectId");val children=threads.filter{threadBelongsToProject(it,project)}
                        val open=id !in model.collapsedProjects||search.isNotBlank()||pageFilter!=WorkspaceFilter.ALL
                        item(key="project:$id"){
                            ProjectHeading(project,children.size,children.count{threadActivity(it)==ThreadActivity.RUNNING},children.count{threadActivity(it).waiting},children.count(model::hasUnseenCompletion),open,model.connected,onToggle={model.collapsedProjects=if(id in model.collapsedProjects)model.collapsedProjects-id else model.collapsedProjects+id},onNew={onProjectNew(id)})
                        }
                        if(open){
                            val shown=children.filter{project.label().contains(search,true)||matches(it)}
                            if(shown.isEmpty())item{Text("暂无对话，从项目菜单新建",Modifier.padding(start=51.dp,top=8.dp,bottom=18.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            items(shown,key={"project-chat:"+it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,true){openThread(it)}}
                        }
                        item(key="divider:$id"){Spacer(Modifier.height(8.dp))}
                    }
                    if(standalone.isNotEmpty()){
                        item{Text("独立对话",Modifier.padding(horizontal=8.dp,vertical=8.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        items(standalone,key={"independent:"+it.optString("hostId")+it.optString("id")}){WorkspaceThread(it,model,false){openThread(it)}}
                    }
                    if(visible.isEmpty()&&standalone.isEmpty())item{WorkspaceEmpty(pageFilter,search,false)}
                }
            }
        }
    }
}

@Composable private fun WorkspaceEmpty(filter:WorkspaceFilter,search:String,activeOnly:Boolean){
    val title=when{search.isNotBlank()->"没有找到相关对话";filter==WorkspaceFilter.RUNNING->"暂无运行中的对话";filter==WorkspaceFilter.PENDING->"暂无需要你处理的对话";activeOnly->"暂无待处理任务";else->"你的工作空间"}
    EmptyState(title,if(search.isNotBlank())"试试其他关键词，或切换筛选。"else if(filter==WorkspaceFilter.PENDING)"需要审批或回答的对话会自动显示在这里。"else if(filter!=WorkspaceFilter.ALL)"状态变化后会自动显示在这里。"else"连接电脑后，项目与对话会同步到这里。")
}

@Composable private fun ProjectHeading(project:JSONObject,count:Int,running:Int,waiting:Int,completed:Int,open:Boolean,connected:Boolean,onToggle:()->Unit,onNew:()->Unit){
    var menu by remember{mutableStateOf(false)};var path by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().padding(start=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically){
        Row(Modifier.weight(1f).clickable(onClick=onToggle).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
            PocketIcon(if(open)"下拉" else "展开",Modifier.size(14.dp));Spacer(Modifier.width(6.dp))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("项目",Modifier.size(18.dp))}
            Spacer(Modifier.width(8.dp));Text(project.label(),Modifier.weight(1f,false),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp));Text("$count",style=MaterialTheme.typography.bodySmall,color=PocketPalette.muted)
            if(!open&&running>0){Spacer(Modifier.width(5.dp));ProjectActivityCount("$running 运行",if(connected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,"${project.label()}：$running 个运行中${if(connected)""else"，上次状态"}")}
            if(!open&&waiting>0){Spacer(Modifier.width(5.dp));ProjectActivityCount("$waiting 等待",if(connected)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,"${project.label()}：$waiting 个等待处理${if(connected)""else"，上次状态"}")}
            if(completed>0){Spacer(Modifier.width(6.dp));CompletionDot("${project.label()}：$completed 个任务刚完成，未查看")}
        }
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

@Composable private fun CompletionDot(description:String){
    Box(Modifier.size(8.dp).background(Color(0xFF2785F5),RoundedCornerShape(50)).semantics{contentDescription=description})
}

@Composable private fun ProjectActivityCount(text:String,color:Color,description:String){
    Surface(shape=RoundedCornerShape(6.dp),color=color.copy(alpha=.10f),modifier=Modifier.semantics{contentDescription=description}){
        Text(text,Modifier.padding(horizontal=5.dp,vertical=2.dp),fontSize=10.sp,fontWeight=FontWeight.Medium,color=color,maxLines=1)
    }
}

@Composable private fun ThreadActivityMarker(activity:ThreadActivity,connected:Boolean){
    val color=when{
        !connected->MaterialTheme.colorScheme.onSurfaceVariant
        activity==ThreadActivity.RUNNING->MaterialTheme.colorScheme.primary
        activity.waiting->MaterialTheme.colorScheme.tertiary
        activity==ThreadActivity.ERROR->MaterialTheme.colorScheme.error
        else->MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(22.dp),contentAlignment=Alignment.Center){
        if(activity==ThreadActivity.RUNNING&&connected)CircularProgressIndicator(Modifier.size(18.dp),color=color,trackColor=color.copy(alpha=.22f),strokeWidth=2.dp)
        else CompositionLocalProvider(LocalContentColor provides color){
            PocketIcon(when{activity.inFlight->"时钟";activity==ThreadActivity.ERROR->"任务";else->"会话"},Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun WorkspaceThread(thread:JSONObject,model:PocketModel,nested:Boolean,onOpen:()->Unit){
    var menu by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf(false)};var rename by remember{mutableStateOf(false)}
    val activity=threadActivity(thread);val running=activity==ThreadActivity.RUNNING
    val deleteBlock=model.threadManagementBlock()
    val managementLabel=threadManagementLabel(thread)
    val accent=when{!model.connected->MaterialTheme.colorScheme.onSurfaceVariant;running->MaterialTheme.colorScheme.primary;activity.waiting->MaterialTheme.colorScheme.tertiary;else->MaterialTheme.colorScheme.error}
    Row(Modifier.fillMaxWidth().heightIn(min=60.dp),verticalAlignment=Alignment.CenterVertically){
        if(nested)Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f)){
        Surface(shape=RoundedCornerShape(12.dp),color=Color.Transparent,modifier=Modifier.fillMaxWidth().combinedClickable(onClick=onOpen,onLongClick={menu=true},onLongClickLabel="管理对话").semantics{contentDescription="${thread.label()}，${if(!model.connected&&activity.actionable)"上次状态："else""}${activity.label}"}){
            Row(Modifier.fillMaxWidth().heightIn(min=60.dp).padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                ThreadActivityMarker(activity,model.connected)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text(thread.label(),style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(managementLabel!=null)Text(managementLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else if(activity.actionable)Text((if(model.connected)""else"上次状态 · ")+activity.label,style=MaterialTheme.typography.bodySmall,color=accent)
                }
                if(model.hasUnseenCompletion(thread)){Spacer(Modifier.width(8.dp));CompletionDot("${thread.label()}：任务已完成，未查看")}
                val time=threadTime(thread)
                if(time.isNotBlank()){Spacer(Modifier.width(10.dp));Text(time,style=MaterialTheme.typography.bodySmall,color=PocketPalette.muted)}
            }
        }
        DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
            DropdownMenuItem(text={Text("重命名对话")},enabled=model.connected&&!model.busy&&model.pendingOperation==null,onClick={menu=false;rename=true})
            DropdownMenuItem(text={Text(if(thread.optBoolean("archived"))"恢复对话"else"归档对话")},enabled=deleteBlock==null,onClick={menu=false;model.manageThread(thread,if(thread.optBoolean("archived"))"unarchive"else"archive")})
            DropdownMenuItem(text={Text("删除对话",color=MaterialTheme.colorScheme.error)},enabled=deleteBlock==null,onClick={menu=false;deleting=true})
        }
        }
    }
    if(rename)RenameThreadDialog(model,thread){rename=false}
    if(deleting)DeleteThreadDialog(model,thread,deleteBlock){deleting=false}
}
