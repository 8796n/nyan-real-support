// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
(() => {
  'use strict';
  const nonce = location.hash.slice(1), officialOrigin = new URL(location.href).origin;
  if (parent === window || !/^[a-f0-9]{64}$/.test(nonce)) return;
  // These assets are now loaded. A second policy removes external script loads
  // before the signed UI is installed; CSP policies intersect, never relax.
  const policy = document.createElement('meta');
  policy.httpEquiv = 'Content-Security-Policy';
  policy.content = "default-src 'none'; script-src blob:; style-src 'unsafe-inline'; img-src data: blob:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'";
  document.head.append(policy);
  let accepted = false, closed = false, bridge = null;
  const scripts = new Map();
  const timeout = setTimeout(close, 5000);
  function close() {
    if (closed) return;
    closed = true;
    clearTimeout(timeout);
    window.removeEventListener('message', initialize);
    window.removeEventListener('error', close);
    if (bridge) bridge.close();
    for (const [url, cancel] of scripts) { URL.revokeObjectURL(url); cancel(); }
    scripts.clear();
  }
  async function mount(html, host) {
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    // The canonical UI is self-contained. Do not silently expand this contract
    // to load PC-provided assets as scripts, nested pages or network resources.
    if (parsed.querySelector('base,link,iframe,frame,object,embed,form,script[src]') ||
        [...parsed.querySelectorAll('script')].some(s => s.type && s.type !== 'text/javascript') ||
        [...parsed.querySelectorAll('*')].some(n => [...n.attributes].some(a => /^on/i.test(a.name))))
      throw Error('Remote UI is not self-contained');
    Object.defineProperty(window, 'NyanRemoteHost', { value: host });
    for (const style of parsed.querySelectorAll('style')) document.head.append(document.importNode(style, true));
    const sources = [...parsed.querySelectorAll('script')].map(s => s.textContent);
    parsed.querySelectorAll('script').forEach(s => s.remove());
    document.body.replaceChildren(...[...parsed.body.childNodes].map(n => document.importNode(n, true)));
    for (const text of sources) {
      if (closed) throw Error('Frame closed during bootstrap');
      const url = URL.createObjectURL(new Blob([text], {type:'text/javascript'}));
      const script = document.createElement('script'); script.async = false; script.src = url;
      await new Promise((resolve, reject) => {
        const clear = () => { URL.revokeObjectURL(url); scripts.delete(url); script.onload = script.onerror = null; };
        scripts.set(url, () => { script.remove(); reject(Error('Frame script stopped')); });
        script.onerror = () => { clear(); reject(Error('Frame script failed')); };
        script.onload = () => { clear(); resolve(); };
        document.body.append(script);
      });
    }
  }
  function initialize(event) {
    if (accepted || event.source !== parent || event.origin !== officialOrigin ||
        !event.data || Object.keys(event.data).sort().join(',') !== 'nonce,type' ||
        event.data.type !== 'spatial-frame-init' || event.data.nonce !== nonce || event.ports.length !== 1) return;
    accepted = true;
    clearTimeout(timeout);
    window.removeEventListener('message', initialize);
    bridge = SpatialRemoteFrame.client(event.ports[0], mount, close);
  }
  window.addEventListener('message', initialize);
  window.addEventListener('error', close);
  window.addEventListener('pagehide', close, { once: true });
  parent.postMessage({ type: 'spatial-frame-ready', nonce }, officialOrigin);
})();
