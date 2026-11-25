// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import './frame-bridge.js';
import { verifyRemoteUi } from './verify-ui.mjs';
import { createFrameSource } from './frame-source.mjs';
const { channel, textSize, validSetting, settingsSnapshot } = globalThis.SpatialRemoteFrame;

// productKey must come from the official loader's fixed product-key.pem asset,
// never from the PC response. Hooks own all authentication and network state.
// start/stop/send are ordered; send must return true only after queue admission.
// preview returns the existing JSON text and observes the supplied AbortSignal.
export async function createRemoteFrame({ container, envelopeText, htmlBytes, productKey, storage, controllerAllowed = false,
    hooks, onReady, onClose }) {
  for (const name of ['start', 'stop', 'send', 'preview']) {
    if (typeof hooks?.[name] !== 'function') throw Error('Missing remote frame hook');
  }
  if (typeof onReady !== 'function' || typeof onClose !== 'function') throw Error('Missing frame lifecycle');
  if (typeof controllerAllowed !== 'boolean') throw Error('Invalid controller capability');
  const verified = await verifyRemoteUi(envelopeText, htmlBytes, productKey);
  const doc = container.ownerDocument, win = doc.defaultView;
  const source = await createFrameSource(win);
  const url = new URL(source.url);
  const nonce = Array.from(crypto.getRandomValues(new Uint8Array(32)), b => b.toString(16).padStart(2, '0')).join('');
  url.hash = nonce;
  const iframe = doc.createElement('iframe');
  iframe.title = 'Spatial Wall Remote';
  iframe.setAttribute('sandbox', 'allow-scripts allow-pointer-lock');
  iframe.setAttribute('allow', 'fullscreen *');
  iframe.referrerPolicy = 'no-referrer';
  let closed = false, loaded = false, announced = false, connected = false, ready = false, bridge = null;
  let preview = null, wakeWanted = false, wake = null, wakeRevision = 0;
  const lifetime = new AbortController();
  const timeout = setTimeout(close, 5000);
  function releaseWake() {
    ++wakeRevision;
    const held = wake; wake = null;
    if (held) Promise.resolve(held.release()).catch(() => {});
  }
  async function updateWake() {
    releaseWake();
    if (!wakeWanted || closed || doc.hidden || !win.navigator.wakeLock) return;
    const revision = wakeRevision;
    try {
      const held = await win.navigator.wakeLock.request('screen');
      if (closed || revision !== wakeRevision) await held.release();
      else wake = held;
    } catch {}
  }
  function close() {
    if (closed) return;
    closed = true; clearTimeout(timeout); lifetime.abort();
    if (preview) preview.abort();
    win.removeEventListener('message', announce);
    win.removeEventListener('pagehide', close);
    doc.removeEventListener('visibilitychange', updateWake);
    iframe.removeEventListener('load', load);
    if (bridge) bridge.close();
    releaseWake();
    try { win.navigator.keyboard?.unlock(); } catch {}
    for (const name of ['control', 'controller']) { try { hooks.stop(name); } catch {} }
    iframe.remove(); source.revoke(); onClose();
  }
  function send(kind, payload = '') {
    return !!(ready && !closed && bridge?.send(kind, payload));
  }
  function startPreview() {
    if (preview) throw Error('A preview is already pending');
    const request = new AbortController(); preview = request;
    const timer = setTimeout(() => request.abort(), 2500);
    // This work is deliberately outside the command queue: a slow image must
    // never delay input releases. At most one preview exists for this frame.
    const aborted = new Promise(resolve => request.signal.addEventListener('abort', () => resolve(''), { once: true }));
    Promise.race([Promise.resolve().then(() => hooks.preview(request.signal)), aborted])
      .then(text => {
        if (closed || request.signal.aborted && lifetime.signal.aborted) return;
        if (typeof text !== 'string') text = '';
        send('preview', text);
      }, () => { if (!closed) send('preview', ''); })
      .finally(() => { clearTimeout(timer); if (preview === request) preview = null; });
  }
  async function receive(kind, payload) {
    if (kind === 'ready') {
      if (ready || payload !== '') throw Error('Invalid frame readiness');
      ready = true; clearTimeout(timeout); onReady(); return;
    }
    if (!ready) throw Error('Frame is not ready');
    switch (kind) {
      case 'control-start': case 'control-stop': case 'controller-stop':
        if (payload !== '') throw Error('Invalid frame command');
        if (kind === 'control-start') await hooks.start('control');
        else await hooks.stop(kind === 'control-stop' ? 'control' : 'controller');
        break;
      case 'controller-start':
        if (payload !== 'left' && payload !== 'right') throw Error('Invalid controller hand');
        await hooks.start('controller', payload); break;
      case 'control': case 'controller':
        if (await hooks.send(kind, payload) !== true) throw Error('Remote send rejected');
        break;
      case 'request-preview':
        if (payload !== '') throw Error('Invalid preview request');
        startPreview(); break;
      case 'setting': {
        textSize(payload, 8192);
        const pair = JSON.parse(payload);
        if (!Array.isArray(pair) || pair.length !== 2 || !validSetting(...pair)) throw Error('Invalid UI preference');
        // Preferences remain best-effort, as in the direct browser UI. A full
        // storage quota must not interrupt an otherwise healthy input session.
        try { storage.setItem(...pair); } catch {}
        break;
      }
      case 'wake':
        if (payload !== '0' && payload !== '1') throw Error('Invalid wake request');
        wakeWanted = payload === '1'; updateWake(); break;
      case 'keyboard':
        if (payload !== '0' && payload !== '1') throw Error('Invalid keyboard request');
        if (payload === '1' && doc.fullscreenElement === iframe) {
          try { win.navigator.keyboard?.lock().catch(() => {}); } catch {}
        } else { try { win.navigator.keyboard?.unlock(); } catch {} }
        break;
      case 'vibrate':
        if (!/^(?:0|[1-9][0-9]{0,2}|1000)$/.test(payload)) throw Error('Invalid vibration');
        if (!doc.hidden) { try { win.navigator.vibrate?.(Number(payload)); } catch {} }
        break;
      case 'test-vibration': {
        if (payload !== '') throw Error('Invalid vibration test');
        let accepted = false;
        if (!doc.hidden) { try { accepted = win.navigator.vibrate?.(200) === true; } catch {} }
        send('vibration-result', accepted ? '1' : '0'); break;
      }
      default: throw Error('Unsupported frame command');
    }
  }
  function connect() {
    if (!loaded || !announced || connected || closed) return;
    connected = true;
    const ports = new win.MessageChannel();
    bridge = channel(ports.port1, receive, close);
    // An opaque origin requires '*'. Source, nonce, fixed URL, one transfer and
    // load lifecycle checks bind this port to the child we created.
    iframe.contentWindow.postMessage({ type: 'spatial-frame-init', nonce }, '*', [ports.port2]);
    bridge.send('initialize', JSON.stringify({ html: verified.html, settings: settingsSnapshot(storage), controllerAllowed }));
  }
  function announce(event) {
    if (closed || event.source !== iframe.contentWindow || event.origin !== 'null' ||
        !event.data || Object.keys(event.data).sort().join(',') !== 'nonce,type' ||
        event.data.type !== 'spatial-frame-ready' || event.data.nonce !== nonce || event.ports.length) return;
    if (announced) { close(); return; }
    announced = true; connect();
  }
  function load() {
    if (loaded) { close(); return; }
    loaded = true; connect();
  }
  win.addEventListener('message', announce);
  win.addEventListener('pagehide', close, { once: true });
  doc.addEventListener('visibilitychange', updateWake);
  iframe.addEventListener('load', load);
  iframe.src = url.href;
  container.append(iframe);
  return Object.freeze({ iframe, close, events: Object.freeze({
    open: () => send('open'), close: (note = '') => send('control-close', note),
    text: text => send('text', text), cursor: bytes => send('cursor', bytes),
    controllerText: text => send('controller-text', text), controllerClose: () => send('controller-close'),
  }) });
}
