// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Only a staged, content-addressed bundle registers a worker. Source previews do not.
export function watchOffline(win, changed) {
  const match = /^(.*\/)assets\/([a-f0-9]{64})\/offline\.mjs$/.exec(new URL(import.meta.url).pathname);
  if (!match || !win.navigator.serviceWorker || typeof win.MessageChannel !== 'function') { changed('offlineUnavailable'); return () => {}; }
  const serviceWorker = win.navigator.serviceWorker, removers = [];
  let stopped = false, cancelCheck = () => {};
  const notify = key => { if (!stopped) changed(key); };
  function listen(target, event, callback) {
    target.addEventListener(event, callback); removers.push(() => target.removeEventListener(event, callback));
  }
  function observe(registration) {
    function check(worker, success) {
      const ports = new win.MessageChannel();
      const finish = key => { cancelCheck(); notify(key); };
      const timer = setTimeout(() => finish('offlineUnavailable'), 2000);
      cancelCheck = () => { clearTimeout(timer); ports.port1.onmessage = ports.port1.onmessageerror = null; ports.port1.close(); ports.port2.close(); };
      ports.port1.onmessage = event => finish(event.data === true ? success : 'offlineUnavailable');
      ports.port1.onmessageerror = () => finish('offlineUnavailable');
      try { worker.postMessage('spatial-offline-check-v2', [ports.port2]); }
      catch { finish('offlineUnavailable'); }
    }
    function report() {
      cancelCheck();
      if (registration.waiting) check(registration.waiting, 'offlineUpdate');
      else if (registration.active?.state === 'activated') check(registration.active, serviceWorker.controller ? 'offlineReady' : 'offlineNext');
      else if (registration.installing?.state === 'redundant') notify('offlineUnavailable');
      else notify('offlineSaving');
    }
    function installing() {
      if (registration.installing) listen(registration.installing, 'statechange', report);
      report();
    }
    listen(registration, 'updatefound', installing); installing();
  }
  notify('offlineSaving');
  const url = new URL(match[1] + 'sw.js', win.location.origin);
  // Registration/update checks can contact the official site. No PC request or
  // credential passes through this registration API or is sent to the worker.
  serviceWorker.register(url.href, { scope:match[1], updateViaCache:'none' }).then(registration => {
    if (!stopped) observe(registration);
  }, async () => {
    try {
      const existing = await serviceWorker.getRegistration(url.href);
      if (!stopped && existing) observe(existing); else notify('offlineUnavailable');
    } catch { notify('offlineUnavailable'); }
  });
  return () => { stopped = true; cancelCheck(); for (const remove of removers) remove(); };
}
