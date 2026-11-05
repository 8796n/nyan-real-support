// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "devices.h"
#include "ws_message_validation.h"
#include <algorithm>
#include <openssl/crypto.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

namespace nyan {
namespace {
bool grants_valid(uint8_t grants)
{
    return grants && !(grants & ~(NYAN_PAIRING_GRANT_REMOTE | NYAN_PAIRING_GRANT_GLASSES |
                                NYAN_PAIRING_GRANT_CONTROLLER));
}
template<size_t N> bool nonzero(const std::array<uint8_t, N> &bytes)
{
    return std::any_of(bytes.begin(), bytes.end(), [](uint8_t value) { return value != 0; });
}
bool label_valid(std::string_view label)
{
    return !label.empty() && label.size() <= 96 && ws_valid_utf8(label) &&
           std::none_of(label.begin(), label.end(), [](unsigned char c) { return c < 0x20 || c == 0x7f; });
}
template<size_t N> void append_hex(std::string &out, const std::array<uint8_t, N> &bytes)
{
    constexpr char digits[] = "0123456789abcdef";
    for (auto byte : bytes) { out += digits[byte >> 4]; out += digits[byte & 15]; }
}
template<size_t N> bool parse_hex(std::string_view text, std::array<uint8_t, N> &out)
{
    if (text.size() != N * 2) return false;
    for (size_t i = 0; i < N; ++i) {
        unsigned value = 0;
        for (size_t j = 0; j < 2; ++j) {
            const char c = text[i * 2 + j];
            if (c >= '0' && c <= '9') value = value * 16 + unsigned(c - '0');
            else if (c >= 'a' && c <= 'f') value = value * 16 + unsigned(c - 'a' + 10);
            else return false;
        }
        out[i] = static_cast<uint8_t>(value);
    }
    return true;
}
}

remote_devices::credential::~credential() { clear(); }
void remote_devices::credential::clear()
{
    OPENSSL_cleanse(secret.data(), secret.size());
    device.fill(0);
}
std::string remote_devices::credential::authorization() const
{
    if (!nonzero(device) || !nonzero(secret)) return {};
    std::string value = "Device ";
    value.reserve(104);
    append_hex(value, device);
    value += '.';
    append_hex(value, secret);
    return value;
}

bool remote_devices::load(records saved)
{
    ready_ = false;
    records_.clear();
    if (saved.size() > capacity) return false;
    for (size_t i = 0; i < saved.size(); ++i) {
        const auto &item = saved[i];
        if (!nonzero(item.device) || !nonzero(item.verifier) ||
            !grants_valid(item.grants) || !label_valid(item.label)) return false;
        for (size_t j = 0; j < i; ++j)
            if (item.device == saved[j].device || item.verifier == saved[j].verifier) return false;
    }
    records_ = std::move(saved);
    ready_ = true;
    return true;
}

bool remote_devices::commit(records next, const persist &save)
{
    bool written = false;
    try { written = save && save(next); } catch (...) { }
    if (!written) {
        ready_ = false;
        return false;
    }
    records_.swap(next);
    return true;
}

bool remote_devices::can_enroll(uint8_t grants, std::string_view label) const
{
    return ready_ && records_.size() < capacity && grants_valid(grants) && label_valid(label);
}

bool remote_devices::enroll(uint8_t grants, std::string label, const persist &save, credential &out)
{
    out.clear();
    if (!can_enroll(grants, label)) return false;
    credential candidate;
    if (!RAND_bytes(candidate.device.data(), candidate.device.size()) ||
        !RAND_bytes(candidate.secret.data(), candidate.secret.size()) ||
        !nonzero(candidate.device) || !nonzero(candidate.secret)) return false;
    record item{candidate.device, {}, grants, std::move(label)};
    SHA256(candidate.secret.data(), candidate.secret.size(), item.verifier.data());
    for (const auto &saved : records_)
        if (saved.device == item.device || saved.verifier == item.verifier) return false;
    records next = records_;
    next.push_back(std::move(item));
    if (!commit(std::move(next), save)) return false;
    out.device = candidate.device;
    out.secret = candidate.secret;
    return true;
}

std::optional<remote_devices::record> remote_devices::authenticate(std::string_view authorization,
                                                                  uint8_t required_grants) const
{
    if (!ready_ || !grants_valid(required_grants) || authorization.size() != 104 ||
        authorization.substr(0, 7) != "Device " || authorization[39] != '.') return std::nullopt;
    credential supplied;
    if (!parse_hex(authorization.substr(7, 32), supplied.device) ||
        !parse_hex(authorization.substr(40), supplied.secret)) return std::nullopt;
    bytes32 verifier{};
    SHA256(supplied.secret.data(), supplied.secret.size(), verifier.data());
    for (const auto &item : records_) {
        if (item.device == supplied.device &&
            CRYPTO_memcmp(item.verifier.data(), verifier.data(), verifier.size()) == 0 &&
            (item.grants & required_grants) == required_grants) return item;
    }
    return std::nullopt;
}

bool remote_devices::revoke(const id &device, const persist &save)
{
    if (!ready_) return false;
    records next = records_;
    const auto found = std::find_if(next.begin(), next.end(), [&](const record &item) { return item.device == device; });
    if (found == next.end()) return false;
    next.erase(found);
    return commit(std::move(next), save);
}
bool remote_devices::revoke_all(const persist &save)
{
    return ready_ && commit({}, save);
}
} // namespace nyan
