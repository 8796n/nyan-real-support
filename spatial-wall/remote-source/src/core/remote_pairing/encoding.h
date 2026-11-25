// SPDX-License-Identifier: MIT
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
enum { NYAN_DEVICE_AUTHORIZATION_BYTES = 104, NYAN_TICKET_AUTHORIZATION_BYTES = 71 };
// Accept only the canonical one-use auxiliary credential as a header value.
int nyan_ticket_authorization_valid(const char *value, size_t size);
// Write the Device header value without a NUL terminator. Returns 1 on success.
// The owner may send these bytes only over a pinned, encrypted connection.
int nyan_device_authorization(const uint8_t *id, size_t id_size, const uint8_t *secret, size_t secret_size,
                              uint8_t *out, size_t capacity);

// A named-curve P-256 public key in canonical DER with an uncompressed point.
int nyan_pairing_canonical_spki(const uint8_t *der, size_t size);
#ifdef __cplusplus
}
#include <array>
#include <string>
#include <string_view>

namespace nyan {
bool remote_device_label_valid(std::string_view label);
std::string remote_base64url(const uint8_t *bytes, size_t size);
// Bounded registration fields. EVP_DecodeBase64 needs space for terminal padding.
struct remote_decoded_bytes {
    std::array<uint8_t, 165> bytes{};
    size_t size = 0;
    ~remote_decoded_bytes();
};
bool remote_decode_base64url(std::string_view text, remote_decoded_bytes &out);
bool remote_p256_spki(const uint8_t *bytes, size_t size);
}
#endif
