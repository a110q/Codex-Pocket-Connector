import { promises as fs } from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

const fail=message=>{throw Object.assign(new Error(message),{notSubmitted:true});};
export function nextRun(schedule,from=Date.now()){
  if(schedule.type==='interval')return from+schedule.hours*3600000;
  const formatter=new Intl.DateTimeFormat('en-US',{timeZone:schedule.timeZone,hour:'2-digit',minute:'2-digit',hourCycle:'h23',weekday:'short'});
  for(let at=Math.floor(from/60000)*60000+60000;at<from+8*86400000;at+=60000){
    const parts=Object.fromEntries(formatter.formatToParts(at).map(p=>[p.type,p.value]));
    if(Number(parts.hour)===schedule.hour&&Number(parts.minute)===schedule.minute&&(!schedule.weekdays||!['Sat','Sun'].includes(parts.weekday)))return at;
  }throw new Error('无法计算下次执行时间');
}
function validate(args){
  if(typeof args.name!=='string'||!args.name.trim()||args.name.length>100)fail('请输入任务名称（最多 100 字）');
  if(typeof args.prompt!=='string'||!args.prompt.trim()||args.prompt.length>30000)fail('请输入任务指令（最多 30000 字）');
  if(typeof args.projectId!=='string'||!args.projectId)fail('请选择执行项目');
  const s=args.schedule;
  if(s?.type==='interval'){if(!Number.isInteger(s.hours)||s.hours<1||s.hours>168)fail('重复间隔需为 1 至 168 小时');}
  else if(s?.type==='daily'){
    if(!Number.isInteger(s.hour)||s.hour<0||s.hour>23||!Number.isInteger(s.minute)||s.minute<0||s.minute>59||typeof s.timeZone!=='string'||s.timeZone.length>100)fail('执行时间无效');
    try{new Intl.DateTimeFormat('en',{timeZone:s.timeZone}).format();}catch{fail('时区无效');}
  }else fail('请选择重复方式');
  if(typeof args.enabled!=='boolean')fail('任务状态无效');
  return {name:args.name.trim(),prompt:args.prompt,projectId:args.projectId,schedule:s,enabled:args.enabled};
}
// Pocket schedules are explicit connector jobs, independent of the private
// desktop automation service. No Codex automation TOML files are modified.
export class PocketScheduler {
  constructor(adapter,file,{now=Date.now,autoStart=true}={}){
    Object.assign(this,{adapter,file,now});this.jobs=[];this.queue=Promise.resolve();this.running=new Set();
    this.ready=this.load().catch(e=>{this.storageError=e;});if(autoStart){this.timer=setInterval(()=>this.tick().catch(()=>{}),15000);this.timer.unref();}
  }
  async load(){try{const data=JSON.parse(await fs.readFile(this.file,'utf8'));this.jobs=data.jobs??[];for(const job of this.jobs){for(const run of job.history??[])if(run.status==='starting')run.status='unknown';if(job.nextAt<=this.now())job.nextAt=nextRun(job.schedule,this.now());}}catch(e){if(e.code!=='ENOENT')throw new Error('定时任务存储损坏，请在电脑恢复备份');}}
  async write(){await fs.mkdir(path.dirname(this.file),{recursive:true,mode:0o700});const temp=`${this.file}.tmp`;await fs.writeFile(temp,JSON.stringify({version:1,jobs:this.jobs}),{mode:0o600});await fs.rename(temp,this.file);}
  serial(task){const next=this.queue.catch(()=>{}).then(async()=>{await this.ready;if(this.storageError)throw this.storageError;return task();});this.queue=next;return next;}
  async list(){await this.ready;if(this.storageError)throw this.storageError;return {jobs:this.jobs,timeZone:Intl.DateTimeFormat().resolvedOptions().timeZone,engine:'pocket',requiresConnector:true};}
  async call(action,args){return this.serial(async()=>{
    if(action==='automations.list')return this.list();
    if(action==='automation.save'){
      const value=validate(args);await this.adapter.workspace.root({projectId:value.projectId});
      let job=args.id?this.jobs.find(j=>j.id===args.id):null;if(args.id&&!job)fail('任务不存在');
      if(job&&this.running.has(job.id))fail('任务正在启动，请稍后编辑');
      if(!job){job={id:randomUUID(),history:[]};this.jobs.push(job);}
      Object.assign(job,value,{nextAt:value.enabled?nextRun(value.schedule,this.now()):null,updatedAt:this.now()});await this.write();return {saved:true,job};
    }
    const job=this.jobs.find(j=>j.id===args.id);if(!job)fail('任务不存在');
    if(action==='automation.delete'){if(this.running.has(job.id))fail('任务正在启动，请稍后删除');this.jobs=this.jobs.filter(j=>j!==job);await this.write();return {deleted:true};}
    if(action==='automation.run')return this.run(job);
    fail('不支持的定时任务操作');
  });}
  async run(job){
    if(this.running.has(job.id))fail('任务正在启动，请勿重复操作');
    this.running.add(job.id);const run={id:randomUUID(),at:this.now(),status:'starting'};
    job.history=[run,...job.history??[]].slice(0,30);job.nextAt=job.enabled?nextRun(job.schedule,this.now()):null;
    // Persist intent before sending: restarting never blindly replays a run.
    try{await this.write();const result=await this.adapter.createThread({projectId:job.projectId,text:job.prompt,title:`定时 · ${job.name}`,clientUserMessageId:run.id});run.threadId=result.threadId;run.status='submitted';return {submitted:true,threadId:result.threadId};}
    catch(e){run.status=e.uncertain?'unknown':'failed';run.error=e.message;throw e;}
    finally{this.running.delete(job.id);await this.write();this.adapter.emit('event',{type:'automation.updated',id:job.id});}
  }
  async tick(){return this.serial(async()=>{if(!this.adapter.ready)return;for(const job of this.jobs.filter(j=>j.enabled&&j.nextAt&&j.nextAt<=this.now())){try{await this.run(job);}catch{}}});}
  close(){clearInterval(this.timer);}
}
