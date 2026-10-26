package dev.codexpocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable internal fun ToolSystemBars() {
    val view=LocalView.current
    val light=MaterialTheme.colorScheme.background.luminance()>.5f
    SideEffect{(view.parent as? DialogWindowProvider)?.window?.let{window->
        window.statusBarColor=android.graphics.Color.TRANSPARENT
        window.navigationBarColor=android.graphics.Color.TRANSPARENT
        window.isStatusBarContrastEnforced=false
        window.isNavigationBarContrastEnforced=false
        androidx.core.view.WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light}
    }}
}

@Composable internal fun ToolCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

@Composable internal fun ToolBadge(kind:String,tint:Color=MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(38.dp).background(tint.copy(alpha=.09f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides tint){PocketIcon(kind,Modifier.size(20.dp))}
    }
}

@Composable internal fun ToolPill(label:String,positive:Boolean=false,attention:Boolean=false) {
    val tint=when{attention->MaterialTheme.colorScheme.tertiary;positive->if(MaterialTheme.colorScheme.background.luminance()>.5f)Color(0xFF278064)else Color(0xFF77D8B2);else->MaterialTheme.colorScheme.onSurfaceVariant}
    Surface(shape=RoundedCornerShape(6.dp),color=tint.copy(alpha=.09f)){
        Text(label,Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=11.sp,fontWeight=FontWeight.Medium,color=tint)
    }
}

@Composable internal fun ToolHeading(title:String,subtitle:String?=null,onRefresh:()->Unit,action:(@Composable ()->Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold);subtitle?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        action?.invoke()
        PocketAction("刷新","刷新$title",onClick=onRefresh)
    }
}

@Composable internal fun ToolSearch(value:String,onChange:(String)->Unit,hint:String) {
    OutlinedTextField(value,onChange,placeholder={Text(hint,fontSize=13.sp)},singleLine=true,leadingIcon={PocketIcon("搜索",Modifier.size(18.dp))},trailingIcon={if(value.isNotEmpty())PocketAction("关闭","清空搜索"){onChange("")}},shape=RoundedCornerShape(13.dp),textStyle=MaterialTheme.typography.bodyMedium,colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp))
}

