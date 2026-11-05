// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
import { createPairing } from './pairing.mjs';

// Development fixtures only. No network, saved credentials, or product registration.
export function runBrowserChecks(module) {
  let checks = 0;
  const check = (condition, label) => { if (!condition) throw new Error(label); checks++; };
  const code = new TextEncoder().encode('012345');
  const context = new Uint8Array(98).fill(42);
  context[0] = 2; context[97] = 1;
  for (const wrongCode of [false, true]) {
    const client = createPairing(module, 0, context, code);
    const server = createPairing(module, 1, context, new TextEncoder().encode(wrongCode ? '012346' : '012345'));
    try {
      client.processPeer(server.message());
      server.processPeer(client.message());
      let rejected = false;
      try { client.verifyConfirmation(server.confirmation()); } catch { rejected = true; }
      check(rejected === wrongCode, 'server confirmation');
      if (!wrongCode) {
        server.verifyConfirmation(client.confirmation());
        const a = client.enrollmentProof(), b = server.enrollmentProof();
        check(a.length === 32 && a.every((value, i) => value === b[i]), 'enrollment proof');
        a.fill(0); b.fill(0);
      } else {
        rejected = false;
        try { client.enrollmentProof(); } catch { rejected = true; }
        check(rejected, 'failed session cannot enroll');
      }
    } finally { client.close(); server.close(); }
  }
  code.fill(0);
  return { checks, memoryBytes: module.HEAPU8.byteLength };
}
