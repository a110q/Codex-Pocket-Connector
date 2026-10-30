package dev.codexpocket.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

val LocalAppUpdateModel=staticCompositionLocalOf<AppUpdateModel?>{null}
@Composable fun UpdateEntry(){
    val provided=LocalAppUpdateModel.current;val context=LocalContext.current.applicationContext
    val model=remember(provided){provided?:AppUpdateModel(context)}
    DisposableEffect(model){onDispose{if(provided==null)model.close()}}
    var open by remember{mutableStateOf(false)}
    UtilityRow("检测更新","当前版本 ${model.currentVersion}","刷新"){open=true}
    if(open)Dialog(onDismissRequest={open=false},properties=DialogProperties(usePlatformDefaultWidth=false)){UpdateScreen(model){open=false}}
}
@Composable fun UpdateScreen(model:AppUpdateModel,onBack:()->Unit){
    val colors=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(),color=colors.background){Column(Modifier.statusBarsPadding().navigationBarsPadding()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){PocketAction("返回","返回设置",onClick=onBack);Text("关于与更新",fontSize=18.sp,fontWeight=FontWeight.SemiBold)}
        HorizontalDivider(color=colors.outlineVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            CodexMark(Modifier.size(44.dp));Text("让 Pocket，保持最新。",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
            Text("检测新版本，查看更新内容。",fontSize=13.sp,color=colors.onSurfaceVariant)
            Surface(color=colors.surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,colors.outlineVariant)){
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Row(Modifier.fillMaxWidth()){
                        Column(Modifier.weight(1f)){Text("当前版本",fontSize=12.sp,color=colors.onSurfaceVariant);Text(model.currentVersion,fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("update-current"))}
                        Column(Modifier.weight(1f)){Text("最新版本",fontSize=12.sp,color=colors.onSurfaceVariant);Text(model.release?.version?:"尚未检测",fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("update-latest"))}
                    }
                    HorizontalDivider(color=colors.outlineVariant)
                    if(model.status=="checking")Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text("正在检测更新…",fontSize=14.sp)}
                    else Text(when(model.status){"current"->if(model.release?.let{UpdateVersion.compare(model.currentVersion,it.version)>0}==true)"当前版本高于已发布版本。"else"当前已是最新版本。";"available"->"发现新版本 ${model.release?.version}";"error"->"检测未完成，可重新尝试。";else->"点击下方按钮检测更新。"},fontSize=14.sp)
                    model.error?.let{Text(it,fontSize=13.sp,color=colors.error,modifier=Modifier.testTag("update-error"))}
                    model.release?.let{release->
                        Text("更新内容",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        Text(release.notes.ifBlank{"本次包含稳定性改进。"},fontSize=14.sp,lineHeight=23.sp)
                        if(model.download=="downloading"){
                            val percent=(model.received*100/release.asset.size).coerceIn(0,100).toInt()
                            LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth())
                            Text("正在下载 $percent%",fontSize=12.sp)
                        }
                    }
                    Button(onClick=model::check,enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){Text(if(model.status=="checking")"正在检测…"else"检测更新")}
                    if(model.status=="available"&&model.download!="ready")OutlinedButton(onClick=model::download,enabled=!model.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){Text(if(model.download=="downloading")"正在下载…"else if(model.download=="error")"重新下载"else"下载安卓更新")}
                    if(model.download=="ready"){
                        Text("安装包已下载并通过校验。",fontSize=13.sp,color=colors.onSurfaceVariant)
                        Button(onClick=model::install,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(12.dp)){Text("安装更新")}
                    }
                    model.installMessage?.let{Text(it,fontSize=12.sp,lineHeight=21.sp,color=colors.onSurfaceVariant)}
                }
            }
            Text("安装新版本会保留原账号和电脑配对信息。",fontSize=12.sp,color=colors.onSurfaceVariant)
        }
    }}
}
