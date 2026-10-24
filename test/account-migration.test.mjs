import test from 'node:test';import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';import {Accounts} from '../accounts.mjs';
test('the one-computer schema migrates losslessly and supports multiple owned computers',()=>{
  const db=new DatabaseSync(':memory:');
  db.exec(`CREATE TABLE accounts(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,display_name TEXT NOT NULL,password_hash TEXT NOT NULL,role TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,rejection_reason TEXT NOT NULL DEFAULT '',expires_at INTEGER,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL);
    INSERT INTO accounts VALUES('u','tester','Tester','private-hash','user','active','test','',NULL,1,1);
    CREATE TABLE account_hosts(host_id TEXT PRIMARY KEY,user_id TEXT UNIQUE NOT NULL);
    INSERT INTO account_hosts VALUES('old-computer','u');`);
  const original=db.prepare('SELECT * FROM accounts').get();
  const accounts=new Accounts(db);const {max_hosts,...after}=accounts.row('u');
  assert.deepEqual({...after},{...original});assert.equal(max_hosts,5);assert.equal(accounts.owner('old-computer'),'u');
  accounts.bindHost('u','new-computer');assert.equal(accounts.owner('new-computer'),'u');
  new Accounts(db);assert.equal(db.prepare('SELECT count(*) n FROM account_hosts').get().n,2);db.close();
});
