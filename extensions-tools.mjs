import { promises as fs } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
const contentHash=value=>createHash('sha256').update(value).digest('hex');

const fail=message=>{throw Object.assign(new Error(message),{notSubmitted:true});};
export class ExtensionTools {
  constructor(adapter){this.adapter=adapter;this.tokens=new Map();this.oauth=new Map();}
  async cwd(args){return args.projectId||args.threadId?await this.adapter.workspace.root(args):undefined;}
  async skills(args){const cwd=await this.cwd(args);const result=await this.adapter.server.request('skills/list',{...(cwd?{cwds:[cwd]}:{}),forceReload:true});return {skills:(result.data??[]).flatMap(entry=>entry.skills??[]),errors:(result.data??[]).flatMap(entry=>entry.errors??[])};}
  async plugins(args,catalog=false){const cwd=await this.cwd(args);const result=await this.adapter.server.request(catalog?'plugin/list':'plugin/installed',{...(cwd?{cwds:[cwd]}:{}),...(catalog?{forceRefetch:!!args.refresh}:{})});return {plugins:(result.marketplaces??[]).flatMap(m=>(m.plugins??[]).map(p=>({...p,marketplaceName:m.name,marketplacePath:m.path}))),errors:result.marketplaceLoadErrors??[]};}
  async skill(args){const skill=(await this.skills(args)).skills.find(s=>s.path===args.path);if(!skill)fail('技能已变化，请刷新列表');return skill;}
  async call(action,args){
    const server=this.adapter.server;
    try{switch(action){
      case 'extensions.list':return this.skills(args);
      case 'skill.read':{const skill=await this.skill(args),stat=await fs.stat(skill.path);if(stat.size>512*1024)fail('技能文件过大，请在电脑查看');const content=await fs.readFile(skill.path,'utf8');return {...skill,content,contentHash:contentHash(content),editable:await this.editable(skill)};}
      case 'skill.set':{const skill=await this.skill(args);if(typeof args.enabled!=='boolean')fail('技能开关无效');await server.request('skills/config/write',{path:skill.path,enabled:args.enabled});return {saved:true,...await this.skills(args)};}
      case 'skill.save':return this.saveSkill(args);
      case 'skill.delete':{const skill=await this.skill(args);if(!await this.editable(skill))fail('仅可删除用户自行创建的本地技能');const backups=path.join(os.homedir(),'.codex/pocket-skill-backups');await fs.mkdir(backups,{recursive:true,mode:0o700});await fs.rename(path.dirname(skill.path),path.join(backups,`${path.basename(path.dirname(skill.path))}-${Date.now()}`));return {deleted:true};}
      case 'plugin.list':return this.plugins(args,!!args.catalog);
      case 'plugin.install':case 'plugin.uninstall':case 'plugin.set':{
        const plugin=(await this.plugins(args,action==='plugin.install')).plugins.find(p=>p.id===args.pluginId);if(!plugin)fail('插件已变化，请刷新市场');
        if(action==='plugin.install'){
          if(plugin.installed)return {installed:true};
          if(plugin.installPolicy==='NOT_AVAILABLE'||plugin.availability&&plugin.availability!=='AVAILABLE')fail('当前账号或组织不允许安装此插件');
          if(plugin.mustShowInstallationInterstitial)fail('此插件需要先在电脑查看官方安装告知并同意');
          return await server.request('plugin/install',{pluginName:plugin.name,...(plugin.marketplacePath?{marketplacePath:plugin.marketplacePath}:{remoteMarketplaceName:plugin.marketplaceName})});
        }
        if(!plugin.installed)fail('插件尚未安装');
        if(plugin.installPolicy==='INSTALLED_BY_DEFAULT')fail('此插件由管理员管理，请在电脑处理');
        if(action==='plugin.uninstall')return await server.request('plugin/uninstall',{pluginId:plugin.id});
        if(typeof args.enabled!=='boolean')fail('插件开关无效');
        await server.request('config/value/write',{keyPath:`plugins.${JSON.stringify(plugin.id)}.enabled`,value:args.enabled,mergeStrategy:'replace'});
        return {saved:true};
      }
      case 'account.usage':{
        const [rate,usage]=await Promise.allSettled([server.request('account/rateLimits/read',{}),server.request('account/usage/read',args.threadId?{threadId:args.threadId}:{})]);
        let tokenUsage=this.tokens.get(args.threadId);
        if(!tokenUsage&&args.threadId){const snapshot=this.adapter.snapshots.get(`local:${args.threadId}`);tokenUsage=snapshot?.state?.latestTokenUsageInfo??snapshot?.state?.tokenUsage??snapshot?.state?.thread?.tokenUsage;}
        if(!tokenUsage&&args.threadId)tokenUsage=await this.storedTokens(args.threadId);
        return {at:new Date().toISOString(),rateLimits:rate.status==='fulfilled'?rate.value:null,rateError:rate.status==='rejected'?rate.reason.message:null,usage:usage.status==='fulfilled'?usage.value:null,usageError:usage.status==='rejected'?usage.reason.message:null,tokenUsage:tokenUsage??null};
      }
      case 'mcp.list':{const list=await server.pages('mcpServerStatus/list',args.threadId?{threadId:args.threadId}:{});return {servers:list,logins:[...this.oauth.values()]};}
      case 'mcp.login':{const servers=await server.pages('mcpServerStatus/list',args.threadId?{threadId:args.threadId}:{});if(!servers.some(s=>s.name===args.name))fail('工具服务不存在');const login=await server.request('mcpServer/oauth/login',{name:args.name,...(args.threadId?{threadId:args.threadId}:{}),timeoutSecs:300});this.oauth.set(login.loginId??args.name,{name:args.name,loginId:login.loginId,status:'pending'});return login;}
      default:fail('不支持的扩展操作');
    }}catch(e){if(!e.uncertain&&typeof e.code==='number')e.notSubmitted=true;throw e;}
  }
  onEvent({method,params}){
    if(method==='thread/tokenUsage/updated'&&params?.threadId)this.tokens.set(params.threadId,params.tokenUsage);
    if(method==='mcpServer/oauthLogin/completed'){const id=params.loginId??params.name;this.oauth.set(id,{...params,status:params.success?'completed':'failed'});this.adapter.emit('event',{type:'mcp.updated'});}
  }
  async storedTokens(threadId){
    try{
      const {thread}=await this.adapter.server.request('thread/read',{threadId,includeTurns:false});
      if(!thread?.path)return null;
      const codexHome=this.adapter.options?.codexHome??process.env.CODEX_HOME??path.join(os.homedir(),'.codex');
      const file=await fs.realpath(thread.path);
      let allowed=false;for(const dir of ['sessions','archived_sessions']){const root=await fs.realpath(path.join(codexHome,dir)).catch(()=>null);if(root){const rel=path.relative(root,file);if(rel&&!rel.startsWith('..')&&!path.isAbsolute(rel))allowed=true;}}
      if(!allowed)return null;
      const handle=await fs.open(file,'r');try{const stat=await handle.stat();const start=Math.max(0,stat.size-2*1024*1024);const buffer=Buffer.alloc(stat.size-start);await handle.read(buffer,0,buffer.length,start);let lines=buffer.toString('utf8').split('\n');if(start)lines.shift();
        for(const line of lines.reverse()){let record;try{record=JSON.parse(line);}catch{continue;}const info=record.type==='event_msg'&&record.payload?.type==='token_count'?record.payload.info:null;if(info?.last_token_usage||info?.total_token_usage){return {total:info.total_token_usage?camelUsage(info.total_token_usage):null,last:info.last_token_usage?camelUsage(info.last_token_usage):null,modelContextWindow:info.model_context_window??null,source:'stored-token-count'};}}
      }finally{await handle.close();}
    }catch{}return null;
  }
  async editable(skill){
    if(skill.pluginId||!['user','User'].includes(skill.scope))return false;
    for(const base of [path.join(os.homedir(),'.agents/skills'),path.join(os.homedir(),'.codex/skills')]){
      const rel=path.relative(base,skill.path);if(rel&&!rel.startsWith('..')&&!path.isAbsolute(rel)&&!rel.split(path.sep).includes('.system')&&await fs.realpath(skill.path)===skill.path)return true;
    }return false;
  }
  async saveSkill(args){
    if(typeof args.content!=='string'||!args.content.trim()||args.content.length>200000)fail('技能内容为空或过长');
    if(!/^---\s*\n[\s\S]*?\bname:\s*\S+[\s\S]*?\bdescription:\s*\S+[\s\S]*?\n---/.test(args.content))fail('请保留技能头部的 name 和 description');
    let file;
    if(args.path){const skill=await this.skill(args);if(!await this.editable(skill))fail('内置或插件技能请在电脑管理');file=skill.path;if(args.contentHash!==contentHash(await fs.readFile(file,'utf8')))fail('技能已在电脑修改，请重新打开后编辑');}
    else {if(!/^[a-z][a-z0-9-]{1,63}$/.test(args.name??''))fail('技能名称需使用小写字母、数字和短横线');const dir=path.join(os.homedir(),'.agents/skills',args.name);await fs.mkdir(path.dirname(dir),{recursive:true});await fs.mkdir(dir).catch(e=>{if(e.code==='EEXIST')fail('同名技能已存在');throw e;});file=path.join(dir,'SKILL.md');}
    const temporary=`${file}.pocket-${Date.now()}`;await fs.writeFile(temporary,args.content,{mode:0o600,flag:'wx'});await fs.rename(temporary,file);
    return {saved:true,path:file,...await this.skills(args)};
  }
}

function camelUsage(value){return Object.fromEntries(Object.entries(value).filter(([,v])=>typeof v==='number'&&Number.isFinite(v)&&v>=0).map(([k,v])=>[k.replace(/_([a-z])/g,(_,c)=>c.toUpperCase()),v]));}
