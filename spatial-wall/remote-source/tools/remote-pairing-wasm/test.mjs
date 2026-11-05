// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { createPairing } from './pairing.mjs';
import { runBrowserChecks } from './browser-checks.mjs';

const directory = path.resolve(process.argv[2] ?? 'build/remote-loader');
const { default: createModule } = await import(pathToFileURL(path.join(directory, 'pairing-core.mjs')));
const wasmBinary = await readFile(path.join(directory, 'pairing-core.wasm'));
const module = await createModule({ wasmBinary });
assert.equal(runBrowserChecks(module).checks, 4);
const code = new TextEncoder().encode('012345');
const context = Uint8Array.from({ length: 98 }, (_, index) => index);
context[0] = 2;
context[97] = 7;
let checks = 0;
function check(value, label) { assert.ok(value, label); checks++; }
function rejected(operation, label) { assert.throws(operation, undefined, label); checks++; }
const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
function changed(bytes, index = 0) { const result = bytes.slice(); result[index] ^= 1; return result; }

// Only this process's pipe sees test MACs/proofs. Failure output contains labels, never their values.
async function nativePeer(role, ctx, password) {
  const child = spawn(path.join(directory, 'pairing-native/pairing_pipe.exe'), [String(role)],
    { stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true, timeout: 5000 });
  const lines = createInterface({ input: child.stdout, crlfDelay: Infinity })[Symbol.asyncIterator]();
  const ended = new Promise((resolve, reject) => {
    child.once('error', () => reject(new Error('Native fixture failed to start')));
    child.once('exit', (status, signal) => resolve({ status, signal }));
  });
  ended.catch(() => {});
  child.stdin.on('error', () => {});
  const send = bytes => child.stdin.write(Buffer.from(bytes).toString('hex') + '\n');
  async function line() {
    const next = await lines.next();
    check(!next.done && next.value.length <= 64, 'bounded native fixture output');
    return next.value;
  }
  send(ctx); send(password);
  return {
    send, line,
    async bytes() {
      const text = await line();
      check(/^[0-9a-f]{64}$/.test(text), 'native fixture byte format');
      return new Uint8Array(Buffer.from(text, 'hex'));
    },
    async close(expectSuccess = true) {
      child.stdin.end();
      const result = await ended;
      if (expectSuccess) check(result.status === 0 && result.signal === null, 'native fixture completion');
    },
    kill: () => { child.stdin.destroy(); child.kill(); },
  };
}

async function exchange(nativeRole, { badCode = false, contextByte = -1, badServerMac = false, badClientMac = false, badProof = false } = {}) {
  const native = await nativePeer(nativeRole, context, code);
  const local = createPairing(module, 1 - nativeRole,
    contextByte < 0 ? context : changed(context, contextByte), badCode ? new TextEncoder().encode('012346') : code);
  try {
    native.send(local.message());
    local.processPeer(await native.bytes());
    if (nativeRole === 1) {
      let mac = await native.bytes();
      if (badServerMac) mac = changed(mac);
      if (badCode || contextByte >= 0 || badServerMac) {
        rejected(() => local.verifyConfirmation(mac), 'Wasm rejects unauthenticated server');
        rejected(() => local.enrollmentProof(), 'failed Wasm session cannot enroll');
        await native.close(false);
        return;
      }
      local.verifyConfirmation(mac);
      mac.fill(0);
      const confirmation = local.confirmation();
      native.send(badClientMac ? changed(confirmation) : confirmation);
      confirmation.fill(0);
      check(await native.line() === (badClientMac ? 'REJECT' : 'OK'), 'native client-MAC verification');
    } else {
      const confirmation = local.confirmation();
      native.send(badServerMac ? changed(confirmation) : confirmation);
      confirmation.fill(0);
      if (badCode || contextByte >= 0 || badServerMac) {
        check(await native.line() === 'REJECT', 'native rejects unauthenticated server');
        await native.close();
        return;
      }
      check(await native.line() === 'OK', 'native server-MAC verification');
      let mac = await native.bytes();
      if (badClientMac) mac = changed(mac);
      if (badClientMac) {
        rejected(() => local.verifyConfirmation(mac), 'Wasm rejects wrong client MAC');
        rejected(() => local.enrollmentProof(), 'failed Wasm server cannot enroll');
        await native.close(false);
        return;
      }
      local.verifyConfirmation(mac);
      mac.fill(0);
    }
    if (!badClientMac) {
      const proof = local.enrollmentProof(), expected = await native.bytes();
      check(same(proof, expected), 'native/Wasm enrollment proof equality');
      native.send(badProof ? changed(proof) : proof);
      check(await native.line() === (badProof ? 'PROOF_REJECT' : 'PROOF_OK'), 'native proof equality gate');
      proof.fill(0); expected.fill(0);
    }
    await native.close();
  } finally { local.close(); native.kill(); }
}

