// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { lanServer } from './connection-data.mjs';
import { fetchIdentityPin } from './identity.mjs';
import { openOrderedChannel } from './transport.mjs';
import { MAX_UI_BYTES } from './verify-ui.mjs';
const UI_LIMIT = 4 + 8192 + MAX_UI_BYTES, PREVIEW_LIMIT = 2 * 1024 * 1024;
const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const encoder = new TextEncoder();
const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
function deferred() {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  promise.catch(() => {});
  return { promise, resolve, reject };
}
function deadline(signal) {
  const controller = new AbortController(), stopped = deferred(), until = performance.now() + 5000;
  function cancel(reason) { if (!controller.signal.aborted) { controller.abort(reason); stopped.reject(reason); } }
  const abort = () => cancel(new DOMException('PC operation cancelled', 'AbortError'));
  const timeout = () => cancel(new DOMException('PC operation timed out', 'TimeoutError'));
  const timer = setTimeout(timeout, 5000);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  function check() {
    if (performance.now() >= until) timeout();
    if (controller.signal.aborted) throw controller.signal.reason;
  }
  return { signal: controller.signal, check,
    async wait(promise) { check(); const value = await Promise.race([promise, stopped.promise]); check(); return value; },
    dispose() { clearTimeout(timer); signal?.removeEventListener('abort', abort); },
  };
}

