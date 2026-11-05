// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
export const MAX_UI_BYTES = 1024 * 1024;
const decoder = new TextDecoder('utf-8', { fatal: true });

function decodeBase64(value, maxBytes) {
  if (typeof value !== 'string' || !value.length || value.length > Math.ceil(maxBytes / 3) * 4
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new Error('Invalid signed UI encoding');
  }
  const binary = atob(value);
  if (binary.length > maxBytes || btoa(binary) !== value) throw new Error('Invalid signed UI encoding');
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}

// The caller obtains this file only from the official loader's fixed asset URL.
export async function importProductKey(pem) {
  const match = /^-----BEGIN PUBLIC KEY-----\r?\n([A-Za-z0-9+/=\r\n]+)-----END PUBLIC KEY-----\r?\n?$/.exec(pem);
  if (!match) throw new Error('Invalid product signing key');
  const key = await crypto.subtle.importKey('spki', decodeBase64(match[1].replace(/[\r\n]/g, ''), 2048),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
  if (key.algorithm.modulusLength < 2048) throw new Error('Invalid product signing key');
  return key;
}

// Return HTML only after both the signed metadata and its exact UI bytes match.
export async function verifyRemoteUi(envelopeText, htmlBytes, productKey) {
  if (typeof envelopeText !== 'string' || envelopeText.length > 8192
      || !(htmlBytes instanceof Uint8Array) || htmlBytes.length < 1 || htmlBytes.length > MAX_UI_BYTES) {
    throw new Error('Remote UI exceeds its size limit');
  }
  // Keep the hash check and returned HTML on the same immutable snapshot.
  htmlBytes = new Uint8Array(htmlBytes);
  const envelope = JSON.parse(envelopeText);
  if (!envelope || typeof envelope !== 'object' || Object.keys(envelope).sort().join(',') !== 'manifest,signature') {
    throw new Error('Invalid signed UI envelope');
  }
  const manifestBytes = decodeBase64(envelope.manifest, 2048);
  const signature = decodeBase64(envelope.signature, 1024);
  if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', productKey, signature, manifestBytes)) {
    throw new Error('Remote UI product signature is invalid');
  }
  const manifest = JSON.parse(decoder.decode(manifestBytes));
  if (!manifest || Object.keys(manifest).sort().join(',') !== 'format,protocol,sha256,size,version'
      || manifest.format !== 'spatial-wall.remote-ui' || manifest.protocol !== 2
      || typeof manifest.version !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}$/.test(manifest.version)
      || manifest.size !== htmlBytes.length || typeof manifest.sha256 !== 'string'
      || !/^[a-f0-9]{64}$/.test(manifest.sha256)) {
    throw new Error('Unsupported or invalid remote UI manifest');
  }
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', htmlBytes));
  if (Array.from(hash, b => b.toString(16).padStart(2, '0')).join('') !== manifest.sha256) {
    throw new Error('Remote UI content differs from its signed manifest');
  }
  return { html: decoder.decode(htmlBytes), version: manifest.version, sha256: manifest.sha256 };
}
