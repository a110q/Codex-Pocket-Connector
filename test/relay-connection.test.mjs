import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { once } from 'node:events';
import { WebSocketServer } from 'ws';
import { RelayConnection } from '../relay-connection.mjs';
import { CipherChannel, secret } from '../protocol.mjs';

async function until(condition, timeout = 5000) {
  const end = Date.now() + timeout;
  while (!condition()) { if (Date.now() > end) throw new Error('Connection recovery timed out'); await new Promise(resolve => setTimeout(resolve, 10)); }
}

test('silent TCP connection is replaced after missing pong and encrypted traffic resumes with the same credentials', async t => {
  const server = new WebSocketServer({ port: 0, host: '127.0.0.1', autoPong: false });
  await once(server, 'listening');
  const token = secret(), hostId = secret().slice(0,32), key = secret();
  const receiver = new CipherChannel(key,hostId,'mobile'), sender = new CipherChannel(key,hostId,'desktop');
  const accepted = [], failures = []; let healthy = false, reply;
  server.on('connection', (socket, request) => {
    accepted.push(request.headers.authorization);
    socket.on('error',()=>{});
    socket.on('ping', data => { if (healthy) socket.pong(data); });
    socket.on('message', data => { const payload=receiver.decrypt(JSON.parse(data));socket.send(JSON.stringify(receiver.encrypt({type:'ack',value:payload.value}))); });
  });
  const link = new RelayConnection(`ws://127.0.0.1:${server.address().port}`,{headers:{Authorization:`Bearer ${token}`}}, {
    pingIntervalMs: 40,pongTimeoutMs: 30,retryMinMs: 20,retryMaxMs: 40,
    onDisconnect: reason => { failures.push(reason);healthy=true; },
    onMessage: bytes => { reply=sender.decrypt(JSON.parse(bytes)); }
  });
  t.after(async()=>{link.close();for(const socket of server.clients)socket.terminate();await new Promise(resolve=>server.close(resolve));});
  link.start();
  await until(()=>accepted.length>=2 && link.online);
  assert.equal(failures[0],'heartbeat-timeout');
  assert(accepted.every(value=>value===`Bearer ${token}`));
  assert(link.send(JSON.stringify(sender.encrypt({type:'read-only-probe',value:'RECOVERED'}))));
  await until(()=>reply);
  assert.equal(reply.value,'RECOVERED');
  await new Promise(resolve=>setTimeout(resolve,160));
  assert.equal(accepted.length,2);assert.equal(link.online,true);
  const count=accepted.length;link.close();await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(accepted.length,count);assert.equal(link.state,'stopped');
});

test('an upgrade that never replies has a deadline and retry stops on shutdown', async t => {
  const sockets = new Set();let attempts=0;
  const server=net.createServer(socket=>{sockets.add(socket);attempts++;socket.on('error',()=>{});socket.on('data',()=>{});socket.on('close',()=>sockets.delete(socket));});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const link=new RelayConnection(`ws://127.0.0.1:${server.address().port}`,{}, {handshakeTimeoutMs:60,retryMinMs:20,retryMaxMs:30});
  t.after(async()=>{link.close();for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));});
  link.start();await until(()=>attempts>=2);
  assert.equal(link.online,false);
  link.close();const count=attempts;await new Promise(resolve=>setTimeout(resolve,130));assert.equal(attempts,count);
});
