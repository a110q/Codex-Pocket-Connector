import path from 'node:path';
import os from 'node:os';
import { promises as fs } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';

const execute = promisify(execFile);
const hash = value => createHash('sha256').update(value).digest('hex');
const fail = message => { throw Object.assign(new Error(message), { notSubmitted: true }); };
const ignored = new Set(['.git', '.private', 'node_modules', '.gradle', '.idea', 'build', 'dist']);
const inside = (root, target) => { const rel = path.relative(root, target); return !path.isAbsolute(rel) && rel !== '..' && !rel.startsWith(`..${path.sep}`); };
const text = (value, label, max = 500) => { if (typeof value !== 'string' || !value.trim() || value.length > max || /[\x00-\x1f]/.test(value)) fail(`${label}无效`); return value; };

// All paths originate in the installed Codex project catalog or a known chat.
// Mobile clients select a project; they cannot nominate a filesystem root.
export class WorkspaceTools {
  constructor(adapter, { run = execute } = {}) { this.adapter = adapter; this.run = run; this.queues = new Map(); }
  async root(args) {
    let value;
    if (args.projectId) {
      const project = (await this.adapter.listProjects()).projects.find(p => p.projectId === args.projectId);
      if (!project || (project.hostId && project.hostId !== 'local')) fail('请选择本机项目');
      value = project.path;
    } else if (args.threadId) value = (await this.adapter.storedState(args.threadId)).cwd;
    if (!value) fail('请先选择项目或打开有工作目录的对话');
    const root = await fs.realpath(value);
    if (root === path.parse(root).root || root === await fs.realpath(os.homedir())) fail('请使用具体项目目录，不能浏览整台电脑');
    if (!(await fs.stat(root)).isDirectory()) fail('项目目录不存在');
    return root;
  }
  async resolve(root, relative = '', allowMissing = false) {
    if (typeof relative !== 'string' || relative.length > 4096 || /[\x00-\x1f\\]/.test(relative) || path.isAbsolute(relative) || relative.split('/').some(s => s === '..' || ignored.has(s))) fail('文件路径无效');
    const full = path.resolve(root, relative);
    if (!inside(root, full)) fail('文件超出项目目录');
    let current = root;
    for (const part of path.relative(root, full).split(path.sep).filter(Boolean)) {
      current = path.join(current, part);
      try { if ((await fs.lstat(current)).isSymbolicLink()) fail('暂不访问符号链接，请选择项目内实际文件'); }
      catch (e) { if (allowMissing && e.code === 'ENOENT') break; throw e; }
    }
    let ancestor=allowMissing?path.dirname(full):full;
    if(allowMissing)for(;;){try{await fs.lstat(ancestor);break;}catch(e){if(e.code!=='ENOENT')throw e;const parent=path.dirname(ancestor);if(parent===ancestor)throw e;ancestor=parent;}}
    const canonical = await fs.realpath(ancestor);
    if (!inside(root, canonical)) fail('文件超出项目目录');
    return full;
  }
  async list(args) {
    const root = await this.root(args), directory = await this.resolve(root, args.path ?? '');
    const offset = args.offset ?? 0;
    if (!Number.isSafeInteger(offset) || offset < 0) fail('分页位置无效');
    const entries = (await fs.readdir(directory, { withFileTypes: true })).filter(e => !ignored.has(e.name) && !e.isSymbolicLink() && (e.isFile() || e.isDirectory())).sort((a,b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name));
    return { root, path: args.path ?? '', entries: entries.slice(offset, offset + 100).map(e => ({ name: e.name, path: path.relative(root,path.join(directory,e.name)).split(path.sep).join('/'), directory: e.isDirectory() })), nextOffset: offset + 100 < entries.length ? offset + 100 : null, total: entries.length };
  }
  async search(args) {
    const root = await this.root(args), query = text(args.query, '搜索内容', 200).toLocaleLowerCase();
    const pending = [''], entries = []; let visited = 0;
    while (pending.length && visited < 15000 && entries.length < 200) {
      const rel = pending.shift();
      const items = await fs.readdir(await this.resolve(root,rel), { withFileTypes:true }).catch(()=>[]);
      for (const item of items.sort((a,b)=>a.name.localeCompare(b.name))) {
        if (++visited > 15000 || entries.length >= 200) break;
        if (ignored.has(item.name) || item.isSymbolicLink()) continue;
        const name = rel ? `${rel}/${item.name}` : item.name;
        if (item.isDirectory()) pending.push(name);
        if ((item.isFile() || item.isDirectory()) && name.toLocaleLowerCase().includes(query)) entries.push({name:item.name,path:name,directory:item.isDirectory()});
      }
    }
    return {root,entries,truncated:pending.length>0 || visited>=15000 || entries.length>=200};
  }
  async read(args) {
    const root = await this.root(args), file = await this.resolve(root,text(args.path,'文件路径',4096));
    const handle = await fs.open(file,'r');
    try {
      const stat = await handle.stat();
      if (!stat.isFile() || stat.size>50*1024*1024) fail('仅可预览 50 MB 以内的文件');
      const fingerprint=hash(`${file}|${stat.size}|${stat.mtimeMs}`);
      if (args.fingerprint && args.fingerprint!==fingerprint) fail('文件已经更新，请重新打开');
      const offset=args.offset??0;
      if (!Number.isSafeInteger(offset)||offset<0||offset>stat.size) fail('读取位置无效');
      const data=Buffer.alloc(Math.min(192*1024,stat.size-offset));const {bytesRead}=await handle.read(data,0,data.length,offset);
      const current=await fs.stat(await this.resolve(root,args.path));if(current.dev!==stat.dev||current.ino!==stat.ino||current.size!==stat.size||current.mtimeMs!==stat.mtimeMs)fail('文件路径或内容已变化，请重新打开');
      return {name:path.basename(file),size:stat.size,total:stat.size,fingerprint,data:data.subarray(0,bytesRead).toString('base64'),nextOffset:offset+bytesRead,done:offset+bytesRead===stat.size};
    } finally {await handle.close();}
  }
  async previewFile(args) {
    const root = await this.root(args), file = await this.resolve(root, text(args.path, '文件路径', 4096));
    const stat = await fs.stat(file);
    if (!stat.isFile() || stat.size > 50 * 1024 * 1024 || !['.html', '.htm'].includes(path.extname(file).toLowerCase())) fail('请选择 50 MB 以内的 HTML 文件');
    if (args.fingerprint && args.fingerprint !== hash(`${file}|${stat.size}|${stat.mtimeMs}`)) fail('文件已经更新，请重新打开');
    return { path: file, root };
  }
  async references(args) {
    if (!args.fileReferences?.length) return [];
    if (!Array.isArray(args.fileReferences)||args.fileReferences.length>20) fail('每条消息最多引用 20 个项目文件');
    const root=await this.root(args),result=[];
    for(const rel of [...new Set(args.fileReferences)]) {
      const file=await this.resolve(root,text(rel,'文件引用',4096)),stat=await fs.stat(file);
      if(!stat.isFile())fail('只能引用文件');result.push({name:path.relative(root,file).split(path.sep).join('/'),path:file});
    }
    return result;
  }
  async git(root,argv,{mutating=false}={}) {
    try {
      // Disable interactive prompts, shell interpolation and optional locks on reads.
      const {stdout}=await this.run('git',['-c','core.quotepath=false',...argv],{cwd:root,encoding:'utf8',timeout:mutating?60000:15000,maxBuffer:4*1024*1024,windowsHide:true,env:{...process.env,GIT_TERMINAL_PROMPT:'0',...(mutating?{}:{GIT_OPTIONAL_LOCKS:'0'})}});
      return stdout;
    }catch(e){throw Object.assign(new Error(e.killed?'Git 操作超时，请检查电脑状态':String(e.stderr||e.message).slice(0,2000)),{uncertain:mutating,notSubmitted:!mutating});}
  }
  async status(args) {
    const root=await this.root(args);
    let repo;
    try { repo=(await this.git(root,['rev-parse','--show-toplevel'])).trim(); }
    catch(error) {
      if(/not a git repository/i.test(error.message))return {available:false,reason:'not-repository',root,message:'这个项目还没有 Git 仓库。选择已有 Git 仓库的项目后，即可查看分支和代码修改。'};
      if(/ENOENT|not recognized|找不到指定的文件/i.test(error.message))return {available:false,reason:'git-not-installed',root,message:'电脑尚未安装 Git，安装后即可查看代码修改。'};
      throw error;
    }
    if(!inside(root,repo)||!inside(repo,root))return {available:false,reason:'repository-root-required',root,message:'当前项目是仓库的子目录。请选择仓库根目录对应的项目。'};
    const [raw,branch,refs,head,upstream]=await Promise.all([
      this.git(root,['status','--porcelain=v1','-z','--untracked-files=all']),this.git(root,['branch','--show-current']),this.git(root,['for-each-ref','--format=%(refname:short)','refs/heads','refs/remotes']),this.git(root,['rev-parse','HEAD']).catch(()=>''),this.git(root,['rev-parse','--abbrev-ref','--symbolic-full-name','@{u}']).catch(()=>'')]);
    const parts=raw.split('\0'),files=[];
    for(let i=0;i<parts.length;i++)if(parts[i]){const code=parts[i].slice(0,2),file=parts[i].slice(3);let original=null;if(/[RC]/.test(code))original=parts[++i];files.push({path:file,status:code,original,staged:code[0]!==' '&&code[0]!=='?',unstaged:code[1]!==' '});}
    return {available:true,root,branch:branch.trim()||'(detached HEAD)',head:head.trim(),upstream:upstream.trim(),branches:refs.trim().split('\n').filter(Boolean),files,revision:await this.revision(root,raw)};
  }
  async revision(root,raw) {
    raw??=await this.git(root,['status','--porcelain=v1','-z','--untracked-files=all']);
    const tracked=await this.git(root,['diff','--no-ext-diff','--binary','HEAD']).catch(()=>this.git(root,['diff','--no-ext-diff','--binary']));
    const untracked=await this.git(root,['ls-files','--others','--exclude-standard','-z']);
    const contents=[];
    for(const rel of untracked.split('\0').filter(Boolean)) {
      const file=await this.resolve(root,rel),stat=await fs.stat(file);
      if(stat.size>50*1024*1024)fail('存在超过 50 MB 的未跟踪文件，请先在电脑处理');
      contents.push(hash(await fs.readFile(file)));
    }
    const head=await this.git(root,['rev-parse','HEAD']).catch(()=>'');
    return hash(head+raw+tracked+untracked+contents.join('|'));
  }
  async diff(args) {
    const root=await this.root(args),rel=text(args.path,'文件路径',4096);await this.resolve(root,rel,true);
    const scope=args.scope??'working';if(!['working','staged'].includes(scope))fail('差异类型无效');
    const tracked=await this.git(root,['ls-files','--',rel]);let diff;
    if(!tracked.trim()) {
      const file=await this.resolve(root,rel),data=await fs.readFile(file);
      if(data.length>512*1024)fail('文件过大，请在电脑查看差异');
      diff=data.includes(0)?'Binary file added':`--- /dev/null\n+++ b/${rel}\n@@ -0,0 +1 @@\n${data.toString('utf8').split('\n').map(s=>'+'+s).join('\n')}`;
    } else diff=await this.git(root,['diff','--no-ext-diff','--no-color',...(scope==='staged'?['--cached']:[]),'--',rel]);
    return {path:rel,scope,diff:diff.slice(0,700000),truncated:diff.length>700000};
  }
  async serial(root,task){const before=this.queues.get(root)??Promise.resolve();const next=before.catch(()=>{}).then(task);this.queues.set(root,next);try{return await next;}finally{if(this.queues.get(root)===next)this.queues.delete(root);}}
  async commit(args) {
    const root=await this.root(args);
    return this.serial(root,async()=>{
      const status=await this.status(args);
      if(status.available===false)fail(status.message);
      if(!args.revision||args.revision!==status.revision)fail('工作区已变化，请重新检查差异后提交');
      const message=text(args.message,'提交说明',2000);
      if(!Array.isArray(args.paths)||!args.paths.length||args.paths.length>200)fail('请选择要提交的文件');
      if(status.files.some(f=>f.staged&&!args.paths.includes(f.path)))fail('暂存区包含未选文件，请先在电脑处理，防止意外提交');
      for(const rel of args.paths){text(rel,'文件路径',4096);await this.resolve(root,rel,true);if(!status.files.some(f=>f.path===rel))fail('所选文件已变化');}
      await this.git(root,['add','--',...args.paths],{mutating:true});
      const output=await this.git(root,['commit','-m',message],{mutating:true});
      return {submitted:true,output,...await this.status(args)};
    });
  }
  async push(args) {
    const root=await this.root(args);return this.serial(root,async()=>{
      const status=await this.status(args);
      if(status.available===false)fail(status.message);
      if(!args.head||status.head!==args.head||status.branch!==args.branch)fail('分支或提交已变化，请刷新后推送');
      if(!status.upstream)fail('当前分支没有上游，请先在电脑设置上游分支');
      const output=await this.git(root,['push'],{mutating:true});return {submitted:true,output};
    });
  }
  async createPr(args) {
    const root=await this.root(args);return this.serial(root,async()=>{
      const status=await this.status(args);
      if(status.available===false)fail(status.message);
      if(status.head!==args.head||status.branch!==args.branch)fail('分支已变化，请刷新后创建 PR');
      const base=text(args.base,'目标分支',200),title=text(args.title,'PR 标题',250);
      if(base.startsWith('-')||!status.branches.includes(base)||status.branch==='(detached HEAD)')fail('请选择有效的目标分支');
      if(typeof args.body!=='string'||args.body.length>30000)fail('PR 描述无效');
      let result;try{result=await this.run('gh',['pr','create','--base',base.replace(/^(origin|upstream)\//,''),'--head',status.branch,'--title',title,'--body',args.body,'--draft'],{cwd:root,encoding:'utf8',timeout:60000,maxBuffer:1024*1024,windowsHide:true,env:{...process.env,GH_PROMPT_DISABLED:'1'}});}catch(e){throw Object.assign(new Error(e.code==='ENOENT'?'电脑需要安装 GitHub CLI 并登录后才能创建 PR':String(e.stderr||e.message).slice(0,2000)),{notSubmitted:e.code==='ENOENT',uncertain:e.code!=='ENOENT'});}
      const url=result.stdout.trim();if(!/^https:\/\/(?:github\.com|[^/]+)\/[^/]+\/[^/]+\/pull\/\d+$/.test(url))throw Object.assign(new Error('PR 操作已返回，请在电脑检查结果'),{uncertain:true});
      return {url,submitted:true,draft:true};
    });
  }
  async call(action,args){try{return await this[({'workspace.list':'list','workspace.search':'search','workspace.read':'read','git.status':'status','git.diff':'diff','git.commit':'commit','git.push':'push','git.pr.create':'createPr'})[action]](args);}catch(e){if(!e.uncertain)e.notSubmitted=true;throw e;}}
}
