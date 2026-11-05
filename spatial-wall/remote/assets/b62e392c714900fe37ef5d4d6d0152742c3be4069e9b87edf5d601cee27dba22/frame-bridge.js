// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Classic script in the opaque frame, side-effect import in the trusted parent.
(() => {
  'use strict';
  const TEXT = 256 * 1024, MEDIA = 4 * 1024 * 1024 + 40;
  const LIMITS = Object.freeze({ text: TEXT, media: MEDIA, count: 64, bytes: 8 * 1024 * 1024 });
  const SETTINGS = Object.freeze(['nyanRemoteTune', 'nyanRemoteStripLeft', 'nyanSettingsOpen',
    'nyanVrHand', 'nyanVrHaptics', 'nyanRemoteMarker', 'nyanRemotePanesSwapped', 'nyanRemotePreviewLive']);
  const encoder = new TextEncoder();
  function textSize(value, max) {
    if (typeof value !== 'string' || value.length > max) throw Error('Invalid frame text');
    const size = encoder.encode(value).length;
    if (size > max) throw Error('Frame text exceeds limit');
    return size;
  }
  function validSetting(key, value) {
    if (!SETTINGS.includes(key) || typeof value !== 'string' || value.length > 4096) return false;
    if (key === 'nyanVrHand') return value === 'left' || value === 'right';
    if (key === 'nyanRemoteTune') {
      try {
        const v = JSON.parse(value), ranges = { sens: [.5, 4], wheel: [.3, 3], dolly: [.3, 3], hold: [200, 800], vib: [0, 3] };
        return v && Object.keys(v).length === 5 && Object.entries(ranges).every(([k, [lo, hi]]) =>
          Number.isFinite(v[k]) && v[k] >= lo && v[k] <= hi);
      } catch { return false; }
    }
    if (key === 'nyanSettingsOpen') {
      try { const v = JSON.parse(value); return Array.isArray(v) && v.length <= 64 &&
        v.every(s => typeof s === 'string' && s.length <= 96 && !/[\x00-\x1f\x7f]/.test(s)); }
      catch { return false; }
    }
    return value === '0' || value === '1';
  }
  function settingsSnapshot(storage) {
    const result = {};
    for (const key of SETTINGS) {
      try { const value = storage.getItem(key); if (validSetting(key, value)) result[key] = value; } catch {}
    }
    return result;
  }
  function validateSettings(settings) {
    if (!settings || typeof settings !== 'object' || Array.isArray(settings) ||
        Object.entries(settings).some(([k, v]) => !validSetting(k, v))) throw Error('Invalid frame settings');
  }
  function payloadSize(kind, value) {
    if (typeof kind !== 'string' || !/^[a-z-]{1,24}$/.test(kind)) throw Error('Invalid frame message');
    if (kind === 'cursor') {
      if (!(value instanceof Uint8Array) || value.byteLength > MEDIA) throw Error('Invalid frame cursor');
      return value.byteLength;
    }
    return textSize(value, kind === 'initialize' ? 3 * 1024 * 1024 : kind === 'preview' ? MEDIA : TEXT);
  }
  // Both directions admit bounded work before posting. ACK follows processing,
  // so a slow or suspended peer cannot grow an unbounded MessagePort queue.
  function channel(port, receive, ended) {
    let closed = false, next = 0, incoming = 0, bytes = 0, receiving = 0, receiveBytes = 0;
    const pending = new Map();
    let work = Promise.resolve();
    function close(notify = true) {
      if (closed) return;
      closed = true;
      clearInterval(timer);
      if (notify) { try { port.postMessage({ close: true }); } catch {} }
      port.onmessage = port.onmessageerror = null;
      port.close(); pending.clear(); bytes = 0;
      ended();
    }
    const timer = setInterval(() => {
      const first = pending.values().next().value;
      if (first && Date.now() - first.at >= 5000) close();
    }, 1000);
    port.onmessageerror = () => close();
    port.onmessage = event => {
      if (closed) return;
      try {
        const m = event.data;
        if (!m || typeof m !== 'object') throw Error('Invalid frame envelope');
        const keys = Object.keys(m).sort().join(',');
        if (keys === 'close' && m.close === true) { close(false); return; }
        if (keys === 'ack' && Number.isSafeInteger(m.ack)) {
          const first = pending.keys().next().value;
          if (m.ack !== first) throw Error('Unexpected frame acknowledgement');
          bytes -= pending.get(first).size; pending.delete(first); return;
        }
        if (keys !== 'id,kind,payload' || !Number.isSafeInteger(m.id) || m.id !== incoming + 1)
          throw Error('Invalid frame sequence');
        const size = payloadSize(m.kind, m.payload);
        if (++receiving > LIMITS.count || receiveBytes + size > LIMITS.bytes) throw Error('Frame receive overflow');
        incoming = m.id; receiveBytes += size;
        work = work.then(async () => {
          if (closed) return;
          await receive(m.kind, m.payload);
          if (closed) return;
          receiving--; receiveBytes -= size;
          port.postMessage({ ack: m.id });
        }).catch(() => close());
      } catch { close(); }
    };
    port.start();
    return Object.freeze({
      send(kind, payload = '') {
        if (closed) return false;
        try {
          const size = payloadSize(kind, payload);
          if (pending.size >= LIMITS.count || bytes + size > LIMITS.bytes || next === Number.MAX_SAFE_INTEGER)
            throw Error('Frame send overflow');
          if (payload instanceof Uint8Array) payload = new Uint8Array(payload);
          pending.set(++next, { size, at: Date.now() }); bytes += size;
          port.postMessage({ id: next, kind, payload });
          return true;
        } catch { close(); return false; }
      },
      close,
      get closed() { return closed; },
    });
  }
  // No native APIs, credential, URL, arbitrary storage key or network primitive
  // is exposed to the UI. The snapshot contains only non-secret preferences.
  function client(port, mount, ended) {
    let initialized = false, mounted = false, callbacks = null, controllerAllowed = false, bootBytes = 0;
    const boot = [];
    const requests = new Map();
    let settings = {};
    function finish() {
      for (const request of requests.values()) { clearTimeout(request.timer); request.resolve(null); }
      requests.clear();
      boot.length = 0; bootBytes = 0;
      if (callbacks) callbacks.close();
      ended();
    }
    const bridge = channel(port, async (kind, payload) => {
      if (kind === 'initialize') {
        if (initialized) throw Error('Frame already initialized');
        const data = JSON.parse(payload);
        if (Object.keys(data).sort().join(',') !== 'controllerAllowed,html,settings' || typeof data.html !== 'string' ||
            typeof data.controllerAllowed !== 'boolean') throw Error('Invalid frame initialization');
        textSize(data.html, 1024 * 1024); validateSettings(data.settings);
        initialized = true; settings = { ...data.settings }; controllerAllowed = data.controllerAllowed;
        await mount(data.html, host);
        if (bridge.closed) return;
        if (!callbacks) throw Error('Frame did not bind its callbacks');
        mounted = true;
        bridge.send('ready');
        for (const [kind, payload] of boot) if (!bridge.send(kind, payload)) break;
        boot.length = 0; bootBytes = 0;
        return;
      }
      if (!callbacks) throw Error('Frame is not ready');
      switch (kind) {
        case 'open': if (payload !== '') throw Error('Invalid open'); callbacks.open(); break;
        case 'control-close': textSize(payload, 256); callbacks.close(payload); break;
        case 'text': callbacks.text(payload); break;
        case 'cursor': callbacks.cursor(payload); break;
        case 'controller-text': callbacks.controllerText(payload); break;
        case 'controller-close': if (payload !== '') throw Error('Invalid close'); callbacks.controllerClose(); break;
        case 'preview': case 'vibration-result': {
          const request = requests.get(kind);
          if (!request) throw Error('Unexpected frame reply');
          const value = kind === 'preview' ? (payload ? JSON.parse(payload) : null) :
            payload === '1' ? true : payload === '0' ? false : null;
          if (kind === 'vibration-result' && value === null) throw Error('Invalid vibration result');
          requests.delete(kind); clearTimeout(request.timer); request.resolve(value);
          break;
        }
        default: throw Error('Unsupported frame event');
      }
    }, finish);
    function send(kind, payload = '') {
      if (bridge.closed) return false;
      if (mounted) return bridge.send(kind, payload);
      try {
        const size = payloadSize(kind, payload);
        if (boot.length >= LIMITS.count || bootBytes + size > LIMITS.bytes) throw Error('Frame bootstrap overflow');
        boot.push([kind, payload instanceof Uint8Array ? new Uint8Array(payload) : payload]);
        bootBytes += size;
        return true;
      } catch { bridge.close(); return false; }
    }
    function request(kind, reply) {
      if (requests.has(reply) || bridge.closed) return Promise.resolve(null);
      return new Promise(resolve => {
        // Missing replies are terminal, preventing late responses from being
        // mistaken for a subsequent request and bounding retained work.
        requests.set(reply, { resolve, timer: setTimeout(() => bridge.close(), 4000) });
        send(kind);
      });
    }
    const host = Object.freeze({
      get controllerAllowed() { return controllerAllowed; },
      bind(events) {
        if (!initialized || callbacks || ['open', 'close', 'text', 'cursor', 'controllerText', 'controllerClose']
            .some(k => typeof events[k] !== 'function')) { bridge.close(); return; }
        callbacks = events;
      },
      start() { return send('control-start'); },
      stop() { return send('control-stop'); },
      send(text) { return send('control', text); },
      controllerStart(hand) { return send('controller-start', hand); },
      controllerStop() { return send('controller-stop'); },
      controllerSend(text) { return send('controller', text); },
      keyboard(on) { return send('keyboard', on ? '1' : '0'); },
      wake(on) { return send('wake', on ? '1' : '0'); },
      vibrate(ms) { return send('vibrate', String(ms)); },
      testVibration() { return request('test-vibration', 'vibration-result'); },
      storage: Object.freeze({
        getItem(key) { return SETTINGS.includes(key) ? settings[key] ?? null : null; },
        setItem(key, value) {
          if (!validSetting(key, value)) throw Error('Invalid UI preference');
          if (!send('setting', JSON.stringify([key, value]))) throw Error('Frame is closed');
          settings[key] = value;
        },
      }),
      requestPreview() { return request('request-preview', 'preview'); },
    });
    return Object.freeze({ close: bridge.close });
  }
  Object.defineProperty(globalThis, 'SpatialRemoteFrame', { value: Object.freeze({
    LIMITS, SETTINGS, textSize, validSetting, settingsSnapshot, channel, client,
  }) });
})();
