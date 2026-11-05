// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#include "window.h"
#include <openssl/mem.h>
#include <algorithm>

namespace nyan {
bool pairing_window::start(const context_bytes &context, const code_bytes &code, uint64_t now_ms)
{
    cancel();
    // Creation also validates the context/code with the shared protocol's rules.
    first_.reset(nyan_pairing_new(NYAN_PAIRING_SERVER, context.data(), context.size(),
                                  code.data(), code.size()));
    if (!first_) return false;
    context_ = context;
    code_ = code;
    opened_ = last_time_ = now_ms;
    active_ = true;
    return true;
}

void pairing_window::cancel()
{
    active_ = false;
    first_.reset();
    for (auto &slot : pending_) {
        slot.session.reset();
        slot.confirmed = false;
    }
    OPENSSL_cleanse(code_.data(), code_.size());
    attempts_ = 0;
}

bool pairing_window::current(uint64_t now_ms)
{
    if (!active_) return false;
    if (now_ms < last_time_ || now_ms - opened_ >= 60000) {
        cancel();
        return false;
    }
    last_time_ = now_ms;
    return true;
}

bool pairing_window::matches(const bytes32 &nonce) const
{
    return std::equal(nonce.begin(), nonce.end(), context_.begin() + 1);
}

bool pairing_window::context(uint64_t now_ms, context_bytes &out)
{
    if (!current(now_ms)) return false;
    out = context_;
    return true;
}

bool pairing_window::display_code(uint64_t now_ms, code_bytes &out, unsigned &seconds_left)
{
    if (!current(now_ms)) return false;
    out = code_;
    seconds_left = static_cast<unsigned>((60000 - (now_ms - opened_) + 999) / 1000);
    return true;
}

std::optional<pairing_window::exchange_reply> pairing_window::exchange(
    const bytes32 &nonce, const bytes32 &client_message, uint64_t now_ms)
{
    if (!current(now_ms) || !matches(nonce) || attempts_ == pending_.size()) return {};
    // Spend the attempt before returning a PAKE response; disconnects never refund it.
    const unsigned index = attempts_++;
    auto &slot = pending_[index];
    if (index == 0) slot.session = std::move(first_);
    else slot.session.reset(nyan_pairing_new(NYAN_PAIRING_SERVER, context_.data(), context_.size(),
                                             code_.data(), code_.size()));
    exchange_reply result{};
    result.attempt = static_cast<uint8_t>(index + 1);
    if (!slot.session ||
        !nyan_pairing_message(slot.session.get(), result.message.data(), result.message.size()) ||
        !nyan_pairing_process_peer(slot.session.get(), client_message.data(), client_message.size()) ||
        !nyan_pairing_confirmation(slot.session.get(), result.confirmation.data(), result.confirmation.size())) {
        slot.session.reset();
        return {};
    }
    return result;
}

pairing_window::pending *pairing_window::find(const bytes32 &nonce, uint8_t attempt, uint64_t now_ms)
{
    if (!current(now_ms) || !matches(nonce) || attempt == 0 || attempt > attempts_) return nullptr;
    auto &slot = pending_[attempt - 1];
    return slot.session ? &slot : nullptr;
}

bool pairing_window::confirm(const bytes32 &nonce, uint8_t attempt, const bytes32 &mac, uint64_t now_ms)
{
    auto *slot = find(nonce, attempt, now_ms);
    if (!slot || slot->confirmed) return false;
    if (!nyan_pairing_verify_confirmation(slot->session.get(), mac.data(), mac.size())) {
        slot->session.reset();
        return false;
    }
    slot->confirmed = true;
    return true;
}

std::optional<uint8_t> pairing_window::enrollment_grants(const bytes32 &nonce, uint8_t attempt,
                                                       const bytes32 &proof, uint64_t now_ms)
{
    auto *slot = find(nonce, attempt, now_ms);
    if (!slot || !slot->confirmed) return {};
    bytes32 expected{};
    const bool ready = nyan_pairing_enrollment_proof(slot->session.get(), expected.data(), expected.size());
    const bool equal = ready && CRYPTO_memcmp(expected.data(), proof.data(), expected.size()) == 0;
    OPENSSL_cleanse(expected.data(), expected.size());
    return equal ? std::optional<uint8_t>(context_.back()) : std::nullopt;
}

bool pairing_window::enroll(const bytes32 &nonce, uint8_t attempt, const bytes32 &proof, uint64_t now_ms,
                            const std::function<bool(uint8_t)> &persist)
{
    const auto grants = enrollment_grants(nonce, attempt, proof, now_ms);
    if (!grants || !persist || !persist(*grants)) return false;
    cancel();
    return true;
}
} // namespace nyan
