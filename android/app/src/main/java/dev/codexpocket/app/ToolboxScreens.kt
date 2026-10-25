package dev.codexpocket.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun remoteTime(value:Long):String=if(value<=0)"暂不可用"else try{DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))}catch(_:Exception){"暂不可用"}
private fun JSONObject.strings(key:String)=optJSONArray(key)?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()
private fun usageNumber(value:JSONObject?,key:String):String=if(value==null||value.isNull(key))"—"else value.optLong(key).toString()
internal val toolSections=listOf("files" to "项目文件","git" to "代码与 Git","skills" to "技能","plugins" to "插件","schedules" to "定时任务","usage" to "会话统计","mcp" to "工具服务")

@Composable fun RenameThreadDialog(model:PocketModel,thread:JSONObject,onDismiss:()->Unit){
    var name by remember{mutableStateOf(thread.label())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("重命名对话")},text={OutlinedTextField(name,{if(it.length<=60)name=it},label={Text("对话名称")},supportingText={Text("${name.length} / 60")},singleLine=true,modifier=Modifier.fillMaxWidth())},confirmButton={TextButton(onClick={model.renameThread(thread,name.trim());onDismiss()},enabled=name.isNotBlank()&&!model.busy&&model.connected&&model.pendingOperation==null){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun ToolboxScreen(model:PocketModel,initial:String="home",onClose:()->Unit){
    var section by remember{mutableStateOf(initial)}
    var project by remember{mutableStateOf(model.selected?.optString("projectId")?.takeIf{it.isNotBlank()}?:model.projects.firstOrNull()?.optString("projectId"))}
    val args=JSONObject().also{if(model.selected!=null)it.put("threadId",model.selected!!.getString("id"))else if(project!=null)it.put("projectId",project)}
    BackHandler{if(section!="home")section="home"else onClose()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        ToolSystemBars()
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","返回工具目录"){if(section=="home")onClose()else section="home"};Text(if(section=="home")"工作空间工具"else toolSections.find{it.first==section}?.second.orEmpty(),Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold);PocketAction("关闭","关闭工具面板",onClick=onClose)}
            if(model.selected==null&&section in listOf("files","git"))ToolProjectPicker(model,project){project=it}
            if(!model.connected)Text("连接电脑后才能读取或更改状态",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
            model.error?.let{ErrorBanner(it){model.error=null}}
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
            key(section,args.toString(),model.profile?.optString("hostId")){
                when(section){
                    "home"->ToolHome(model){section=it}
                    "files"->if(args.length()>0)FilesPanel(model,args){onClose()}else ToolEmpty("项目","先选择一个项目","选择项目后，即可浏览文件、搜索和预览。")
                    "git"->if(args.length()>0)GitPanel(model,args)else ToolEmpty("差异","先选择一个项目","选择已有 Git 仓库的项目，即可查看分支与修改。")
                    "skills"->SkillsPanel(model,args,onClose)
                    "plugins"->PluginsPanel(model,args)
                    "schedules"->SchedulesPanel(model,args,onClose)
                    "usage"->UsagePanel(model,args)
                    "mcp"->McpPanel(model,args)
                }
            }
        }}
    }
}

@Composable private fun RemoteData(model:PocketModel,action:String,args:JSONObject,revision:Int=0,content:@Composable (JSONObject,()->Unit)->Unit){
    var data by remember{mutableStateOf<JSONObject?>(null)};var error by remember{mutableStateOf<String?>(null)};var loading by remember{mutableStateOf(false)};var reload by remember{mutableIntStateOf(0)}
    LaunchedEffect(action,args.toString(),revision,reload,model.toolRevision,model.connected){if(!model.connected)return@LaunchedEffect;loading=true;error=null;try{data=model.rpc(action,args)}catch(e:CancellationException){throw e}catch(e:Exception){data=null;if(action=="git.status"&&e.message?.contains("not a git repository",true)==true)data=JSONObject().put("available",false).put("reason","not-repository").put("message","这个项目还没有 Git 仓库。选择已有 Git 仓库的项目后，即可查看分支和代码修改。")else error=e.message}finally{loading=false}}
    Column(Modifier.fillMaxSize()){
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{ToolEmpty("电脑","暂时无法读取",if(it.contains("Unsupported request",true))"请先更新电脑连接器，再使用此功能。"else it){reload++}}
        if(data!=null&&error==null)content(data!!){reload++}else if(!loading&&error==null)EmptyState("等待连接","连接电脑后读取最新状态。")
    }
}

@Composable private fun FilesPanel(model:PocketModel,args:JSONObject,onReference:()->Unit){
    var path by remember{mutableStateOf("")};var query by remember{mutableStateOf("")};var search by remember{mutableStateOf("")};var offset by remember{mutableIntStateOf(0)}
    LaunchedEffect(query){delay(300);search=query.trim();offset=0}
    if(model.previewKind!=null){PreviewPane(model,Modifier.fillMaxWidth().fillMaxHeight(),true,{},onMinimize={model.closePreview()});return}
    Column(Modifier.fillMaxSize()){
        Box(Modifier.padding(horizontal=16.dp,vertical=12.dp)){ToolSearch(query,{query=it},"搜索项目文件")}
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick={path=path.substringBeforeLast('/',"");offset=0;query=""},enabled=path.isNotBlank()){Text("上一级")};Text(if(path.isBlank())"项目根目录"else path,Modifier.weight(1f),fontSize=12.sp)}
        val input=JSONObject(args.toString()).put("path",path).put("offset",offset).put("query",search)
        RemoteData(model,if(search.isBlank())"workspace.list"else"workspace.search",input){data,reload->
            LazyColumn(contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp)){
                val entries=data.optJSONArray("entries").objects()
                if(entries.isEmpty())item{EmptyState("没有匹配文件","尝试其他名称或返回上一级。")}
                items(entries,key={it.optString("path")}){entry->
                    val directory=entry.optBoolean("directory")
                    Row(Modifier.fillMaxWidth().clickable{if(directory){path=entry.getString("path");offset=0;query=""}else model.previewProjectFile(args,entry.getString("path"))}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){PocketIcon(if(directory)"项目"else"文件",Modifier.size(20.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(entry.optString("name"),fontSize=14.sp);if(search.isNotBlank())Text(entry.optString("path"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(!directory&&model.selected!=null)TextButton(onClick={model.addReference(entry.getString("path"));onReference()}){Text("@引用")}}
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                }
                item{Row{if(offset>0)TextButton(onClick={offset=(offset-100).coerceAtLeast(0)}){Text("上一页")};if(!data.isNull("nextOffset"))TextButton(onClick={offset=data.getInt("nextOffset")}){Text("下一页")}};if(data.optBoolean("truncated"))Text("结果较多，请缩小搜索范围。",fontSize=12.sp);TextButton(onClick=reload){Text("刷新文件")}}
            }
        }
    }
}

@Composable private fun DiffView(data:JSONObject,onBack:()->Unit){
    val viewWidth=LocalConfiguration.current.screenWidthDp.dp
    Column(Modifier.fillMaxSize()){
        TextButton(onClick=onBack){Text("返回修改文件")};SelectionContainer{Text(data.optString("path"),Modifier.padding(horizontal=16.dp),fontWeight=FontWeight.Medium,fontSize=13.sp)}
        if(data.optBoolean("truncated"))Text("差异过长，部分内容请在电脑查看",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),contentPadding=PaddingValues(vertical=12.dp)){
            items(unifiedDiffLines(data.optString("diff"))){line->val added=line.kind=="add";val removed=line.kind=="remove";Row(Modifier.widthIn(min=viewWidth).background(when{added->Color(0x2222AA66);removed->Color(0x22DD4455);else->Color.Transparent}).padding(horizontal=12.dp,vertical=2.dp)){Text("${line.oldLine?:""}".padStart(4)+" "+"${line.newLine?:""}".padStart(4),fontFamily=FontFamily.Monospace,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(12.dp));SelectionContainer{Text(line.text.ifEmpty{" "},fontFamily=FontFamily.Monospace,fontSize=12.sp,color=when{added->Color(0xFF23834B);removed->Color(0xFFC63C4C);else->MaterialTheme.colorScheme.onSurface})}}}

        }
    }
}

@Composable private fun GitPanel(model:PocketModel,args:JSONObject){
    var branchesExpanded by remember{mutableStateOf(false)};var diff by remember{mutableStateOf<JSONObject?>(null)};var failure by remember{mutableStateOf<String?>(null)};var staged by remember{mutableStateOf(false)};var selected by remember{mutableStateOf(setOf<String>())};var revision by remember{mutableIntStateOf(0)}
    var operation by remember{mutableStateOf<String?>(null)};var commitMessage by remember{mutableStateOf("")};var base by remember{mutableStateOf<String?>(null)};var confirm by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    if(diff!=null){DiffView(diff!!){diff=null};return}
    RemoteData(model,"git.status",args,revision){data,reload->
        if(data.has("available")&&!data.optBoolean("available")){
            ToolEmpty("差异",if(data.optString("reason")=="git-not-installed")"电脑尚未安装 Git"else if(data.optString("reason")=="repository-root-required")"请选择仓库根目录"else"这个项目还没有 Git 仓库",data.optString("message").removePrefix("这个项目还没有 Git 仓库。"),reload)
            return@RemoteData
        }
        val files=data.optJSONArray("files").objects();val can=model.connected&&!model.busy&&model.pendingOperation==null
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{ToolHeading("工作区","${files.size} 个修改文件",reload)}
            item{ToolCard{
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){ToolBadge("差异");Column(Modifier.weight(1f)){Text(data.optString("branch"),fontSize=19.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(data.optString("upstream").ifBlank{"尚未设置上游分支"},fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};ToolPill(if(files.isEmpty())"无修改"else"${files.size} 项修改",positive=files.isEmpty())}
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={operation="review"},enabled=can&&model.selected!=null,shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),modifier=Modifier.weight(1f)){Text("发起审查",fontSize=12.sp)}
                    OutlinedButton(onClick={operation="commit"},enabled=can&&files.isNotEmpty(),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),modifier=Modifier.weight(1f)){Text("提交",fontSize=12.sp)}
                    OutlinedButton(onClick={operation="push";confirm=true},enabled=can&&data.optString("upstream").isNotBlank(),shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp),modifier=Modifier.weight(1f)){Text("推送",fontSize=12.sp)}
                }
            }}
            item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!staged,{staged=false},label={Text("未暂存差异",fontSize=12.sp)});FilterChip(staged,{staged=true},label={Text("已暂存差异",fontSize=12.sp)})}}
            failure?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
            if(files.isEmpty())item{EmptyState("工作区干净","当前没有未提交修改。")}
            items(files,key={it.optString("path")}){file->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(file.optString("path") in selected,{checked->selected=if(checked)selected+file.getString("path")else selected-file.getString("path")});Surface(onClick={scope.launch{try{diff=model.rpc("git.diff",JSONObject(args.toString()).put("path",file.getString("path")).put("scope",if(staged)"staged"else"working"))}catch(e:Exception){failure=e.message}}},modifier=Modifier.weight(1f),color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(10.dp)){Row(Modifier.padding(12.dp)){Text(file.optString("status"),fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(file.optString("path"),fontSize=13.sp)}}}}
            item{TextButton(onClick={branchesExpanded=!branchesExpanded}){Text(if(branchesExpanded)"收起分支列表"else"查看全部分支",fontSize=12.sp)};if(branchesExpanded)Text(data.strings("branches").joinToString("\n"),fontFamily=FontFamily.Monospace,fontSize=12.sp)}
        }
        if(operation in listOf("commit","review")&&!confirm)AlertDialog(onDismissRequest={operation=null},title={Text(when(operation){"commit"->"提交所选修改";else->"发起代码审查"})},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            when(operation){
                "commit"->{Text("${data.optString("branch")} · 已选择 ${selected.size} 个文件",fontSize=13.sp);Text(selected.joinToString("\n"),fontSize=12.sp);OutlinedTextField(commitMessage,{commitMessage=it},label={Text("提交说明")})}

                else->{Text("审查结果会显示在当前对话；不会直接修改文件。",fontSize=13.sp);DropdownPicker("基础分支（可选）",data.strings("branches").map{it to it},base,"审查未提交修改"){base=it};if(model.selected==null)Text("请先打开此项目的一条对话，再发起审查。",color=MaterialTheme.colorScheme.error)}
            }
        }},confirmButton={TextButton(onClick={confirm=true},enabled=can&&when(operation){"commit"->selected.isNotEmpty()&&commitMessage.isNotBlank();else->model.selected!=null}){Text("检查并继续")}},dismissButton={TextButton(onClick={operation=null}){Text("取消")}})
        if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("确认${when(operation){"commit"->"提交";"push"->"推送";else->"审查"}}？")},text={Text(when(operation){"commit"->"分支 ${data.optString("branch")}\n文件：${selected.joinToString("、")}\n说明：$commitMessage";"push"->"将 ${data.optString("branch")} 的提交 ${data.optString("head").take(8)} 推送至 ${data.optString("upstream")}。";else->"范围：${base?:"未提交修改"}。将向当前对话发送审查指令。"})},confirmButton={TextButton(onClick={val op=operation;confirm=false;operation=null;val input=JSONObject(args.toString()).put("head",data.optString("head")).put("branch",data.optString("branch"));when(op){"commit"->input.put("revision",data.getString("revision")).put("message",commitMessage).put("paths",JSONArray(selected.toList()));"review"->input.put("target",if(base==null)JSONObject().put("type","uncommittedChanges")else JSONObject().put("type","baseBranch").put("branch",base))};model.toolMutation(if(op=="review")"review.start"else"git.$op",input){r->revision++;selected=emptySet();r.optString("url").takeIf{it.isNotBlank()}?.let{model.openLink(it)};model.notice=if(op=="review")"审查已发送到对话"else"操作已完成"}}){Text("确认执行")}},dismissButton={TextButton(onClick={confirm=false}){Text("返回检查")}})
    }
}

