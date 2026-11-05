// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
// Only fixed official assets become bootstrap blobs, never PC response bytes.
export async function createFrameSource(win) {
  const names = ['frame.html', 'frame-bridge.js', 'frame.js'], urls = [];
  const controller = new AbortController();
  let rejectTimeout;
  const timeout = new Promise((_, reject) => { rejectTimeout = reject; });
  const timer = setTimeout(() => {
    controller.abort(); rejectTimeout(Error('Frame assets timed out'));
  }, 5000);
  const revoke = () => { for (const url of urls.splice(0)) URL.revokeObjectURL(url); };
  async function read(name) {
    const url = new URL('./' + name, import.meta.url);
    if (url.origin !== win.location.origin) throw Error('Frame assets must use the official origin');
    let response, reader;
    const cancel = () => { (reader ? reader.cancel() : response?.body?.cancel())?.catch(() => {}); };
    controller.signal.addEventListener('abort', cancel, {once:true});
    try {
      response = await win.fetch(url, { credentials:'omit', redirect:'error', cache:'no-cache', signal:controller.signal });
      if (controller.signal.aborted || response.status !== 200 || response.redirected || !response.body)
        throw Error('Frame asset unavailable');
      reader = response.body.getReader();
      const bytes = new Uint8Array(128 * 1024);
      let size = 0;
      while (true) {
        const part = await reader.read();
        if (controller.signal.aborted) throw Error('Frame assets stopped');
        if (part.done) break;
        if (!(part.value instanceof Uint8Array) || part.value.length > bytes.length - size)
          throw Error('Frame asset exceeds limit');
        bytes.set(part.value, size); size += part.value.length;
      }
      if (!size) throw Error('Empty frame asset');
      return new TextDecoder('utf-8', {fatal:true}).decode(bytes.subarray(0, size)).replace(/\r\n/g, '\n');
    } finally {
      controller.signal.removeEventListener('abort', cancel);
      if (reader) { reader.cancel().catch(() => {}); reader.releaseLock(); }
      else response?.body?.cancel().catch(() => {});
    }
  }
  try {
    const parts = await Promise.race([Promise.all(names.map(read)), timeout]);
    let html = parts[0];
    const hashes = [];
    for (let i = 1; i < names.length; i++) {
      const tag = `<script src="./${names[i]}"${i === 2 ? ' defer' : ''}></script>`;
      if (html.split(tag).length !== 2 || /<\/script/i.test(parts[i])) throw Error('Unexpected fixed frame template');
      const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(parts[i])));
      hashes.push("'sha256-" + btoa(String.fromCharCode(...hash)) + "'");
      html = html.replace(tag, '<script>' + parts[i] + '</script>');
    }
    html = html.replace("script-src 'self' blob:", 'script-src blob: ' + hashes.join(' '));
    if (controller.signal.aborted) throw Error('Frame assets stopped');
    const url = URL.createObjectURL(new Blob([html], {type:'text/html'}));
    urls.push(url);
    return {url, revoke};
  } catch (error) { revoke(); throw error; }
  finally { clearTimeout(timer); controller.abort(); }
}
