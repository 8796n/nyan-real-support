// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
const ENTRY = 'https://support.nyanreal.jp/spatial-wall/remote/';
const FIELDS = 'cert,grants,host,invite,port,spki,v';

export function lanServer(host, port) {
  if (typeof host !== 'string' || host.length > 15 ||
      !Number.isInteger(port) || port < 1 || port > 65535) throw Error('Invalid LAN endpoint');
  const parts = host.split('.');
  if (parts.length !== 4 || parts.some(p => !/^(?:0|[1-9][0-9]{0,2})$/.test(p) ||
      String(Number(p)) !== p || Number(p) > 255))
    throw Error('Invalid LAN endpoint');
  const [a, b] = parts.map(Number);
  if (!(a === 10 || a === 172 && b >= 16 && b <= 31 || a === 192 && b === 168 || a === 169 && b === 254))
    throw Error('Invalid LAN endpoint');
  return `http://${host}:${port}`;
}

export function encodeBase64url(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length < 1 || bytes.length > 256)
    throw Error('Invalid bounded byte value');
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
export function decodeBase64url(text, minimum, maximum) {
  if (typeof text !== 'string' || text.length > Math.ceil(maximum * 8 / 6) ||
      !/^[A-Za-z0-9_-]+$/.test(text)) throw Error('Invalid canonical base64url');
  let bytes;
  try { bytes = Uint8Array.from(atob(text.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0)); }
  catch { throw Error('Invalid canonical base64url'); }
  if (bytes.length < minimum || bytes.length > maximum || encodeBase64url(bytes) !== text) {
    bytes.fill(0); throw Error('Invalid canonical base64url');
  }
  return bytes;
}
export async function validateSpki(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.length < 1 || bytes.length > 256) throw Error('Invalid PC identity');
  const snapshot = new Uint8Array(bytes);
  try {
    const key = await crypto.subtle.importKey('spki', snapshot, { name: 'ECDSA', namedCurve: 'P-256' }, true, ['verify']);
    const canonical = new Uint8Array(await crypto.subtle.exportKey('spki', key));
    if (canonical.length !== snapshot.length || !canonical.every((b, i) => b === snapshot[i])) throw Error();
    return snapshot;
  } catch { throw Error('Invalid PC identity'); }
}

// Take the fragment before any asynchronous work; never put it in an error or log.
export function takePairingLink(location, history) {
  if (!location.hash) return Promise.resolve(null);
  const value = location.href;
  try { history.replaceState(null, '', location.pathname + location.search); }
  catch { throw Error('Unable to remove pairing invitation from the address'); }
  return parsePairingLink(value);
}
export async function parsePairingLink(value) {
  let invite;
  try {
    if (typeof value !== 'string' || value.length > 2048 || !value.startsWith(ENTRY + '#')) throw Error();
    const fields = Object.create(null), parts = value.slice(ENTRY.length + 1).split('&');
    if (parts.length !== 7) throw Error();
    for (const part of parts) {
      const pair = part.split('=');
      if (pair.length !== 2 || !pair[0] || !pair[1] || Object.hasOwn(fields, pair[0])) throw Error();
      fields[pair[0]] = pair[1];
    }
    if (Object.keys(fields).sort().join(',') !== FIELDS || fields.v !== '2' ||
        !/^[1-9][0-9]{0,4}$/.test(fields.port) || String(Number(fields.port)) !== fields.port ||
        fields.grants.length !== 1 || !/^[1-7]$/.test(fields.grants)) throw Error();
    const host = fields.host, port = Number(fields.port), grants = Number(fields.grants);
    lanServer(host, port);
    const spki = decodeBase64url(fields.spki, 1, 256);
    const certificateHash = decodeBase64url(fields.cert, 32, 32);
    invite = decodeBase64url(fields.invite, 32, 32);
    await validateSpki(spki);
    return { host, port, grants, spki, certificateHash, invite };
  } catch {
    invite?.fill(0);
    throw Error('Invalid v2 Spatial Wall pairing link');
  }
}

// Call only for an explicit user action. No saved device credential is accepted.
export function androidIntent({ host, port, grants, spki, certificateHash, invite }) {
  lanServer(host, port);
  if (!Number.isInteger(grants) || grants < 1 || grants > 7 ||
      !(certificateHash instanceof Uint8Array) || certificateHash.length !== 32 ||
      !(invite instanceof Uint8Array) || invite.length !== 32) throw Error('Invalid pairing invitation');
  return `intent://pair?v=2&host=${host}&port=${port}&spki=${encodeBase64url(spki)}` +
    `&cert=${encodeBase64url(certificateHash)}&invite=${encodeBase64url(invite)}&grants=${grants}` +
    '#Intent;scheme=nyanreal;package=jp.nyanreal.spatialwall.remote;end';
}
