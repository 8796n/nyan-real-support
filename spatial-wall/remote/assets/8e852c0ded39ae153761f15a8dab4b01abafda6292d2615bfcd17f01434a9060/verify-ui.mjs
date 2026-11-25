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

async function signedManifest(envelopeText, productKey) {
  if (typeof envelopeText !== 'string' || envelopeText.length > 8192) throw Error('Signed envelope exceeds its size limit');
  const envelope = JSON.parse(envelopeText);
  if (!envelope || typeof envelope !== 'object' || Object.keys(envelope).sort().join(',') !== 'manifest,signature') {
    throw new Error('Invalid signed UI envelope');
  }
  const manifestBytes = decodeBase64(envelope.manifest, 2048);
  const signature = decodeBase64(envelope.signature, 1024);
  if (!await crypto.subtle.verify('RSASSA-PKCS1-v1_5', productKey, signature, manifestBytes)) {
    throw new Error('Product signature is invalid');
  }
  return JSON.parse(decoder.decode(manifestBytes));
}

export async function verifyPayloadHash(bytes, expected) {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  if (Array.from(hash, b => b.toString(16).padStart(2, '0')).join('') !== expected) {
    throw new Error('Payload content differs from its signed manifest');
  }
}

export async function verifyApkManifest(envelopeText, productKey) {
  const m = await signedManifest(envelopeText, productKey);
  if (!m || Object.keys(m).sort().join(',') !== 'application_id,schema,sha256,size,t,version_code,version_name'
      || m.t !== 'android_apk' || m.schema !== 1 || m.application_id !== 'jp.nyanreal.spatialwall.remote'
      || !Number.isSafeInteger(m.version_code) || m.version_code < 1 || m.version_code > 2100000000
      || typeof m.version_name !== 'string' || !/^\d+\.\d+\.\d+$/.test(m.version_name)
      || !Number.isSafeInteger(m.size) || m.size < 1 || m.size > 128 * 1024 * 1024
      || typeof m.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(m.sha256)) {
    throw Error('Invalid signed Android app manifest');
  }
  return m;
}

// Return HTML only after both the signed metadata and its exact UI bytes match.
export async function verifyRemoteUi(envelopeText, htmlBytes, productKey) {
  if (!(htmlBytes instanceof Uint8Array) || htmlBytes.length < 1 || htmlBytes.length > MAX_UI_BYTES) {
    throw new Error('Remote UI exceeds its size limit');
  }
  htmlBytes = new Uint8Array(htmlBytes);
  const manifest = await signedManifest(envelopeText, productKey);
  if (!manifest || Object.keys(manifest).sort().join(',') !== 'format,protocol,sha256,size,version'
      || manifest.format !== 'spatial-wall.remote-ui' || manifest.protocol !== 2
      || typeof manifest.version !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}$/.test(manifest.version)
      || manifest.size !== htmlBytes.length || typeof manifest.sha256 !== 'string'
      || !/^[a-f0-9]{64}$/.test(manifest.sha256)) {
    throw new Error('Unsupported or invalid remote UI manifest');
  }
  await verifyPayloadHash(htmlBytes, manifest.sha256);
  return { html: decoder.decode(htmlBytes), version: manifest.version, sha256: manifest.sha256 };
}
