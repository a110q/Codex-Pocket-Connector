package dev.codexpocket.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
    ModalBottomSheet(onDismissRequest=onDismiss,dragHandle={PocketSheetHandle()},containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(topStart=18.dp,topEnd=18.dp)){
        LaunchedEffect(initialSection){withFrameNanos{};when(initialSection){"effort"->effortFocus.bringIntoView();"permission"->permissionFocus.bringIntoView()}}
        Column(Modifier.fillMaxWidth().heightIn(max=790.dp)){
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp).padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                Text("运行配置",Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                PocketAction("关闭","关闭对话设置",onClick=onDismiss)
            }
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("模型",fontSize=12.sp,color=PocketPalette.muted)
                if(model.models.isEmpty())Text("电脑暂未提供可切换的模型，保留当前设置。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                // The number and identity of models come from the connected computer.
                model.models.chunked(2).forEach{row->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        row.forEach{option->
                            val id=option.getString("id");val selected=modelId==id
                            Surface(onClick={modelId=id;val next=supported(id);effort=effort?.takeIf{it in next}?:next.find{it=="high"}?:next.firstOrNull()},
                                color=if(selected)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f)else PocketPalette.subtle.copy(alpha=.5f),
                                contentColor=if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.weight(1f).heightIn(min=48.dp)){
                                Row(Modifier.padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                                    Text(id,Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    if(selected){Spacer(Modifier.width(6.dp));PocketIcon("检查",Modifier.size(14.dp))}
                                }
                            }
                        }
                        if(row.size==1)Spacer(Modifier.weight(1f))
                    }
                }
                Column(Modifier.bringIntoViewRequester(effortFocus),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("推理强度",fontSize=12.sp,color=PocketPalette.muted)
                    if(efforts.isEmpty())Text("当前模型未提供可切换的推理强度。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    else efforts.chunked(3).forEach{row->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            row.forEach{value->ConfigOption(effortLabel(value),effort==value,Modifier.weight(1f)){effort=value}}
                            repeat(3-row.size){Spacer(Modifier.weight(1f))}
                        }
                    }
                }
                Column(Modifier.bringIntoViewRequester(permissionFocus),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("远程电脑访问权限",fontSize=12.sp,color=PocketPalette.muted)
                    val modes=listOf("read-only","auto","full-access")
                    val largeText=LocalDensity.current.fontScale>1.25f
                    BoxWithConstraints{
                        if(maxWidth<300.dp||largeText)Column(verticalArrangement=Arrangement.spacedBy(8.dp)){modes.forEach{id->PermissionOption(id,permission==id,Modifier.fillMaxWidth()){permission=id}}}
                        else Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(8.dp)){modes.forEach{id->PermissionOption(id,permission==id,Modifier.weight(1f).fillMaxHeight()){permission=id}}}
                    }
                    permission?.takeIf{it!=original.optString("permissionMode")}?.let{selected->Text(when(selected){"read-only"->"只查看文件；写入时需要审批。";"auto"->"可读写当前工作区文件。";else->"可访问电脑文件并执行命令。"},fontSize=12.sp,color=PocketPalette.muted)}
                    if(permission==null)Text("当前使用自定义权限，未选择新模式时保留。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=16.dp,bottom=16.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Button(onClick={if(changed)model.updateSettings(modelId?.takeIf{it!=original.optString("model")},effort?.takeIf{it!=original.optString("reasoningEffort")},permission?.takeIf{it!=original.optString("permissionMode")});onDismiss()},enabled=!model.busy&&model.connected&&model.pendingOperation==null,modifier=Modifier.fillMaxWidth().height(48.dp),shape=RoundedCornerShape(12.dp)){Text(if(changed)"保存设置"else"完成设置",fontSize=13.sp,fontWeight=FontWeight.SemiBold)}
            }
        }
    }
}

@Composable private fun ConfigOption(label:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit){
    Surface(onClick=onClick,color=if(selected)MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f)else PocketPalette.subtle.copy(alpha=.5f),
        contentColor=if(selected)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=modifier.heightIn(min=48.dp)){
        Box(Modifier.padding(horizontal=8.dp,vertical=12.dp),contentAlignment=Alignment.Center){Text(label,fontSize=13.sp,fontWeight=FontWeight.Medium)}
    }
}

@Composable private fun PermissionOption(id:String,selected:Boolean,modifier:Modifier,onClick:()->Unit){
    ConfigOption(if(id=="auto")"工作区"else permissionLabel(id),selected,modifier,onClick)
}
