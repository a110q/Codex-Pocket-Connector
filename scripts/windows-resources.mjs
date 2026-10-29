import { readFileSync, writeFileSync } from 'node:fs';
import { versionParts } from '../version.mjs';
const pad = data => Buffer.concat([data, Buffer.alloc((4-data.length%4)%4)]);
const utf16 = value => Buffer.from(value+'\0','utf16le');
function resource(type, name, data) {
  const h=Buffer.alloc(32);h.writeUInt32LE(data.length,0);h.writeUInt32LE(32,4);h.writeUInt16LE(0xffff,8);h.writeUInt16LE(type,10);h.writeUInt16LE(0xffff,12);h.writeUInt16LE(name,14);h.writeUInt16LE(0x1030,20);h.writeUInt16LE(0x0409,22);
  return Buffer.concat([h,pad(data)]);
}
function versionInfo(parts) {
  const fixed=Buffer.alloc(52);
  [0xfeef04bd,0x10000,(parts[0]<<16)|parts[1],(parts[2]<<16)|parts[3],(parts[0]<<16)|parts[1],(parts[2]<<16)|parts[3],0x3f,0,0x40004,1,0,0,0].forEach((v,i)=>fixed.writeUInt32LE(v>>>0,i*4));
  const header=Buffer.alloc(6),key=pad(Buffer.concat([header,utf16('VS_VERSION_INFO')]));
  header.writeUInt16LE(key.length+fixed.length,0);header.writeUInt16LE(fixed.length,2);
  // pad() copied the header before it was filled.
  header.copy(key);return Buffer.concat([key,fixed]);
}
export function windowsResources({output,icon,version,payload}) {
  const parts=versionParts(version).map(value=>{if(value>65535n)throw Error('Windows version component exceeds 65535');return Number(value)});
  while(parts.length<4)parts.push(0);
  const nullHeader=Buffer.alloc(32);nullHeader.writeUInt32LE(32,4);nullHeader.writeUInt16LE(0xffff,8);nullHeader.writeUInt16LE(0xffff,12);
  const ico=readFileSync(icon);if(ico.readUInt16LE(2)!==1||ico.readUInt16LE(4)!==1)throw Error('Expected single-image Windows icon');
  const size=ico.readUInt32LE(14),offset=ico.readUInt32LE(18),image=ico.subarray(offset,offset+size);
  const group=Buffer.alloc(20);group.writeUInt16LE(1,2);group.writeUInt16LE(1,4);ico.copy(group,6,6,18);group.writeUInt16LE(1,18);
  const manifest=Buffer.from(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0"><assemblyIdentity name="CodexPocket.Connector" version="${parts.join('.')}" processorArchitecture="amd64" type="win32"/><description>Codex Pocket</description><trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security></trustInfo><compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application><supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/></application></compatibility><application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings><dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true</dpiAware><longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware></windowsSettings></application></assembly>`);
  const chunks=[nullHeader,resource(3,1,image),resource(14,1,group),resource(16,1,versionInfo(parts)),resource(24,1,manifest)];if(payload)chunks.push(resource(10,101,readFileSync(payload)));writeFileSync(output,Buffer.concat(chunks));
}