for (const role of [0, 1]) {
  await exchange(role);
  await exchange(role, { badCode: true });
  for (const contextByte of [1, 33, 65, 97]) await exchange(role, { contextByte });
  await exchange(role, { badServerMac: true });
  await exchange(role, { badClientMac: true });
  await exchange(role, { badProof: true });
}
for (const operation of ['confirmation', 'enrollmentProof']) {
  const session = createPairing(module, 0, context, code);
  rejected(() => session[operation](), 'early output rejected');
  rejected(() => session.message(), 'invalid state is terminal');
  session.close(); session.close();
}
const a = createPairing(module, 0, context, code), b = createPairing(module, 1, context, code);
try {
  const first = a.message();
  check(same(first, a.message()), 'message getter may repeat');
  check(!same(first, b.message()), 'independent random public values');
  a.processPeer(b.message());
  rejected(() => a.processPeer(b.message()), 'peer processed only once');
  rejected(() => a.verifyConfirmation(new Uint8Array(32)), 'failed session is terminal');
} finally { a.close(); b.close(); }
for (const parameters of [[2, context, code], [0, context.subarray(1), code], [0, context, code.subarray(1)]]) {
  rejected(() => createPairing(module, ...parameters), 'invalid construction rejected');
}
const invalid = createPairing(module, 0, context, code);
rejected(() => invalid.processPeer(new Uint8Array(31)), 'invalid peer length');
rejected(() => invalid.message(), 'invalid length frees session');
check(module.HEAPU8.byteLength === 16 * 1024 * 1024, 'fixed memory bound');
const pending = new Map();
const traced = new Proxy(module, { get(target, name) {
  if (name === '_malloc') return size => { const pointer = target._malloc(size); pending.set(pointer, size); return pointer; };
  if (name === '_free') return pointer => {
    const size = pending.get(pointer);
    check(size !== undefined && target.HEAPU8.subarray(pointer, pointer + size).every(byte => byte === 0), 'temporary heap buffers wiped');
    pending.delete(pointer); target._free(pointer);
  };
  return target[name];
} });
const tracedSession = createPairing(traced, 0, context, code);
tracedSession.message();
rejected(() => tracedSession.processPeer(new Uint8Array(31)), 'failed input buffer cleanup');
check(pending.size === 0, 'no heap adapter allocations retained');
let freeCalls = 0;
const trappingFree = new Proxy(module, { get(target, name) {
  if (name === '_nyan_pairing_free') return pointer => {
    freeCalls++; target._nyan_pairing_free(pointer); throw new Error('fixture release error');
  };
  return target[name];
} });
const released = createPairing(trappingFree, 0, context, code);
rejected(() => released.close(), 'release trap propagates');
released.close();
check(freeCalls === 1, 'release trap cannot cause double free');
rejected(() => released.message(), 'release trap leaves the session closed');
const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
Object.defineProperty(globalThis, 'crypto', { configurable: true, value: undefined });
try {
  const fresh = await createModule({ wasmBinary });
  rejected(() => createPairing(fresh, 0, context, code), 'missing secure randomness fails closed');
} finally { Object.defineProperty(globalThis, 'crypto', descriptor); }
console.log(`Product pairing native/Wasm checks passed: ${checks}; both roles, MACs and proofs; no raw key API`);
