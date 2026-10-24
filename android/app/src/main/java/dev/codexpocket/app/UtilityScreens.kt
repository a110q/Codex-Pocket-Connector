package dev.codexpocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun Welcome(scan:()->Unit,manual:()->Unit){
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal=26.dp,vertical=32.dp)){
        Spacer(Modifier.height(40.dp));CodexMark(Modifier.size(52.dp));Spacer(Modifier.height(28.dp))
        Text("让工作，\n随时继续。",fontSize=34.sp,lineHeight=46.sp,fontWeight=FontWeight.Bold)
        Text("手机上的 Codex，连接电脑上的工作空间。",Modifier.padding(top=16.dp),fontSize=14.sp,lineHeight=24.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        listOf("01" to "在电脑打开配对页面","02" to "扫描二维码，连接你的电脑","03" to "电脑确认后，继续项目与对话").forEach{(n,label)->
            Row(Modifier.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically){Text(n,fontSize=12.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(18.dp));Text(label,fontSize=14.sp)}
        }
        Spacer(Modifier.height(32.dp));Button(onClick=scan,modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(12.dp)){Text("扫码连接电脑",fontWeight=FontWeight.SemiBold)}
        TextButton(onClick=manual,modifier=Modifier.fillMaxWidth()){Text("粘贴配对内容",color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text("电脑保持开机、联网和 Codex 运行",Modifier.fillMaxWidth().padding(top=20.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
    }
}

@Composable fun ComputerScreen(model:PocketModel,scan:()->Unit,manual:()->Unit){
    var removing by remember{mutableStateOf<org.json.JSONObject?>(null)}
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
            Column(Modifier.fillMaxWidth().padding(22.dp)){
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(14.dp),modifier=Modifier.size(56.dp)){Box(contentAlignment=Alignment.Center){CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon("电脑",Modifier.size(30.dp))}}}
                Spacer(Modifier.height(20.dp));Text(model.profile?.optString("name")?:"我的电脑",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp));StatusPill(model);Spacer(Modifier.height(20.dp))
                Text(if(model.pairing)"请在电脑端配对页面允许这台手机连接。" else "断网后会自动尝试恢复。电脑需要保持开机、联网，Codex 与连接服务持续运行。",fontSize=13.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp));Button(onClick=model::reconnectNow,enabled=!model.busy,modifier=Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(10.dp)){PocketIcon("刷新",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(model.pairing)"检查配对状态" else "重新连接")}
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("已添加的电脑（${model.computers.size}）",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);TextButton(onClick=scan,enabled=!model.busy&&model.pendingOperation==null){Text("添加电脑")}}
        model.computers.forEach{computer->
            val current=computer.optString("hostId")==model.profile?.optString("hostId")
            Surface(shape=RoundedCornerShape(14.dp),color=if(current)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,if(current)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)){
                Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                    PocketIcon("电脑",Modifier.size(23.dp));Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)){Text(computer.optString("name","我的电脑"),fontWeight=FontWeight.Medium,maxLines=2);Text((if(computer.optString("mode")=="lan")"局域网"else"公网远程")+" · "+(if(current)model.status else"已保存"),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    TextButton(onClick={model.switchComputer(computer.getString("hostId"))},enabled=!current&&!model.busy&&model.pendingOperation==null){Text(if(current)"当前电脑"else"连接")}
                    PocketAction("关闭","移除电脑 ${computer.optString("name")}",!model.busy&&model.pendingOperation==null){removing=computer}
                }
            }
        }
        UtilityRow("扫码添加电脑","已有电脑会保留，添加后可随时切换","图片",scan)
        UtilityRow("粘贴配对内容","使用完整的电脑配对信息","附件",manual)
    }
    removing?.let{computer->AlertDialog(onDismissRequest={removing=null},title={Text("移除这台电脑？")},text={Text("从手机移除 ${computer.optString("name")} 的配对和缓存。电脑上的对话不会删除；公网设备额度请由管理员移除绑定释放。")},confirmButton={TextButton(onClick={model.removeComputer(computer.getString("hostId"));removing=null}){Text("移除")}},dismissButton={TextButton(onClick={removing=null}){Text("取消")}})}
}

