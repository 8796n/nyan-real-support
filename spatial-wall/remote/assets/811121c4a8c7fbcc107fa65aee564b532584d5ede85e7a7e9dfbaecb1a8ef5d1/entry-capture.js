// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Run before module dependencies load. The invitation stays in this parent only.
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