@Composable internal fun ToolEmpty(icon:String,title:String,description:String,onRefresh:(()->Unit)?=null) {
    ToolCard(Modifier.padding(16.dp)){
        Column(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ToolBadge(icon)
            Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            Text(description,fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            onRefresh?.let{OutlinedButton(onClick=it,shape=RoundedCornerShape(10.dp)){Text("刷新状态",fontSize=12.sp)}}
        }
    }
}

@Composable internal fun ToolProjectPicker(model:PocketModel,selected:String?,onSelect:(String)->Unit) {
    var expanded by remember{mutableStateOf(false)}
    val label=model.projects.find{it.optString("projectId")==selected}?.label()?:"选择项目"
    Box(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
        Surface(onClick={expanded=true},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                PocketIcon("项目",Modifier.size(20.dp));Column(Modifier.weight(1f)){Text("当前项目",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(label,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)};PocketIcon("下拉",Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
            model.projects.forEach{project->DropdownMenuItem(text={Text(project.label(),maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={onSelect(project.optString("projectId"));expanded=false})}
        }
    }
}

internal fun skillDisplayName(skill:JSONObject):String = skill.optJSONObject("interface")?.optString("displayName")?.takeIf{it.isNotBlank()&&it!="null"}?:skill.optString("name")
internal fun skillSource(skill:JSONObject):String=when{
    skill.optString("pluginId").let{it.isNotBlank()&&it!="null"}||skill.optString("path").contains("/plugins/")->"插件技能"
    skill.optString("scope").lowercase()=="system"||skill.optString("path").contains("/.system/")->"内置技能"
    skill.optString("scope").lowercase()=="project"||skill.optString("scope").lowercase()=="repo"->"项目技能"
    else->"个人技能"
}
internal fun canMcpLogin(server:JSONObject)=server.optString("authStatus") in listOf("notLoggedIn","oAuth")&&server.optString("connectionStatus")!="disabled"
internal fun mcpAuthLabel(server:JSONObject):String=when(server.optString("authStatus")){"notLoggedIn"->"待授权";"oAuth"->"已授权";"bearerToken"->"电脑已配置";"unsupported"->"电脑管理";else->"状态待确认"}
internal fun mcpDisplayName(server:JSONObject):String=server.optJSONObject("serverInfo")?.optString("title")?.takeIf{it.isNotBlank()&&it!="null"}?:when(server.optString("name")){"aoci"->"应用连接";"codex_app"->"Codex 桌面";"cua_repl"->"浏览器与桌面";"node_repl"->"脚本运行时";else->server.optString("name")}
internal fun pluginCanInstall(plugin:JSONObject)=!plugin.optBoolean("installed")&&!plugin.optBoolean("mustShowInstallationInterstitial")&&plugin.optString("installPolicy")!="NOT_AVAILABLE"&&plugin.optString("availability").let{it.isBlank()||it=="null"||it=="AVAILABLE"}
internal fun pluginCanManage(plugin:JSONObject)=plugin.optBoolean("installed")&&plugin.optString("installPolicy")!="INSTALLED_BY_DEFAULT"

@Composable internal fun ToolHome(model:PocketModel,onSelect:(String)->Unit) {
    LazyVerticalGrid(GridCells.Fixed(2),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        item(span={GridItemSpan(maxLineSpan)}){
            ToolCard{
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    ToolBadge("电脑")
                    Column(Modifier.weight(1f)){Text(model.profile?.computerName()?.takeIf{it.isNotBlank()}?:"当前电脑",fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("项目、工具与任务",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    ToolPill(if(model.connected)"已连接"else"未连接",positive=model.connected)
                }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}){Text("项目与工具",Modifier.padding(top=8.dp,bottom=2.dp),fontSize=12.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(toolSections,key={it.first}){(id,name)->
            Surface(onClick={onSelect(id)},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                Column(Modifier.padding(14.dp).heightIn(min=104.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){ToolBadge(when(id){"files"->"项目";"git"->"差异";"skills"->"推理";"plugins"->"工具";"schedules"->"时钟";"usage"->"用量";else->"权限"});Spacer(Modifier.weight(1f));CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant){PocketIcon("展开",Modifier.size(16.dp))}}
                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text(name,fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text(when(id){"files"->"浏览、预览与引用";"git"->"分支与代码修改";"skills"->"查找与管理技能";"plugins"->"扩展与启用状态";"schedules"->"让电脑定时执行";"usage"->"Token 与上下文";else->"服务与授权状态"},fontSize=12.sp,lineHeight=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)}
                }
            }
        }
    }
}

@Composable internal fun SkillDetail(skill:JSONObject,onClose:()->Unit,onEdit:()->Unit) {
    var content by remember{mutableStateOf(false)};var location by remember{mutableStateOf(false)}
    BackHandler(onBack=onClose)
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        ToolSystemBars()
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.statusBarsPadding().navigationBarsPadding()){
            Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","返回技能列表",onClick=onClose);Text("技能详情",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold);PocketAction("关闭",onClick=onClose)}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                ToolCard{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){ToolBadge("推理");Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(skill.optString("name"),fontSize=19.sp,fontWeight=FontWeight.SemiBold);ToolPill(skill.optString("source"))}}}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!content,{content=false},label={Text("简介")});FilterChip(content,{content=true},label={Text("技能内容")})}
                ToolCard{androidx.compose.foundation.text.selection.SelectionContainer{Text(if(content)skill.optString("content")else skill.optString("description"),fontSize=if(content)12.sp else 14.sp,lineHeight=if(content)21.sp else 24.sp,fontFamily=if(content)FontFamily.Monospace else FontFamily.SansSerif)}}
                TextButton(onClick={location=!location}){Text(if(location)"收起来源位置"else"查看来源位置",fontSize=12.sp)}
                if(location)Text(skill.optString("path"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(skill.optBoolean("editable"))Button(onClick=onEdit,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(16.dp)){Text("编辑技能")}
        }}
    }
}
