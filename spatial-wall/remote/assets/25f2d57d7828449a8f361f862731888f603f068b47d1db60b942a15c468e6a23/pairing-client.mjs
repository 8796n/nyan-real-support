// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { createPairing } from './pairing.mjs';
import { openOrderedChannel } from './transport.mjs';
import { validGrants } from './connection-data.mjs';
const encoder = new TextEncoder();
const MAX_REPLY = 308, TIMEOUT = 5000;

function copy(bytes, size, name) {
  if (!(bytes instanceof Uint8Array) || bytes.length !== size) throw Error('Invalid ' + name);
  return new Uint8Array(bytes);
}
function labelBytes(label) {
  if (typeof label !== 'string' || !label.length || label.length > 96 || /[\x00-\x1f\x7f]/.test(label))
    throw Error('Invalid device label');
  const bytes = encoder.encode(label);
  if (bytes.length > 96 || new TextDecoder().decode(bytes) !== label) throw Error('Invalid device label');
  return bytes;
}
function endpoints(server) {
  // Parse the original authority before URL normalization can turn octal,
  // hexadecimal or shortened numeric addresses into another destination.
  if (typeof server !== 'string') throw Error('Invalid pairing server');
  const match = /^http:\/\/([A-Za-z0-9.-]{1,253})(?::([1-9][0-9]{0,4}))?\/?$/.exec(server);
  if (!match || match[0] !== server) throw Error('Invalid pairing server');
  const host = match[1].toLowerCase(), port = Number(match[2] || 80);
  if (port > 65535) throw Error('Invalid pairing port');
  if (/^[0-9.]+$/.test(host)) {
    const parts = host.split('.');
    if (parts.length !== 4 || parts.some(p => !/^(?:0|[1-9][0-9]{0,2})$/.test(p) || Number(p) > 255))
      throw Error('Invalid IPv4 server');
  } else if (!host.split('.').every(p => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(p))) {
    throw Error('Invalid pairing hostname');
  }
  const url = new URL(server);
  if (url.hostname !== host || url.username || url.password || url.search || url.hash || url.pathname !== '/')
    throw Error('Invalid pairing server');
  url.pathname = '/pair-v2';
  const pair = url.href;
  url.protocol = 'https:'; url.port = String(port); url.pathname = '/remote-v2';
  return { pair, enroll: url.href };
}
function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function same(a, b) { return a.length === b.length && a.every((v, i) => v === b[i]); }
function supported() {
  if (globalThis.isSecureContext === false || typeof WebTransport !== 'function' || !crypto?.subtle)
    throw Error('Secure WebTransport pairing is unavailable');
}

// One deadline covers HTTP bodies, crypto checks, ready, stream creation and the
// enrollment reply. A signal/deadline wins even if a transport ignores abort.
function attempt(signal) {
  const controller = new AbortController(), deadline = performance.now() + TIMEOUT;
  let rejectStopped;
  const stopped = new Promise((_, reject) => { rejectStopped = reject; });
  stopped.catch(() => {});
  const cancel = reason => {
    if (controller.signal.aborted) return;
    controller.abort(reason); rejectStopped(reason);
  };
  const abort = () => cancel(new DOMException('Pairing cancelled', 'AbortError'));
  const timeout = () => cancel(new DOMException('Pairing timed out', 'TimeoutError'));
  const timer = setTimeout(timeout, TIMEOUT);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  function check() {
    if (performance.now() >= deadline) timeout();
    if (controller.signal.aborted) throw controller.signal.reason;
  }
  return {
    signal: controller.signal,
    check,
    async wait(operation) { check(); const value = await Promise.race([operation, stopped]); check(); return value; },
    close() { clearTimeout(timer); signal?.removeEventListener('abort', abort); controller.abort(); },
  };
}

