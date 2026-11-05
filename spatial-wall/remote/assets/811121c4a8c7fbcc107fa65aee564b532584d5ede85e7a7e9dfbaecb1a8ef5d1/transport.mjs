// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>

const TEXT = 1;
const BINARY = 2;
const PING = 3;
const PONG = 4;
const EMPTY = new Uint8Array(0);
const STOPPED = Symbol('stopped');
const encoder = new TextEncoder();

function deferred() {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

function limit(value, minimum, maximum, name) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RangeError('Invalid ' + name);
  }
  return value;
}

// Call after transport.ready. One handle owns one stream, never the whole session.
// send* returns queue acceptance, not delivery; onClose reports failed delivery.
// Wire: u32BE body length (including kind), u8 kind, UTF-8/binary or empty Ping/Pong.
// A zero text/binary limit disables that kind, including empty messages.
export function openOrderedChannel(transport, {
  maxTextBytes, maxBinaryBytes, maxPendingBytes, maxPendingMessages,
  onText, onBinary, onPong, onClose,
}) {
  limit(maxTextBytes, 0, 0xfffffffe, 'maxTextBytes');
  limit(maxBinaryBytes, 0, 0xfffffffe, 'maxBinaryBytes');
  limit(maxPendingBytes, 5, Number.MAX_SAFE_INTEGER, 'maxPendingBytes');
  limit(maxPendingMessages, 1, Number.MAX_SAFE_INTEGER, 'maxPendingMessages');
  if (typeof onText !== 'function' || typeof onBinary !== 'function' ||
      typeof onPong !== 'function' || typeof onClose !== 'function') throw new TypeError('Channel callbacks are required');

  const ready = deferred();
  const closed = deferred();
  const stopped = deferred();
  // A caller may stop a channel before it starts awaiting ready.
  ready.promise.catch(() => {});
  let active = true;
  let opened = false;
  let reader, writer;
  let queuedBytes = 0;
  const queue = [];
  let writing = false;

  function finish(error = null) {
    if (!active) return;
    active = false;
    opened = false;
    queue.length = 0;
    queuedBytes = 0;
    stopped.resolve(STOPPED);
    ready.reject(error || new Error('Channel stopped before ready'));
    if (reader) {
      reader.cancel(error).catch(() => {});
      reader.releaseLock();
    }
    if (writer) {
      writer.abort(error).catch(() => {});
      writer.releaseLock();
    }
    closed.resolve(error);
    try { onClose(error); } catch { /* Teardown must complete even if the owner throws. */ }
  }

  function fail(reason) {
    finish(reason instanceof Error ? reason : new Error(String(reason)));
  }

  async function wait(operation) {
    const result = await Promise.race([operation, stopped.promise]);
    if (!active || result === STOPPED) throw STOPPED;
    return result;
  }

  function fits(payloadBytes) {
    if (queue.length >= maxPendingMessages || payloadBytes + 5 > maxPendingBytes - queuedBytes) {
      fail(new Error('Channel send queue limit exceeded'));
      return false;
    }
    return true;
  }

  async function writeQueued() {
    writing = true;
    try {
      while (active && queue.length) {
        const frame = queue[0];
        await wait(writer.write(frame));
        queue.shift();
        queuedBytes -= frame.byteLength;
      }
    } catch (error) {
      if (active) fail(error);
    } finally {
      writing = false;
    }
  }

  function enqueue(kind, payload) {
    if (!fits(payload.byteLength)) return false;
    const frame = new Uint8Array(payload.byteLength + 5);
    new DataView(frame.buffer).setUint32(0, payload.byteLength + 1);
    frame[4] = kind;
    frame.set(payload, 5);
    queue.push(frame);
    queuedBytes += frame.byteLength;
    if (!writing) void writeQueued();
    return true;
  }

  function sendText(text) {
    if (!opened) return false;
    if (typeof text !== 'string') throw new TypeError('Text payload must be a string');
    // UTF-8 needs at least one byte per UTF-16 code unit, and at most three.
    if (maxTextBytes === 0 || text.length > maxTextBytes) {
      fail(new Error('Text payload limit exceeded'));
      return false;
    }
    if (!fits(text.length)) return false;
    const payload = encoder.encode(text);
    if (payload.byteLength > maxTextBytes) {
      fail(new Error('Text payload limit exceeded'));
      return false;
    }
    return enqueue(TEXT, payload);
  }

  function sendBinary(bytes) {
    if (!opened) return false;
    if (!(bytes instanceof Uint8Array)) throw new TypeError('Binary payload must be Uint8Array');
    if (maxBinaryBytes === 0 || bytes.byteLength > maxBinaryBytes) {
      fail(new Error('Binary payload limit exceeded'));
      return false;
    }
    // enqueue checks bounds before making an owned snapshot, including Buffer views.
    return enqueue(BINARY, bytes);
  }

  function sendPing() {
    return opened && enqueue(PING, EMPTY);
  }

  async function receive() {
    const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
    let chunk = new Uint8Array(0);
    let offset = 0;
    async function readExactly(size, allowEnd = false) {
      const result = new Uint8Array(size);
      let copied = 0;
      while (copied < size) {
        if (offset === chunk.byteLength) {
          const next = await wait(reader.read());
          if (next.done) {
            if (allowEnd && copied === 0) return null;
            throw new Error('Truncated channel frame');
          }
          if (!(next.value instanceof Uint8Array)) throw new Error('Invalid channel byte chunk');
          chunk = next.value;
          offset = 0;
          if (!chunk.byteLength) continue;
        }
        const count = Math.min(size - copied, chunk.byteLength - offset);
        result.set(chunk.subarray(offset, offset + count), copied);
        copied += count;
        offset += count;
      }
      return result;
    }

    try {
      while (active) {
        const header = await readExactly(4, true);
        if (header === null) {
          if (queue.length) throw new Error('Channel closed with pending messages');
          finish();
          return;
        }
        const length = new DataView(header.buffer).getUint32(0);
        if (length < 1 || length - 1 > Math.max(maxTextBytes, maxBinaryBytes)) {
          throw new Error('Channel frame length limit exceeded');
        }
        const kind = (await readExactly(1))[0];
        if (kind === PING || kind === PONG) {
          if (length !== 1) throw new Error('Channel Ping/Pong payload must be empty');
          if (kind === PING) {
            if (!enqueue(PONG, EMPTY)) return;
          } else {
            await wait(onPong());
          }
          continue;
        }
        if (kind !== TEXT && kind !== BINARY) throw new Error('Unknown channel frame kind');
        const maximum = kind === TEXT ? maxTextBytes : maxBinaryBytes;
        if (maximum === 0 || length - 1 > maximum) {
          throw new Error('Channel payload limit exceeded');
        }
        const payload = await readExactly(length - 1);
        if (!active) return;
        await wait(kind === TEXT ? onText(decoder.decode(payload)) : onBinary(payload));
      }
    } catch (error) {
      if (active) fail(error);
    }
  }

  async function open() {
    try {
      const stream = await transport.createBidirectionalStream();
      if (!active) {
        stream.readable.cancel().catch(() => {});
        stream.writable.abort().catch(() => {});
        return;
      }
      reader = stream.readable.getReader();
      writer = stream.writable.getWriter();
      opened = true;
      ready.resolve();
      void receive();
    } catch (error) {
      if (active) fail(error);
    }
  }

  transport.closed.then(() => fail(new Error('WebTransport session closed')), fail);
  void open();
  return Object.freeze({
    ready: ready.promise,
    closed: closed.promise,
    sendText,
    sendBinary,
    sendPing,
    stop: () => finish(),
    get pendingBytes() { return queuedBytes; },
    get pendingMessages() { return queue.length; },
    get isOpen() { return opened; },
  });
}
