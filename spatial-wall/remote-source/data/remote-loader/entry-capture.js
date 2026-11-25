// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Run before module dependencies load and before the page is drawn: the invitation, which
// stays in this parent only, and the light or dark theme.
(() => {
  let value = location.hash ? location.href : null, failed = false;
  if (value) {
    try { history.replaceState(null, '', location.pathname + location.search); }
    catch { value = null; failed = true; }
  }
  Object.defineProperty(globalThis, 'SpatialRemoteEntryLink', { value() {
    const result = value; value = null;
    if (failed) throw Error('Unable to remove pairing invitation from the address');
    return result;
  } });
})();
// The remote's own theme choice (docs/remote-theme-plan.md), which it saves here under
// nyanRemoteTheme: "auto" (or none, or anything else) follows the phone. The page and its
// theme-color are set before the first paint; entry.mjs calls apply() when the remote saves.
(() => {
  const root = globalThis.document?.documentElement;
  if (!root) return;
  const BACKGROUND = { dark: '#101216', light: '#eef0f6' };
  let choice = 'auto', query = null;
  function render() {
    const theme = choice === 'light' || choice === 'auto' && !!query?.matches ? 'light' : 'dark';
    root.setAttribute('data-theme', theme);
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', BACKGROUND[theme]);
  }
  function apply(value) {
    choice = value === 'light' || value === 'dark' ? value : 'auto';
    render();
  }
  const saved = () => { try { return localStorage.getItem('nyanRemoteTheme'); } catch { return null; } };
  try {
    query = matchMedia('(prefers-color-scheme: light)');
    query.addEventListener('change', () => { if (choice === 'auto') render(); });
  } catch { query = null; }
  apply(saved());
  // Another tab of this entry saved a new choice.
  addEventListener('storage', event => { if (event.key === null || event.key === 'nyanRemoteTheme') apply(saved()); });
  Object.defineProperty(globalThis, 'SpatialRemoteEntryTheme', { value: Object.freeze({ apply }) });
})();
