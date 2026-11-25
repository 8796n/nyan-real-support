// SPDX-License-Identifier: MIT
#include "encoding.h"
#include "pairing.h"
#include "ws_message_validation.h"
#include <algorithm>
#include <openssl/base64.h>
#include <openssl/bytestring.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/nid.h>

namespace {
struct secret_text {
    std::string value;
    ~secret_text() { if (!value.empty()) OPENSSL_cleanse(value.data(), value.size()); }
};
bssl::UniquePtr<EVP_PKEY> parse_spki(const uint8_t *bytes, size_t size)
{
    if (!bytes || !size || size > 256) return nullptr;
    CBS input;
    CBS_init(&input, bytes, size);
    bssl::UniquePtr<EVP_PKEY> key(EVP_parse_public_key(&input));
    if (!key || CBS_len(&input) || EVP_PKEY_get_ec_curve_nid(key.get()) != NID_X9_62_prime256v1)
        return nullptr;
    return key;
}
} // namespace

int nyan_ticket_authorization_valid(const char *value, size_t size)
{
    if (!value || size != NYAN_TICKET_AUTHORIZATION_BYTES) return 0;
    const std::string_view text(value, size);
    constexpr std::string_view prefix = "Ticket ";
    return text.substr(0, prefix.size()) == prefix && std::all_of(text.begin() + prefix.size(), text.end(),
        [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); });
}

int nyan_device_authorization(const uint8_t *id, size_t id_size, const uint8_t *secret, size_t secret_size,
                              uint8_t *out, size_t capacity)
{
    if (!id || id_size != 16 || !secret || secret_size != 32 || !out || capacity < NYAN_DEVICE_AUTHORIZATION_BYTES)
        return 0;
    const auto hex = [](uint8_t *dest, const uint8_t *src, size_t size) {
        constexpr char digits[] = "0123456789abcdef";
        for (size_t i = 0; i < size; ++i) {
            *dest++ = digits[src[i] >> 4];
            *dest++ = digits[src[i] & 15];
        }
        return dest;
    };
    constexpr char prefix[] = "Device ";
    auto *cursor = std::copy_n(prefix, sizeof(prefix) - 1, out);
    cursor = hex(cursor, id, id_size);
    *cursor++ = '.';
    hex(cursor, secret, secret_size);
    return 1;
}

int nyan_pairing_canonical_spki(const uint8_t *der, size_t size)
{
    auto key = parse_spki(der, size);
    uint8_t encoded[91];
    bssl::ScopedCBB output;
    return key && CBB_init_fixed(output.get(), encoded, sizeof(encoded)) &&
           EVP_marshal_public_key(output.get(), key.get()) && CBB_len(output.get()) == size &&
           CRYPTO_memcmp(encoded, der, size) == 0;
}

namespace nyan {
bool remote_device_label_valid(std::string_view label)
{
    return !label.empty() && label.size() <= NYAN_PAIRING_MAX_LABEL_BYTES && ws_valid_utf8(label) &&
           std::none_of(label.begin(), label.end(), [](unsigned char c) { return c < 0x20 || c == 0x7f; });
}

bool remote_p256_spki(const uint8_t *bytes, size_t size) { return bool(parse_spki(bytes, size)); }

remote_decoded_bytes::~remote_decoded_bytes() { OPENSSL_cleanse(bytes.data(), bytes.size()); }

std::string remote_base64url(const uint8_t *bytes, size_t size)
{
    std::string encoded(((size + 2) / 3) * 4 + 1, '\0');
    encoded.resize(EVP_EncodeBlock(reinterpret_cast<uint8_t *>(encoded.data()), bytes, size));
    while (!encoded.empty() && encoded.back() == '=') encoded.pop_back();
    std::replace(encoded.begin(), encoded.end(), '+', '-');
    std::replace(encoded.begin(), encoded.end(), '/', '_');
    return encoded;
}
bool remote_decode_base64url(std::string_view text, remote_decoded_bytes &out)
{
    if (text.empty() || text.size() > 215 || text.size() % 4 == 1 ||
        std::any_of(text.begin(), text.end(), [](char c) {
            return !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                     (c >= '0' && c <= '9') || c == '-' || c == '_');
        })) return false;
    secret_text padded{std::string(text)};
    std::replace(padded.value.begin(), padded.value.end(), '-', '+');
    std::replace(padded.value.begin(), padded.value.end(), '_', '/');
    while (padded.value.size() % 4) padded.value += '=';
    if (!EVP_DecodeBase64(out.bytes.data(), &out.size, out.bytes.size(),
                         reinterpret_cast<const uint8_t *>(padded.value.data()), padded.value.size())) return false;
    secret_text canonical{remote_base64url(out.bytes.data(), out.size)};
    return canonical.value == text;
}
} // namespace nyan
