// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
export const MIN_CHROMIUM_MAJOR = 156;

// Compatibility admission, not an attestation of a modified browser. Unknown
// engines and iOS WebKit are rejected before any credential or LAN operation.
export function supportsSecureRemote(win) {
  try {
    const nav = win.navigator, ua = nav.userAgent || '';
    if (!win.isSecureContext || typeof win.WebTransport !== 'function' || !win.crypto?.subtle || !win.WebAssembly ||
        /iPad|iPhone|iPod|CriOS|FxiOS|EdgiOS/i.test(ua) ||
        nav.platform === 'MacIntel' && nav.maxTouchPoints > 1 ||
        /; wv\)/.test(ua) || /Android/.test(ua) && /Version\/4\.0/.test(ua)) return false;
    let version;
    if (nav.userAgentData !== undefined) {
      const data = nav.userAgentData;
      if (!['Android','Windows','macOS','Linux','Chrome OS'].includes(data?.platform) || !Array.isArray(data.brands)) return false;
      const chromium = data.brands.filter(value => value?.brand === 'Chromium');
      if (chromium.length !== 1 || typeof chromium[0].version !== 'string') return false;
      version = chromium[0].version;
    } else {
      if (!/Android|Windows NT|Macintosh|Linux|CrOS/.test(ua)) return false;
      version = /(?:^|[ ;])(?:Chrome|Chromium|HeadlessChrome)\/([1-9][0-9]{0,3})\./.exec(ua)?.[1];
    }
    if (!version || !/^[1-9][0-9]{0,3}$/.test(version) || String(Number(version)) !== version ||
        Number(version) < MIN_CHROMIUM_MAJOR) return false;
    // Dictionary conversion precedes URL validation. Recognizing this member
    // must propagate our getter's exception; ignoring it fails the invalid URL.
    // Neither case starts a valid network connection or sends a credential.
    const marker = {};
    try {
      const transport = new win.WebTransport(':', { get serverCertificateHashes() { throw marker; } });
      transport.closed?.catch(() => {}); transport.close();
    } catch (error) { return error === marker; }
    return false;
  } catch { return false; }
}

export async function localNetworkPermission(win) {
  try {
    const { state } = await win.navigator.permissions.query({ name: 'local-network' });
    return ['granted','denied'].includes(state) ? state : 'prompt';
  } catch { return 'prompt'; }
}
