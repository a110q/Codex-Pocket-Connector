package dev.codexpocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable fun ThreadSettingsSheet(model:PocketModel,initialSection:String="model",onDismiss:()->Unit){
    val original=model.snapshot?.optJSONObject("thread")?.optJSONObject("settings")?:JSONObject()
    var modelId by remember{mutableStateOf(original.optString("model").takeIf{it.isNotBlank()&&it!="null"})}
    var effort by remember{mutableStateOf(original.optString("reasoningEffort").takeIf{it.isNotBlank()&&it!="null"})}
    var permission by remember{mutableStateOf(original.optString("permissionMode").takeIf{it in listOf("read-only","auto","full-access")})}
    fun supported(id:String?)=model.models.find{it.optString("id")==id}?.optJSONArray("reasoningEfforts")?.let{a->(0 until a.length()).map{a.getString(it)}}.orEmpty()
    val efforts=supported(modelId)
    val changed=(modelId!=null&&modelId!=original.optString("model"))||(effort!=null&&effort!=original.optString("reasoningEffort"))||(permission!=null&&permission!=original.optString("permissionMode"))
    val effortFocus=remember{BringIntoViewRequester()};val permissionFocus=remember{BringIntoViewRequester()}
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
        LaunchedEffect(initialSection){withFrameNanos{};when(initialSection){"effort"->effortFocus.bringIntoView();"permission"->permissionFocus.bringIntoView()}}
        Column(Modifier.fillMaxWidth().heightIn(max=790.dp)){
            Row(Modifier.fillMaxWidth().padding(start=22.dp,end=10.dp).padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text("对话设置",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                PocketAction("关闭","关闭对话设置",onClick=onDismiss)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("模型",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                if(model.models.isEmpty())Text("电脑暂未提供可切换的模型，保留当前设置。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    model.models.forEachIndexed{index,option->
                        val id=option.getString("id");val selected=modelId==id
                        Surface(onClick={modelId=id;val next=supported(id);effort=effort?.takeIf{it in next}?:next.find{it=="high"}?:next.firstOrNull()},color=if(selected)MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(12.dp)){
                            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.size(34.dp)){Box(contentAlignment=Alignment.Center){PocketIcon("推理",Modifier.size(18.dp))}}
                                Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){
                                    Text(id,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    Text(if(id==original.optString("model"))"当前对话使用的模型" else "电脑提供的可用模型",fontSize=10.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(8.dp));RadioButton(selected=selected,onClick=null,modifier=Modifier.size(22.dp))
                            }
                        }
                        if(index<model.models.lastIndex)HorizontalDivider(Modifier.padding(start=58.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.bringIntoViewRequester(effortFocus),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("推理强度",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                    if(efforts.isEmpty())Text("当前模型未提供可切换的推理强度。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else Row((if(efforts.size<=5)Modifier.fillMaxWidth()else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp)).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)){
                        efforts.forEach{value->Surface(onClick={effort=value},color=if(effort==value)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,contentColor=if(effort==value)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,shape=RoundedCornerShape(10.dp),modifier=(if(efforts.size<=5)Modifier.weight(1f)else Modifier.widthIn(min=54.dp)).heightIn(min=40.dp)){
                            Box(Modifier.padding(horizontal=10.dp,vertical=9.dp),contentAlignment=Alignment.Center){Text(effortLabel(value),fontSize=12.sp,maxLines=1)}
                        }}
                    }
                    Text("更高的推理强度可能需要更长的处理时间。",fontSize=10.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.bringIntoViewRequester(permissionFocus),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("访问权限",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                    val modes=listOf(Triple("read-only","查看文件，写入时审批","查看"),Triple("auto","可读写当前工作区文件","项目"),Triple("full-access","访问电脑文件与命令","终端"))
                    val largeText=LocalDensity.current.fontScale>1.25f
                    BoxWithConstraints{
                        if(maxWidth<300.dp||largeText)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){modes.forEach{(id,description,icon)->PermissionOption(id,description,icon,permission==id,Modifier.fillMaxWidth()){permission=id}}}
                        else Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){modes.forEach{(id,description,icon)->PermissionOption(id,description,icon,permission==id,Modifier.weight(1f).fillMaxHeight()){permission=id}}}
                    }
                    if(permission==null)Text("当前使用自定义权限，未选择新模式时保留。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=10.dp,bottom=16.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Button(onClick={model.updateSettings(modelId?.takeIf{it!=original.optString("model")},effort?.takeIf{it!=original.optString("reasoningEffort")},permission?.takeIf{it!=original.optString("permissionMode")});onDismiss()},enabled=changed&&!model.busy&&model.connected&&model.pendingOperation==null,modifier=Modifier.fillMaxWidth().height(46.dp),shape=RoundedCornerShape(12.dp)){Text("保存设置",fontSize=13.sp,fontWeight=FontWeight.SemiBold)}
                Text("应用于后续消息",Modifier.padding(top=8.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun PermissionOption(id:String,description:String,icon:String,selected:Boolean,modifier:Modifier,onClick:()->Unit){
    Surface(onClick=onClick,color=if(selected)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f) else MaterialTheme.colorScheme.surface,contentColor=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=modifier){
        Column(Modifier.padding(11.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            PocketIcon(icon,Modifier.size(18.dp))
            Text(if(id=="auto")"工作区" else permissionLabel(id),fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(description,fontSize=10.sp,lineHeight=16.sp,color=if(selected)MaterialTheme.colorScheme.primary.copy(alpha=.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
