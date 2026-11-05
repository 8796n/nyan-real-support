// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>

// The owner supplies expiry, attempt limits, and the authenticated enrollment channel.
// No protocol, key derivation, or confirmation logic lives in this heap adapter.
export function createPairing(module, role, context, code) {
  let session = 0;
  const close = () => {
    const owned = session;
    session = 0;
    if (owned) module._nyan_pairing_free(owned);
  };
  const fail = () => { close(); throw new Error('Pairing operation rejected'); };
  function buffer(bytes, operation) {
    if (!(bytes instanceof Uint8Array) || bytes.length > 98) fail();
    const pointer = module._malloc(Math.max(1, bytes.length));
    if (!pointer) fail();
    try {
      module.HEAPU8.set(bytes, pointer);
      return operation(pointer, bytes.length);
    } finally {
      module.HEAPU8.fill(0, pointer, pointer + bytes.length);
      module._free(pointer);
    }
  }
  try {
    if (role !== 0 && role !== 1) fail();
    session = buffer(context, (ctx, length) => buffer(code,
      (password, size) => module._nyan_pairing_new(role, ctx, length, password, size)));
    if (!session) fail();
  } catch (error) { close(); throw error; }
  function input(name, bytes) {
    try {
      if (!session || buffer(bytes, (pointer, size) => module[name](session, pointer, size)) !== 1) fail();
    } catch (error) { close(); throw error; }
  }
  function output(name) {
    try {
      if (!session) fail();
      return buffer(new Uint8Array(32), (pointer, size) => {
        if (module[name](session, pointer, size) !== 1) fail();
        return module.HEAPU8.slice(pointer, pointer + size);
      });
    } catch (error) { close(); throw error; }
  }
  return Object.freeze({
    message: () => output('_nyan_pairing_message'),
    processPeer: bytes => input('_nyan_pairing_process_peer', bytes),
    confirmation: () => output('_nyan_pairing_confirmation'),
    verifyConfirmation: bytes => input('_nyan_pairing_verify_confirmation', bytes),
    // Clear the returned proof after use; send only over authenticated pinned TLS/WT.
    enrollmentProof: () => output('_nyan_pairing_enrollment_proof'),
    close,
  });
}
