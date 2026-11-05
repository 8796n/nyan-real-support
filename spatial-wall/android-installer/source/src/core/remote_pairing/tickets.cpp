// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "tickets.h"
#include <algorithm>
#include <openssl/crypto.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

namespace nyan {
namespace {
bool same(const bridge_tickets::owner &a, const bridge_tickets::owner &b)
{
    return a.device == b.device && a.control == b.control && a.generation == b.generation;
}
struct secret {
    std::array<uint8_t, 32> bytes{};
    ~secret() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};
}

void bridge_tickets::clear()
{
    slots_ = {};
    owner_ = {};
    last_time_ = 0;
    has_time_ = false;
}
bool bridge_tickets::current(const owner &live, uint64_t now_ms)
{
    if (has_time_ && now_ms < last_time_) { clear(); return false; }
    last_time_ = now_ms;
    has_time_ = true;
    if (!live.control || !live.generation ||
        std::none_of(live.device.begin(), live.device.end(), [](uint8_t value) { return value != 0; }))
        return false;
    for (auto &slot : slots_)
        if (slot.active && now_ms - slot.issued >= lifetime_ms) slot.active = false;
    return true;
}

std::optional<std::string> bridge_tickets::issue(route endpoint, const owner &live, uint64_t now_ms)
{
    const auto index = static_cast<size_t>(endpoint);
    if (index >= slots_.size() || !current(live, now_ms)) return {};
    if (!same(live, owner_)) { slots_ = {}; owner_ = live; }
    // A retry replaces the previous ticket, even when random generation fails.
    slots_[index].active = false;
    secret random;
    if (!RAND_bytes(random.bytes.data(), random.bytes.size())) return {};
    std::string authorization = "Ticket ";
    authorization.reserve(71);
    constexpr char hex[] = "0123456789abcdef";
    for (auto value : random.bytes) { authorization += hex[value >> 4]; authorization += hex[value & 15]; }
    auto &slot = slots_[index];
    SHA256(reinterpret_cast<const uint8_t *>(authorization.data()), authorization.size(), slot.verifier.data());
    slot.issued = now_ms;
    slot.active = true;
    return authorization;
}

bool bridge_tickets::check(std::string_view authorization, route endpoint, const owner &live, uint64_t now_ms)
{
    const auto index = static_cast<size_t>(endpoint);
    if (index >= slots_.size() || !current(live, now_ms) || !same(live, owner_) ||
        authorization.size() != 71 || authorization.substr(0, 7) != "Ticket ") return false;
    const auto &slot = slots_[index];
    if (!slot.active) return false;
    remote_devices::bytes32 verifier{};
    SHA256(reinterpret_cast<const uint8_t *>(authorization.data()), authorization.size(), verifier.data());
    return CRYPTO_memcmp(verifier.data(), slot.verifier.data(), verifier.size()) == 0;
}

bool bridge_tickets::consume(std::string_view authorization, route endpoint, const owner &live, uint64_t now_ms)
{
    if (!check(authorization, endpoint, live, now_ms)) return false;
    slots_[static_cast<size_t>(endpoint)].active = false;
    return true;
}
} // namespace nyan
