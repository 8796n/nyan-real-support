// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    NYAN_PAIRING_CLIENT = 0,
    NYAN_PAIRING_SERVER = 1,
    NYAN_PAIRING_VERSION = 2,
    NYAN_PAIRING_CONTEXT_BYTES = 98,
    NYAN_PAIRING_CODE_BYTES = 6,
    NYAN_PAIRING_MESSAGE_BYTES = 32,
    NYAN_PAIRING_CONFIRMATION_BYTES = 32,
    NYAN_PAIRING_ENROLLMENT_BYTES = 32,
    NYAN_PAIRING_GRANT_REMOTE = 1,
    NYAN_PAIRING_GRANT_GLASSES = 2,
    NYAN_PAIRING_GRANT_CONTROLLER = 4
};

typedef struct nyan_pairing nyan_pairing;

// Context: version(1), window nonce(32), PC SPKI SHA256(32), leaf SHA256(32),
// grants(1). The code is exactly six ASCII digits, without a terminating NUL.
// Client = SPAKE2 Alice; server = Bob. A session is single-threaded and one-shot.
// No shared key is exposed. Creation fails with NULL; other operations return
// 1 on success or 0 on failure. Any operation failure makes the session terminal.
nyan_pairing *nyan_pairing_new(int role, const uint8_t *context, size_t context_len,
                              const uint8_t *code, size_t code_len);

// Output buffers must be exactly 32 bytes. Getters may repeat a successful read.
int nyan_pairing_message(nyan_pairing *session, uint8_t *out, size_t out_len);
int nyan_pairing_process_peer(nyan_pairing *session, const uint8_t *peer, size_t peer_len);

// Server confirmation is available after process_peer; client confirmation only
// after verifying the server. verify_confirmation may succeed only once.
int nyan_pairing_confirmation(nyan_pairing *session, uint8_t *out, size_t out_len);
int nyan_pairing_verify_confirmation(nyan_pairing *session, const uint8_t *peer, size_t peer_len);

// Available only after peer confirmation. Send this proof ONLY inside TLS/WT
// pinned to the authenticated context; never in public HTTP or a URL. The caller
// owns expiry, atomic single-use consumption, and credential persistence.
int nyan_pairing_enrollment_proof(nyan_pairing *session, uint8_t *out, size_t out_len);

// Accepts NULL. Cancelling a session requires freeing it, not reusing it.
void nyan_pairing_free(nyan_pairing *session);

#ifdef __cplusplus
}
#endif
