// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include "pairing.h"
#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>

namespace nyan {
// The owner serializes all calls, including persistence, and supplies monotonic time.
class pairing_window {
public:
    using bytes32 = std::array<uint8_t, 32>;
    using context_bytes = std::array<uint8_t, NYAN_PAIRING_CONTEXT_BYTES>;
    using code_bytes = std::array<uint8_t, NYAN_PAIRING_CODE_BYTES>;
    struct exchange_reply {
        uint8_t attempt;
        bytes32 message;
        bytes32 confirmation;
    };
    // The PC generates a fresh nonce and uniform random code for every start.
    bool start(const context_bytes &context, const code_bytes &code, uint64_t now_ms);
    void cancel();
    ~pairing_window() { cancel(); }
    bool context(uint64_t now_ms, context_bytes &out);
    bool display_code(uint64_t now_ms, code_bytes &out, unsigned &seconds_left);
    std::optional<exchange_reply> exchange(const bytes32 &nonce, const bytes32 &client_message,
                                          uint64_t now_ms);
    bool confirm(const bytes32 &nonce, uint8_t attempt, const bytes32 &mac, uint64_t now_ms);
    // Check without consuming the proof. Expiry/clock reversal still cancels it.
    std::optional<uint8_t> enrollment_grants(const bytes32 &nonce, uint8_t attempt,
                                            const bytes32 &proof, uint64_t now_ms);
    // Invoke only for a pinned encrypted connection. Persistence must be atomic,
    // report success only after saving, and must not reenter this window.
    bool enroll(const bytes32 &nonce, uint8_t attempt, const bytes32 &proof, uint64_t now_ms,
                const std::function<bool(uint8_t grants)> &persist);

private:
    using session_ptr = std::unique_ptr<nyan_pairing, decltype(&nyan_pairing_free)>;
    struct pending {
        session_ptr session{nullptr, nyan_pairing_free};
        bool confirmed = false;
    };
    bool current(uint64_t now_ms);
    bool matches(const bytes32 &nonce) const;
    pending *find(const bytes32 &nonce, uint8_t attempt, uint64_t now_ms);
    context_bytes context_{};
    code_bytes code_{};
    std::array<pending, 5> pending_{};
    session_ptr first_{nullptr, nyan_pairing_free};
    uint64_t opened_ = 0, last_time_ = 0;
    unsigned attempts_ = 0;
    bool active_ = false;
};
} // namespace nyan