@Composable private fun SkillsPanel(model:PocketModel,args:JSONObject,onClose:()->Unit){
    var revision by remember{mutableIntStateOf(0)};var editing by remember{mutableStateOf<JSONObject?>(null)};var detail by remember{mutableStateOf<JSONObject?>(null)};var query by remember{mutableStateOf("")};var enabledOnly by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    RemoteData(model,"extensions.list",args,revision){data,reload->
        val skills=data.optJSONArray("skills").objects()
        val filtered=skills.filter{(!enabledOnly||it.optBoolean("enabled"))&&(skillDisplayName(it)+it.optString("name")+it.optString("description")).contains(query,true)}
        Column(Modifier.fillMaxSize()){
            Column(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                ToolHeading("我的技能","${skills.size} 项技能 · ${skills.count{it.optBoolean("enabled")}} 项已启用",reload){PocketAction("新增","创建技能",enabled=model.connected&&!model.busy){editing=JSONObject().put("content","---\nname: my-skill\ndescription: Describe when to use this skill\n---\n\n# Instructions\n")}}
                ToolSearch(query,{query=it},"搜索技能名称或用途")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!enabledOnly,{enabledOnly=false},label={Text("全部",fontSize=12.sp)});FilterChip(enabledOnly,{enabledOnly=true},label={Text("已启用",fontSize=12.sp)})}
            }
            LazyColumn(contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(filtered.isEmpty())item{Text("没有匹配的技能",Modifier.padding(vertical=24.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(filtered,key={it.optString("path")}){skill->ToolCard{
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        ToolBadge("推理")
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(skillDisplayName(skill),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);ToolPill(skillSource(skill))}
                        Switch(skill.optBoolean("enabled"),{enabled->model.toolMutation("skill.set",JSONObject(args.toString()).put("path",skill.getString("path")).put("enabled",enabled)){revision++}},enabled=!model.busy&&model.pendingOperation==null&&model.connected)
                    }
                    Text(skill.optJSONObject("interface")?.optString("shortDescription")?.takeIf{it.isNotBlank()&&it!="null"}?:skill.optString("description"),fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={scope.launch{try{detail=model.rpc("skill.read",JSONObject(args.toString()).put("path",skill.getString("path"))).also{it.put("name",skillDisplayName(skill)).put("source",skillSource(skill)).put("path",skill.getString("path")).put("description",skill.optString("description"))}}catch(e:Exception){model.error=e.message}}},contentPadding=PaddingValues(horizontal=0.dp,vertical=0.dp),modifier=Modifier.heightIn(min=36.dp)){Text("查看详情",fontSize=12.sp);PocketIcon("展开",Modifier.size(14.dp))}
                        Spacer(Modifier.weight(1f))
                        if(model.selected!=null&&skill.optBoolean("enabled"))TextButton(onClick={model.draft=model.draft+" $"+skill.optString("name")+" ";model.notice="技能已加入输入框";onClose()},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text("使用技能",fontSize=12.sp)}
                    }
                }}
                if(data.optJSONArray("errors").objects().isNotEmpty())item{Text("部分技能未能加载，可在电脑检查后刷新。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
    detail?.let{skill->SkillDetail(skill,{detail=null}){editing=skill;detail=null}}
    editing?.let{skill->var name by remember{mutableStateOf(skill.optString("name","my-skill"))};var content by remember{mutableStateOf(skill.optString("content"))};var deleting by remember{mutableStateOf(false)}
        Dialog(onDismissRequest={editing=null},properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(skill.has("path"))"编辑技能"else"创建技能",Modifier.weight(1f),fontSize=20.sp);PocketAction("关闭"){editing=null}}
            if(!skill.has("path"))OutlinedTextField(name,{name=it},label={Text("目录名称（小写字母、数字、短横线）")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(content,{content=it},modifier=Modifier.weight(1f).fillMaxWidth(),label={Text("SKILL.md")},textStyle=MaterialTheme.typography.bodySmall.copy(fontFamily=FontFamily.Monospace))
            Row{if(skill.has("path"))TextButton(onClick={deleting=true}){Text("删除技能",color=MaterialTheme.colorScheme.error)};Spacer(Modifier.weight(1f));Button(onClick={model.toolMutation("skill.save",JSONObject(args.toString()).put("name",name).put("content",content).put("contentHash",skill.optString("contentHash")).also{if(skill.has("path"))it.put("path",skill.getString("path"))}){revision++;editing=null}},enabled=!model.busy&&model.pendingOperation==null){Text("保存到电脑")}}
        }}}
        if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除技能？")},text={Text("电脑上的用户技能将移到备份目录，不再被加载。")},confirmButton={TextButton(onClick={model.toolMutation("skill.delete",JSONObject(args.toString()).put("path",skill.getString("path"))){revision++;editing=null};deleting=false}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
    }
}

@Composable private fun FullEditor(title:String,initial:String,editable:Boolean,onClose:()->Unit,footer:@Composable ()->Unit={}){Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=18.sp);PocketAction("关闭",onClick=onClose)};SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())){Text(initial,fontFamily=FontFamily.Monospace,fontSize=12.sp)};footer()}}}}

