// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { parsePairingLink, androidIntent, lanServer, encodeBase64url } from './connection-data.mjs';
import { DEVICE_STORE_KEY, loadDevices, saveDevice, forgetDevice, forgetAllDevices } from './device-store.mjs';
import { enrollWithInvite, pairWithCode } from './pairing-client.mjs';
import { mountDeviceFrame } from './device-frame.mjs';
import { importProductKey } from './verify-ui.mjs';
import { supportsSecureRemote, localNetworkPermission } from './browser-support.mjs';
import { watchOffline } from './offline.mjs';
const SELECTED = 'spatial-wall.selected-pc.v2';
const WORDS = {
  en: {
    intro:'Connect to Spatial Wall on the same local network. Scan the QR shown on your PC, or enter its address and six-digit code.',
    'invite-title':'Register this browser', 'saved-title':'Saved PCs', empty:'No PCs saved in this browser.',
    'pair-title':'Register with a code', 'label-title':'Name shown on the PC', 'host-title':'PC address', 'port-title':'Port', 'code-title':'Six-digit code',
    register:'Register and connect', 'browser-pair':'Register and connect', 'open-app':'Open installed Android app',
    'app-note':'If the Android app is already installed, open it to register it with this PC.',
    glassesMissing:'This registration allows remote control only. To use glasses connected to Android, open Remote on the PC, turn on “Allow Android glasses bridge”, then register the Android app with a new QR or six-digit code.',
    'android-title':'Android app', 'download-app':'Get Android app from PC', 'save-apk':'Save APK',
    'download-note':'To install or update the Android app, connect to your PC and choose Get Android app from PC. Requires Android 11 or later on a 64-bit ARM device.',
    apkLoading:'Getting the Android app from your PC and verifying its signature…',
    apkReady:'Open the saved APK on Android to install it. Return here and choose Connect Android app, then Open installed Android app. No new QR is needed.',
    'handoff-app':'Connect Android app', handoffLoading:'Preparing the app connection…',
    handoffReady:'Ready. Choose Open installed Android app before this invitation expires and confirm the PC in the app.',
    handoffFailed:'Could not prepare the app connection. Check the PC has an up-to-date Spatial Wall installation and try again.',
    apkFailed:'Could not verify or download the Android app. Check the PC has an up-to-date Spatial Wall installation and try again.',
    'entry-source':'Entry source', 'entry-license':'MIT license',
    'local-note':'Saved access belongs to this browser. Removing it here does not revoke the device on your PC. Clearing browser data requires registration again.',
    'remove-all':'Clear saved PCs in this browser', cancel:'Cancel', disconnect:'Disconnect', 'change-pc':'Choose another PC',
    connect:'Connect', forget:'Remove here', label:'Phone browser', loading:'Loading secure connection tools…', ready:'Ready to connect.',
    unsupported:'Use Chrome or Chromium 156 or later on Android or desktop. iPhone and iPad browsers are not supported.',
    networkPrompt:'Select Connect to allow access to your PC on the local network.',
    networkDenied:'Allow local network access in this site’s browser settings, then select Connect.',
    offlineSaving:'Saving the official connection tools for offline use…',
    offlineReady:'Offline connection tools are ready. Your PC must remain reachable on the LAN.',
    offlineNext:'Offline files are saved. Close and reopen this page to use them on your next visit.',
    offlineUpdate:'An update is saved. Close all remote tabs and any remote opened from the home screen, then reopen the remote.',
    offlineUnavailable:'Offline files are not confirmed. Keep internet access available and reopen this page to retry.',
    runtimeNotice:'Wasm runtime licenses', boringNotice:'BoringSSL licenses',
    invalidLink:'This registration link is invalid. Scan a new QR from your PC.',
    storeError:'Saved registrations cannot be read. Explicitly clear them here to register again.',
    toolsError:'Connection tools could not be loaded. Open this page online and try again.',
    registering:'Registering with your PC…', connecting:'Connecting to your PC…', connected:'Connected.', cancelled:'Cancelled.',
    failed:'Connection failed. Check the PC and local network; if access was removed, register again.',
    saveError:'Registration could not be saved. No remote was opened. Check browser storage and register again.',
    removed:'Saved access was removed from this browser.', changed:'Saved registrations changed in another tab. The connection was closed.',
    invalidInput:'Enter a local IPv4 address, a port from 1 to 65535, and exactly six digits.',
    disconnected:'Disconnected.', app:'Opening the installed Android app…', selectionError:'The PC selection could not be saved for reloading.',
    remote:'Remote', glasses:'Android glasses bridge', embedded:'Open this page directly to connect.',
  },
  ja: {
    intro:'同じLAN内のSpatial Wallに接続します。PCに表示されたQRを読み取るか、PCのアドレスと6桁コードを入力してください。',
    'invite-title':'このブラウザを登録', 'saved-title':'保存済みPC', empty:'このブラウザに保存済みのPCはありません。',
    'pair-title':'コードで登録', 'label-title':'PCに表示する端末名', 'host-title':'PCのアドレス', 'port-title':'ポート', 'code-title':'6桁コード',
    register:'登録して接続', 'browser-pair':'登録して接続', 'open-app':'インストール済みAndroidアプリを開く',
    'app-note':'Androidアプリをすでにインストールしている場合は、アプリを開いてこのPCに登録できます。',
    glassesMissing:'この登録で使えるのはリモコン操作のみです。Androidにつないだメガネを使うには、PCの「リモコン操作」で「Android メガネブリッジを許可」をオンにして、新しいQRか6桁コードでAndroidアプリを登録してください。',
    'android-title':'Androidアプリ', 'download-app':'PCからAndroidアプリを取得', 'save-apk':'APKを保存',
    'download-note':'Androidアプリをインストール・更新するには、PCに接続して「PCからAndroidアプリを取得」を選びます。Android 11以降の64ビットARM端末で利用できます。',
    apkLoading:'PCからAndroidアプリを取得し、製品署名を確認しています…',
    apkReady:'保存したAPKをAndroidで開いてインストールしてください。この画面に戻り「アプリへ接続を引き継ぐ」→「インストール済みAndroidアプリを開く」を選びます。QRの再読取は不要です。',
    'handoff-app':'アプリへ接続を引き継ぐ', handoffLoading:'アプリへの接続を準備しています…',
    handoffReady:'準備できました。期限が切れる前に「インストール済みAndroidアプリを開く」を選び、アプリで接続先のPCを確認してください。',
    handoffFailed:'接続を引き継げませんでした。PCのSpatial Wallが最新版か確認して、もう一度お試しください。',
    apkFailed:'Androidアプリを取得・検証できませんでした。PCのSpatial Wallが最新版か確認して、もう一度お試しください。',
    'entry-source':'入口のソース', 'entry-license':'MITライセンス',
    'local-note':'保存したアクセスはこのブラウザ専用です。ここで削除してもPC上の端末登録は解除されません。ブラウザのデータを消した場合は再登録が必要です。',
    'remove-all':'このブラウザの保存済みPCを削除', cancel:'キャンセル', disconnect:'切断', 'change-pc':'別のPCを選ぶ',
    connect:'接続', forget:'このブラウザから削除', label:'スマホブラウザ', loading:'安全な接続に必要な機能を読み込んでいます…', ready:'接続できます。',
    unsupported:'AndroidまたはPCのChrome / Chromium 156以降を使用してください。iPhone・iPadのブラウザには対応していません。',
    networkPrompt:'「接続」を押して、LAN内のPCへのアクセスを許可してください。',
    networkDenied:'ブラウザのこのサイトの設定でローカルネットワークへのアクセスを許可してから、「接続」を押してください。',
    offlineSaving:'公式の接続機能をオフライン用に保存しています…',
    offlineReady:'オフライン用の接続機能を利用できます。PCにはLANで接続できる必要があります。',
    offlineNext:'オフライン用ファイルを保存しました。このページを閉じて開き直すと、次回から利用できます。',
    offlineUpdate:'更新を保存しました。リモコンの全タブと、ホーム画面から開いたリモコンを閉じて、開き直してください。',
    offlineUnavailable:'オフライン用ファイルの保存を確認できません。ネット接続を保ち、このページを開き直して再試行してください。',
    runtimeNotice:'Wasm実行環境のライセンス', boringNotice:'BoringSSLのライセンス',
    invalidLink:'登録リンクが無効です。PCの新しいQRを読み取ってください。',
    storeError:'保存済みの登録を読み取れません。再登録する場合は、このブラウザの保存済みPCを明示的に削除してください。',
    toolsError:'接続機能を読み込めませんでした。インターネットに接続してこのページを開き直してください。',
    registering:'PCに登録しています…', connecting:'PCに接続しています…', connected:'接続しました。', cancelled:'キャンセルしました。',
    failed:'接続できませんでした。PCとLANを確認し、アクセスが解除された場合は再登録してください。',
    saveError:'登録を保存できなかったためリモコンを開きませんでした。ブラウザの保存領域を確認し、再登録してください。',
    removed:'このブラウザの保存済みアクセスを削除しました。', changed:'別のタブで保存済み登録が変更されたため、接続を閉じました。',
    invalidInput:'LANのIPv4アドレス、1〜65535のポート、数字6桁を入力してください。',
    disconnected:'切断しました。', app:'インストール済みAndroidアプリを開いています…', selectionError:'再読込用のPC選択を保存できませんでした。',
    remote:'リモコン', glasses:'Androidメガネブリッジ', embedded:'接続するには、このページを直接開いてください。',
  },
};
const defaults = { loadDevices, saveDevice, forgetDevice, forgetAllDevices, parsePairingLink, watchOffline,
  enrollWithInvite, pairWithCode, mountDeviceFrame,
  async loadKey() {
    const response = await fetch(new URL('./product-key.pem', import.meta.url), { credentials:'omit', redirect:'error', referrerPolicy:'no-referrer' });
    if (!response.ok) throw Error('Product key unavailable');
    return importProductKey(await response.text());
  },
  async loadModule() { const { default: createModule } = await import('./pairing-core.mjs'); return createModule(); },
};

