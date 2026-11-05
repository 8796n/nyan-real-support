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
    intro:'Scan the QR on your PC, or register with a six-digit code.',
    'invite-title':'Register with this PC', 'saved-title':'Saved PCs',
    'pair-title':'Register with a code', 'label-title':'Name shown on the PC', 'host-title':'PC address', 'port-title':'Port', 'code-title':'Six-digit code',
    register:'Register and connect', 'browser-pair':'Register and connect', 'open-app':'Open in Android app',
    'app-note':'If the Android app is installed, you can register it instead.',
    glassesMissing:'This registration covers remote control only. To use glasses connected to Android, turn on “Allow Android glasses bridge” under Remote on the PC, then register the app with a new QR or six-digit code.',
    'android-title':'Android app', 'android-menu-button':'Android app', 'download-app':'Get app from PC', 'save-apk':'Save APK',
    'download-note':'To install or update the app, connect to your PC and open the Android app menu. Android 11 or later, 64-bit ARM.',
    apkLoading:'Getting the app from your PC and checking its signature…',
    apkReady:'Open the saved APK to install it, then come back and choose Hand off to app.',
    'handoff-app':'Hand off to app', handoffLoading:'Preparing…',
    handoffReady:'Within a minute, choose Open in Android app and confirm the PC in the app.',
    handoffFailed:'Could not prepare the handoff. Update Spatial Wall on the PC and try again.',
    apkFailed:'Could not verify or download the app. Update Spatial Wall on the PC and try again.',
    'entry-source':'Source', 'entry-license':'MIT license',
    'local-note':'Registrations are stored in this browser only. Removing them here does not remove this device from the PC.',
    'remove-all':'Remove all from this browser', cancel:'Cancel', disconnect:'Disconnect', 'change-pc':'Choose another PC',
    connect:'Connect', forget:'Remove', label:'Phone browser', loading:'Loading…', ready:'',
    unsupported:'Open this page in Chrome 156 or later on Android or a PC. iPhone and iPad are not supported.',
    networkPrompt:'Select Connect and allow access to your local network.',
    networkDenied:'Allow local network access for this site in browser settings, then select Connect.',
    offlineUpdate:'An update is ready. Close every remote tab and home-screen window, then reopen.',
    offlineUnavailable:'Offline files are not confirmed. Go online and reopen this page.',
    runtimeNotice:'Wasm licenses', boringNotice:'BoringSSL licenses',
    invalidLink:'This QR link is invalid. Show a new QR on your PC.',
    storeError:'Saved registrations cannot be read. Choose Remove all from this browser, then register again.',
    toolsError:'Could not load. Go online and reopen this page.',
    registering:'Registering…', connecting:'Connecting…', connected:'Connected.', cancelled:'Cancelled.',
    failed:'Could not connect. Check the PC and network. If the PC removed this device, register again.',
    saveError:'Registration could not be saved. Check browser storage and register again.',
    removed:'Removed from this browser.', changed:'Registrations changed in another tab, so the connection was closed.',
    invalidInput:'Check the PC address (for example 192.168.1.7), the port and the six-digit code.',
    disconnected:'Disconnected.', app:'Opening the Android app…', selectionError:'The PC selection could not be saved for reloading.',
    remote:'Remote', glasses:'Android glasses bridge', embedded:'Open this page directly to connect.',
  },
  ja: {
    intro:'PCに表示されたQRを読み取るか、6桁コードで登録します。',
    'invite-title':'このPCに登録', 'saved-title':'登録済みのPC',
    'pair-title':'コードで登録', 'label-title':'PCに表示する名前', 'host-title':'PCのアドレス', 'port-title':'ポート', 'code-title':'6桁コード',
    register:'登録して接続', 'browser-pair':'登録して接続', 'open-app':'Androidアプリで開く',
    'app-note':'Androidアプリを入れている場合は、アプリで登録することもできます。',
    glassesMissing:'この登録で使えるのはリモコン操作だけです。Androidにつないだメガネも使うには、PCの「リモコン操作」で「Android メガネブリッジを許可」をオンにして、新しいQRか6桁コードでアプリを登録してください。',
    'android-title':'Androidアプリ', 'android-menu-button':'Androidアプリ', 'download-app':'PCからアプリを取得', 'save-apk':'APKを保存',
    'download-note':'アプリのインストールと更新は、PCに接続してから「Androidアプリ」メニューで行います。Android 11以降・64ビットARM。',
    apkLoading:'PCからアプリを取得し、署名を確認しています…',
    apkReady:'保存したAPKを開いてインストールし、ここに戻って「アプリに引き継ぐ」を選んでください。',
    'handoff-app':'アプリに引き継ぐ', handoffLoading:'準備しています…',
    handoffReady:'1分以内に「Androidアプリで開く」を選び、アプリで接続先のPCを確認してください。',
    handoffFailed:'引き継げませんでした。PCのSpatial Wallを最新にして、もう一度お試しください。',
    apkFailed:'アプリを取得・検証できませんでした。PCのSpatial Wallを最新にして、もう一度お試しください。',
    'entry-source':'ソース', 'entry-license':'MITライセンス',
    'local-note':'登録はこのブラウザにだけ保存されます。ここで削除してもPC側の登録は残ります。',
    'remove-all':'このブラウザの登録をすべて削除', cancel:'キャンセル', disconnect:'切断', 'change-pc':'別のPCを選ぶ',
    connect:'接続', forget:'削除', label:'スマホのブラウザ', loading:'読み込んでいます…', ready:'',
    unsupported:'AndroidまたはPCのChrome 156以降で開いてください。iPhone・iPadには対応していません。',
    networkPrompt:'「接続」を押して、LAN内のPCへのアクセスを許可してください。',
    networkDenied:'ブラウザのサイト設定でローカルネットワークへのアクセスを許可してから、「接続」を押してください。',
    offlineUpdate:'更新があります。リモコンのタブとホーム画面から開いたリモコンをすべて閉じて、開き直してください。',
    offlineUnavailable:'オフライン用ファイルの保存を確認できません。インターネットにつないで開き直してください。',
    runtimeNotice:'Wasmのライセンス', boringNotice:'BoringSSLのライセンス',
    invalidLink:'このQRのリンクは無効です。PCで新しいQRを表示してください。',
    storeError:'登録を読み込めません。「このブラウザの登録をすべて削除」を押してから登録し直してください。',
    toolsError:'読み込めませんでした。インターネットに接続して開き直してください。',
    registering:'登録しています…', connecting:'接続しています…', connected:'接続しました。', cancelled:'キャンセルしました。',
    failed:'接続できませんでした。PCとネットワークを確認してください。PC側で削除された場合は登録し直してください。',
    saveError:'登録を保存できませんでした。ブラウザの保存領域を確認して、登録し直してください。',
    removed:'このブラウザから削除しました。', changed:'別のタブで登録が変わったため、接続を閉じました。',
    invalidInput:'PCのアドレス（例: 192.168.1.7）、ポート、6桁コードを確認してください。',
    disconnected:'切断しました。', app:'Androidアプリを開いています…', selectionError:'再読み込み用のPC選択を保存できませんでした。',
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
    for (const key of ['intro','invite-title','saved-title','pair-title','label-title','host-title','port-title','code-title',
      'register','browser-pair','open-app','local-note','remove-all','cancel','disconnect','change-pc',
      'android-title','android-menu-button','download-app','download-note','entry-source','entry-license']) $(key).textContent = text(key);
    $('status').textContent = text(message);
    // Offline caching is silent unless the user has to act on it: an update, or a cache that failed.
    $('offline-status').textContent = text(offlineStatus) || '';
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
    $('remote-pc').textContent = active?.label || ''; $('android-menu-button').hidden = !android;
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
    $('saved-section').hidden = devices.length === 0; $('saved').replaceChildren();
    for (const device of devices) {
      const row = doc.createElement('div'), name = doc.createElement('span'), connect = doc.createElement('button'), remove = doc.createElement('button');
      row.className = 'saved-pc'; name.textContent = device.host + ':' + device.port;
      connect.type = remove.type = 'button'; connect.textContent = text('connect'); remove.textContent = text('forget');
      connect.className = 'primary'; remove.className = 'text danger';
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
    wipe(devices); devices = saved; blocked = false;
    if (!devices.length) $('pair-box').open = true; // nothing saved: the code form is the way in
    render(); return true;
  }
  function cancel(key = 'cancelled') {
    const old = active; active = null; old?.abort.abort(); frame?.close(); frame = null;
    clearTimeout(old?.appTimer); if (old) old.appUrl = null;
    if (old?.apkUrl) win.URL.revokeObjectURL(old.apkUrl);
    $('android-menu').hidePopover?.();
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
    op.label = device.host + ':' + device.port; status('connecting');
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
  if (selection === 'choose') $('pair-box').open = true;
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
