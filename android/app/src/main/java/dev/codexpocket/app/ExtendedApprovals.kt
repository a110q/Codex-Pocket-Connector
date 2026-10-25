package dev.codexpocket.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

private fun permissionPathLabel(entry:JSONObject?):String {
    if(entry==null)return "未知目录"
    if(entry.optString("type")=="path")return entry.optString("path")
    if(entry.optString("type")=="glob_pattern")return entry.optString("pattern")
    val value=entry.optJSONObject("value")?:return entry.toString()
    return when(value.optString("kind")){"root"->"整个文件系统";"minimal"->"基础系统目录";"project_roots"->"项目目录";"tmpdir"->"系统临时目录";"slash_tmp"->"/tmp";"unknown"->value.optString("path","未知目录");else->entry.toString()}+value.optString("subpath").takeIf{it.isNotBlank()&&it!="null"}.orEmpty().let{if(it.isBlank())""else" / $it"}
}
internal fun normalizedForm(schema:JSONObject):JSONObject {
    val result=JSONObject(schema.toString())
    result.optJSONObject("properties")?.let{fields->fields.keys().forEach{id->fields.optJSONObject(id)?.let{field->
        field.optJSONArray("oneOf")?.objects()?.takeIf{it.isNotEmpty()&&it.all{v->v.has("const")}}?.let{options->field.put("enum",JSONArray(options.map{it.get("const")})).put("enumNames",JSONArray(options.map{it.optString("title",it.get("const").toString())}));field.remove("oneOf");if(!field.has("type"))field.put("type","string")}
        field.optJSONObject("items")?.let{items->items.optJSONArray("anyOf")?.objects()?.takeIf{it.isNotEmpty()&&it.all{v->v.has("const")}}?.let{options->items.put("enum",JSONArray(options.map{it.get("const")})).put("enumNames",JSONArray(options.map{it.optString("title",it.get("const").toString())}));items.remove("anyOf");if(!items.has("type"))items.put("type","string")}}
    }}}
    return result
}
private fun formFields(schema:JSONObject)=schema.optJSONObject("properties")?.let{obj->obj.keys().asSequence().mapNotNull{key->obj.optJSONObject(key)?.let{key to it}}.toList()}.orEmpty()
private fun supportsSimpleForm(schema:JSONObject):Boolean=schema.optString("type")=="object"&&schema.optJSONObject("properties")!=null&&formFields(schema).size==schema.getJSONObject("properties").length()&&listOf("pattern","oneOf","anyOf","allOf","\$ref","not","if","then","else","dependentRequired","dependentSchemas","patternProperties").none{schema.has(it)}&&formFields(schema).all{(_,field)->
    listOf("pattern","oneOf","anyOf","allOf","\$ref","not","if","then","else","dependentRequired","dependentSchemas","patternProperties").none{field.has(it)}&&(!field.has("format")||field.optString("format") in listOf("email","uri","date","date-time","password"))&&(field.optString("type") in listOf("string","boolean","number","integer")||(field.optString("type")=="array"&&field.optJSONObject("items")?.optString("type")=="string"&&field.optJSONObject("items")?.optJSONArray("enum")!=null))
}

internal fun supportsForm(schema:JSONObject)=supportsSimpleForm(normalizedForm(schema))

