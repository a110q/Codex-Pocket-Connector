import { readFileSync } from 'node:fs';

const fail = message => Object.assign(new Error(message), { status: 400 });
const string = (value, max, label) => {
  if (typeof value !== 'string' || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)) throw fail(`${label}格式不正确`);
  return value.trim();
};
function image(value) {
  if (value === '') return '';
  if (typeof value !== 'string' || value.length > 700000 || !/^data:image\/(png|jpeg);base64,(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) throw fail('二维码需要 PNG 或 JPEG 图片，单张不超过 512 KB');
  const bytes = Buffer.from(value.split(',')[1], 'base64');
  let width = 0, height = 0;
  if (value.startsWith('data:image/png;') && bytes.length >= 33 && bytes.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex')) && bytes.toString('ascii',12,16) === 'IHDR') {
    width = bytes.readUInt32BE(16); height = bytes.readUInt32BE(20);
  } else if (value.startsWith('data:image/jpeg;') && bytes[0] === 255 && bytes[1] === 216) {
    for (let offset = 2; offset + 4 <= bytes.length;) {
      if (bytes[offset++] !== 255) break;
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 218 || marker === 217) break;
      if (offset + 2 > bytes.length) break;
      const length = bytes.readUInt16BE(offset);
      if (length < 2 || offset + length > bytes.length) break;
      if ([192,193,194].includes(marker) && length >= 8) { height = bytes.readUInt16BE(offset+3); width = bytes.readUInt16BE(offset+5); break; }
      offset += length;
    }
  }
  if (bytes.length > 512*1024 || width < 64 || height < 64 || width > 4096 || height > 4096) throw fail('二维码图片无效，尺寸需要 64–4096 像素，单张不超过 512 KB');
  return value;
}
export function validateContacts(body) {
  const groupExpiresOn = string(body.groupExpiresOn,10,'群码有效期');
  if (groupExpiresOn && (!/^\d{4}-\d{2}-\d{2}$/.test(groupExpiresOn) || !Number.isFinite(Date.parse(groupExpiresOn)) || new Date(groupExpiresOn).toISOString().slice(0,10) !== groupExpiresOn)) throw fail('群码有效期请按年-月-日填写，例如 2026-11-06');
  return { schema:1, title:string(body.title,60,'标题'), description:string(body.description,500,'联系说明'), wechatId:string(body.wechatId,64,'微信号'), personalQr:image(body.personalQr), groupQr:image(body.groupQr), groupExpiresOn };
}
export const defaultContacts = Object.freeze({ ...validateContacts(JSON.parse(readFileSync(new URL('./contact-defaults.json',import.meta.url),'utf8'))), updatedAt:0 });
