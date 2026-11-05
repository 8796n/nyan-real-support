// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { lanServer, encodeBase64url, decodeBase64url, validateSpki } from './connection-data.mjs';
export const DEVICE_STORE_KEY = 'spatial-wall.devices.v2';
const KEY = DEVICE_STORE_KEY, MAX_DEVICES = 16, MAX_BYTES = 16384;
const FIELDS = 'grants,host,id,port,secret,spki';
const hex = bytes => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
function unhex(value, size) {
  if (typeof value !== 'string' || value.length !== size * 2 || !/^[0-9a-f]+$/.test(value)) throw Error();
  const bytes = Uint8Array.from(value.match(/../g), p => parseInt(p, 16));
  if (!bytes.some(b => b)) throw Error();
  return bytes;
}
function snapshot(device) {
  lanServer(device?.host, device?.port);
  if (!Number.isInteger(device.grants) || device.grants < 1 || device.grants > 7 ||
      !(device.id instanceof Uint8Array) || device.id.length !== 16 || !device.id.some(b => b) ||
      !(device.secret instanceof Uint8Array) || device.secret.length !== 32 || !device.secret.some(b => b)) throw Error();
  return { host: device.host, port: device.port, id: hex(device.id), secret: hex(device.secret),
    grants: device.grants, spki: encodeBase64url(device.spki) };
}
async function validate(record) {
  if (!record || Object.keys(record).sort().join(',') !== FIELDS) throw Error();
  const secret = unhex(record.secret, 32);
  try {
    const device = { ...record, id: unhex(record.id, 16), secret, spki: decodeBase64url(record.spki, 1, 256) };
    snapshot(device);
    await validateSpki(device.spki);
    return device;
  } catch { secret.fill(0); throw Error(); }
}
function wipe(devices) { for (const device of devices) device.secret.fill(0); }

// This is origin storage, not an OS keystore. It must never be passed to the UI frame.
async function read(storage) {
  const devices = [];
  try {
    const text = storage.getItem(KEY);
    if (text === null) return { text, devices };
    if (typeof text !== 'string' || text.length > MAX_BYTES) throw Error();
    const record = JSON.parse(text);
    if (!record || Object.keys(record).sort().join(',') !== 'devices,v' || record.v !== 2 ||
        !Array.isArray(record.devices) || record.devices.length > MAX_DEVICES) throw Error();
    const seen = new Set();
    for (const entry of record.devices) {
      if (seen.has(entry?.spki)) throw Error();
      seen.add(entry?.spki); devices.push(await validate(entry));
    }
    return { text, devices };
  } catch { wipe(devices); throw Error('Saved PC registrations are unavailable or invalid'); }
}
export async function loadDevices(storage) { return (await read(storage)).devices; }

function write(storage, before, devices) {
  const text = JSON.stringify({ v: 2, devices: devices.map(snapshot) });
  if (text.length > MAX_BYTES) throw Error();
  // Detect another tab's edits during async validation instead of losing them.
  if (storage.getItem(KEY) !== before) throw Error();
  storage.setItem(KEY, text);
  if (storage.getItem(KEY) !== text) throw Error();
}
export async function saveDevice(storage, device) {
  let saved, current;
  try {
    // Snapshot caller-owned arrays before the first await.
    saved = await validate(snapshot(device));
    current = await read(storage);
    const key = encodeBase64url(saved.spki);
    const index = current.devices.findIndex(d => encodeBase64url(d.spki) === key);
    if (index === -1) {
      if (current.devices.length === MAX_DEVICES) throw Error();
      current.devices.push(saved);
    } else { current.devices[index].secret.fill(0); current.devices[index] = saved; }
    write(storage, current.text, current.devices);
  } catch { throw Error('PC registration could not be saved'); }
  finally { saved?.secret.fill(0); if (current) wipe(current.devices); }
}
export async function forgetDevice(storage, spki) {
  const key = encodeBase64url(await validateSpki(spki));
  let current;
  try {
    current = await read(storage);
    write(storage, current.text, current.devices.filter(d => encodeBase64url(d.spki) !== key));
  } catch { throw Error('PC registration could not be removed'); }
  finally { if (current) wipe(current.devices); }
}
// This explicit operation also recovers corrupt data; loading never resets it.
export function forgetAllDevices(storage) {
  try { storage.removeItem(KEY); if (storage.getItem(KEY) !== null) throw Error(); }
  catch { throw Error('PC registrations could not be removed'); }
}
