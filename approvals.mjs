const decisions=new Set(['accept','acceptForSession','decline','cancel']);
const object=value=>value&&typeof value==='object'&&!Array.isArray(value);
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const fail=message=>{throw Object.assign(new Error(message),{notSubmitted:true});};

export function validateForm(schema,content) {
  if(!object(schema)||schema.type!=='object'||!object(schema.properties)||!object(content))fail('不支持的表单，请在电脑处理');
  const unsupported=['pattern','oneOf','anyOf','allOf','$ref','not','if','then','else','dependentRequired','dependentSchemas','patternProperties'];
  if(unsupported.some(key=>Object.hasOwn(schema,key))||!Array.isArray(schema.required??[])||(schema.required??[]).some(key=>!Object.hasOwn(schema.properties,key)))fail('此类表单验证需在电脑处理');
  for(const field of Object.values(schema.properties))if(!object(field)||unsupported.some(key=>Object.hasOwn(field,key))||(field.format&&!['email','uri','date','date-time','password'].includes(field.format)))fail('此类表单验证需在电脑处理');
  if(Object.keys(content).some(key=>!Object.hasOwn(schema.properties,key)))fail('表单包含未请求的字段');
  for(const key of schema.required??[])if(!Object.hasOwn(content,key))fail('请完成所有必填字段');
  for(const [key,value] of Object.entries(content)) {
    const field=schema.properties[key];
    if(field.enum&&!field.enum.some(v=>equal(v,value)))fail('表单选项无效');
    if(field.type==='string') {
      if(typeof value!=='string'||value.length>(field.maxLength??20000)||value.length<(field.minLength??0))fail('表单文本无效');
      if(field.format==='email'&&!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value))fail('邮箱格式无效');
      if(field.format==='uri'){try{new URL(value);}catch{fail('地址格式无效');}}
      if(field.format==='date'&&(!/^\d{4}-\d{2}-\d{2}$/.test(value)||!Number.isFinite(Date.parse(value))||new Date(value).toISOString().slice(0,10)!==value))fail('日期格式无效');
      if(field.format==='date-time'&&(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/i.test(value)||!Number.isFinite(Date.parse(value))))fail('时间格式无效');
    }
    else if(field.type==='boolean') {if(typeof value!=='boolean')fail('表单开关无效');}
    else if(['number','integer'].includes(field.type)) {if(typeof value!=='number'||!Number.isFinite(value)||(field.type==='integer'&&!Number.isInteger(value))||value<(field.minimum??-Infinity)||value>(field.maximum??Infinity))fail('表单数字无效');}
    else if(field.type==='array'&&field.items?.type==='string'&&Array.isArray(field.items.enum)) {if(!Array.isArray(value)||value.length<(field.minItems??0)||value.length>(field.maxItems??100)||value.some(v=>!field.items.enum.includes(v))||(field.uniqueItems&&new Set(value).size!==value.length))fail('表单多选无效');}
    else fail('此类表单字段需在电脑处理');
  }
  return content;
}

function permissionsResponse(request,args) {
  if(['decline','cancel'].includes(args.decision))return {permissions:{},scope:'turn'};
  if(!['accept','acceptForSession'].includes(args.decision))fail('授权选项无效');
  const wanted=request.params?.permissions??{},response=args.response??{permissions:wanted,scope:args.decision==='acceptForSession'?'session':'turn'};
  if(!object(response.permissions)||Object.keys(response.permissions).some(k=>!['fileSystem','network'].includes(k))||!['turn','session'].includes(response.scope??'turn'))fail('权限响应无效');
  const granted=response.permissions;
  if(granted.network!=null) {if(!object(granted.network)||Object.keys(granted.network).some(k=>k!=='enabled')||(granted.network.enabled!=null&&typeof granted.network.enabled!=='boolean')||(granted.network.enabled===true&&wanted.network?.enabled!==true))fail('不能授予未请求的网络权限');}
  if(granted.fileSystem) {
    if(!object(granted.fileSystem)||Object.keys(granted.fileSystem).some(k=>!['read','write','entries','globScanMaxDepth'].includes(k)))fail('文件权限响应无效');
    for(const key of ['read','write','entries'])if(granted.fileSystem[key]!=null) {
      if(!Array.isArray(granted.fileSystem[key])||granted.fileSystem[key].some(v=>!(wanted.fileSystem?.[key]??[]).some(w=>equal(v,w))))fail('不能授予未请求的文件权限');
    }
    if((wanted.fileSystem?.entries??[]).some(entry=>entry?.access==='deny'&&!(granted.fileSystem.entries??[]).some(value=>equal(entry,value))))fail('不能移除请求中的文件访问限制');
    if(granted.fileSystem.globScanMaxDepth!=null&&(!Number.isInteger(granted.fileSystem.globScanMaxDepth)||granted.fileSystem.globScanMaxDepth<1||granted.fileSystem.globScanMaxDepth>(wanted.fileSystem?.globScanMaxDepth??0)))fail('不能扩大目录扫描范围');
  }
  return {permissions:granted,scope:response.scope??'turn'};
}

export function approvalResponse(request,args) {
  const method=request.method;
  if(method==='item/permissions/requestApproval')return permissionsResponse(request,args);
  if(method==='mcpServer/elicitation/request') {
    const response=args.response??{action:args.decision,content:null};
    if(!['accept','decline','cancel'].includes(response.action))fail('授权选项无效');
    if(response.action!=='accept')return {action:response.action,content:null};
    const mode=request.params?.mode;
    if(mode==='form'||mode==='openai/form')return {action:'accept',content:validateForm(request.params.requestedSchema,response.content)};
    if(mode==='url')return {action:'accept',content:null};
    fail('此类身份验证请在电脑完成');
  }
  if(method==='item/tool/requestUserInput') {
    const answers=args.response?.answers;if(!object(answers)||!Object.keys(answers).length)fail('请输入回答');
    const ids=new Set((request.params?.questions??[]).map(q=>q.id));
    for(const [id,answer]of Object.entries(answers))if(!ids.has(id)||!Array.isArray(answer?.answers)||answer.answers.length>20||answer.answers.some(v=>typeof v!=='string'||v.length>20000))fail('Invalid question answer / 回答格式无效');
    return {answers};
  }
  if(method==='item/commandExecution/requestApproval'||method==='item/fileChange/requestApproval') {
    if(!decisions.has(args.decision))fail('Unsupported approval decision / 授权选项无效');
    const offered=request.params?.availableDecisions;
    if(Array.isArray(offered)&&!offered.includes(args.decision))fail('此请求不允许该授权选项');
    return {decision:args.decision};
  }
  fail('此类授权请在电脑处理');
}

export const APPROVAL_METHODS=new Set(['item/commandExecution/requestApproval','item/fileChange/requestApproval','item/tool/requestUserInput','item/permissions/requestApproval','mcpServer/elicitation/request']);
