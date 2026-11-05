// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "registration.h"
#include <algorithm>
#include <openssl/base64.h>
#include <openssl/bytestring.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/nid.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

namespace nyan {
namespace {
constexpr uint8_t allowed_grants = NYAN_PAIRING_GRANT_REMOTE | NYAN_PAIRING_GRANT_GLASSES |
                                   NYAN_PAIRING_GRANT_CONTROLLER;
bool grants_valid(uint8_t value) { return value && !(value & ~allowed_grants); }
struct secret_text {
    std::string value;
    ~secret_text() { if (!value.empty()) OPENSSL_cleanse(value.data(), value.size()); }
};
struct decoded_header {
    // Decoding padded base64 may require up to two bytes of spare capacity.
    std::array<uint8_t, 165> bytes{};
    size_t size = 0;
    ~decoded_header() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
struct secret32 {
    remote_registration::bytes32 bytes;
    ~secret32() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
} // namespace
std::string remote_base64url(const uint8_t *bytes, size_t size)
{
    std::string encoded(((size + 2) / 3) * 4 + 1, '\0');
    encoded.resize(EVP_EncodeBlock(reinterpret_cast<uint8_t *>(encoded.data()), bytes, size));
    while (!encoded.empty() && encoded.back() == '=') encoded.pop_back();
    std::replace(encoded.begin(), encoded.end(), '+', '-');
    std::replace(encoded.begin(), encoded.end(), '/', '_');
    return encoded;
}
namespace {
bool decode(std::string_view text, decoded_header &out)
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
bool p256_spki(const uint8_t *bytes, size_t size)
{
    if (!bytes || !size || size > 256) return false;
    CBS input;
    CBS_init(&input, bytes, size);
    bssl::UniquePtr<EVP_PKEY> key(EVP_parse_public_key(&input));
    return key && CBS_len(&input) == 0 && EVP_PKEY_get_ec_curve_nid(key.get()) == NID_X9_62_prime256v1;
}
bool random_code(remote_registration::code_bytes &code)
{
    size_t filled = 0;
    // Rejection sampling is uniform over decimal digits, including leading zero.
    // Bound retries as well; even an unexpected RNG failure cannot hang the owner.
    for (unsigned attempt = 0; attempt < 64 && filled < code.size(); ++attempt) {
        std::array<uint8_t, 16> random{};
        if (!RAND_bytes(random.data(), random.size())) {
            OPENSSL_cleanse(random.data(), random.size());
            return false;
        }
        for (uint8_t value : random) {
            if (value < 250 && filled < code.size()) code[filled++] = uint8_t('0' + value % 10);
        }
        OPENSSL_cleanse(random.data(), random.size());
    }
    return filled == code.size();
}
remote_registration::bytes32 bytes32_at(const uint8_t *data)
{
    remote_registration::bytes32 result;
    std::copy_n(data, result.size(), result.begin());
    return result;
}
} // namespace

remote_registration::response::~response()
{
    if (!body.empty()) OPENSSL_cleanse(body.data(), body.size());
}
remote_registration::remote_registration(remote_devices &devices, const remote_snapshot &snapshot,
                                         remote_snapshot::writer write)
    : devices_(devices), snapshot_(snapshot), write_(std::move(write)) {}
remote_registration::~remote_registration() { cancel(); }

bool remote_registration::configure(const uint8_t *spki, size_t size, const bytes32 &leaf_hash)
{
    if (!p256_spki(spki, size) || !snapshot_.key_size() || !write_ ||
        std::all_of(leaf_hash.begin(), leaf_hash.end(), [](uint8_t b) { return b == 0; })) {
        cancel(); configured_ = false; spki_.clear(); return false;
    }
    std::vector<uint8_t> next(spki, spki + size);
    if (!configured_ || next != spki_ || leaf_hash != leaf_hash_) cancel();
    spki_ = std::move(next);
    SHA256(spki_.data(), spki_.size(), spki_hash_.data());
    leaf_hash_ = leaf_hash;
    configured_ = true;
    return true;
}
void remote_registration::clear_invite()
{
    have_invite_ = false; invite_grants_ = 0;
    OPENSSL_cleanse(invite_.data(), invite_.size());
}
void remote_registration::cancel()
{
    window_.cancel(); clear_invite();
    OPENSSL_cleanse(app_invites_.data(), sizeof(app_invites_));
}
bool remote_registration::observe(uint64_t now_ms)
{
    const bool forward = !have_time_ || now_ms >= last_time_;
    have_time_ = true; last_time_ = now_ms;
    if (!forward) cancel();
    return forward;
}
bool remote_registration::invite_current(uint64_t now_ms)
{
    if (have_invite_ && now_ms - invite_opened_ >= 60000) clear_invite();
    return have_invite_;
}
bool remote_registration::start_code(uint8_t grants, uint64_t now_ms)
{
    window_.cancel();
    if (!observe(now_ms) || !configured_ || !devices_.ready() || !grants_valid(grants)) return false;
    pairing_window::context_bytes context{};
    context.front() = NYAN_PAIRING_VERSION; context.back() = grants;
    std::copy(spki_hash_.begin(), spki_hash_.end(), context.begin() + 33);
    std::copy(leaf_hash_.begin(), leaf_hash_.end(), context.begin() + 65);
    code_bytes code{};
    const bool generated = RAND_bytes(context.data() + 1, 32) && random_code(code);
    const bool started = generated && window_.start(context, code, now_ms);
    OPENSSL_cleanse(code.data(), code.size());
    return started;
}
bool remote_registration::display_code(uint64_t now_ms, code_bytes &out, unsigned &seconds_left)
{
    out.fill(0); seconds_left = 0;
    return observe(now_ms) && configured_ && devices_.ready() && window_.display_code(now_ms, out, seconds_left);
}
bool remote_registration::invitation(uint8_t grants, uint64_t now_ms, bytes32 &out, unsigned &seconds_left)
{
    out.fill(0); seconds_left = 0;
    if (!observe(now_ms) || !configured_ || !devices_.ready() || !grants_valid(grants)) return false;
    if (!invite_current(now_ms) || invite_grants_ != grants) {
        clear_invite();
        if (!RAND_bytes(invite_.data(), invite_.size())) { clear_invite(); return false; }
        invite_opened_ = now_ms; invite_grants_ = grants; have_invite_ = true;
    }
    out = invite_;
    seconds_left = static_cast<unsigned>((60000 - (now_ms - invite_opened_) + 999) / 1000);
    return true;
}
remote_registration::response remote_registration::failure(unsigned status) const
{
    response result;
    result.status = status; result.close_listener = !devices_.ready();
    return result;
}
remote_registration::app_invite *remote_registration::find_app_invite(const uint8_t *token, uint64_t now_ms)
{
    app_invite *found = nullptr;
    for (auto &pending : app_invites_) {
        if (!pending.grants) continue;
        const auto &saved = devices_.saved();
        const bool parent_current = std::any_of(saved.begin(), saved.end(), [&](const auto &record) {
            return record.device == pending.parent && record.verifier == pending.verifier && record.grants == pending.grants;
        });
        if (now_ms - pending.opened >= 60000 || !parent_current)
            OPENSSL_cleanse(&pending, sizeof(pending));
        else if (token && CRYPTO_memcmp(pending.token.data(), token, pending.token.size()) == 0)
            found = &pending;
    }
    return found;
}
remote_registration::response remote_registration::app_invitation(std::string_view authorization, uint64_t now_ms)
{
    if (!devices_.ready()) return failure(503);
    if (!observe(now_ms) || !configured_) return failure(403);
    const auto parent = devices_.authenticate(authorization, NYAN_PAIRING_GRANT_REMOTE);
    if (!parent || !devices_.can_enroll(parent->grants, "Android")) return failure(403);
    find_app_invite(nullptr, now_ms);
    auto slot = std::find_if(app_invites_.begin(), app_invites_.end(), [&](const auto &pending) {
        return pending.grants && pending.parent == parent->device;
    });
    if (slot == app_invites_.end())
        slot = std::find_if(app_invites_.begin(), app_invites_.end(), [](const auto &pending) { return !pending.grants; });
    if (slot == app_invites_.end()) return failure(503);
    response result;
    result.body.resize(66);
    secret32 token{};
    if (!RAND_bytes(token.bytes.data(), token.bytes.size())) return failure(503);
    *slot = {parent->device, parent->verifier, token.bytes, now_ms, parent->grants};
    result.status = 200; result.content = response::format::binary;
    result.body[0] = NYAN_PAIRING_VERSION; result.body[1] = parent->grants;
    std::copy(leaf_hash_.begin(), leaf_hash_.end(), result.body.begin() + 2);
    std::copy(token.bytes.begin(), token.bytes.end(), result.body.begin() + 34);
    return result;
}
remote_registration::response remote_registration::pair_get(uint64_t now_ms)
{
    if (!devices_.ready()) return failure(503);
    pairing_window::context_bytes context{};
    if (!observe(now_ms) || !configured_ || !window_.context(now_ms, context)) return failure(404);
    response result;
    result.status = 200; result.content = response::format::binary;
    result.body.assign(context.begin(), context.end());
    return result;
}
remote_registration::response remote_registration::pair_post(const uint8_t *body, size_t size, uint64_t now_ms)
{
    if (!body || (size != 65 && size != 66) ||
        (body[0] == 1 ? size != 65 : body[0] == 2 ? size != 66 : true)) return failure(400);
    if (!devices_.ready()) return failure(503);
    if (!observe(now_ms) || !configured_) return failure(403);
    const auto nonce = bytes32_at(body + 1);
    if (body[0] == 2)
        return failure(window_.confirm(nonce, body[33], bytes32_at(body + 34), now_ms) ? 204 : 403);
    const auto reply = window_.exchange(nonce, bytes32_at(body + 33), now_ms);
    if (!reply) return failure(403);
    response result;
    result.status = 200; result.content = response::format::binary;
    result.body.reserve(65); result.body.push_back(reply->attempt);
    result.body.insert(result.body.end(), reply->message.begin(), reply->message.end());
    result.body.insert(result.body.end(), reply->confirmation.begin(), reply->confirmation.end());
    return result;
}
unsigned remote_registration::enrollment_status(bool encrypted, std::string_view authorization,
                                                size_t body_size, uint64_t now_ms)
{
    if (!encrypted) return 403;
    if (body_size) return 400;
    if (!devices_.ready()) return 503;
    if (!observe(now_ms) || !configured_) return 403;
    const bool pairing = authorization.substr(0, 8) == "Pairing ";
    const bool invite = authorization.substr(0, 7) == "Invite ";
    if (!pairing && !invite) return 403;
    decoded_header data;
    if (!decode(authorization.substr(pairing ? 8 : 7), data)) return 400;
    const size_t prefix = pairing ? 65 : 32;
    if (data.size < prefix + 1 || data.size > prefix + 96) return 400;
    std::optional<uint8_t> grants;
    if (pairing) {
        secret32 proof{bytes32_at(data.bytes.data() + 33)};
        grants = window_.enrollment_grants(bytes32_at(data.bytes.data()), data.bytes[32], proof.bytes, now_ms);
    } else if (invite_current(now_ms) && CRYPTO_memcmp(invite_.data(), data.bytes.data(), invite_.size()) == 0) {
        grants = invite_grants_;
    } else if (auto *pending = find_app_invite(data.bytes.data(), now_ms)) {
        grants = pending->grants;
    }
    const std::string_view label(reinterpret_cast<const char *>(data.bytes.data() + prefix), data.size - prefix);
    return grants && devices_.can_enroll(*grants, label) ? 200 : 403;
}
remote_registration::response remote_registration::enroll(bool encrypted, std::string_view authorization,
                                                          size_t body_size, uint64_t now_ms)
{
    const auto status = enrollment_status(encrypted, authorization, body_size, now_ms);
    if (status != 200) return failure(status);
    const bool pairing = authorization.substr(0, 8) == "Pairing ";
    decoded_header data;
    if (!decode(authorization.substr(pairing ? 8 : 7), data)) return failure(400);
    const size_t prefix = pairing ? 65 : 32;
    const std::string label(reinterpret_cast<const char *>(data.bytes.data() + prefix), data.size - prefix);
    // Reserve the complete response before persistence. After committing there
    // are only fixed byte copies, with no allocation that could lose the reply.
    response result;
    result.body.resize(52 + spki_.size());
    remote_devices::credential credential;
    uint8_t granted = 0;
    const auto persist = [&](uint8_t grants) {
        const bool saved = devices_.enroll(grants, label, [&](const auto &records) {
            return snapshot_.save(records, write_);
        }, credential);
        if (saved) granted = grants;
        return saved;
    };
    bool saved = false;
    if (pairing) {
        // Proof is passed only to this encrypted handler. All PAKE math and
        // mutual confirmation remain in the existing shared implementation.
        const auto nonce = bytes32_at(data.bytes.data());
        secret32 proof{bytes32_at(data.bytes.data() + 33)};
        saved = window_.enroll(nonce, data.bytes[32], proof.bytes, now_ms, persist);
    } else if (invite_current(now_ms) && CRYPTO_memcmp(invite_.data(), data.bytes.data(), invite_.size()) == 0) {
        saved = persist(invite_grants_);
        if (saved) clear_invite();
    } else if (auto *pending = find_app_invite(data.bytes.data(), now_ms)) {
        saved = persist(pending->grants);
        if (saved) OPENSSL_cleanse(pending, sizeof(*pending));
    }
    if (!saved) return failure(devices_.ready() ? 403 : 503);
    result.status = 200; result.content = response::format::binary;
    result.body[0] = NYAN_PAIRING_VERSION;
    std::copy(credential.device.begin(), credential.device.end(), result.body.begin() + 1);
    std::copy(credential.secret.begin(), credential.secret.end(), result.body.begin() + 17);
    result.body[49] = granted;
    result.body[50] = static_cast<uint8_t>(spki_.size() >> 8);
    result.body[51] = static_cast<uint8_t>(spki_.size());
    std::copy(spki_.begin(), spki_.end(), result.body.begin() + 52);
    return result;
}
} // namespace nyan