// Dependencies can be supplied by local tests; the product entry always uses the
// fixed official modules above, never code or import URLs received from a PC.
export async function startLauncher(win = globalThis, dependencies = {}) {
  const api = { ...defaults, ...dependencies }, doc = win.document, $ = id => doc.getElementById(id);
  let language = /^ja\b/i.test(win.navigator.language) ? 'ja' : 'en', message = 'loading';
  let devices = [], invitation = null, active = null, frame = null, disposed = false, constrained = null;
  let blocked = false, productKey, module, revision = 0;
  let offlineStatus = 'offlineUnavailable', stopOffline;
  const listeners = [], supported = supportsSecureRemote(win);
  const android = /Android/i.test(win.navigator.userAgent);
  const text = key => WORDS[language][key];
  const wipe = values => { for (const device of values) device.secret.fill(0); };
  function listen(target, name, callback) { target.addEventListener(name, callback); listeners.push(() => target.removeEventListener(name, callback)); }
  function status(key) { if (!disposed) { message = key; $('status').textContent = text(key); } }
  function current(op) { return !disposed && active === op && !op.abort.signal.aborted; }
  function clearInvitation() { invitation?.invite.fill(0); invitation = null; }
  function render() {
    if (disposed) return;
    doc.documentElement.lang = language; $('language').value = language;
    for (const key of ['intro','invite-title','saved-title','empty','pair-title','label-title','host-title','port-title','code-title',
      'register','browser-pair','open-app','local-note','remove-all','cancel','disconnect','change-pc',
      'android-title','download-app','download-note','entry-source','entry-license']) $(key).textContent = text(key);
    $('status').textContent = text(message);
    $('offline-status').textContent = text(offlineStatus);
    $('runtime-notice').textContent = text('runtimeNotice'); $('boring-notice').textContent = text('boringNotice');
    $('invite').hidden = !invitation;
    if (invitation) {
      $('invite-host').textContent = invitation.host + ':' + invitation.port;
      $('invite-grants').textContent = [[1,'remote'],[2,'glasses']].filter(([bit]) => invitation.grants & bit).map(([,key]) => text(key)).join(' / ');
      $('app-note').textContent = text(invitation.grants & 2 ? 'app-note' : 'glassesMissing');
    }
    $('browser-pair').disabled = !!active || blocked || !supported || !productKey || !invitation || !(invitation.grants & 1);
    $('open-app').hidden = $('app-note').hidden = $('android-app').hidden = !android;
    $('open-app').disabled = !android || !!active || !invitation;
    $('register').disabled = !!active || blocked || !supported || !module || !productKey;
    $('remove-all').disabled = !!active;
    $('host').readOnly = $('port').readOnly = !!constrained;
    $('change-pc').hidden = !constrained; $('change-pc').disabled = !!active;
    $('cancel').hidden = !active; $('panel').hidden = !!active?.ready; $('remote').hidden = !active?.ready;
    $('download-app').disabled = !android || !frame || !active?.controlOpen || active.apkStatus === 'apkLoading';
    $('download-app').hidden = !android || !!active?.apkUrl;
    $('handoff-app').hidden = !android;
    $('handoff-app').disabled = !frame || !active?.ready || active.handoffStatus === 'handoffLoading';
    $('handoff-app').textContent = text(active?.appUrl ? 'open-app' : 'handoff-app');
    $('handoff-status').hidden = !active?.handoffStatus;
    $('handoff-status').textContent = active?.handoffStatus ? text(active.handoffStatus) : '';
    if (active?.handoffStatus === 'handoffReady' && !active.appGlasses)
      $('handoff-status').textContent += ' ' + text('glassesMissing');
    $('save-apk').hidden = !android || !active?.apkUrl;
    if (active?.apkUrl) {
      $('save-apk').href = active.apkUrl;
      $('save-apk').textContent = text('save-apk') + ' (' + active.apkVersion + ')';
    } else {
      $('save-apk').removeAttribute('href');
    }
    $('apk-status').hidden = !active?.apkStatus;
    $('apk-status').textContent = active?.apkStatus ? text(active.apkStatus) : '';
    $('empty').hidden = devices.length !== 0; $('saved').replaceChildren();
    for (const device of devices) {
      const row = doc.createElement('div'), name = doc.createElement('span'), connect = doc.createElement('button'), remove = doc.createElement('button');
      row.className = 'saved-pc'; name.textContent = device.host + ':' + device.port;
      connect.type = remove.type = 'button'; connect.textContent = text('connect'); remove.textContent = text('forget');
      connect.disabled = !!active || blocked || !supported || !productKey; remove.disabled = !!active;
      connect.addEventListener('click', () => select(device));
      remove.addEventListener('click', () => { void removeSaved(device); });
      row.append(name, connect, remove); $('saved').append(row);
    }
  }
  async function refresh() {
    const generation = ++revision;
    let saved;
    try { saved = await api.loadDevices(win.localStorage); }
    catch { if (!disposed && revision === generation) { blocked = true; status('storeError'); } return false; }
    if (disposed || revision !== generation) { wipe(saved); return false; }
    wipe(devices); devices = saved; blocked = false; render(); return true;
  }
  function cancel(key = 'cancelled') {
    const old = active; active = null; old?.abort.abort(); frame?.close(); frame = null;
    clearTimeout(old?.appTimer); if (old) old.appUrl = null;
    if (old?.apkUrl) win.URL.revokeObjectURL(old.apkUrl);
    $('code').value = ''; status(key); render();
  }
  function reload(selection) {
    try { win.sessionStorage.setItem(SELECTED, selection); }
    catch { status('selectionError'); return; }
    cancel(); clearInvitation(); win.location.reload();
  }
  function constrain(host, port) {
    const server = lanServer(host, port);
    if (constrained) return constrained === server;
    const http = new URL(server), https = new URL(server); https.protocol = 'https:'; https.port = String(port);
    const policy = doc.createElement('meta'); policy.httpEquiv = 'Content-Security-Policy';
    policy.content = `connect-src 'self' ${http.origin} ${https.origin} 'unsafe-webtransport-hashes'`;
    doc.head.append(policy); constrained = server; $('host').value = host; $('port').value = String(port); render(); return true;
  }
  function begin(key) {
    if (disposed || active || blocked || !supported || !productKey) return null;
    const op = { abort:new AbortController(), ready:false }; active = op; status(key); render(); return op;
  }
  async function connect(op, device) {
    if (!current(op)) return;
    status('connecting');
    const mounted = await api.mountDeviceFrame({ device, container:$('viewport'), storage:win.localStorage, productKey, signal:op.abort.signal,
      onReady() { if (current(op)) { op.ready = true; status('connected'); render(); } },
      onControlChange(open) { if (current(op)) { op.controlOpen = open; render(); } },
      onClose() { if (current(op)) cancel(op.ready ? 'disconnected' : 'failed'); },
    });
    if (!current(op)) { mounted.close(); return; }
    frame = mounted; render();
  }
  async function prepareApk() {
    const op = active;
    if (!android || !frame || !op?.controlOpen || op.apkUrl || op.apkStatus === 'apkLoading') return;
    op.apkStatus = 'apkLoading'; render();
    try {
      const { blob, version } = await frame.downloadApk();
      if (!current(op)) return;
      op.apkUrl = win.URL.createObjectURL(blob); op.apkVersion = version; op.apkStatus = 'apkReady';
    } catch { if (current(op)) op.apkStatus = 'apkFailed'; }
    render();
  }
  async function handoffApp() {
    const op = active;
    if ($('handoff-app').disabled || $('handoff-app').hidden) return;
    if (op.appUrl && performance.now() < op.appExpiry) {
      const url = op.appUrl; op.appUrl = null; clearTimeout(op.appTimer);
      op.handoffStatus = null; render(); win.location.assign(url); return;
    }
    op.appUrl = null; op.handoffStatus = 'handoffLoading'; render();
    op.appExpiry = performance.now() + 55000;
    let invitation;
    try {
      invitation = await frame.appInvitation();
      if (!current(op)) return;
      if (performance.now() >= op.appExpiry) throw Error('App invitation expired');
      op.appGlasses = !!(invitation.grants & 2);
      op.appUrl = androidIntent(invitation); op.handoffStatus = 'handoffReady';
      // Keep app launch on a separate user tap, independent of network latency.
      op.appTimer = setTimeout(() => {
        op.appUrl = null; op.handoffStatus = null; if (current(op)) render();
      }, op.appExpiry - performance.now());
    } catch { if (current(op)) op.handoffStatus = 'handoffFailed'; }
    finally { invitation?.invite.fill(0); }
    render();
  }
  function select(device) {
    if (active) return;
    if (!constrain(device.host, device.port)) { reload(encodeBase64url(device.spki)); return; }
    const op = begin('connecting'); if (!op) return;
    void connect(op, device).catch(() => { if (current(op)) cancel('failed'); });
  }
  async function register(qr) {
    let password, result, endpoint, op;
    try {
      const host = qr ? invitation.host : $('host').value, portText = qr ? String(invitation.port) : $('port').value;
      if (!/^[1-9][0-9]{0,4}$/.test(portText) || String(Number(portText)) !== portText) throw Error();
      endpoint = { host, port:Number(portText) }; lanServer(host, endpoint.port);
      if (!qr) {
        const value = $('code').value; $('code').value = '';
        if (value.length !== 6 || !/^[0-9]{6}$/.test(value) || !module) throw Error();
        password = new TextEncoder().encode(value);
      }
      if (!constrain(host, endpoint.port)) { reload('choose'); return; }
      op = begin('registering'); if (!op) return;
      const options = { server:lanServer(host,endpoint.port), label:$('label').value, signal:op.abort.signal };
      result = qr ? await api.enrollWithInvite({ ...options, ...invitation }) : await api.pairWithCode({ ...options, module, code:password });
      if (!current(op)) return;
      if (!(result.grants & 1)) throw Error();
      if (qr) clearInvitation();
      try { await api.saveDevice(win.localStorage, { ...endpoint, ...result }); }
      catch { if (current(op)) cancel('saveError'); return; }
      if (!await refresh()) { if (current(op)) cancel('storeError'); return; }
      if (current(op)) await connect(op, { ...endpoint, ...result });
    } catch { if (op ? current(op) : !disposed) { if (op) cancel('failed'); else status('invalidInput'); } }
    finally { password?.fill(0); result?.secret.fill(0); render(); }
  }
  async function removeSaved(device) {
    if (active) return;
    try { await api.forgetDevice(win.localStorage, device.spki); if (await refresh()) status('removed'); }
    catch { blocked = true; status('storeError'); }
    render();
  }
  function dispose() {
    if (disposed) return;
    cancel(); disposed = true; ++revision; clearInvitation(); wipe(devices); devices = [];
    stopOffline?.();
    for (const remove of listeners) remove();
  }
  if (win.top !== win) { win.SpatialRemoteEntryLink?.(); status('embedded'); return { dispose }; }
  listen($('language'),'change',() => { language = $('language').value === 'ja' ? 'ja' : 'en'; render(); });
  listen($('pair-form'),'submit',event => { event.preventDefault(); if (!$('register').disabled) void register(false); });
  listen($('browser-pair'),'click',() => { if (!$('browser-pair').disabled) void register(true); });
  listen($('cancel'),'click',() => cancel()); listen($('disconnect'),'click',() => cancel('disconnected'));
  listen($('download-app'),'click',() => { void prepareApk(); });
  listen($('handoff-app'),'click',() => { void handoffApp(); });
  listen($('change-pc'),'click',() => reload('choose'));
  listen($('remove-all'),'click',() => {
    if (active) return;
    try { api.forgetAllDevices(win.localStorage); void refresh().then(ok => { if (ok) status('removed'); render(); }); }
    catch { blocked = true; status('storeError'); render(); }
  });
  listen($('open-app'),'click',() => {
    if ($('open-app').disabled) return;
    const url = androidIntent(invitation); clearInvitation(); cancel('app'); win.location.assign(url);
  });
  listen(win,'storage',event => {
    if (event.storageArea !== win.localStorage || event.key !== null && event.key !== DEVICE_STORE_KEY) return;
    cancel('changed'); void refresh();
  });
  listen(win,'pagehide',dispose);
  // A BFCache document must not revive its already closed credential owner.
  win.addEventListener('pageshow', event => { if (event.persisted) win.location.reload(); });
  $('label').value = text('label'); render();
  let hadLink = false, selection = null;
  try {
    const value = win.SpatialRemoteEntryLink(); hadLink = value !== null;
    if (hadLink) {
      const parsed = await api.parsePairingLink(value);
      if (disposed) parsed.invite.fill(0); else invitation = parsed;
    }
  } catch { hadLink = true; status('invalidLink'); }
  try { selection = win.sessionStorage.getItem(SELECTED); win.sessionStorage.removeItem(SELECTED); } catch {}
  await refresh();
  if (disposed) return { dispose };
  if (!hadLink && !blocked) status(supported ? 'loading' : 'unsupported');
  if (!supported && !blocked) status('unsupported');
  render();
  if (supported) {
    stopOffline = api.watchOffline(win, key => { if (!disposed) { offlineStatus = key; render(); } });
    void api.loadModule().then(value => { if (!disposed) { module = value; render(); } }, () => { if (!disposed) { status('toolsError'); render(); } });
    try { productKey = await api.loadKey(); }
    catch { if (!disposed) { status('toolsError'); render(); } return { dispose }; }
    if (!disposed) {
      if (!blocked && message === 'loading') status('ready'); render();
      if (!hadLink && !blocked) {
        const selected = selection ? devices.find(d => encodeBase64url(d.spki) === selection) : devices.length === 1 ? devices[0] : null;
        if (selected) {
          const generation = revision;
          const permission = await localNetworkPermission(win);
          if (!disposed && !active && !blocked && revision === generation && devices.includes(selected)) {
            if (permission === 'granted') select(selected);
            else { status(permission === 'denied' ? 'networkDenied' : 'networkPrompt'); render(); }
          }
        }
      }
    }
  }
  return { dispose };
}

if (globalThis.document) void startLauncher().catch(() => {
  document.getElementById('status').textContent = 'Unable to start secure remote. / 安全なリモコンを開始できませんでした。';
});
