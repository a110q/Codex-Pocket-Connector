import path from 'node:path';
import os from 'node:os';
import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';

const execute = promisify(execFile);
export function codexCandidates({ selected = '', env = process.env, executable = process.execPath, platform = process.platform } = {}) {
  const p = platform === 'win32' ? path.win32 : path.posix;
  const windows = platform === 'win32';
  const roots = [];
  if (selected && p.isAbsolute(selected)) {
    roots.push(selected, p.dirname(selected));
    // Selections may point to Codex.exe, resources, codex-cli/bin, or the app.
    let parent = p.dirname(selected);
    for (let i=0; i<3; i++) { roots.push(parent); parent=p.dirname(parent); }
  }
  if(!selected) try { const supplied=JSON.parse(env.POCKET_CODEX_ROOTS || '[]'); if(Array.isArray(supplied)) roots.push(...supplied.filter(x=>typeof x==='string'&&p.isAbsolute(x))); } catch {}
  if (!selected && platform === 'darwin') roots.push('/Applications/Codex.app', '/Applications/ChatGPT.app', p.join(os.homedir(),'Applications/Codex.app'), p.join(os.homedir(),'Applications/ChatGPT.app'));
  if (!selected && windows) {
    if (env.LOCALAPPDATA) for(const r of ['Programs/Codex','Programs/ChatGPT','OpenAI/Codex']) roots.push(p.join(env.LOCALAPPDATA,r));
    if (env.ProgramFiles) for(const r of ['Codex','ChatGPT']) roots.push(p.join(env.ProgramFiles,r));
  }
  if(!selected) roots.push(p.resolve(p.dirname(executable),'..','..'));
  const names = windows ? ['codex.exe'] : ['codex'];
  const layouts = ['Contents/Resources/codex-cli/bin','Contents/Resources/codex-cli','resources/codex-cli/bin','app/resources/codex-cli/bin','resources/codex-cli','app/resources/codex-cli','resources','app/resources','codex-cli/bin','codex-cli'];
  const result = selected ? [] : [env.POCKET_CODEX_CLI];
  for (const root of [...new Set(roots)]) for(const layout of layouts) for(const name of names) result.push(p.join(root,layout,name));
  // Only an explicit file selection may be a standalone CLI. Installed GUI
  // Codex.exe is considered after the known resources layouts, never inferred.
  if (selected && names.includes(p.basename(selected).toLowerCase())) result.push(selected);
  return [...new Set(result.filter(x=>x&&p.isAbsolute(x)))];
}
export function resolveCodexCLI(options = {}) {
  const exists=options.exists ?? existsSync;
  const found=codexCandidates(options).find(exists);
  if (!found) throw new Error('未找到 Codex 运行程序。请选择 Codex 安装目录，或安装并登录 Codex 后重试。');
  return found;
}
export class CodexInstallation {
  constructor(state, { platform=process.platform, run=execute, exists=existsSync, env=process.env, executable=process.execPath }={}) {
    Object.assign(this,{file:path.resolve(state,'codex-installation.json'),platform,run,exists,env,executable}); this.busy=false;
  }
  selected() { try { return JSON.parse(readFileSync(this.file,'utf8')).selectedPath || ''; } catch { return ''; } }
  binary() { return resolveCodexCLI({selected:this.selected(),platform:this.platform,exists:this.exists,env:this.env,executable:this.executable}); }
  state() { let binary=''; try {binary=this.binary()} catch {} return { selectedPath:this.selected(), binary, detected:!!binary }; }
  async save(selectedPath) {
    if(this.busy) throw new Error('正在检查安装目录，请稍候');
    const p=this.platform==='win32'?path.win32:path.posix;
    if(typeof selectedPath!=='string'||selectedPath.length>4096||!p.isAbsolute(selectedPath)||/[\x00-\x1f]/.test(selectedPath)) throw new Error('请选择完整的 Codex 安装路径');
    this.busy=true;
    try {
      const binary=resolveCodexCLI({selected:selectedPath,platform:this.platform,exists:this.exists,env:this.env,executable:this.executable});
      let output;
      try {output=await this.run(binary,['--version'],{timeout:5000,maxBuffer:16384,windowsHide:true})} catch {throw new Error('选中的目录没有可用的 Codex CLI，请选择官方 Codex 安装目录');}
      if(!/^codex-cli\s+\S+/m.test(output.stdout)) throw new Error('选中的程序不是 Codex CLI，请选择 Codex 安装目录');
      mkdirSync(path.dirname(this.file),{recursive:true,mode:0o700});
      const temp=this.file+'.'+randomUUID()+'.tmp';writeFileSync(temp,JSON.stringify({selectedPath,binary},null,2),{mode:0o600,flag:'wx'});renameSync(temp,this.file);
      return this.state();
    } finally { this.busy=false; }
  }
  async pick() {
    let result;
    if(this.platform==='win32') {
      const script="Add-Type -AssemblyName System.Windows.Forms; $d=New-Object System.Windows.Forms.FolderBrowserDialog; $d.Description='Select Codex installation folder'; $d.ShowNewFolderButton=$false; if($d.ShowDialog() -eq 'OK'){[Console]::OutputEncoding=New-Object Text.UTF8Encoding($false);[Console]::Write($d.SelectedPath)}";
      result=await this.run('powershell.exe',['-STA','-NoProfile','-NonInteractive','-EncodedCommand',Buffer.from(script,'utf16le').toString('base64')],{timeout:120000,maxBuffer:16384,windowsHide:true});
    } else if(this.platform==='darwin') {
      try {result=await this.run('/usr/bin/osascript',['-e','POSIX path of (choose folder with prompt "选择 Codex.app 所在的目录，或 Codex.app 安装目录")'],{timeout:120000,maxBuffer:16384});} catch(e) {if(e.stderr?.includes('(-128)'))return {cancelled:true};throw e;}
    } else throw new Error('请在下方输入 Codex 安装路径');
    const selectedPath=result.stdout.trim();return selectedPath ? {selectedPath} : {cancelled:true};
  }
}
