// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include "pairing.h"
#include <array>
#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace nyan {
// The owner serializes calls and persists only records, never issued secrets.
class remote_devices {
public:
    using id = std::array<uint8_t, 16>;
    using bytes32 = std::array<uint8_t, 32>;
    struct record {
        id device;
        bytes32 verifier;
        uint8_t grants;
        std::string label;
    };
    struct credential {
        id device{};
        bytes32 secret{};
        ~credential();
        credential() = default;
        credential(const credential &) = delete;
        credential &operator=(const credential &) = delete;
        void clear();
        // The caller must keep this value out of URLs, plaintext, logs and UI frames.
        std::string authorization() const;
    };
    using records = std::vector<record>;
    // Atomic persistence, success only after saving, no reentry. Failure disables
    // authentication until an explicit, validated reload; stale .bak is not recovery.
    using persist = std::function<bool(const records &)>;
    static constexpr size_t capacity = 64;

    bool load(records saved, bool restore_retired_grant = false);
    bool ready() const { return ready_; }
    const records &saved() const { return records_; }
    bool can_enroll(uint8_t grants, std::string_view label) const;
    bool enroll(uint8_t grants, std::string label, const persist &save, credential &out);
    std::optional<record> authenticate(std::string_view authorization, uint8_t required_grants) const;
    bool revoke(const id &device, const persist &save);
    bool revoke_all(const persist &save);

private:
    bool commit(records next, const persist &save);
    records records_;
    bool ready_ = false;
};
} // namespace nyan
