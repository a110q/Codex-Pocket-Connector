package dev.codexpocket.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun TaskNotificationSettings() {
    val context=LocalContext.current
    var enabled by remember{mutableStateOf(TaskNotifications.enabled(context))}
    var problem by remember{mutableStateOf<String?>(null)}
    fun enable() {try{TaskNotifications.setEnabled(context,true);enabled=true;problem=null}catch(e:Exception){TaskNotifications.setEnabled(context,false);enabled=false;problem="无法启动后台提醒，请重新打开 App 后开启"}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)enable()else{problem="需要允许通知，才能接收任务结果与审批提醒";enabled=false}}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(14.dp),border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("后台任务提醒",fontSize=15.sp);Text("任务完成、出错和等待审批时通知你",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(enabled,onCheckedChange={checked->if(!checked){TaskNotifications.setEnabled(context,false);enabled=false}else if(Build.VERSION.SDK_INT>=33&&!TaskNotifications.allowed(context))permission.launch(Manifest.permission.POST_NOTIFICATIONS)else enable()})}
            Text("开启后通知栏会保留连接状态，可随时关闭。提醒覆盖当前连接方式下已配对的电脑；电脑需保持运行。",fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(enabled)Text("若锁屏后收不到提醒，请在系统设置中允许 Codex Pocket 后台运行。",fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
        }
    }
}
