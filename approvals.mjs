import { isDeepStrictEqual } from 'node:util';
import Ajv from 'ajv';
import addFormats from 'ajv-formats';
const decisions=new Set(['accept','acceptForSession','decline','cancel']);
const object=value=>value&&typeof value==='object'&&!Array.isArray(value);
const equal=isDeepStrictEqual;
const fail=message=>{throw Object.assign(new Error(message),{notSubmitted:true});};

const ajv=new Ajv({strict:false,allErrors:true,validateFormats:true});addFormats(ajv);ajv.addFormat('password',true);
export function normalizeForm(schema){
  const copy=structuredClone(schema);
  for(const field of Object.values(copy.properties??{})){
    if(Array.isArray(field.oneOf)&&field.oneOf.every(o=>Object.hasOwn(o,'const'))){field.enum=field.oneOf.map(o=>o.const);field.enumNames=field.oneOf.map(o=>o.title??String(o.const));delete field.oneOf;field.type??=typeof field.enum[0];}
    if(Array.isArray(field.items?.anyOf)&&field.items.anyOf.every(o=>Object.hasOwn(o,'const'))){field.items.enum=field.items.anyOf.map(o=>o.const);field.items.enumNames=field.items.anyOf.map(o=>o.title??String(o.const));delete field.items.anyOf;field.items.type??='string';}
  }return copy;
}
export function validateForm(schema,content){
  if(!object(schema)||!object(content)||JSON.stringify(schema).length>200000||JSON.stringify(content).length>200000)fail('表单数据无效或过大');
  if(Object.keys(content).some(k=>schema.properties&&!Object.hasOwn(schema.properties,k)))fail('表单包含未请求的字段');
  let validate;try{validate=ajv.compile(normalizeForm(schema));}catch{fail('此表单的验证规则暂不支持，请在电脑完成');}
  if(!validate(content))fail('表单无效：'+(validate.errors??[]).slice(0,3).map(e=>`${e.instancePath||'/'} ${e.message}`).join('；'));
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
  if(response.strictAutoReview!=null&&typeof response.strictAutoReview!=='boolean')fail('自动审查设置无效');
  return {permissions:granted,scope:response.scope??'turn',...(response.strictAutoReview!=null?{strictAutoReview:response.strictAutoReview}:{})};
}

export function approvalResponse(request,args) {
  const method=request.method;
  if(method==='item/permissions/requestApproval')return permissionsResponse(request,args);
  if(method==='mcpServer/elicitation/request') {
    const response=args.response??{action:args.decision,content:null};
    if(!['accept','decline','cancel'].includes(response.action))fail('授权选项无效');
    if(response.action!=='accept')return {action:response.action,content:null};
    const mode=request.params?.mode;
    if(mode==='form'||mode==='openai/form'||mode==='openaiForm')return {action:'accept',content:validateForm(request.params.requestedSchema,response.content)};
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
    const offered=request.params?.availableDecisions;
    if(object(args.decision)){
      const candidates=[...(offered??[]).filter(object),...(request.params?.proposedNetworkPolicyAmendments??[]).map(rule=>({applyNetworkPolicyAmendment:{network_policy_amendment:rule}})),...(request.params?.proposedExecpolicyAmendment?[{acceptWithExecpolicyAmendment:{execpolicy_amendment:request.params.proposedExecpolicyAmendment}}]:[])];
      if(!candidates.some(value=>equal(value,args.decision)))fail('不能授予未请求的规则');
    }else if(!decisions.has(args.decision)||Array.isArray(offered)&&!offered.some(value=>equal(value,args.decision)))fail('Unsupported approval decision / 无效或未提供的授权选项');
    return {decision:args.decision};
  }
  fail('此类授权请在电脑处理');
}

export const APPROVAL_METHODS=new Set(['item/commandExecution/requestApproval','item/fileChange/requestApproval','item/tool/requestUserInput','item/permissions/requestApproval','mcpServer/elicitation/request']);