@Composable private fun PluginsPanel(model:PocketModel,args:JSONObject){
    var catalog by remember{mutableStateOf(false)};var query by remember{mutableStateOf("")};var revision by remember{mutableIntStateOf(0)};var confirm by remember{mutableStateOf<Pair<JSONObject,String>?>(null)}
    Column(Modifier.fillMaxSize()){
        Column(Modifier.padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!catalog,{catalog=false},label={Text("已安装",fontSize=12.sp)});FilterChip(catalog,{catalog=true},label={Text("插件市场",fontSize=12.sp)})}
            ToolSearch(query,{query=it},"搜索插件")
        }
        RemoteData(model,"plugin.list",JSONObject(args.toString()).put("catalog",catalog),revision){data,reload->LazyColumn(contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            val plugins=data.optJSONArray("plugins").objects().filter{(catalog||it.optBoolean("installed"))&&(it.optString("name")+it.optJSONObject("interface")?.optString("description").orEmpty()).contains(query,true)}
            item{ToolHeading(if(catalog)"发现插件"else"我的插件","${plugins.size} 项扩展",reload)}
            if(plugins.isEmpty())item{Text(if(catalog)"没有匹配的插件"else"尚未安装插件",Modifier.padding(vertical=24.dp),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(plugins,key={it.optString("id")}){plugin->ToolCard{
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    ToolBadge("工具")
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(plugin.optJSONObject("interface")?.optString("displayName")?.takeIf{it.isNotBlank()&&it!="null"}?:plugin.optString("name"),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);ToolPill(if(plugin.optBoolean("installed"))if(plugin.optBoolean("enabled"))"已启用"else"已停用"else"未安装",positive=plugin.optBoolean("installed")&&plugin.optBoolean("enabled"))}
                    if(pluginCanManage(plugin))Switch(plugin.optBoolean("enabled"),{enabled->model.toolMutation("plugin.set",JSONObject(args.toString()).put("pluginId",plugin.getString("id")).put("enabled",enabled)){revision++}},enabled=model.connected&&!model.busy&&model.pendingOperation==null)
                }
                plugin.optJSONObject("interface")?.optString("description")?.takeIf{it.isNotBlank()&&it!="null"}?.let{Text(it,fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(plugin.optString("marketplaceName"),Modifier.weight(1f),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(pluginCanManage(plugin))TextButton(onClick={confirm=plugin to "uninstall"},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text("卸载",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    else if(pluginCanInstall(plugin))OutlinedButton(onClick={confirm=plugin to "install"},shape=RoundedCornerShape(10.dp),enabled=model.connected&&!model.busy&&model.pendingOperation==null){Text("安装",fontSize=12.sp)}
                    else ToolPill(if(plugin.optString("installPolicy")=="INSTALLED_BY_DEFAULT")"管理员管理"else"需在电脑安装")
                }
            }}
            if(data.optJSONArray("errors").objects().isNotEmpty())item{Text("部分来源暂时未能加载，可稍后刷新。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }}
    }
    confirm?.let{(plugin,action)->AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="install")"安装插件？"else"卸载插件？")},text={Text("${plugin.optString("name")}\n来源：${plugin.optString("marketplaceName")}\n插件可包含技能和工具，实际调用仍遵循 Codex 权限。")},confirmButton={TextButton(onClick={confirm=null;model.toolMutation("plugin.$action",JSONObject(args.toString()).put("pluginId",plugin.getString("id"))){revision++}}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}

@Composable private fun SchedulesPanel(model:PocketModel,args:JSONObject,onClose:()->Unit){
    var editing by remember{mutableStateOf<JSONObject?>(null)};var revision by remember{mutableIntStateOf(0)};var deleting by remember{mutableStateOf<JSONObject?>(null)};var running by remember{mutableStateOf<JSONObject?>(null)}
    RemoteData(model,"automations.list",JSONObject(),revision){data,reload->LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ToolHeading("我的任务","${data.optJSONArray("jobs")?.length()?:0} 个任务 · ${data.optString("timeZone")}",reload){PocketAction("新增","创建任务",enabled=model.connected&&!model.busy){editing=JSONObject().put("enabled",true).put("projectId",args.optString("projectId"))}}}
        item{Text("由电脑连接器执行。电脑需保持运行，离线期间不补发。",fontSize=11.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(data.optJSONArray("jobs").objects().isEmpty())item{ToolCard{Text("还没有定时任务",fontSize=16.sp,fontWeight=FontWeight.Medium);Text("设置时间和指令，让电脑按计划开始工作。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        items(data.optJSONArray("jobs").objects(),key={it.optString("id")}){job->Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(job.optString("name"),Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Switch(job.optBoolean("enabled"),{enabled->model.toolMutation("automation.save",JSONObject(job.toString()).put("enabled",enabled)){revision++}},enabled=!model.busy&&model.pendingOperation==null)}
            Text(job.optString("prompt"),fontSize=12.sp,maxLines=4);Text(if(job.optBoolean("enabled"))"下次执行：${remoteTime(job.optLong("nextAt"))}"else"已暂停",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            Row{TextButton(onClick={editing=job}){Text("编辑")};TextButton(onClick={running=job}){Text("立即执行")};TextButton(onClick={deleting=job}){Text("删除")}}
            job.optJSONArray("history").objects().take(3).forEach{run->TextButton(onClick={run.optString("threadId").takeIf{it.isNotBlank()&&it!="null"}?.let{model.openThread(JSONObject().put("id",it).put("hostId","local").put("title","定时 · ${job.optString("name")}"));onClose()}},enabled=run.has("threadId")){Text("${remoteTime(run.optLong("at"))} · ${when(run.optString("status")){"submitted"->"已发起，查看对话";"failed"->"启动失败";"unknown"->"结果待确认，请检查电脑";else->"正在启动"}}",fontSize=11.sp)}};job.optJSONArray("history").objects().firstOrNull()?.optString("error")?.takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
        }}}
    }}
    editing?.let{job->ScheduleEditor(model,job,{editing=null}){value->model.toolMutation("automation.save",value){revision++;editing=null}}}
    deleting?.let{job->AlertDialog(onDismissRequest={deleting=null},title={Text("删除定时任务？")},text={Text(job.optString("name"))},confirmButton={TextButton(onClick={deleting=null;model.toolMutation("automation.delete",JSONObject().put("id",job.getString("id"))){revision++}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
    running?.let{job->AlertDialog(onDismissRequest={running=null},title={Text("立即执行任务？")},text={Text("将在电脑新建对话并发送：\n${job.optString("prompt")}")},confirmButton={TextButton(onClick={running=null;model.toolMutation("automation.run",JSONObject().put("id",job.getString("id"))){revision++}}){Text("执行")}},dismissButton={TextButton(onClick={running=null}){Text("取消")}})}
}

@Composable private fun ScheduleEditor(model:PocketModel,job:JSONObject,onClose:()->Unit,onSave:(JSONObject)->Unit){
    var name by remember{mutableStateOf(job.optString("name"))};var prompt by remember{mutableStateOf(job.optString("prompt"))};var project by remember{mutableStateOf(job.optString("projectId").takeIf{it.isNotBlank()})};val schedule=job.optJSONObject("schedule")?:JSONObject();var interval by remember{mutableStateOf(schedule.optString("type")=="interval")};var hour by remember{mutableStateOf(schedule.optInt("hour",9).toString())};var minute by remember{mutableStateOf(schedule.optInt("minute",0).toString())};var hours by remember{mutableStateOf(schedule.optInt("hours",24).toString())};var weekdays by remember{mutableStateOf(schedule.optBoolean("weekdays"))};var zone by remember{mutableStateOf(schedule.optString("timeZone",ZoneId.systemDefault().id))};var error by remember{mutableStateOf<String?>(null)}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){Surface(Modifier.fillMaxSize()){Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text(if(job.has("id"))"编辑任务"else"创建任务",Modifier.weight(1f),fontSize=20.sp);PocketAction("关闭",onClick=onClose)}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(name,{name=it},label={Text("任务名称")},modifier=Modifier.fillMaxWidth());DropdownPicker("执行项目",model.projects.map{it.optString("projectId") to it.label()},project,"选择项目"){project=it};OutlinedTextField(prompt,{prompt=it},label={Text("任务指令")},minLines=4,modifier=Modifier.fillMaxWidth())
            Row{FilterChip(!interval,{interval=false},label={Text("每天 / 工作日")});Spacer(Modifier.width(8.dp));FilterChip(interval,{interval=true},label={Text("间隔小时")})}
            if(interval)OutlinedTextField(hours,{hours=it},label={Text("每隔几小时（1–168）")})else{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedTextField(hour,{hour=it},label={Text("小时 0–23")},modifier=Modifier.weight(1f));OutlinedTextField(minute,{minute=it},label={Text("分钟 0–59")},modifier=Modifier.weight(1f))};Row(verticalAlignment=Alignment.CenterVertically){Checkbox(weekdays,{weekdays=it});Text("仅周一至周五")};OutlinedTextField(zone,{zone=it},label={Text("时区，例如 Asia/Shanghai")},modifier=Modifier.fillMaxWidth())}
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
        Button(onClick={try{val s=JSONObject().put("type",if(interval)"interval"else"daily");if(interval){val h=hours.toInt();require(h in 1..168){"间隔需为 1–168 小时"};s.put("hours",h)}else{val h=hour.toInt();val m=minute.toInt();require(h in 0..23&&m in 0..59){"时间格式无效"};ZoneId.of(zone);s.put("hour",h).put("minute",m).put("weekdays",weekdays).put("timeZone",zone)};onSave(JSONObject().put("name",name).put("prompt",prompt).put("projectId",project).put("schedule",s).put("enabled",job.optBoolean("enabled",true)).also{if(job.has("id"))it.put("id",job.getString("id"))})}catch(e:Exception){error=e.message?:"请检查时间"}},enabled=name.isNotBlank()&&prompt.isNotBlank()&&project!=null&&!model.busy&&model.pendingOperation==null,modifier=Modifier.fillMaxWidth()){Text("保存任务")}
    }}}
}

@Composable private fun UsagePanel(model:PocketModel,args:JSONObject){
    RemoteData(model,"account.usage",args){data,reload->LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{ToolHeading("当前会话","只显示电脑实际记录的数据",reload)}
        val rate=data.optJSONObject("rateLimits");val buckets=rate?.optJSONObject("rateLimitsByLimitId");val limits=buckets?.keys()?.asSequence()?.mapNotNull{buckets.optJSONObject(it)}?.toList().orEmpty().ifEmpty{listOfNotNull(rate?.optJSONObject("rateLimits"))}
        limits.forEach{limit->item{ToolCard{Text(limit.optString("limitName").takeIf{it.isNotBlank()&&it!="null"}?:"账号额度",fontSize=14.sp,fontWeight=FontWeight.Medium);listOf("primary" to "主要额度","secondary" to "长期额度").forEach{(id,label)->limit.optJSONObject(id)?.let{window->val used=window.optInt("usedPercent").coerceIn(0,100);Text("$label · 剩余 ${100-used}%",fontSize=13.sp);LinearProgressIndicator(progress={(100-used)/100f},modifier=Modifier.fillMaxWidth());Text("恢复时间：${remoteTime(window.optLong("resetsAt")*1000)}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
        val token=data.optJSONObject("tokenUsage")?:model.snapshot?.optJSONObject("tokenUsage");val total=token?.optJSONObject("total");val last=token?.optJSONObject("last");val context=token?.optLong("modelContextWindow")?:0L;val used=last?.optLong("totalTokens")?:0L
        if(token==null)item{ToolCard{ToolBadge("用量");Text("还没有会话统计",fontSize=16.sp,fontWeight=FontWeight.Medium);Text(if(model.selected==null)"先打开一条对话，再查看 Token 和上下文。"else"这条对话暂未记录 Token 数据，运行一次后可刷新。",fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        else {
            item{ToolCard{Text("累计 Token",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(usageNumber(total,"totalTokens"),fontSize=32.sp,fontWeight=FontWeight.SemiBold);HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);Row(Modifier.fillMaxWidth()){
                Column(Modifier.weight(1f)){Text("输入",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(usageNumber(total,"inputTokens"),fontSize=16.sp,fontWeight=FontWeight.Medium)}
                Column(Modifier.weight(1f)){Text("输出",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(usageNumber(total,"outputTokens"),fontSize=16.sp,fontWeight=FontWeight.Medium)}
            };Row(Modifier.fillMaxWidth()){Text("缓存 ${usageNumber(total,"cachedInputTokens")}",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("推理 ${usageNumber(total,"reasoningOutputTokens")}",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            if(context>0)item{ToolCard{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("上下文",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Medium);ToolPill("${(used*100/context).coerceIn(0,100)}%",positive=true)};LinearProgressIndicator(progress={(used.toFloat()/context).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(6.dp));Text("上下文：$used / $context（${(used*100/context).coerceIn(0,100)}%）",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        }
        if(limits.isEmpty())item{Text("当前连接未提供账号额度，已隐藏此项。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }}
}

@Composable private fun McpPanel(model:PocketModel,args:JSONObject){
    var revision by remember{mutableIntStateOf(0)};var url by remember{mutableStateOf<String?>(null)}
    if(model.previewKind=="browser"){PreviewPane(model,Modifier.fillMaxSize(),true,{},onMinimize={model.closePreview()});return}
    RemoteData(model,"mcp.list",args,revision){data,reload->LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        val servers=data.optJSONArray("servers").objects();val pending=servers.count{canMcpLogin(it)&&it.optString("authStatus")=="notLoggedIn"}
        item{ToolHeading("已配置服务",if(pending>0)"${servers.size} 个服务 · $pending 个待授权"else"${servers.size} 个服务 · 认证由各服务管理",reload)}
        if(pending>0)item{Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){PocketIcon("权限",Modifier.size(18.dp));Text("有服务需要登录。完成授权后，返回这里刷新状态。",fontSize=12.sp,lineHeight=19.sp)}}}
        if(servers.isEmpty())item{Text("电脑尚未配置工具服务。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(servers,key={it.optString("name")}){server->ToolCard{
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                ToolBadge("工具")
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(mcpDisplayName(server),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(server.optString("name"),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
                ToolPill(mcpAuthLabel(server),positive=server.optString("authStatus") in listOf("oAuth","bearerToken"),attention=server.optString("authStatus")=="notLoggedIn")
            }
            val state=server.optString("connectionStatus")
            if(state in listOf("failed","authenticationRequired","disabled"))Text(when(state){"failed"->"服务连接失败，请在电脑检查配置";"disabled"->"此服务已在电脑停用";else->"服务需要认证"},fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(canMcpLogin(server))Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){OutlinedButton(onClick={model.toolMutation("mcp.login",JSONObject(args.toString()).put("name",server.getString("name"))){result->url=result.optString("authorizationUrl").takeIf{it.startsWith("https://")};revision++}},shape=RoundedCornerShape(10.dp),enabled=model.connected&&!model.busy&&model.pendingOperation==null,contentPadding=PaddingValues(horizontal=14.dp,vertical=6.dp)){Text(if(server.optString("authStatus")=="notLoggedIn")"去授权"else"重新授权",fontSize=12.sp)}}
        }}
        if(servers.any{it.optString("authStatus")=="unsupported"})item{Text("电脑管理的服务无需在手机重复登录。对话中的操作审批仍会显示在聊天中。",Modifier.padding(vertical=8.dp),fontSize=11.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(data.optJSONArray("logins").objects()){login->ToolCard{Text("${login.optString("name")} · ${when(login.optString("status")){"completed"->"授权已完成";"failed"->"授权未完成，请重试或在电脑处理";else->"等待授权完成"}}",fontSize=12.sp)}}
    }}
    url?.let{address->AlertDialog(onDismissRequest={url=null},title={Text("打开授权页面")},text={Text("完成登录后回到这里刷新。只有电脑返回确认，服务才会标记为已授权。")},confirmButton={TextButton(onClick={model.openLink(address);url=null}){Text("继续")}},dismissButton={TextButton(onClick={url=null}){Text("取消")}})}
}

@Composable fun FileMentionSuggestions(model:PocketModel,onBrowse:()->Unit){
    val token=model.draft.substringAfterLast(' ').substringAfterLast('\n')
    val query=token.takeIf{it.startsWith('@')}?.drop(1)
    var matches by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    LaunchedEffect(query,model.selected?.optString("id"),model.connected){matches=emptyList();if(query.isNullOrBlank()||!model.connected)return@LaunchedEffect;delay(250);try{matches=model.rpc("workspace.search",JSONObject().put("threadId",model.selected!!.getString("id")).put("query",query)).optJSONArray("entries").objects().filter{!it.optBoolean("directory")}.take(4)}catch(_:Exception){}}
    if(query!=null)Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){
        Column{matches.forEach{file->TextButton(onClick={model.draft=model.draft.dropLast(token.length);model.addReference(file.getString("path"))},modifier=Modifier.fillMaxWidth()){Text(file.getString("path"),fontSize=12.sp)}};TextButton(onClick=onBrowse){Text("浏览项目文件并引用",fontSize=12.sp)}}
    }
}
