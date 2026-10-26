import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { gunzipSync } from 'node:zlib';
import { PreviewTunnel } from '../preview-tunnel.mjs';

async function collect(tunnel, args) {
  const parts = [];
  for (let n = 0; n < 50; n++) {
    const part = await tunnel.read(args); parts.push(Buffer.from(part.data, 'base64'));
    if (part.done) return Buffer.concat(parts);
  }
  throw new Error('Response did not finish');
}
test('preview transports real HTTP forms, cookies and large bodies without rewriting website content', async () => {
  const server = http.createServer(async (req, res) => {
    const chunks = []; for await (const part of req) chunks.push(part);
    res.setHeader('Set-Cookie', 'qa=passed; HttpOnly'); res.setHeader('Connection', 'close');
    res.end(JSON.stringify({ method: req.method, body: Buffer.concat(chunks).toString(), cookie: req.headers.cookie }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const tunnel = new PreviewTunnel();
  try {
    const { previewId } = tunnel.open({ url: `http://127.0.0.1:${server.address().port}/` });
    const { socketId } = await tunnel.connect({ previewId, host: '127.0.0.1', port: server.address().port });
    const args = { previewId, socketId }, body = 'field=hello%20phone';
    await tunnel.write({ ...args, data: Buffer.from(`POST /submit HTTP/1.1\r\nHost: localhost\r\nCookie: session=qa\r\nContent-Length: ${body.length}\r\n\r\n${body}`).toString('base64') });
    const response = (await collect(tunnel, args)).toString();
    assert.match(response, /200 OK/); assert.match(response, /Set-Cookie: qa=passed/);
    assert.match(response, /"method":"POST"/); assert.match(response, /field=hello%20phone/); assert.match(response, /session=qa/);
    await assert.rejects(tunnel.connect({ previewId, host: '127.0.0.1', port: 22 }), /本地服务/);
    await assert.rejects(tunnel.connect({ previewId: 'unauthorized', host: '127.0.0.1', port: server.address().port }), /已断开/);
  } finally { tunnel.close(); await new Promise(resolve => server.close(resolve)); }
});
test('preview preserves binary streams for TLS and WebSockets; replacing session closes old sockets', async () => {
  const server = net.createServer(socket => socket.pipe(socket));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const tunnel = new PreviewTunnel();
  try {
    const { previewId } = tunnel.open({ url: `https://127.0.0.1:${server.address().port}/` });
    const { socketId } = await tunnel.connect({ previewId, host: '127.0.0.1', port: server.address().port });
    const data = Buffer.alloc(192 * 1024, 128); data[0] = 0; data[1] = 255;
    await tunnel.write({ previewId, socketId, data: data.toString('base64') });
    const chunks = []; let size = 0;
    while (size < data.length) { const part = await tunnel.read({ previewId, socketId }); const b = Buffer.from(part.data, 'base64'); chunks.push(b); size += b.length; }
    assert.deepEqual(Buffer.concat(chunks), data);
    tunnel.open({ url: 'https://example.com' });
    assert.equal(tunnel.sockets.size, 0);
    await assert.rejects(tunnel.read({ previewId, socketId }), /已断开/);
    assert.throws(() => tunnel.open({ url: 'file:///etc/passwd' }), /HTTP/);
    assert.throws(() => tunnel.open({ url: 'http://localhost:43785' }), /管理端口/);
  } finally { tunnel.close(); await new Promise(resolve => server.close(resolve)); }
});

test('concurrent DNS completions cannot exceed the preview connection limit', async () => {
  const server = net.createServer(socket => socket.on('error', () => {}));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const tunnel = new PreviewTunnel();
  try {
    const { previewId } = tunnel.open({ url: `http://127.0.0.1:${server.address().port}` });
    const results = await Promise.allSettled(Array.from({ length: 20 }, () => tunnel.connect({ previewId, host: '127.0.0.1', port: server.address().port })));
    assert.equal(results.filter(r => r.status === 'fulfilled').length, 16);
    assert.equal(tunnel.sockets.size, 16);
    for (const r of results.filter(r => r.status === 'rejected')) assert.match(r.reason.message, /连接过多/);
  } finally { tunnel.close(); await new Promise(resolve => server.close(resolve)); }
});

test('websites cannot reach unspecified, mapped, private, or shared network targets', async () => {
  const tunnel = new PreviewTunnel();
  try {
    const { previewId } = tunnel.open({ url: 'https://example.com' });
    for (const host of ['::', '::1', '::ffff:127.0.0.1', '100.64.0.1', '169.254.169.254', '192.168.1.1', '224.0.0.1']) {
      await assert.rejects(tunnel.connect({ previewId, host, port: 80 }), /内网/);
    }
    assert.equal(tunnel.sockets.size, 0);
  } finally { tunnel.close(); }
});

test('opt-in compression preserves a large module byte-for-byte and old phones still get raw bytes', async () => {
  const source=Buffer.from('export const data='+JSON.stringify(Array.from({length:12000},(_,i)=>({id:i,label:'Module fixture'})))+';');
  const server=net.createServer(socket=>socket.end(source));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const tunnel=new PreviewTunnel();
  try {
    for(const encoding of [undefined,'gzip']) {
      const {previewId}=tunnel.open({url:`http://127.0.0.1:${server.address().port}/`});
      const {socketId}=await tunnel.connect({previewId,host:'127.0.0.1',port:server.address().port});
      const parts=[];let transferred=0,compressed=0;
      while(true){
        const part=await tunnel.read({previewId,socketId,encoding});const bytes=Buffer.from(part.data,'base64');transferred+=bytes.length;
        if(part.encoding){assert.equal(part.encoding,'gzip');assert.equal(encoding,'gzip');compressed++;parts.push(gunzipSync(bytes));}else parts.push(bytes);
        if(part.done)break;
      }
      assert.deepEqual(Buffer.concat(parts),source);
      if(encoding){assert(compressed>0);assert(transferred<source.length/5);}else assert.equal(transferred,source.length);
    }
  }finally{tunnel.close();await new Promise(resolve=>server.close(resolve));}
});
