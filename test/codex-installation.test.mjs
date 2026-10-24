import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync, readFileSync, rmSync, chmodSync } from 'node:fs';
import { resolve } from 'node:path';
import { CodexInstallation, resolveCodexCLI } from '../codex-installation.mjs';

test('Windows CLI discovery separates a moved Node runtime from standard and selected Codex installs',()=>{
  const cli='D:\\软件 安装\\Codex\\app\\resources\\codex.exe';
  const options={platform:'win32',executable:'C:\\Users\\user\\AppData\\Local\\OpenAI\\Codex\\runtimes\\cua_node\\aabbccddeeff0011\\bin\\node.exe',env:{POCKET_CODEX_ROOTS:JSON.stringify(['D:\\软件 安装\\Codex'])},exists:p=>p===cli};
  assert.equal(resolveCodexCLI(options),cli);
  assert.throws(()=>resolveCodexCLI({...options,selected:'D:\\missing\\Codex'}),/未找到/,'an invalid manual selection must not silently fall back to another installation');
  assert.equal(resolveCodexCLI({...options,env:{},selected:'D:\\软件 安装\\Codex\\Codex.exe'}),cli);
  assert.equal(resolveCodexCLI({...options,env:{},selected:'D:\\软件 安装\\Codex\\app\\resources'}),cli);
  assert.throws(()=>resolveCodexCLI({...options,env:{},exists:p=>p==='D:\\软件 安装\\Codex\\Codex.exe'}),/未找到/,'do not infer the GUI executable as app-server');
});
test('a selected installation is version-checked, saved privately, and resolved after process restart',async t=>{
  mkdirSync('.private/tests',{recursive:true});const state=mkdtempSync(resolve('.private/tests/install-'));t.after(()=>rmSync(state,{recursive:true,force:true}));
  const root=resolve(state,'中文 空格 Codex.app'),binary=resolve(root,'Contents/Resources/codex-cli/bin/codex');mkdirSync(resolve(binary,'..'),{recursive:true});writeFileSync(binary,'#!/bin/sh\nprintf "codex-cli 1.2.3\\n"\n');chmodSync(binary,0o700);
  const install=new CodexInstallation(state,{env:{},executable:'/not/node',platform:'darwin'});await install.save(root);
  assert.equal(new CodexInstallation(state,{platform:'darwin'}).binary(),binary);assert.equal(JSON.parse(readFileSync(resolve(state,'codex-installation.json'),'utf8')).selectedPath,root);
  await assert.rejects(install.save('relative'),/完整/);
  writeFileSync(binary,'#!/bin/sh\nprintf "not a CLI\\n"\n');await assert.rejects(install.save(binary),/不是 Codex CLI/);
  assert.equal(install.selected(),root,'a failed selection must not overwrite the working path');
});
