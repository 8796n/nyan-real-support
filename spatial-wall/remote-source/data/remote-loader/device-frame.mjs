// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { createDeviceConnection } from './device-connection.mjs';
import { createRemoteFrame } from './ui-frame.mjs';

// productKey and settings storage belong to the official parent. Only signed UI
// bytes and the existing narrow, credential-free hooks reach the opaque frame.
export async function mountDeviceFrame({ device, container, storage, productKey, signal, onReady, onClose, onControlChange }) {
  if (typeof onReady !== 'function' || typeof onClose !== 'function') throw Error('Missing frame lifecycle');
  const controllerAllowed = !!(device?.grants & 4);
  let frame, connection, closed = false;
  const events = Object.fromEntries(['open', 'close', 'text', 'cursor', 'controllerText', 'controllerClose']
    .map(name => [name, (...args) => {
      if (closed) return;
      if (name === 'open' || name === 'close') onControlChange?.(name === 'open');
      frame?.events[name](...args);
    }]));
  function close() {
    if (closed) return;
    closed = true; signal?.removeEventListener('abort', close);
    connection?.close(); frame?.close(); onClose();
  }
  signal?.addEventListener('abort', close, { once: true });
  try {
    if (signal?.aborted) throw new DOMException('Connection cancelled', 'AbortError');
    connection = createDeviceConnection({ device, events });
    const payload = await connection.loadUi(signal);
    if (closed) throw new DOMException('Connection cancelled', 'AbortError');
    frame = await createRemoteFrame({ container, ...payload, productKey, storage, hooks: connection.hooks,
      controllerAllowed,
      onReady() { if (!closed) onReady(); }, onClose: close });
    if (closed) { frame.close(); throw new DOMException('Connection cancelled', 'AbortError'); }
    return Object.freeze({ close, iframe: frame.iframe, downloadApk: () => connection.downloadApk(productKey) });
  } catch (error) { close(); throw error; }
}