@Composable fun ExtendedApprovalCard(request:JSONObject,model:PocketModel) {
    val permission=request.optString("method")=="item/permissions/requestApproval"
    val params=request.optJSONObject("params")?:JSONObject();val mode=params.optString("mode")
    val schema=normalizedForm(params.optJSONObject("requestedSchema")?:JSONObject())
    val simple=supportsForm(schema)
    val generic=mode in listOf("form","openai/form","openaiForm")&&!schema.toString().contains("x-openai-input")
    val supported=permission||mode=="url"||generic
    var urlOpened by remember(request.optString("id")){mutableStateOf(false)}
    var details by remember(request.optString("id")){mutableStateOf(false)}
    val enabled=model.connected&&!model.busy&&model.pendingOperation==null
    Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if(permission)"需要目录或网络权限"else"${params.optString("serverName","工具")} 需要你的操作",fontWeight=FontWeight.SemiBold,fontSize=14.sp)
            val reason=params.optString(if(permission)"reason"else"message");if(reason.isNotBlank()&&reason!="null")Text(reason,fontSize=12.sp,maxLines=2)
            if(!supported)Text("此类官方身份验证或专用控件需要在电脑完成。",fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick={details=true},enabled=enabled){Text(if(supported)"查看并处理"else"查看详情")};TextButton(onClick={model.respondApproval(request,"decline");details=false},enabled=enabled){Text("拒绝")}}
        }
    }
    if(details) {
        val requested=params.optJSONObject("permissions")?:JSONObject();val fs=requested.optJSONObject("fileSystem")?:JSONObject()
        val choices=buildList {
            if(requested.optJSONObject("network")?.optBoolean("enabled")==true)add(Triple("network","访问网络",null))
            for(kind in listOf("read","write","entries"))fs.optJSONArray(kind)?.let{array->for(i in 0 until array.length()) {
                val item=array.get(i)
                val label=if(kind=="entries"&&item is JSONObject){val entry=item.optJSONObject("path");val path=permissionPathLabel(entry);"${if(item.optString("access")=="write")"读写"else if(item.optString("access")=="deny")"禁止"else"只读"} · $path"}else "${if(kind=="write")"读写"else"只读"} · $item"
                add(Triple("$kind:$i",label,item))
            }}
        }
        var selected by remember(request.optString("id")){mutableStateOf(choices.map{it.first}.toSet())}
        var session by remember{mutableStateOf(false)}
        var strictReview by remember{mutableStateOf(false)}
        var values by remember{mutableStateOf(formFields(schema).mapNotNull{(id,field)->if(!field.has("default"))null else {val value=field.get("default");val enum=field.optJSONArray("enum");val index=enum?.let{a->(0 until a.length()).firstOrNull{a.get(it)==value}};id to if(index!=null)"enum:$index"else value.toString()}}.toMap())}
        var rawContent by remember{mutableStateOf("{}")}
        var showSchema by remember{mutableStateOf(false)}
        var multiples by remember{mutableStateOf(formFields(schema).mapNotNull{(id,field)->field.optJSONArray("default")?.let{a->id to (0 until a.length()).map{a.getString(it)}.toSet()}}.toMap())}
        var error by remember{mutableStateOf<String?>(null)}
        val context=LocalContext.current
        Dialog(onDismissRequest={details=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically){Text(if(permission)"权限申请"else"工具授权",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.SemiBold);PocketAction("关闭","关闭授权详情"){details=false}}
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        val message=params.optString(if(permission)"reason"else"message");if(message.isNotBlank()&&message!="null")Text(message,fontSize=14.sp,lineHeight=23.sp)
                        if(permission) {
                            params.optString("cwd").takeIf{it.isNotBlank()&&it!="null"}?.let{Text("工作目录：$it",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            Text("仅授予下方勾选的权限",fontWeight=FontWeight.Medium)
                            choices.forEach{(id,label,item)->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(id in selected,{checked->selected=if(checked)selected+id else selected-id},enabled=!(item is JSONObject&&item.optString("access")=="deny"));Text(label,Modifier.weight(1f),fontSize=13.sp,lineHeight=21.sp)}}
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(strictReview,{strictReview=it});Text("本轮后续命令先经过自动审查，再按沙箱权限执行",fontSize=12.sp)}
                            if(choices.isEmpty())Text("本次没有额外权限。",fontSize=13.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(!session,{session=false},label={Text("仅本轮")});FilterChip(session,{session=true},label={Text("本次会话")})}
                        }else if(mode=="url") {
                            Text("请在浏览器完成工具授权，再返回此页面。",fontSize=14.sp)
                            val url=params.optString("url");Text(url,fontSize=12.sp,lineHeight=20.sp)
                            OutlinedButton(onClick={try{val uri=Uri.parse(url);require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null);urlOpened=true;model.openLink(url);details=false}catch(_:Exception){error="无法打开此授权地址，请在电脑处理"}}){Text("打开授权页面")}
                        }else if(supported&&!simple) {
                            Text("此扩展表单使用 JSON 输入。请按原始字段填写；提交前会在电脑按完整规则校验。",fontSize=13.sp)
                            TextButton(onClick={showSchema=!showSchema}){Text(if(showSchema)"收起字段规则"else"查看字段规则")}
                            if(showSchema)Text(schema.toString(2),fontSize=11.sp,fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)
                            OutlinedTextField(rawContent,{if(it.length<=200000)rawContent=it},label={Text("表单 JSON")},modifier=Modifier.fillMaxWidth(),minLines=8)
                        }else if(supported) {
                            val required=schema.optJSONArray("required")?.let{array->(0 until array.length()).map{array.getString(it)}.toSet()}.orEmpty()
                            formFields(schema).forEach{(id,field)->
                                val title=field.optString("title",id)+if(id in required)" *"else""
                                val type=field.optString("type");val options=field.optJSONArray("enum")
                                Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                                field.optString("description").takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                if(options!=null)for(i in 0 until options.length())FilterChip(values[id]=="enum:$i",{values=values+(id to "enum:$i")},label={Text(field.optJSONArray("enumNames")?.optString(i)?.takeIf{it.isNotBlank()}?:options.get(i).toString())})
                                else if(type=="boolean")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(values[id]=="true",{values=values+(id to "true")},label={Text("是")});FilterChip(values[id]=="false",{values=values+(id to "false")},label={Text("否")})}
                                else if(type=="array")field.optJSONObject("items")?.optJSONArray("enum")?.let{array->for(i in 0 until array.length()){val value=array.getString(i);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(value in multiples[id].orEmpty(),{checked->multiples=multiples+(id to if(checked)multiples[id].orEmpty()+value else multiples[id].orEmpty()-value)});Text(field.optJSONObject("items")?.optJSONArray("enumNames")?.optString(i)?.takeIf{it.isNotBlank()}?:value,fontSize=13.sp)}}}
                                else OutlinedTextField(values[id].orEmpty(),{values=values+(id to it)},modifier=Modifier.fillMaxWidth().semantics{contentDescription=title},placeholder={Text(if(type=="number"||type=="integer")"输入数字"else"输入内容")},keyboardOptions=KeyboardOptions(keyboardType=when{type=="integer"->KeyboardType.Number;type=="number"->KeyboardType.Decimal;field.optString("format")=="email"->KeyboardType.Email;field.optString("format")=="uri"->KeyboardType.Uri;field.optString("format")=="password"->KeyboardType.Password;else->KeyboardType.Text}),visualTransformation=if(field.optString("format")=="password")PasswordVisualTransformation()else VisualTransformation.None)
                            }
                        }else Text("此请求需在电脑完成。拒绝或取消后，工具不会获得本次授权。",fontSize=14.sp)
                        error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={model.respondApproval(request,"decline");details=false},enabled=enabled,modifier=Modifier.weight(1f)){Text("拒绝")}
                        if(supported)Button(onClick={
                            try {
                                val response=if(permission) {
                                    val grant=JSONObject();if("network" in selected)grant.put("network",JSONObject().put("enabled",true))
                                    val fileSystem=JSONObject();for(kind in listOf("read","write","entries")){val values=choices.filter{it.first.startsWith("$kind:")&&it.first in selected}.map{it.third};if(values.isNotEmpty())fileSystem.put(kind,JSONArray(values))}
                                    if(fileSystem.length()>0){if(fs.has("globScanMaxDepth"))fileSystem.put("globScanMaxDepth",fs.get("globScanMaxDepth"));grant.put("fileSystem",fileSystem)}
                                    JSONObject().put("permissions",grant).put("scope",if(session)"session"else"turn").put("strictAutoReview",strictReview)
                                }else {
                                    val content=if(!simple&&mode!="url")JSONObject(rawContent)else JSONObject();val required=schema.optJSONArray("required")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}.orEmpty()
                                    if(mode!="url"&&simple)for((id,field)in formFields(schema)) {
                                        val value=values[id];val type=field.optString("type")
                                        if(value?.startsWith("enum:")==true)content.put(id,field.getJSONArray("enum").get(value.substringAfter(':').toInt()))
                                        else if(type=="boolean"&&(value!=null||id in required)){require(value!=null){"请选择是或否"};content.put(id,value=="true")}
                                        else if(type=="array"&&(multiples[id]!=null||id in required))content.put(id,JSONArray(multiples[id].orEmpty().toList()))
                                        else if(!value.isNullOrBlank())content.put(id,when(type){"integer"->value.toLongOrNull()?:kotlin.error("请输入整数");"number"->value.toDoubleOrNull()?.takeIf{it.isFinite()}?:kotlin.error("请输入有效数字");else->value})
                                        else require(id !in required){"请完成所有必填字段"}
                                    }
                                    if(mode!="url"&&simple)for((id,field)in formFields(schema))if(content.has(id)) {
                                        val value=content.get(id)
                                        if(value is String){require(value.length>=field.optInt("minLength",0)&&value.length<=field.optInt("maxLength",20000)){"文本长度超出要求"};if(field.optString("format")=="email")require(android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()){"邮箱格式无效"};if(field.optString("format")=="uri")require(!Uri.parse(value).scheme.isNullOrBlank()){"地址格式无效"}}
                                        if(value is Number)require(value.toDouble()>=field.optDouble("minimum",Double.NEGATIVE_INFINITY)&&value.toDouble()<=field.optDouble("maximum",Double.POSITIVE_INFINITY)){"数字超出允许范围"}
                                        if(value is JSONArray)require(value.length()>=field.optInt("minItems",0)&&value.length()<=field.optInt("maxItems",100)){"请选择要求数量的选项"}
                                    }
                                    JSONObject().put("action","accept").put("content",if(mode=="url")JSONObject.NULL else content)
                                }
                                model.respondApproval(request,"accept",response);details=false
                            }catch(e:Exception){error=e.message?:"请检查填写内容"}
                        },enabled=enabled&&(mode!="url"||urlOpened),modifier=Modifier.weight(1f)){Text(if(permission)"允许所选"else if(mode=="url")"继续"else"提交")}
                    }
                }
            }
        }
    }
}

