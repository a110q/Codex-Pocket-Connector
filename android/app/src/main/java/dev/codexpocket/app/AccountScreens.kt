package dev.codexpocket.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun accountStateLabel(value:String)=when(value){"active"->"正常使用";"pending"->"等待审批";"rejected"->"申请未通过";"disabled"->"账号已停用";"expired"->"使用已到期";else->"待验证"}
fun accountDate(value:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(value))

@Composable fun RelayServiceEntry(onClick:()->Unit,enabled:Boolean=true){
    Surface(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.22f))){
        Row(Modifier.fillMaxWidth().heightIn(min=96.dp).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp),modifier=Modifier.size(40.dp)){
                Box(contentAlignment=Alignment.Center){CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon("网页",Modifier.size(24.dp))}}
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text("自建中继 / 切换服务",fontSize=16.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onPrimaryContainer)
                Text("在电脑配置中继后，点这里扫码连接",fontSize=13.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onPrimaryContainer)
            }
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary){PocketIcon("展开",Modifier.size(22.dp))}
        }
    }
}

@Composable fun AccountGate(account:AccountModel,onScan:(()->Unit)?=null,onBuiltin:(()->Unit)?=null,onLan:(()->Unit)?=null){
    var register by rememberSaveable{mutableStateOf(false)}
    var username by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var displayName by rememberSaveable{mutableStateOf("")};var reason by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(26.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(Modifier.padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){CodexMark(Modifier.size(44.dp));Text("Codex Pocket",fontSize=26.sp,fontWeight=FontWeight.Bold)}
        Text("连接你的电脑，随时继续工作。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        val isBuiltin=AccountApi.same(account.api.defaults,AccountApi.bundled(LocalContext.current))
        onScan?.let{RelayServiceEntry(it,enabled=!account.busy)}
        Text(if(isBuiltin)"默认公网服务"else"自建中继：${account.api.defaults.getString("relay")}",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!isBuiltin&&onBuiltin!=null)TextButton(onClick=onBuiltin,enabled=!account.busy){Text("返回默认公网服务")}
        account.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        account.notice?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
        if(account.loggedIn){
            val user=account.user
            Text(user?.optString("displayName").orEmpty(),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
            Text(if(!account.verified)"正在验证账号，请保持网络连接"else accountStateLabel(user?.optString("accessState").orEmpty()),fontSize=18.sp)
            user?.optString("rejectionReason")?.takeIf{it.isNotBlank()}?.let{Text("管理员说明：$it")}
            Text("账号批准后，在电脑选择公网连接，再用手机扫码添加电脑。",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick=account::refresh,enabled=!account.busy,modifier=Modifier.fillMaxWidth().height(50.dp)){Text(if(account.busy)"正在检查…"else"检查审批状态")}
            if(user?.optString("accessState") in listOf("rejected","expired")){
                OutlinedTextField(reason,{reason=it},label={Text("重新申请说明")},modifier=Modifier.fillMaxWidth(),minLines=3)
                OutlinedButton(onClick={account.apply(reason)},enabled=!account.busy&&reason.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("重新提交申请")}
            }
            TextButton(onClick=account::logout,enabled=!account.busy){Text("退出账号")}
        }else{
            Text(if(register)"申请使用"else"欢迎回来",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            OutlinedTextField(username,{username=it.take(32)},label={Text("登录账号")},supportingText={Text("3–32 位英文字母、数字或下划线")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it.take(128)},label={Text("密码")},supportingText={Text("至少 10 个字符")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(register){
                OutlinedTextField(displayName,{displayName=it.take(60)},label={Text("你的称呼")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(reason,{reason=it.take(500)},label={Text("申请说明")},placeholder={Text("告诉管理员你希望如何使用")},minLines=3,modifier=Modifier.fillMaxWidth())
            }
            Button(onClick={if(register)account.register(username,password,displayName,reason)else account.login(username,password)},enabled=!account.busy&&username.length>=3&&password.length>=10&&(!register||(displayName.isNotBlank()&&reason.isNotBlank())),modifier=Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(12.dp)){Text(if(account.busy)"正在处理…"else if(register)"提交申请"else"登录")}
            TextButton(onClick={register=!register;account.error=null;account.notice=null},modifier=Modifier.fillMaxWidth()){Text(if(register)"已有账号，前往登录"else"没有账号，申请使用")}
            Text("首次申请由管理员批准。手机和电脑共用同一个账号。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(onLan!=null){HorizontalDivider();OutlinedButton(onClick=onLan,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("局域网直连 · 无需账号审批")};Text("手机与电脑连接同一 Wi-Fi，扫描电脑的局域网配对码，并在电脑上确认。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Spacer(Modifier.height(20.dp));Text("Codex Pocket  ${BuildConfig.VERSION_NAME}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun AccountSettings(account:AccountModel,onAdmin:()->Unit,onUsers:()->Unit=onAdmin){
    var logout by remember{mutableStateOf(false)};var password by remember{mutableStateOf(false)}
    val user=account.user;val context=LocalContext.current
    Surface(shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text("服务：${account.api.defaults.getString("relay")}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("账号：${user?.optString("username").orEmpty()}",fontWeight=FontWeight.SemiBold)
            Text(if(user?.isNull("expiresAt")!=false)"超级管理员"else"有效期至 ${accountDate(user.optLong("expiresAt"))}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(account.isAdmin){
        UtilityRow("用户中心","${account.users.size} 个账号 · 查看用户列表与设备额度","会话",onUsers)
        UtilityRow("管理中心","${account.pending} 个待审批申请 · ${account.alerts} 条未处理提醒","任务",onAdmin)
        UtilityRow("管理提醒通知","前台检查，后台由系统定期调度","任务"){
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
        }
    }
    UtilityRow("修改登录密码","其他登录会话将失效","设置"){password=true}
    OutlinedButton(onClick={logout=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("退出账号")}
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("退出当前账号？")},text={Text("手机连接会暂停。再次登录后可以继续使用。")},confirmButton={TextButton(onClick={account.logout();logout=false}){Text("退出")}},dismissButton={TextButton(onClick={logout=false}){Text("取消")}})
    if(password)AccountPasswordDialog("修改登录密码",true,{password=false}){old,next->account.changePassword(old,next);password=false}
}

@Composable fun AccountPasswordDialog(title:String,oldRequired:Boolean,onDismiss:()->Unit,submit:(String,String)->Unit){
    var old by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(oldRequired)OutlinedTextField(old,{old=it},label={Text("原密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        OutlinedTextField(next,{next=it.take(128)},label={Text("新密码，至少 10 个字符")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        OutlinedTextField(confirm,{confirm=it.take(128)},label={Text("再次输入新密码")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
    }},confirmButton={TextButton(onClick={submit(old,next)},enabled=next.length>=10&&next==confirm&&(!oldRequired||old.isNotBlank())){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AdminScreen(account:AccountModel,initialFilter:String="pending"){
    if(!account.isAdmin){Text("需要管理员权限",Modifier.padding(24.dp));return}
    var filter by rememberSaveable(initialFilter){mutableStateOf(initialFilter)};var target by remember{mutableStateOf<JSONObject?>(null)}
    var action by remember{mutableStateOf("")};var create by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("账号与授权",fontSize=22.sp,fontWeight=FontWeight.Bold);TextButton(onClick=account::refresh,enabled=!account.busy){Text("刷新")}}
        Text("${account.pending} 个待审批 · ${account.alerts} 条安全提醒",color=MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("pending" to "待审批","all" to "全部账号","security" to "安全提醒","events" to "操作记录").forEach{(id,label)->FilterChip(filter==id,{filter=id},label={Text(label)})}
        }
        account.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(account.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(filter=="pending"||filter=="all"){
            Button(onClick={create=true},enabled=!account.busy,modifier=Modifier.fillMaxWidth()){Text("创建并分配用户账号")}
            val list=account.users.filter{filter=="all"||it.optString("accessState")=="pending"}
            if(list.isEmpty())Text("暂无待处理账号",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEach{user->
                Surface(shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(user.optString("displayName"),fontWeight=FontWeight.SemiBold);Text("${user.optString("username")} · ${accountStateLabel(user.optString("accessState"))}",fontSize=13.sp)
                        Text(user.optString("reason"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!user.isNull("expiresAt"))Text("有效期至 ${accountDate(user.optLong("expiresAt"))}",fontSize=12.sp)
                        val computers=user.optJSONArray("computers").objects().ifEmpty{listOfNotNull(user.optJSONObject("computer"))}
                        Text("手机：${user.optString("phone").takeIf{it!="null"}?:"未绑定"} · 电脑：${computers.size} / ${user.optInt("maxComputers",5)} 台",fontSize=12.sp)
                        if(computers.isNotEmpty())Text(computers.joinToString("、"){it.optString("name")},fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={target=user;action="quota"},enabled=!account.busy){Text("调整电脑额度")}
                        if(user.optString("role")!="admin")FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            if(user.optString("accessState")=="pending"){
                                TextButton(onClick={target=user;action="approve"},enabled=!account.busy){Text("批准")}
                                TextButton(onClick={target=user;action="reject"},enabled=!account.busy){Text("拒绝")}
                            }else{
                                TextButton(onClick={target=user;action=if(user.optString("accessState")=="active")"disable"else"enable"},enabled=!account.busy){Text(if(user.optString("accessState")=="active")"停用账号"else"启用 / 续期")}
                            }
                            TextButton(onClick={target=user;action="phone"},enabled=!account.busy){Text("移除手机")}
                            TextButton(onClick={target=user;action="computer"},enabled=!account.busy){Text("移除电脑")}
                            TextButton(onClick={target=user;action="password"},enabled=!account.busy){Text("重置密码")}
                        }
                    }
                }
            }
        }else{
            if(filter=="security")Text("这里是客户端状态报告，可能被伪造或遗漏。由你决定是否停用账号。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val list=account.events.filter{filter=="events"||it.optString("severity")=="warning"}
            if(list.isEmpty())Text("暂无记录",Modifier.padding(vertical=24.dp))
            list.forEach{event->
                Surface(shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(event.optString("summary"),fontWeight=FontWeight.SemiBold)
                        Text("${event.optString("username")} · ${accountDate(event.optLong("createdAt"))}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(event.optString("severity")=="warning"){
                            Text("版本：${event.optJSONObject("details")?.optString("version").orEmpty()}",fontSize=12.sp)
                            Row{
                                if(event.isNull("acknowledgedAt"))TextButton(onClick={account.acknowledge(event.getString("id"))},enabled=!account.busy){Text("标记已处理")}
                                val user=account.users.find{it.optString("id")==event.optString("userId")}
                                if(user!=null&&user.optString("role")!="admin"&&user.optString("accessState")=="active")TextButton(onClick={target=user;action="disable"},enabled=!account.busy){Text("停用此账号",color=MaterialTheme.colorScheme.error)}
                            }
                        }
                    }
                }
            }
        }
        Text("前台每 15 秒检查。后台提醒受系统调度和通知权限影响。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    target?.let{user->
        if(action=="quota")ComputerQuotaDialog(user,{target=null}){account.computerLimit(user.getString("id"),it);target=null}
        else if(action=="password")AccountPasswordDialog("重置 ${user.optString("username")} 的密码",false,{target=null}){_,next->account.resetPassword(user.getString("id"),next);target=null}
        else AdminDecisionDialog(user,action,{target=null}){days,note->if(action in listOf("phone","computer"))account.removeDevice(user.getString("id"),action)else account.decision(user.getString("id"),action,days,note);target=null}
    }
    if(create)CreateAccountDialog({create=false}){name,pass,display,days->account.create(name,pass,display,days);create=false}
}

@Composable fun ComputerQuotaDialog(user:JSONObject,onDismiss:()->Unit,submit:(Int)->Unit){
    var value by remember{mutableStateOf(user.optInt("maxComputers",5).toString())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("电脑额度")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("账号：${user.optString("username")}")
        OutlinedTextField(value,{value=it.filter(Char::isDigit).take(2)},label={Text("最多绑定电脑（1–50 台）")},singleLine=true)
        Text("调低额度前需要先移除多余电脑。",fontSize=12.sp)
    }},confirmButton={TextButton(onClick={submit(value.toInt())},enabled=(value.toIntOrNull()?:0) in 1..50){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun AdminDecisionDialog(user:JSONObject,action:String,onDismiss:()->Unit,submit:(Int,String)->Unit){
    var days by remember{mutableStateOf("30")};var note by remember{mutableStateOf("")}
    val approving=action in listOf("approve","enable")
    val title=when(action){"approve"->"批准使用";"enable"->"启用或续期";"reject"->"拒绝申请";"phone"->"移除绑定手机";"computer"->"移除绑定电脑";else->"停用账号"}
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("账号：${user.optString("username")}")
        if(approving)OutlinedTextField(days,{days=it.filter(Char::isDigit).take(4)},label={Text("使用天数（1–3650）")},singleLine=true)
        if(action in listOf("disable","phone","computer"))Text("现有远程连接将断开，后续访问会被阻止。电脑上已开始的任务不会自动停止。",fontSize=13.sp)
        if(action !in listOf("phone","computer"))OutlinedTextField(note,{note=it.take(500)},label={Text("说明（可选）")},minLines=2)
    }},confirmButton={TextButton(onClick={submit(days.toIntOrNull()?:30,note)},enabled=!approving||(days.toIntOrNull()?:0) in 1..3650){Text("确认")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable fun CreateAccountDialog(onDismiss:()->Unit,submit:(String,String,String,Int)->Unit){
    var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var days by remember{mutableStateOf("30")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("创建用户账号")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        OutlinedTextField(username,{username=it.take(32)},label={Text("登录账号")},singleLine=true)
        OutlinedTextField(name,{name=it.take(60)},label={Text("用户称呼")},singleLine=true)
        OutlinedTextField(password,{password=it.take(128)},label={Text("初始密码，至少 10 个字符")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        OutlinedTextField(days,{days=it.filter(Char::isDigit).take(4)},label={Text("有效天数")},singleLine=true)
    }},confirmButton={TextButton(onClick={submit(username,password,name,days.toInt())},enabled=username.length>=3&&name.isNotBlank()&&password.length>=10&&(days.toIntOrNull()?:0) in 1..3650){Text("创建并批准")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