async function publicRequest(url, body, status, length, run) {
  run.check();
  let response, reader;
  try {
    response = await run.wait(fetch(url, {
      method: body ? 'POST' : 'GET', mode: 'cors', targetAddressSpace: 'local', credentials: 'omit',
      redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer', signal: run.signal,
      ...(body ? { body, headers: { 'Content-Type': 'application/octet-stream' } } : {}),
    }).then(value => {
      // Retain ownership before wait() checks cancellation. Abort can win
      // between fetch fulfillment and the awaiting caller's continuation.
      response = value;
      if (run.signal.aborted) { value.body?.cancel().catch(() => {}); throw run.signal.reason; }
      return value;
    }));
    const size = response.headers.get('Content-Length');
    if (response.status !== status || response.redirected ||
        (status === 204 ? size !== null && size !== '0' : size !== String(length)))
      throw Error('Pairing HTTP response rejected');
    if (status === 204 && !response.body) return new Uint8Array(0);
    // Some browsers expose an empty stream for 204. Drain it with the same
    // deadline and zero-byte bound instead of treating the stream as content.
    if (!response.body || status !== 204 &&
        response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/octet-stream')
      throw Error('Invalid pairing content type');
    reader = response.body.getReader();
    const bytes = new Uint8Array(length);
    let offset = 0;
    while (true) {
      const part = await run.wait(reader.read());
      if (part.done) break;
      if (!(part.value instanceof Uint8Array) || part.value.length > length - offset)
        throw Error('Pairing response exceeds its limit');
      bytes.set(part.value, offset); offset += part.value.length;
    }
    if (offset !== length) throw Error('Truncated pairing response');
    return bytes;
  } finally {
    if (reader) { reader.cancel().catch(() => {}); reader.releaseLock(); }
    else response?.body?.cancel().catch(() => {});
  }
}

async function identity(spki, run) {
  if (!(spki instanceof Uint8Array) || !spki.length || spki.length > 256) throw Error('Invalid PC identity');
  await run.wait(crypto.subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']));
  return new Uint8Array(await run.wait(crypto.subtle.digest('SHA-256', spki)));
}
async function credential(bytes, hash, grants, leaf, run) {
  if (bytes.length < 53 || bytes.length > MAX_REPLY || bytes[0] !== 2 || bytes[49] !== grants)
    throw Error('Invalid enrollment response');
  const size = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint16(50);
  if (!size || size > 256 || bytes.length !== 52 + size ||
      !bytes.subarray(1, 17).some(v => v) || !bytes.subarray(17, 49).some(v => v))
    throw Error('Invalid enrollment fields');
  const spki = bytes.slice(52);
  if (!same(await identity(spki, run), hash)) throw Error('Enrollment identity differs from the authenticated PC');
  run.check();
  // Only the trusted parent receives this object. The caller wipes secret after
  // protected persistence/use; no credential is passed to a UI or URL here.
  return { id: bytes.slice(1, 17), secret: bytes.slice(17, 49), grants, spki, certificateHash: leaf.slice() };
}
async function enroll(url, authorization, hash, grants, leaf, run) {
  let transport, channel, received, resolveReply, rejectReply, sent = false;
  const reply = new Promise((resolve, reject) => { resolveReply = resolve; rejectReply = reject; });
  reply.catch(() => {});
  try {
    run.check();
    transport = new WebTransport(url, { serverCertificateHashes: [{ algorithm: 'sha-256', value: leaf.slice() }] });
    // Attach rejection handling immediately, including failure before ready.
    transport.closed.catch(() => {});
    await run.wait(transport.ready);
    channel = openOrderedChannel(transport, {
      maxTextBytes: 256, maxBinaryBytes: MAX_REPLY, maxPendingBytes: 1024, maxPendingMessages: 4,
      onText() { rejectReply(Error('Unexpected enrollment text')); throw Error('Unexpected enrollment text'); },
      onBinary(bytes) {
        if (!sent || received) { bytes.fill(0); rejectReply(Error('Unexpected enrollment reply')); throw Error('Unexpected enrollment reply'); }
        received = bytes; resolveReply(bytes);
        channel.stop(); // This logical channel has exactly one reply.
      },
      onPong() {},
      onClose() { if (!received) rejectReply(Error('Enrollment channel closed before its reply')); },
    });
    await run.wait(channel.ready);
    sent = true;
    if (!channel.sendText('/enroll-v2\n' + authorization)) throw Error('Enrollment request was not accepted');
    const bytes = await run.wait(reply);
    return await credential(bytes, hash, grants, leaf, run);
  } finally {
    channel?.stop();
    try { transport?.close(); } catch {}
    received?.fill(0);
  }
}

// module is the already-loaded, officially delivered common Wasm core. Loading
// it belongs to bootstrap and is not charged to this single five-second attempt.
export async function pairWithCode({ server, code, label, module, signal }) {
  supported();
  const urls = endpoints(server), password = copy(code, 6, 'pairing code');
  let run, session, proof, enrollment;
  try {
    if (!password.every(v => v >= 48 && v <= 57)) throw Error('Pairing code must contain six digits');
    const name = labelBytes(label);
    run = attempt(signal);
    const context = await publicRequest(urls.pair, null, 200, 98, run);
    if (context[0] !== 2 || !validGrants(context[97])) throw Error('Unsupported pairing context');
    const hash = context.slice(33, 65), leaf = context.slice(65, 97);
    session = createPairing(module, 0, context, password); password.fill(0);
    const exchange = new Uint8Array(65); exchange[0] = 1;
    exchange.set(context.subarray(1, 33), 1); exchange.set(session.message(), 33);
    const response = await publicRequest(urls.pair, exchange, 200, 65, run);
    const number = response[0];
    if (number < 1 || number > 5) throw Error('Invalid pairing attempt');
    session.processPeer(response.subarray(1, 33)); session.verifyConfirmation(response.subarray(33));
    run.check();
    const confirmation = new Uint8Array(66); confirmation[0] = 2;
    confirmation.set(context.subarray(1, 33), 1); confirmation[33] = number;
    const mac = session.confirmation(); confirmation.set(mac, 34); mac.fill(0);
    await publicRequest(urls.pair, confirmation, 204, 0, run);
    proof = session.enrollmentProof();
    enrollment = new Uint8Array(65 + name.length);
    enrollment.set(context.subarray(1, 33)); enrollment[32] = number;
    enrollment.set(proof, 33); enrollment.set(name, 65);
    return await enroll(urls.enroll, 'Pairing ' + base64url(enrollment), hash, context[97], leaf, run);
  } finally {
    password.fill(0); proof?.fill(0); enrollment?.fill(0);
    try { session?.close(); } finally { run?.close(); }
  }
}

// The caller obtains this tuple together from the PC's trusted QR. Never replace
// its SPKI, grants or leaf with values fetched from unauthenticated HTTP.
export async function enrollWithInvite({ server, invite, certificateHash, spki, grants, label, signal }) {
  supported();
  const urls = endpoints(server), invitation = copy(invite, 32, 'invitation');
  let run, enrollment;
  try {
    const leaf = copy(certificateHash, 32, 'certificate pin');
    if (!(spki instanceof Uint8Array) || !spki.length || spki.length > 256 || !validGrants(grants))
      throw Error('Invalid QR identity or grants');
    spki = new Uint8Array(spki);
    const name = labelBytes(label);
    run = attempt(signal);
    const hash = await identity(spki, run);
    enrollment = new Uint8Array(32 + name.length); enrollment.set(invitation); enrollment.set(name, 32);
    return await enroll(urls.enroll, 'Invite ' + base64url(enrollment), hash, grants, leaf, run);
  } finally { invitation.fill(0); enrollment?.fill(0); run?.close(); }
}