// The caller supplies a successfully persisted, verified registration. Credentials
// and sessions stay in the parent; events contain only existing UI protocol data.
export function createDeviceConnection({ device, events }) {
  const server = lanServer(device?.host, device?.port);
  if (globalThis.isSecureContext === false || typeof WebTransport !== 'function' || !crypto?.subtle)
    throw Error('Secure WebTransport is unavailable');
  if (!Number.isInteger(device.grants) || device.grants < 1 || device.grants > 7 || !(device.grants & 1) ||
      !(device.id instanceof Uint8Array) || device.id.length !== 16 || !device.id.some(b => b) ||
      !(device.secret instanceof Uint8Array) || device.secret.length !== 32 || !device.secret.some(b => b) ||
      !(device.spki instanceof Uint8Array) || device.spki.length < 1 || device.spki.length > 256)
    throw Error('Invalid remote registration');
  for (const name of ['open', 'close', 'text', 'cursor', 'controllerText', 'controllerClose'])
    if (typeof events?.[name] !== 'function') throw Error('Missing connection callback');
  const grants = device.grants, port = device.port, spki = new Uint8Array(device.spki), secret = new Uint8Array(device.secret);
  let authorization = 'Device ' + hex(device.id) + '.' + hex(secret), closed = false;
  const owners = { control: null, controller: null };
  const operations = new Set();
  let previewPending = false;
  async function transport(run) {
    const pin = await run.wait(fetchIdentityPin(server + '/identity-v2', spki, { signal: run.signal }));
    run.check();
    const url = new URL(server); url.protocol = 'https:'; url.port = String(port); url.pathname = '/remote-v2';
    const wt = new WebTransport(url.href, { serverCertificateHashes: [{ algorithm: 'sha-256', value: pin.certificateHash }] });
    wt.closed.catch(() => {});
    try { await run.wait(wt.ready); return wt; }
    catch (error) { wt.close(); throw error; }
  }
  async function oneReply(route, maximum, signal, shared) {
    const cancel = new AbortController(), run = deadline(cancel.signal), reply = deferred();
    let wt = shared, channel, value, sent = false;
    const abort = () => cancel.abort();
    operations.add(cancel);
    signal?.addEventListener('abort', abort, { once: true });
    if (closed || signal?.aborted) abort();
    try {
      run.check();
      if (!wt) wt = await transport(run);
      run.check();
      channel = openOrderedChannel(wt, { maxTextBytes: 256, maxBinaryBytes: maximum,
        maxPendingBytes: 1024, maxPendingMessages: 4,
        onText() { throw Error('Unexpected text response'); }, onPong() {},
        onBinary(bytes) {
          if (!sent || value) throw Error('Unexpected additional response');
          value = bytes;
        },
        onClose(error) { error || !value ? reply.reject(Error('Incomplete PC response')) : reply.resolve(value); },
      });
      await run.wait(channel.ready); sent = true;
      if (!channel.sendText(route + '\n' + authorization)) throw Error('PC request rejected');
      return await run.wait(reply.promise);
    } finally {
      channel?.stop(); if (!shared) wt?.close();
      run.dispose(); operations.delete(cancel); signal?.removeEventListener('abort', abort);
    }
  }
  function stop(name) {
    const owner = owners[name];
    if (!owner) return;
    owners[name] = null; owner.wanted = false; clearTimeout(owner.retry);
    owner.active?.abort.abort(); owner.active?.channel?.stop(); owner.active?.wt?.close();
    if (name === 'control' && owner.active && !owner.active.notified) {
      owner.active.notified = true; events.close();
    }
  }
  function current(name, owner, active) { return !closed && owners[name] === owner && owner.active === active; }
  async function launch(name, owner) {
    const active = { abort: new AbortController(), channel: null, wt: null, finished: false, busy: false, notified: false };
    owner.active = active;
    const run = deadline(active.abort.signal), first = deferred();
    function finish() {
      if (active.finished) return;
      active.finished = true; first.reject(Error('PC channel closed'));
      active.abort.abort(); active.channel?.stop(); active.wt?.close();
      if (!current(name, owner, active)) return;
      if (name === 'control') {
        if (!active.notified) { active.notified = true; events.close(); }
        if (owner.wanted) owner.retry = setTimeout(() => { if (!closed && owners[name] === owner) void launch(name, owner); }, 1200);
      } else {
        owners[name] = null;
        // The UI handles busy by selecting another hand. A second close event
        // would cancel that new request before its generation reaches us.
        if (!active.busy) events.controllerClose();
      }
    }
    try {
      active.wt = await transport(run);
      if (!current(name, owner, active)) { active.wt.close(); return; }
      let sent = false, opened = false;
      active.channel = openOrderedChannel(active.wt, {
        maxTextBytes: 256 * 1024, maxBinaryBytes: name === 'control' ? 4 * 1024 * 1024 + 40 : 0,
        maxPendingBytes: 4 * 1024 * 1024, maxPendingMessages: 64, onPong() {}, onClose: finish,
        onText(text) {
          if (!sent || !current(name, owner, active)) throw Error('Unexpected channel response');
          if (!opened) { run.check(); opened = true; first.resolve(); if (name === 'control') events.open(); }
          if (!current(name, owner, active)) return;
          try {
            const type = JSON.parse(text)?.t;
            if (type === 'busy' || type === 'replaced') { owner.wanted = false; active.busy = true; }
          } catch {}
          name === 'control' ? events.text(text) : events.controllerText(text);
        },
        onBinary(bytes) {
          if (!opened || !current(name, owner, active)) throw Error('Unexpected cursor response');
          events.cursor(bytes);
        },
      });
      await run.wait(active.channel.ready); sent = true;
      const route = name === 'control' ? '/ws' : '/controller-' + owner.hand + '-v1';
      if (!active.channel.sendText(route + '\n' + authorization)) throw Error('PC channel rejected');
      await run.wait(first.promise);
    } catch { finish(); }
    finally { run.dispose(); }
  }
  function start(name, hand) {
    if (closed) return;
    if (name !== 'control' && name !== 'controller') throw Error('Invalid logical channel');
    if (name === 'controller' && (!(grants & 4) || hand !== 'left' && hand !== 'right'))
      throw Error('Controller permission is unavailable');
    if (owners[name]?.wanted && (name === 'control' || owners[name].hand === hand)) return;
    stop(name);
    const owner = { wanted: true, hand, active: null, retry: null }; owners[name] = owner;
    // Synchronous queue admission lets a following UI stop cancel pending I/O.
    void launch(name, owner);
  }
  function send(name, text) {
    const active = owners[name]?.active;
    if (closed || !active?.channel?.isOpen) return false;
    if (name === 'controller' && (typeof text !== 'string' || text.length > 4096 || encoder.encode(text).length > 4096)) {
      stop(name); return false;
    }
    return active.channel.sendText(text);
  }
  function close() {
    if (closed) return;
    closed = true;
    for (const cancel of operations) cancel.abort();
    stop('control'); stop('controller'); secret.fill(0); authorization = '';
  }
  return Object.freeze({ close, hooks: Object.freeze({ start, stop, send,
    async preview(signal) {
      const active = owners.control?.active;
      if (closed || previewPending || !active?.channel?.isOpen) return '';
      previewPending = true;
      try { return decoder.decode(await oneReply('/preview', PREVIEW_LIMIT, signal, active.wt)); }
      finally { previewPending = false; }
    },
  }),
  async loadUi(signal) {
    const bytes = await oneReply('/remote-ui-v2', UI_LIMIT, signal);
    if (bytes.length < 6) throw Error('Invalid signed UI response');
    const length = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(0);
    if (length < 1 || length > 8192 || bytes.length <= 4 + length || bytes.length - 4 - length > MAX_UI_BYTES)
      throw Error('Invalid signed UI response');
    return { envelopeText: decoder.decode(bytes.subarray(4, 4 + length)), htmlBytes: bytes.slice(4 + length) };
  } });
}