@Composable fun UtilityRow(title:String,description:String,icon:String,onClick:()->Unit){
    Surface(onClick=onClick,color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically){PocketIcon(icon,Modifier.size(21.dp));Spacer(Modifier.width(14.dp));Column(Modifier.weight(1f)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium);if(description.isNotBlank())Text(description,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};PocketIcon("展开",Modifier.size(15.dp))}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(model:PocketModel,onComputer:()->Unit={},onTasks:()->Unit={},account:AccountModel?=null,onAdmin:()->Unit={},onUsers:()->Unit=onAdmin,onExitLan:(()->Unit)?=null,onEnterLan:(()->Unit)?=null){
    var forget by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        account?.let{AccountSettings(it,onAdmin,onUsers)}
        if(onExitLan!=null){Text("局域网直连",fontWeight=FontWeight.SemiBold);Text("同一网络中使用，无需账号审批。离开局域网后，请切换公网远程。",fontSize=13.sp);OutlinedButton(onClick=onExitLan,modifier=Modifier.fillMaxWidth()){Text("切换到公网远程登录")}}
        if(onEnterLan!=null)OutlinedButton(onClick=onEnterLan,modifier=Modifier.fillMaxWidth()){Text("切换到局域网直连")}
        UtilityRow("我的电脑","${model.computers.size} 台已添加 · 点击切换","电脑",onComputer)
        UtilityRow("任务动态","查看运行中、等待操作和出错的对话","任务",onTasks)
        Text("外观",Modifier.padding(top=12.dp,start=4.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
            FlowRow(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(id,label)->FilterChip(selected=model.appearance==id,onClick={model.updateAppearance(id)},label={Text(label,fontSize=13.sp)})}
            }
        }
        Text("连接与隐私",Modifier.padding(top=12.dp,start=4.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("配对信息与聊天缓存加密保存在手机上。",fontSize=14.sp)
                Text("回到 App 会重新连接并读取状态。当前没有锁屏推送。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("要撤销手机访问权限，请在电脑配对页面撤销设备。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick={forget=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(11.dp)){Text("移除当前电脑和缓存",color=MaterialTheme.colorScheme.error)}
        Text("Codex Pocket  ${BuildConfig.VERSION_NAME}",Modifier.fillMaxWidth().padding(top=12.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
    }
    if(forget)AlertDialog(onDismissRequest={forget=false},title={Text("移除本机连接？")},text={Text("清除手机保存的配对信息、草稿和缓存，电脑上的对话仍然保留。")},confirmButton={TextButton(onClick={model.forget();forget=false}){Text("移除")}},dismissButton={TextButton(onClick={forget=false}){Text("取消")}})
}

@Composable fun NewTaskDialog(model:PocketModel,preset:String?,onDismiss:()->Unit,create:(String,String?,String?)->Unit){
    var text by rememberSaveable{mutableStateOf("")};var project by rememberSaveable{mutableStateOf(preset)};var selectedModel by rememberSaveable{mutableStateOf<String?>(null)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("关闭","取消新建对话",onClick=onDismiss);Text("新建对话",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text("从一个想法开始",fontSize=24.sp,fontWeight=FontWeight.SemiBold)
                    Text("任务会在 ${model.profile?.optString("name")?:"电脑"} 上执行。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    DropdownPicker("项目",model.projects.map{it.getString("projectId") to it.label()},project,"独立会话"){project=it}
                    DropdownPicker("模型",model.models.map{it.getString("id") to it.getString("id")},selectedModel,"使用电脑默认模型"){selectedModel=it}
                    OutlinedTextField(text,{text=it},placeholder={Text("描述你希望 Codex 完成的工作…",fontSize=14.sp)},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp).semantics{contentDescription="新对话指令"},shape=RoundedCornerShape(14.dp))
                }
                Button(onClick={create(text,project,selectedModel)},enabled=text.isNotBlank()&&model.connected&&!model.busy&&model.pendingOperation==null,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp).height(52.dp),shape=RoundedCornerShape(12.dp)){Text("开始对话",fontWeight=FontWeight.SemiBold);Spacer(Modifier.width(8.dp));PocketIcon("前往",Modifier.size(18.dp))}
            }
        }
    }
}
