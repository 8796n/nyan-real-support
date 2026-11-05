// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>

export const IDENTITY_MANIFEST_BYTES = 161;
const DOMAIN = new TextEncoder().encode('Spatial Wall identity v2\0');
const MAX_VALIDITY_SECONDS = 14 * 24 * 60 * 60;
const FETCH_TIMEOUT_MS = 5000;

function copySpki(spki) {
  if (!(spki instanceof Uint8Array) || spki.length < 1 || spki.length > 256) {
    throw new Error('Invalid PC identity SPKI');
  }
  return new Uint8Array(spki);
}

// Identity comes from the saved QR trust, never from this HTTP response.
export async function verifyIdentityManifest(bytes, savedSpki, nonce,
    nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!(bytes instanceof Uint8Array) || bytes.length !== IDENTITY_MANIFEST_BYTES ||
      !(nonce instanceof Uint8Array) || nonce.length !== 32) {
    throw new Error('Invalid identity manifest length');
  }
  if (!Number.isSafeInteger(nowSeconds) || nowSeconds < 0 ||
      nowSeconds > Number.MAX_SAFE_INTEGER - MAX_VALIDITY_SECONDS) {
    throw new Error('Invalid identity verification time');
  }
  // Snapshot every caller-owned byte buffer before the first asynchronous operation.
  bytes = new Uint8Array(bytes);
  nonce = new Uint8Array(nonce);
  const spki = copySpki(savedSpki);
  if (!DOMAIN.every((value, index) => bytes[index] === value)) {
    throw new Error('Invalid identity manifest domain');
  }
  if (!nonce.every((value, index) => bytes[25 + index] === value)) {
    throw new Error('Identity challenge does not match');
  }
  const expiry = new DataView(bytes.buffer).getBigUint64(89);
  if (expiry <= BigInt(nowSeconds) || expiry > BigInt(nowSeconds + MAX_VALIDITY_SECONDS)) {
    throw new Error('Identity manifest expiry is invalid');
  }
  const key = await crypto.subtle.importKey('spki', spki,
    { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  if (!await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key,
    bytes.subarray(97), bytes.subarray(0, 97))) {
    throw new Error('PC identity signature is invalid');
  }
  return { certificateHash: bytes.slice(57, 89), expiresAt: Number(expiry) };
}

// The owner selects a fixed LAN endpoint and remains responsible for saving pins.
export async function fetchIdentityPin(endpoint, savedSpki, { signal } = {}) {
  const url = new URL(endpoint);
  if (url.protocol !== 'http:' || url.username || url.password ||
      url.pathname !== '/identity-v2' || url.search || url.hash) {
    throw new Error('Invalid identity endpoint');
  }
  const spki = copySpki(savedSpki);
  const nonce = crypto.getRandomValues(new Uint8Array(32));
  url.searchParams.set('challenge', Array.from(nonce, value => value.toString(16).padStart(2, '0')).join(''));
  const controller = new AbortController();
  const deadline = performance.now() + FETCH_TIMEOUT_MS;
  let reader, response, rejectStopped;
  const stopped = new Promise((_, reject) => { rejectStopped = reject; });
  stopped.catch(() => {});
  const cancel = reason => {
    if (controller.signal.aborted) return;
    controller.abort(reason);
    rejectStopped(reason);
  };
  const abort = () => cancel(signal.reason || new DOMException('Identity request aborted', 'AbortError'));
  const timeout = () => cancel(new DOMException('Identity request timed out', 'TimeoutError'));
  const timer = setTimeout(timeout, FETCH_TIMEOUT_MS);
  async function wait(operation) {
    const result = await Promise.race([operation, stopped]);
    if (performance.now() >= deadline) timeout();
    if (controller.signal.aborted) throw controller.signal.reason;
    return result;
  }
  try {
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    if (controller.signal.aborted) throw controller.signal.reason;
    const pendingResponse = fetch(url.href, {
      method: 'GET', mode: 'cors', targetAddressSpace: 'local', credentials: 'omit',
      redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer', signal: controller.signal,
    }).then(value => {
      // Retain ownership before wait() can reject a just-fulfilled response.
      response = value;
      // A fetch implementation can finish after cancellation; discard its body too.
      if (controller.signal.aborted) {
        response.body?.cancel().catch(() => {});
        throw controller.signal.reason;
      }
      return response;
    });
    response = await wait(pendingResponse);
    if (response.status !== 200 ||
        response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() !== 'application/octet-stream' ||
        !response.body) {
      throw new Error('Invalid identity HTTP response');
    }
    reader = response.body.getReader();
    const bytes = new Uint8Array(IDENTITY_MANIFEST_BYTES);
    let length = 0;
    while (true) {
      const part = await wait(reader.read());
      if (part.done) break;
      if (!(part.value instanceof Uint8Array) || part.value.byteLength > bytes.length - length) {
        throw new Error('Identity response exceeds its size limit');
      }
      bytes.set(part.value, length);
      length += part.value.byteLength;
    }
    if (length !== bytes.length) throw new Error('Truncated identity response');
    return await wait(verifyIdentityManifest(bytes, spki, nonce));
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
    controller.abort();
    if (reader) {
      reader.cancel().catch(() => {});
      reader.releaseLock();
    } else response?.body?.cancel().catch(() => {});
  }
}
