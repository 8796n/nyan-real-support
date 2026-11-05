// SPDX-License-Identifier: MIT
// Copyright (C) 2026 8796n <info@8796.jp>
#pragma once
#include "devices.h"

namespace nyan {
// One live glasses owner, one outstanding ticket for each auxiliary route.
class bridge_tickets {
public:
    enum class route : uint8_t { video, audio, media };
    struct owner {
        remote_devices::id device{};
        uint64_t control = 0;
        uint64_t generation = 0;
    };
    static constexpr uint64_t lifetime_ms = 10000;
    // The caller obtains owner from the authenticated live control connection,
    // never from peer-supplied IDs, and serializes this with owner replacement.
    std::optional<std::string> issue(route endpoint, const owner &live, uint64_t now_ms);
    // Admission only. Recheck and consume after the channel actually opens.
    bool check(std::string_view authorization, route endpoint, const owner &live, uint64_t now_ms);
    bool consume(std::string_view authorization, route endpoint, const owner &live, uint64_t now_ms);
    void clear();

private:
    struct slot {
        remote_devices::bytes32 verifier{};
        uint64_t issued = 0;
        bool active = false;
    };
    bool current(const owner &live, uint64_t now_ms);
    std::array<slot, 3> slots_{};
    owner owner_{};
    uint64_t last_time_ = 0;
    bool has_time_ = false;
};
} // namespace nyan