@Composable fun ApprovalDecisionList(request:JSONObject,model:PocketModel,onSubmitted:()->Unit){
    val params=request.optJSONObject("params")?:JSONObject();val available=params.optJSONArray("availableDecisions")
    val choices=if(available!=null)(0 until available.length()).map{available.get(it)}else listOf("accept","acceptForSession","decline","cancel")
    val amendments=buildList<Any>{addAll(choices);params.optJSONArray("proposedNetworkPolicyAmendments")?.objects()?.forEach{rule->val decision=JSONObject().put("applyNetworkPolicyAmendment",JSONObject().put("network_policy_amendment",rule));if(none{it.toString()==decision.toString()})add(decision)};params.optJSONArray("proposedExecpolicyAmendment")?.let{prefix->val decision=JSONObject().put("acceptWithExecpolicyAmendment",JSONObject().put("execpolicy_amendment",prefix));if(none{it.toString()==decision.toString()})add(decision)}}
    params.optJSONObject("networkApprovalContext")?.let{Text("网络目标：${it.optString("host")} (${it.optString("protocol")})",fontWeight=FontWeight.Medium)}
    amendments.forEach{choice->val label=if(choice is JSONObject){val network=choice.optJSONObject("applyNetworkPolicyAmendment")?.optJSONObject("network_policy_amendment");if(network!=null)"${if(network.optString("action")=="allow")"保存允许规则"else"保存拒绝规则"} · ${network.optString("host")}"else"保存命令前缀规则：${choice.optJSONObject("acceptWithExecpolicyAmendment")?.optJSONArray("execpolicy_amendment")}"}else when(choice.toString()){ "accept"->"允许本次";"acceptForSession"->"本次会话允许";"decline"->"拒绝";"cancel"->"取消请求";else->choice.toString()}
        OutlinedButton(onClick={model.approve(request,choice);onSubmitted()},enabled=model.connected&&!model.busy&&model.pendingOperation==null,modifier=Modifier.fillMaxWidth()){Text(label,fontSize=13.sp)}
    }
}
